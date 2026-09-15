#!/usr/bin/env bash
# Resolves Chef 360 node UUIDs by tag (e.g. role=loadbalancer) using the Node
# Management API's ad-hoc filter execution endpoint. Prints a comma-separated
# list of matching node IDs on stdout (suitable for NODE_IDS).
#
# Required env vars:
#   CHEF360_BASE_URL    e.g. https://chef360.slaplabs.us
#   CHEF360_API_KEY     API key (sent as 'api-key' header)
#   CHEF360_API_SECRET  API secret (sent as 'api-secret' header)
#   CHEF360_ORG_ID      Chef 360 organization UUID
#   CHEF360_TENANT_ID   Chef 360 tenant UUID
#   TAG_NAME            tag name to match, e.g. "role"
#   TAG_VALUE           tag value to match, e.g. "loadbalancer"
#
# Optional env vars:
#   NODE_MGMT_PATH      path prefix for the node management API (default: /node/management/v1)
#   TAG_NAMESPACE       attribute namespace tags are stored under (default: tags)
set -euo pipefail

: "${CHEF360_BASE_URL:?CHEF360_BASE_URL is required}"
: "${CHEF360_API_KEY:?CHEF360_API_KEY is required}"
: "${CHEF360_API_SECRET:?CHEF360_API_SECRET is required}"
: "${CHEF360_ORG_ID:?CHEF360_ORG_ID is required}"
: "${CHEF360_TENANT_ID:?CHEF360_TENANT_ID is required}"
: "${TAG_NAME:?TAG_NAME is required}"
: "${TAG_VALUE:?TAG_VALUE is required}"

NODE_MGMT_PATH="${NODE_MGMT_PATH:-/node/management/v1}"
TAG_NAMESPACE="${TAG_NAMESPACE:-tags}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

FILTER_PAYLOAD=$(jq -n \
  --arg name "$TAG_NAME" \
  --arg namespace "$TAG_NAMESPACE" \
  --arg value "$TAG_VALUE" \
  '{
    constraints: {
      attributes: [
        { name: $name, namespace: [$namespace], value: [$value], operator: "=" }
      ]
    }
  }')

HTTP_CODE=$(curl -sS -o /tmp/chef360-filter-response.json -w '%{http_code}' \
  -X POST "${CHEF360_BASE_URL}${NODE_MGMT_PATH}/filters/exec" \
  -H "Content-Type: application/json" \
  -H "api-key: ${CHEF360_API_KEY}" \
  -H "api-secret: ${CHEF360_API_SECRET}" \
  -H "OrganizationId: ${CHEF360_ORG_ID}" \
  -H "TenantId: ${CHEF360_TENANT_ID}" \
  -d "$FILTER_PAYLOAD")

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "Node filter lookup failed (HTTP ${HTTP_CODE}):" >&2
  jq . /tmp/chef360-filter-response.json >&2 2>/dev/null || cat /tmp/chef360-filter-response.json >&2
  exit 1
fi

NODE_IDS=$(jq -r '[.items[].id] | join(",")' /tmp/chef360-filter-response.json)

if [[ -z "$NODE_IDS" ]]; then
  echo "No nodes matched tag ${TAG_NAME}=${TAG_VALUE}" >&2
  exit 1
fi

printf '%s' "$NODE_IDS"
