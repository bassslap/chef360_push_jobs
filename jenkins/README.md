# Chef Push Jobs replacement (Jenkins + Chef 360 Courier)

Replaces the legacy Chef Push Jobs feature: this Jenkins job forces a
`chef-client` run on 1..n nodes by submitting a job instance to the Chef 360
Courier orchestrator API, then polls the Courier state service for
completion.

## How it works

1. `jenkins/scripts/force-chef-client-run.sh` builds a Courier `job-instance`
   payload (see `POST /job-instances` in the Chef Courier Orchestrator API)
   targeting the given node UUIDs with a single step that runs `chef-client`
   via the `chef-client-interpreter` skill already installed on managed nodes.
   If `NODE_IDS` is left blank, it resolves nodes by tag instead (see below).
2. It submits the job instance, then polls
   `GET /instance/{instanceId}` on the Courier state service until the
   instance reports `success` or `failure` (or times out).
3. `jenkins/Jenkinsfile` wraps the script as a parameterized Jenkins pipeline.

## Targeting nodes by tag instead of raw UUIDs

Rather than pasting node UUIDs into the `NODE_IDS` job parameter, you can
target nodes by an existing tag (e.g. `role=loadbalancer`) — leave `NODE_IDS`
blank and set `TAG_NAME`/`TAG_VALUE` (both default to `role`/`loadbalancer`).

`jenkins/scripts/resolve-nodes-by-tag.sh` resolves the tag to node UUIDs by
calling the Chef 360 Node Management API's ad-hoc filter endpoint
(`POST /filters/exec`), matching an attribute filter against the `tags`
namespace. `force-chef-client-run.sh` calls this automatically when `NODE_IDS`
is empty.

Verify the `tags` namespace name against your tenant (Node Management API →
`GET /nodes/{nodeId}` shows a node's attributes/namespaces) and override with
`TAG_NAMESPACE` if it differs.

## Jenkins setup

1. Create a new Pipeline job pointing at `jenkins/Jenkinsfile` in this repo.
2. Add two Jenkins credentials (Secret text):
   - `chef360-api-key`
   - `chef360-api-secret`
3. Run the job with parameters:
   - `CHEF360_BASE_URL` — Chef 360 API gateway base URL
   - `CHEF360_ORG_ID` / `CHEF360_TENANT_ID` — from your Chef 360 org
   - `NODE_IDS` — one or more node UUIDs (from the Node Management API/UI),
     or leave blank and use `TAG_NAME`/`TAG_VALUE` to target by tag

## Creating the Chef 360 credential for Jenkins

Jenkins does not need to be an enrolled node, and no separate courier
workstation is required — it authenticates to the Courier orchestrator API
directly as a service identity using an Application Key (`accessKey`/
`secretKey`), sent as the `api-key`/`api-secret` headers.

Run `jenkins/scripts/create-chef360-application-key.sh` once (from a machine
with access to your Chef 360 tenant) to mint that key:

```bash
export CHEF360_BASE_URL=https://chef360.slaplabs.us
export CHEF360_TENANT_ID=<tenant-uuid>
export CHEF360_EMAIL=<admin-user-email>
export CHEF360_PASSWORD=<admin-user-password>
export COHORT_ID=<application-cohort-uuid>
export ROLE_ID=<role-uuid-to-grant>
./jenkins/scripts/create-chef360-application-key.sh
```

The script logs in, exchanges the oauth code for a JWT, then calls
`POST /application-key` to create a scoped, expiring automation credential.
The `secretKey` is only shown once — copy it immediately into the
`chef360-api-secret` Jenkins credential (and `accessKey` into
`chef360-api-key`).

## Verify before first real run

- `INTERPRETER_NAME`/version bounds in the script default to
  `chef/courier-interpreter/chef-client` (1.0.0–2.0.0). Confirm the exact
  interpreter name and skill version against your Chef 360 tenant (Node
  Management API lists `installedSkills` per node, e.g.
  `chef-client-interpreter`) and adjust the script defaults if needed.
- `ORCHESTRATOR_PATH` / `STATE_PATH` default to `/courier/orchestrator-api/v1`
  and `/courier/state-api/v1`. Confirm actual routing through your Chef 360
  API gateway.
