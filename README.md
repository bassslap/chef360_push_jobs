# Chef 360 Courier + Jenkins POC

> **Disclaimer:** This repository is for lab and proof-of-concept use only. It is not production-ready and should not be deployed without a separate security, reliability, and operational review.

This repository is a proof-of-concept replacement for the legacy Chef Push Jobs capability.

The goal is simple: use Jenkins to trigger a Chef 360 Courier job through the Chef 360 API, which then runs `chef-client` on one or more managed nodes.

This is not a production-grade replacement for all Chef Push Jobs behaviors. It is a customer-facing POC designed to demonstrate the capability and validate the integration pattern.

## What this repo does

- Jenkins submits a Courier job instance through the Chef 360 API
- The job targets node UUIDs or a saved tag filter
- A Courier step runs the `chef-client` interpreter on the managed nodes
- Jenkins polls the Courier state API until the job succeeds or fails
- Optional Proxmox/OpenTofu files provision the Jenkins server used for the POC

## Key components

- `jenkins-plugin/` – Jenkins plugin that resolves nodes, creates the Courier job, and polls for completion
- `jenkins/` – optional scripted Pipeline workflow and Courier validation examples
- `jenkins/courier/` – sample Courier payload JSON used for test execution
- `proxmox/` – optional OpenTofu/Terraform definitions for provisioning the Jenkins server

The Jenkins plugin is the primary integration. The shell scripts under `jenkins/scripts/` are optional helpers for teams that prefer a scripted Pipeline or need one-time credential and tenant setup:

- `force-chef-client-run.sh` – submits and polls a Courier job for the scripted Pipeline
- `resolve-nodes-by-tag.sh` – resolves tag targets for that shell workflow
- `resolve-chef360-org-id.sh` – one-time organization and tenant lookup helper
- `create-chef360-application-key.sh` – optional one-time application-key bootstrap helper

## Architecture

```text
Jenkins job
  -> resolves Chef 360 node IDs
  -> calls Chef 360 Courier API
  -> creates a manual job instance
  -> polls instance status
  -> returns success/failure to Jenkins
```

## Screenshots

These screenshots show the POC workflow from Jenkins configuration through successful Courier execution.

Before publishing screenshots, replace internal hostnames, IP addresses, UUIDs, node names, usernames, and credential identifiers with representative values.

### Jenkins configuration

![Jenkins Chef 360 Courier build-step configuration](docs/screenshots/jenkins-configuration.png)

### Successful Jenkins build

![Successful Jenkins build triggering a Courier job](docs/screenshots/jenkins-successful-build.png)

### Successful Courier run

![Successful Courier run on two managed nodes](docs/screenshots/courier-successful-run.png)

## Why this exists

The legacy Chef Push Jobs feature was a convenient way to trigger remote `chef-client` executions. This repo demonstrates an equivalent pattern using the modern Chef 360 Courier orchestration model and Jenkins as the trigger layer.

## POC scope

- Demonstrates the API interaction pattern
- Validates Jenkins-driven invocation
- Uses node targeting by UUID or saved tag filter
- Designed for a customer proof of concept, not a generalized enterprise product

## Notes

- The repository includes example payloads and scripts for validating the integration
- The Proxmox/OpenTofu configuration is only used to provision the Jenkins server for the POC
- The implementation intentionally focuses on the Courier API integration path
- Any customer or tenant-specific values should be replaced before reuse outside the lab

## Related assets

- Jenkins plugin: `jenkins-plugin/`
- Job scripts: `jenkins/`
- Jenkins server provisioning: `proxmox/`
