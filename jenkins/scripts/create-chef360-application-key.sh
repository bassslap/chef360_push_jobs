#!/usr/bin/env bash
# One-time helper: creates a Chef 360 Application Key (service/automation
# credential) for Jenkins to call the Courier orchestrator API with.
# Prints the resulting accessKey/secretKey - store these as the
# 'chef360-api-key' / 'chef360-api-secret' Jenkins credentials. The secretKey
# is only ever shown once, so save it immediately.
#
# Required env vars:
#   CHEF360_BASE_URL   e.g. https://chef360.slaplabs.us
#   CHEF360_TENANT_ID  Chef 360 tenant UUID
#   CHEF360_EMAIL      email of a local user with permission to manage app keys
#   CHEF360_PASSWORD   password for that user
#   COHORT_ID          application cohort UUID for the key
#   ROLE_ID            role UUID to grant the key (e.g. a CI/automation role)
#
# Optional env vars:
#   KEY_NAME           default: "jenkins-chef-push-jobs"
#   KEY_DESCRIPTION    default: "Jenkins service account for Chef Push Jobs replacement"
#   EXPIRY_DAYS        default: 365
#   IP_RANGES          default: "*" (comma-separated ranges, e.g. "192.168.0.1-192.168.0.40")
#   IP_CIDRS           default: "*" (comma-separated CIDRs, e.g. "192.168.0.0/24")
#   MAC_ADDRESSES      default: "*" (comma-separated MAC addresses)
#   ALLOWED_PLATFORMS  default: "linux"
#   IDENTITY_PATH      path prefix for identity API (default: /identity/auth/v1)
#   ACCOUNTS_PATH      path prefix for user accounts API (default: /identity/auth/v1)
set -euo pipefail

: "${CHEF360_BASE_URL:?CHEF360_BASE_URL is required}"
: "${CHEF360_TENANT_ID:?CHEF360_TENANT_ID is required}"
: "${CHEF360_EMAIL:?CHEF360_EMAIL is required}"
: "${CHEF360_PASSWORD:?CHEF360_PASSWORD is required}"
: "${COHORT_ID:?COHORT_ID is required}"
: "${ROLE_ID:?ROLE_ID is required}"

KEY_NAME="${KEY_NAME:-jenkins-chef-push-jobs}"
KEY_DESCRIPTION="${KEY_DESCRIPTION:-Jenkins service account for Chef Push Jobs replacement}"
EXPIRY_DAYS="${EXPIRY_DAYS:-365}"
IP_RANGES="${IP_RANGES:-*}"
IP_CIDRS="${IP_CIDRS:-*}"
MAC_ADDRESSES="${MAC_ADDRESSES:-*}"
ALLOWED_PLATFORMS="${ALLOWED_PLATFORMS:-linux}"
IDENTITY_PATH="${IDENTITY_PATH:-/identity/auth/v1}"
ACCOUNTS_PATH="${ACCOUNTS_PATH:-/identity/auth/v1}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

STATE=$(uuidgen | tr '[:upper:]' '[:lower:]')
EXPIRY_AT=$(date -u -v+"${EXPIRY_DAYS}"d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
  || date -u -d "+${EXPIRY_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)

echo "Logging in as ${CHEF360_EMAIL}..."
LOGIN_RESPONSE=$(curl -sS -X POST "${CHEF360_BASE_URL}${IDENTITY_PATH}/identity/user/login" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg email "$CHEF360_EMAIL" --arg password "$CHEF360_PASSWORD" --arg state "$STATE" \
        '{email: $email, password: $password, state: $state}')")

OAUTH_CODE=$(printf '%s' "$LOGIN_RESPONSE" | jq -r '.item.oauthCode // empty')
if [[ -z "$OAUTH_CODE" ]]; then
  echo "Login failed:" >&2
  printf '%s\n' "$LOGIN_RESPONSE" | jq . >&2 || printf '%s\n' "$LOGIN_RESPONSE" >&2
  exit 1
fi

echo "Exchanging oauth code for JWT..."
JWT_RESPONSE=$(curl -sS -X POST "${CHEF360_BASE_URL}${IDENTITY_PATH}/identity/user/jwt" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg code "$OAUTH_CODE" --arg state "$STATE" '{oauthCode: $code, state: $state}')")

ACCESS_TOKEN=$(printf '%s' "$JWT_RESPONSE" | jq -r '.item.accessToken // empty')
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "JWT exchange failed:" >&2
  printf '%s\n' "$JWT_RESPONSE" | jq . >&2 || printf '%s\n' "$JWT_RESPONSE" >&2
  exit 1
fi

echo "Creating application key '${KEY_NAME}'..."
KEY_PAYLOAD=$(jq -n \
  --arg name "$KEY_NAME" \
  --arg description "$KEY_DESCRIPTION" \
  --arg expiryAt "$EXPIRY_AT" \
  --arg cohortId "$COHORT_ID" \
  --arg roleId "$ROLE_ID" \
  --arg ipRanges "$IP_RANGES" \
  --arg ipCIDRs "$IP_CIDRS" \
  --arg macAddresses "$MAC_ADDRESSES" \
  --arg allowedPlatforms "$ALLOWED_PLATFORMS" \
  '{
    name: $name,
    description: $description,
    expiryAt: $expiryAt,
    cohortId: $cohortId,
    roleId: $roleId,
    ipRanges: $ipRanges,
    ipCIDRs: $ipCIDRs,
    macAddresses: $macAddresses,
    allowedPlatforms: $allowedPlatforms,
    type: "enrollment-cli"
  }')

HTTP_CODE=$(curl -sS -o /tmp/chef360-app-key-response.json -w '%{http_code}' \
  -X POST "${CHEF360_BASE_URL}${ACCOUNTS_PATH}/application-key" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "TenantId: ${CHEF360_TENANT_ID}" \
  -d "$KEY_PAYLOAD")

if [[ "$HTTP_CODE" != "201" && "$HTTP_CODE" != "200" ]]; then
  echo "Application key creation failed (HTTP ${HTTP_CODE}):" >&2
  jq . /tmp/chef360-app-key-response.json >&2 2>/dev/null || cat /tmp/chef360-app-key-response.json >&2
  exit 1
fi

echo ""
echo "Application key created. Store these as Jenkins credentials NOW (secret is shown once):"
jq -r '.item | "  chef360-api-key:    \(.accessKey // .id)\n  chef360-api-secret: \(.secretKey // "<not returned, use rotate endpoint>")"' \
  /tmp/chef360-app-key-response.json
