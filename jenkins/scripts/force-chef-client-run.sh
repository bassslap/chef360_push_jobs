#!/usr/bin/env bash
# Submits a Courier job-instance to Chef 360 to force a chef-client run on
# one or more nodes, then (optionally) polls the Courier State service for
# completion.
#
# Required env vars:
#   CHEF360_BASE_URL      e.g. https://chef360.slaplabs.us
#   CHEF360_ORG_ID        Chef 360 organization UUID
#   CHEF360_TENANT_ID     Chef 360 tenant UUID
#   CHEF360_API_KEY       API key (sent as 'api-key' header)
#   CHEF360_API_SECRET    API secret (sent as 'api-secret' header)
#   NODE_IDS              Comma-separated list of target node UUIDs (1..n)
#
# Optional env vars:
#   EXECUTION_TYPE        sequential|parallel (default: parallel)
#   BATCH_TYPE            percent|number (default: number)
#   BATCH_VALUE           batch size value (default: number of nodes, i.e. all at once)
#   TIMEOUT_SECONDS       per-distribution-group timeout (default: 900)
#   SUCCESS_PERCENT       required success percent (default: 100)
#   INTERPRETER_NAME      courier interpreter identifier for chef-client (default: chef/courier-interpreter/chef-client)
#   INTERPRETER_MIN_VER   minimum interpreter/skill version (default: 1.0.0)
#   INTERPRETER_MAX_VER   maximum interpreter/skill version (default: 2.0.0)
#   ORCHESTRATOR_PATH     path prefix for the orchestrator API (default: /courier/orchestrator-api/v1)
#   STATE_PATH            path prefix for the state API (default: /courier/state-api/v1)
#   POLL_FOR_COMPLETION   "true"/"false" (default: true)
#   POLL_INTERVAL_SECONDS (default: 15)
#   POLL_TIMEOUT_SECONDS  (default: 1800)
set -euo pipefail

: "${CHEF360_BASE_URL:?CHEF360_BASE_URL is required}"
: "${CHEF360_ORG_ID:?CHEF360_ORG_ID is required}"
: "${CHEF360_TENANT_ID:?CHEF360_TENANT_ID is required}"
: "${CHEF360_API_KEY:?CHEF360_API_KEY is required}"
: "${CHEF360_API_SECRET:?CHEF360_API_SECRET is required}"
: "${NODE_IDS:?NODE_IDS is required (comma-separated node UUIDs)}"

EXECUTION_TYPE="${EXECUTION_TYPE:-parallel}"
BATCH_TYPE="${BATCH_TYPE:-number}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-900}"
SUCCESS_PERCENT="${SUCCESS_PERCENT:-100}"
INTERPRETER_NAME="${INTERPRETER_NAME:-chef/courier-interpreter/chef-client}"
INTERPRETER_MIN_VER="${INTERPRETER_MIN_VER:-1.0.0}"
INTERPRETER_MAX_VER="${INTERPRETER_MAX_VER:-2.0.0}"
ORCHESTRATOR_PATH="${ORCHESTRATOR_PATH:-/courier/orchestrator-api/v1}"
STATE_PATH="${STATE_PATH:-/courier/state-api/v1}"
POLL_FOR_COMPLETION="${POLL_FOR_COMPLETION:-true}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-15}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-1800}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

# Build a JSON array of node UUIDs from the comma-separated NODE_IDS input.
NODE_ID_JSON=$(printf '%s' "$NODE_IDS" | tr ',' '\n' | sed '/^\s*$/d' | jq -R . | jq -s .)
NODE_COUNT=$(printf '%s' "$NODE_ID_JSON" | jq 'length')
BATCH_VALUE="${BATCH_VALUE:-$NODE_COUNT}"

JOB_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
INSTANCE_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

PAYLOAD=$(jq -n \
  --arg id "$INSTANCE_ID" \
  --arg jobId "$JOB_ID" \
  --arg executionType "$EXECUTION_TYPE" \
  --arg batchType "$BATCH_TYPE" \
  --argjson batchValue "$BATCH_VALUE" \
  --argjson timeoutSeconds "$TIMEOUT_SECONDS" \
  --argjson successPercent "$SUCCESS_PERCENT" \
  --argjson nodeIds "$NODE_ID_JSON" \
  --arg interpreterName "$INTERPRETER_NAME" \
  --arg minVersion "$INTERPRETER_MIN_VER" \
  --arg maxVersion "$INTERPRETER_MAX_VER" \
  '{
    Id: $id,
    jobId: $jobId,
    name: "force-chef-client-run",
    target: {
      executionType: $executionType,
      groups: [
        {
          timeoutSeconds: $timeoutSeconds,
          batchSize: { type: $batchType, value: $batchValue },
          distributionMethod: "batching",
          earlyTermination: false,
          successCriteria: [
            { status: "success", numRuns: { type: "percent", value: $successPercent } }
          ],
          nodeListType: "nodes",
          nodeIdentifiers: $nodeIds
        }
      ]
    },
    actions: {
      accessMode: "agent",
      steps: [
        {
          name: "run-chef-client",
          description: "Force a chef-client run (Chef Push Jobs replacement)",
          interpreter: {
            name: $interpreterName,
            skill: { minVersion: $minVersion, maxVersion: $maxVersion }
          },
          command: { exec: "chef-client" },
          retryCount: 1
        }
      ]
    }
  }')

echo "Submitting Courier job instance ${INSTANCE_ID} for ${NODE_COUNT} node(s)..."
echo "$PAYLOAD" | jq .

HTTP_CODE=$(curl -sS -o /tmp/courier-response.json -w '%{http_code}' \
  -X POST "${CHEF360_BASE_URL}${ORCHESTRATOR_PATH}/job-instances" \
  -H "Content-Type: application/json" \
  -H "api-key: ${CHEF360_API_KEY}" \
  -H "api-secret: ${CHEF360_API_SECRET}" \
  -H "OrganizationId: ${CHEF360_ORG_ID}" \
  -H "TenantId: ${CHEF360_TENANT_ID}" \
  -d "$PAYLOAD")

echo "Orchestrator response (HTTP ${HTTP_CODE}):"
cat /tmp/courier-response.json 2>/dev/null | jq . || cat /tmp/courier-response.json 2>/dev/null

if [[ "$HTTP_CODE" != "201" && "$HTTP_CODE" != "200" ]]; then
  echo "Failed to submit job instance to Courier orchestrator" >&2
  exit 1
fi

echo "Job instance submitted: jobId=${JOB_ID} instanceId=${INSTANCE_ID}"

if [[ "$POLL_FOR_COMPLETION" != "true" ]]; then
  exit 0
fi

echo "Polling Courier state service for completion (timeout ${POLL_TIMEOUT_SECONDS}s)..."
ELAPSED=0
while (( ELAPSED < POLL_TIMEOUT_SECONDS )); do
  STATUS_JSON=$(curl -sS \
    -H "api-key: ${CHEF360_API_KEY}" \
    -H "api-secret: ${CHEF360_API_SECRET}" \
    -H "OrganizationId: ${CHEF360_ORG_ID}" \
    -H "TenantId: ${CHEF360_TENANT_ID}" \
    "${CHEF360_BASE_URL}${STATE_PATH}/instance/${INSTANCE_ID}" || true)

  STATUS=$(printf '%s' "$STATUS_JSON" | jq -r '.item.status // empty' 2>/dev/null || true)

  if [[ -n "$STATUS" ]]; then
    echo "  [$(date +%H:%M:%S)] instance status: ${STATUS}"
    if [[ "$STATUS" == "success" ]]; then
      echo "Job instance completed successfully."
      exit 0
    elif [[ "$STATUS" == "failure" ]]; then
      echo "Job instance reported failure." >&2
      printf '%s' "$STATUS_JSON" | jq . >&2 || true
      exit 1
    fi
  fi

  sleep "$POLL_INTERVAL_SECONDS"
  ELAPSED=$(( ELAPSED + POLL_INTERVAL_SECONDS ))
done

echo "Timed out waiting for job instance completion after ${POLL_TIMEOUT_SECONDS}s" >&2
exit 1
