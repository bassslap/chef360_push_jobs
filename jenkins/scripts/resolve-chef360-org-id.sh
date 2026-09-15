#!/usr/bin/env bash
# Resolves the Chef 360 tenant/org UUIDs for a given org name (e.g.
# "phillips-sa") by logging in and listing the organizations the user
# belongs to. Prints "tenantId=<uuid> orgId=<uuid>" on success.
#
# Required env vars:
#   CHEF360_BASE_URL   e.g. https://internal.cloud.chef.io
#   CHEF360_EMAIL       email of a local user with access to the org
#   CHEF360_PASSWORD    password for that user
#   ORG_NAME            org name to resolve, e.g. "phillips-sa"
#
# Optional env vars:
#   IDENTITY_PATH        path prefix for identity API (default: /identity/auth/v1)
set -euo pipefail

: "${CHEF360_BASE_URL:?CHEF360_BASE_URL is required}"
: "${CHEF360_EMAIL:?CHEF360_EMAIL is required}"
: "${CHEF360_PASSWORD:?CHEF360_PASSWORD is required}"
: "${ORG_NAME:?ORG_NAME is required}"

IDENTITY_PATH="${IDENTITY_PATH:-/identity/auth/v1}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

STATE=$(uuidgen | tr '[:upper:]' '[:lower:]')

echo "Logging in as ${CHEF360_EMAIL}..." >&2
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

JWT_RESPONSE=$(curl -sS -X POST "${CHEF360_BASE_URL}${IDENTITY_PATH}/identity/user/jwt" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg code "$OAUTH_CODE" --arg state "$STATE" '{oauthCode: $code, state: $state}')")

ACCESS_TOKEN=$(printf '%s' "$JWT_RESPONSE" | jq -r '.item.accessToken // empty')
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "JWT exchange failed:" >&2
  printf '%s\n' "$JWT_RESPONSE" | jq . >&2 || printf '%s\n' "$JWT_RESPONSE" >&2
  exit 1
fi

echo "Looking up organization '${ORG_NAME}'..." >&2
ORGS_RESPONSE=$(curl -sS "${CHEF360_BASE_URL}${IDENTITY_PATH}/self/organizations" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}")

MATCH=$(printf '%s' "$ORGS_RESPONSE" | jq -c --arg name "$ORG_NAME" '.items[] | select(.name == $name)')
if [[ -z "$MATCH" ]]; then
  echo "No organization named '${ORG_NAME}' found for this user:" >&2
  printf '%s\n' "$ORGS_RESPONSE" | jq . >&2 || printf '%s\n' "$ORGS_RESPONSE" >&2
  exit 1
fi

TENANT_ID=$(printf '%s' "$MATCH" | jq -r '.tenantId')
ORG_ID=$(printf '%s' "$MATCH" | jq -r '.orgId')

echo "tenantId=${TENANT_ID} orgId=${ORG_ID}"
