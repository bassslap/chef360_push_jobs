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

## Shared Chef 360 host: internal.cloud.chef.io

`https://internal.cloud.chef.io` is a **shared, multi-tenant** Chef 360
instance — it hosts other orgs besides ours (`phillips-sa`). Every API call
in this pipeline requires explicit `CHEF360_ORG_ID`/`CHEF360_TENANT_ID`
values (there is no default), and every request sends them as the
`OrganizationId`/`TenantId` headers, so a run can never silently fall through
to another org's nodes. Always resolve and pass the `phillips-sa` UUIDs
explicitly — see below.

### Resolving the phillips-sa org/tenant UUIDs

Run `jenkins/scripts/resolve-chef360-org-id.sh` once to look up the UUIDs for
our org by name:

```bash
export CHEF360_BASE_URL=https://internal.cloud.chef.io
export CHEF360_EMAIL=<your-email>
export CHEF360_PASSWORD=<your-password>
export ORG_NAME=phillips-sa
./jenkins/scripts/resolve-chef360-org-id.sh
# tenantId=<uuid> orgId=<uuid>
```

Use the printed `tenantId`/`orgId` as the `CHEF360_TENANT_ID`/`CHEF360_ORG_ID`
job parameters (or set them as the Jenkins job's default parameter values so
runners don't have to look them up each time).

## Jenkins setup

1. Create a new Pipeline job pointing at `jenkins/Jenkinsfile` in this repo.
2. Add two Jenkins credentials (Secret text):
   - `chef360-api-key`
   - `chef360-api-secret`
3. Run the job with parameters:
   - `CHEF360_BASE_URL` — `https://internal.cloud.chef.io`
   - `CHEF360_ORG_ID` / `CHEF360_TENANT_ID` — the `phillips-sa` UUIDs resolved
     above (required every run — there is no default)
   - `NODE_IDS` — one or more node UUIDs (from the Node Management API/UI),
     or leave blank and use `TAG_NAME`/`TAG_VALUE` to target by tag

## Creating the Chef 360 credential for Jenkins

Jenkins does not need to be an enrolled node, and no separate courier
workstation is required — it authenticates to the Courier orchestrator API
directly as a service identity using an Application Key (`accessKey`/
`secretKey`), sent as the `api-key`/`api-secret` headers.

There are two practical ways to obtain that credential:

1. use the `chef-platform-auth-cli` device registration flow on the Jenkins
   runtime itself, or
2. run `jenkins/scripts/create-chef360-application-key.sh` from a machine with
   a valid tenant login.

### Installing the CLI tools inside the Jenkins container

The stock Jenkins container image is intentionally minimal and does not ship
with `sudo`, so the bundled installer script fails unless the script is
patched to skip the `sudo` call. The working approach is to install the CLI
as root in the container and then register the container as its own device.

For the next container build, add both `bash-completion` and `jq` to the
container startup so the shell is ready for CLI interaction and JSON parsing:

```dockerfile
RUN apt-get update && apt-get install -y bash-completion jq && \
    echo 'if [ -f /etc/bash_completion ] && ! shopt -oq posix; then . /etc/bash_completion; fi' >> /var/jenkins_home/.bashrc
```

This ensures the Jenkins shell loads completions and `jq` is available for any
CLI requests or troubleshooting in the container.


Install the auth CLI:

```bash
# run from the Jenkins container as root
curl -sk https://internal.cloud.chef.io/platform/bundledtools/v1/static/install.sh \
  -o /tmp/install.sh
chmod +x /tmp/install.sh
sed -i 's/sudo //g' /tmp/install.sh
INSTALL_DIR=/opt/chef-360 BIN_DIR=/usr/local/bin \
  TOOL="chef-platform-auth-cli" \
  SERVER="https://internal.cloud.chef.io" \
  VERSION="latest" \
  /tmp/install.sh
```

This installs the `chef-platform-auth-cli` binary successfully in the
container even though the stock image lacks `sudo`.

Install the Courier CLI as well for manual validation and debugging:

```bash
# run from the Jenkins container as root
curl -sk https://internal.cloud.chef.io/platform/bundledtools/v1/static/install.sh \
  -o /tmp/install-courier.sh
chmod +x /tmp/install-courier.sh
sed -i 's/sudo //g' /tmp/install-courier.sh
INSTALL_DIR=/opt/chef-360 BIN_DIR=/usr/local/bin \
  TOOL="chef-courier-cli" \
  SERVER="https://internal.cloud.chef.io" \
  VERSION="latest" \
  /tmp/install-courier.sh
```

This gives the Jenkins runtime both the `chef-platform-auth-cli` and the
`chef-courier-cli` utilities so you can validate authorization and submit job
operations directly from the same container.

### Registering the Jenkins device in Chef 360

Once the CLI is installed, register the Jenkins container as a device for the
`phillips-sa` profile:

```bash
chef-platform-auth-cli register-device \
  --device-name jenkins-01 \
  --profile-name phillips-sa \
  --url https://internal.cloud.chef.io
```

Authorize the device in the browser when prompted. The command will then print
`AccessKey` and `SecretKey` for the device profile. Store the value in Jenkins
as a single Secret Text credential in the exact format:

```text
<accessKey>:<secretKey>
```

This is the format the Jenkins plugin expects in
`ForceChefClientRunBuilder.perform()` before it sends the `api-key` and
`api-secret` headers.

### Alternative: minting an application key from a tenant login

If you prefer the direct script-based flow, run
`jenkins/scripts/create-chef360-application-key.sh` once (from a machine with
access to your Chef 360 tenant) to mint that key:

```bash
export CHEF360_BASE_URL=https://internal.cloud.chef.io
export CHEF360_TENANT_ID=<phillips-sa-tenant-uuid>
export CHEF360_EMAIL=<admin-user-email>
export CHEF360_PASSWORD=<admin-user-password>
export COHORT_ID=<application-cohort-uuid>
export ROLE_ID=<role-uuid-to-grant>
./jenkins/scripts/create-chef360-application-key.sh
```

The script logs in, exchanges the oauth code for a JWT, then calls
`POST /application-key` to create a scoped, expiring automation credential.
The `secretKey` is only shown once — copy it immediately into the Jenkins
credential as `accessKey:secretKey`.

## Verify before first real run

- `INTERPRETER_NAME`/version bounds in the script default to
  `chef/courier-interpreter/chef-client` (1.0.0–2.0.0). Confirm the exact
  interpreter name and skill version against your Chef 360 tenant (Node
  Management API lists `installedSkills` per node, e.g.
  `chef-client-interpreter`) and adjust the script defaults if needed.
- `ORCHESTRATOR_PATH` / `STATE_PATH` default to `/courier/orchestrator-api/v1`
  and `/courier/state-api/v1`. Confirm actual routing through your Chef 360
  API gateway.
- For the local lab's private certificate, set the plugin's optional `CA
  certificate file` field to the absolute path of the downloaded `root-ca.crt`
  inside the Jenkins container, such as `/root/root-ca.crt`. Leave it blank
  for endpoints signed by a CA already trusted by the JVM.
