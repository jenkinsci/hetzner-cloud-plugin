<!--
     Copyright 2021 https://dnation.cloud

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

          http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Hetzner Cloud Plugin for Jenkins (Percona patched fork)

Forked from [jenkinsci/hetzner-cloud-plugin](https://github.com/jenkinsci/hetzner-cloud-plugin) v103 with robustness, rate-limiting, retry, and DC failover patches.

Current version: **v103.percona.14**.

For older releases (v103.percona.1 through v103.percona.8), see [`CHANGELOG.md`](CHANGELOG.md).

## Percona patches

### v103.percona.14: Wait for cloud-init before launching remoting JVM

`HetznerServerComputerLauncher.getAgentCommand()` now wraps the remoting launch in a guard that calls `cloud-init status --wait` (when present) and verifies `java` is on PATH before `exec`'ing the JVM. On stock images (Hetzner debian-12, image id 114690387) the user-data script installs openjdk via apt during cloud-init's `modules-final` stage and can take 1-3 minutes to finish; the plugin previously SSHed in as soon as Hetzner reported `status="running"`, scped the launch script, and `exec`'d `java -jar remoting.jar` while apt was still unpacking openjdk. The channel EOFed instantly and the failure surfaced as `bootstrap_io`. Verified on pg.cd (hundreds of `EOFException: unexpected stream termination` at `HetznerServerComputerLauncher.launchAgent`) and reproduced on a cpx62 in pg.cd's private network: cloud-init `modules-final` took ~49 seconds, during which `which java` returned not-found while the plugin's launch was firing.

Pre-baked images (no `cloud-init` on PATH and java already installed) skip the wait. The aarch64 snapshot path is unaffected. The outer `bootDeadline` future bound in `NodeCallable.doProvision` is unchanged, so a hung cloud-init is still cleaned up by the existing timeout.

### v103.percona.11: Anonymous loopback metrics endpoint

`HetznerPrometheusEndpoint` now `implements UnprotectedRootAction` instead of `RootAction`. v10 dropped the `Jenkins.SYSTEM_READ` check inside `doIndex`, but `GlobalMatrixAuthorizationStrategy` still rejected anonymous loopback callers with HTTP 403 before `doIndex` was reached. v11 makes the endpoint anonymous-readable so the master-side Grafana Alloy systemd unit can scrape `http://127.0.0.1:8080/hetzner-prometheus/` with no credentials. The trust boundary is the Jenkins 8080 loopback bind, not core ACLs. Required for ADR 0013 push-model rollout (PS-10997 Phase 2).

### v103.percona.10: Drop SYSTEM_READ gate inside doIndex

Removed the `Jenkins.get().checkPermission(Jenkins.SYSTEM_READ)` call inside `HetznerPrometheusEndpoint.doIndex()`. Necessary but not sufficient (see v11): the core authorization filter still gated anonymous callers. Kept for completeness; v11 supersedes.

### v103.percona.9: Self-contained `/hetzner-prometheus` endpoint

Stapler `RootAction` at `/hetzner-prometheus` exposes 40 `hetzner_*` metric families (DC circuit breaker state, provisioning latency, API rate-limit headroom, CRW iterations, template suppression, anomaly counters) in Prometheus 0.0.4 text format. Bundles `io.prometheus:simpleclient` directly, so the plugin does not depend on the Jenkins community `prometheus-plugin` (a fleet audit found 0/10 masters had it installed). PS-10997 Phase 1.

| Component | Purpose |
|-----------|---------|
| `HetznerPrometheusEndpoint` | Stapler `RootAction` serving Prometheus 0.0.4 text format |
| `HetznerMetricProvider` | Registers all `hetzner_*` collectors against `CollectorRegistry.defaultRegistry` |
| `BundledSimpleClientPusher` | Single source for plugin-instrumentation metrics |

### v103.percona.5: Retry and template error suppression

OkHttp retry interceptor and template-level error tracking to handle transient API failures and suppress misconfigured templates.

| Component | Purpose |
|-----------|---------|
| `RetryInterceptor` | OkHttp interceptor with exponential backoff and jitter for transient errors (429, 502, 504, socket timeouts) |
| `TemplateErrorTracker` | Suppresses templates with persistent config errors (e.g., deprecated image IDs) |

Behavior:
- **Retry:** Max 3 retries with AWS-style full jitter. Honors `Retry-After` header for 429. Does not retry 401/403/404/409/422/500/503
- **Template suppression:** 3-failure threshold suppresses a template for 30 minutes. Half-open probe on expiry allows a single attempt before re-suppressing
- Config errors (`invalid_input`) abort DC failover immediately (not DC-scoped)
- Rate-limit (429) during DC failover aborts the entire provisioning attempt (token-scoped, not DC-scoped)
- Boot status polling skips API calls when rate-limited
- Observability via Script Console: `TemplateErrorTracker.getStatus()`

| File | Change |
|------|--------|
| `RetryInterceptor` | New: OkHttp interceptor with exponential backoff + jitter |
| `TemplateErrorTracker` | New: per-template error tracking and suppression |
| `NodeCallable.call()` | Config error detection, rate-limit abort during failover |
| `HetznerCloud.provision()` | Check `TemplateErrorTracker.isSuppressed()` before using a template |

### v103.percona.4: Rate-limit infrastructure and API caching

Per-token API client with rate-limit awareness, response caching, and hardened exception handling.

| Component | Purpose |
|-----------|---------|
| `HetznerApiClient` | Per-token API client wrapper with rate-limit state tracking and lazy auth |
| `RateLimitInterceptor` | OkHttp interceptor parsing `RateLimit-Limit/Remaining/Reset` headers |
| API caches | Guava caches for SSH keys (30min), label IDs (15min), server lists (30sec) |

Behavior:
- `checkRateLimit()` guard before API-intensive operations (`createServer`, `getOrCreateSshKey`, `fetchAllServers`)
- Lazy auth via `AuthInterceptor` picks up token rotations immediately
- HTTP 401 invalidates the cached client (supports token rotation)
- Cache stats available via `HetznerCloudResourceManager.getCacheStats()` (Script Console)
- `OrphanedNodesCleaner` skips cleanup cycle when rate-limited
- `destroyServer()` catches `Exception` (not just `IOException`), fixing the CRW timer death bug for all exception types

| File | Change |
|------|--------|
| `HetznerApiClient` | New: per-token client with rate-limit state, lazy auth, 401 invalidation |
| `RateLimitInterceptor` | New: OkHttp interceptor for rate-limit header parsing |
| `HetznerCloudResourceManager` | Replaced `ClientFactory.create()` with `HetznerApiClient.forCredentials()`, added API caches, rate-limit guards |
| `HetznerCloud.provision()` | Rate-limit check before provisioning |
| `OrphanedNodesCleaner` | Skip cleanup when rate-limited |
| `Helper.assertValidResponse()` | Throws `HetznerProvisioningException` on HTTP 429 |

### v103.percona.3: DC circuit breaker failover

Per-datacenter circuit breakers that automatically route provisioning away from
unhealthy Hetzner locations (e.g., during DC maintenance or API outages).

| Component | Purpose |
|-----------|---------|
| `DcCircuitBreaker` | Per-DC state machine (CLOSED / OPEN / HALF_OPEN) |
| `DcHealthTracker` | Tracks consecutive failures per location, ranks templates by DC health |
| `HetznerProvisioningException` | Typed exception carrying HTTP status, Hetzner error code, and DC location |

Behavior:
- Triggers on HTTP 422 / `resource_unavailable` / `placement_error` / `server_limit_exceeded` from Hetzner API
- 2-failure threshold trips the breaker for a DC
- 5-minute auto-reset moves to HALF_OPEN, allowing a single probe request
- Successful probe closes the breaker; failure re-opens it
- `HetznerCloud.pickTemplate()` replaced with `rankTemplatesByHealth()` (healthy DCs first, shuffled within partitions)
- `NodeCallable` rewritten with DC failover loop: iterates through ranked templates, tries each DC, records success/failure
- All state is in-memory (resets on JVM restart)

| File | Change |
|------|--------|
| `DcCircuitBreaker` | New: per-DC state machine with threshold and auto-reset |
| `DcHealthTracker` | New: registry of breakers, `sortByHealth()` for template ranking |
| `HetznerProvisioningException` | New: typed exception with `isRateLimited()`, `isConfigError()`, `isResourceUnavailable()` |
| `HetznerCloud` | `pickTemplate()` -> `rankTemplatesByHealth()`, passes ranked list to `NodeCallable` |
| `NodeCallable` | Complete rewrite with DC failover loop, cleanup on bootstrap failure |

#### Observability via CLI

The `jenkins hetzner` CLI provides structured access to DC health state:

```bash
# Single instance
jenkins hetzner -i rel health           # DC breaker status
jenkins hetzner -i rel status           # Overview (plugin + clouds + DCs + nodes)
jenkins hetzner -i rel nodes            # Active hcloud-* workers
jenkins hetzner -i rel servers          # Running VMs from Hetzner API
jenkins hetzner -i rel templates        # Configured server templates
jenkins hetzner -i rel version          # Plugin version + MD5
jenkins hetzner -i rel orphans          # Orphan VMs + ghost nodes
jenkins hetzner -i rel reset [dc]       # Reset circuit breaker
jenkins hetzner -i rel trip <dc>        # Simulate DC failure

# Fleet-wide
jenkins hetzner fleet health            # DC health across all 10 instances
jenkins hetzner fleet versions          # Plugin versions across all 10 instances
```

All commands support `--json`, `--llm`, `--raw` output modes.

### v103.percona.2: Architecture validation and null-safety

Architecture validation to detect wrong CPU type provisioning, launcher null-safety, and deployment tooling.

| File | Change |
|------|--------|
| `NodeCallable` | Architecture validation via `inferArchFromServerType()` (CAX* = arm64, else x86_64) and post-boot `uname -m` check via `UnameCallable` |
| `OrphanedNodesCleaner` | Removed `setTemporarilyOffline()` call for ghost nodes, added race guard |
| `DefaultConnectionMethod` | Null-safety for `publicNet` / `ipv4` with descriptive errors |
| `DefaultV6ConnectionMethod` | Null-safety for `publicNet` / `ipv6` with descriptive errors |
| `PublicAddressOnly` | Null-safety for `publicNet` / `ipv4` with descriptive errors |
| `PublicV6AddressOnly` | Null-safety for `publicNet` / `ipv6`, `IllegalArgumentException` -> `IllegalStateException` |
| `AbstractByLabelSelector` | `.findFirst().get()` -> `.findFirst().orElseThrow()` with descriptive error |

### v103.percona.1: Retention bug fixes

Fixes three bugs that cause idle Hetzner VMs to accumulate indefinitely:

**Bug 1 (critical): `ComputerRetentionWork` timer death.** `destroyServer()` wraps `IOException` in `IllegalStateException`. This unchecked exception propagates through `CloudRetentionStrategy.check()` into `ComputerRetentionWork.doRun()`, permanently killing the periodic retention timer. After that, no idle node on the instance ever gets cleaned up until Jenkins restarts. Confirmed empirically: CRW was dead for 28 hours on two production instances.

**Bug 2: One-directional orphan cleanup.** `OrphanedNodesCleaner` only removes VMs without Jenkins nodes. It does not remove Jenkins nodes without VMs (ghost nodes), which accumulate after API failures or restarts.

**Bug 3: Null transient fields after restart.** `cloud`, `template`, and `serverInstance` are `transient` fields. After Jenkins restart/deserialization they are null, causing NPE in `_terminate()` and `isAlive()`.

| File | Fix |
|------|-----|
| `HetznerServerAgent._terminate()` | Catch all exceptions (protects CRW timer), null guards for transient fields, safe launcher cast |
| `HetznerServerAgent.isAlive()` | Full null safety, catch-all returning false |
| `HetznerServerAgent.getDisplayName()` | Complete null chain protection |
| `HetznerCloudResourceManager.destroyServer()` | Log-and-return on IOException instead of throwing IllegalStateException |
| `HetznerCloudResourceManager.refreshServerInfo()` | Throws IOException (checked) instead of IllegalStateException |
| `OrphanedNodesCleaner` | Bi-directional cleanup (VMs without nodes AND nodes without VMs), per-item try-catch, catch-all in doRun() |
| `Helper.assertValidResponse()` | Null body guard |
| `HelperTest` | Tests for null body and error response handling |

## Build

Requires [just](https://github.com/casey/just) and Docker:

```bash
just build    # Build .hpi (skips tests, ~2min with cached deps)
just test     # Build + run tests (~7min first run, cached after)
```

Maven dependencies are cached in a Docker volume (`hetzner-m2-cache`).

Additional recipes:

```bash
just dc-health <inst>        # DC circuit breaker health via Groovy
just verify <inst>           # Integration verify (create temp job, run, check, delete)
just verify-failover <inst>  # Test all 5 server types (x64/aarch64)
just backup <inst>           # Backup cloud config from Jenkins
just restore <inst>          # Restore cloud config
just release                 # Tag, push, create GitHub release with .hpi
```

## Deploy

Drop-in replacement for upstream hetzner-cloud v103. Same artifact name, same dependencies.

```bash
# Deploy to a single instance
just deploy rel

# Deploy to all 10 instances
just deploy-all

# Verify deployment
just check
jenkins hetzner fleet versions
```

Post-deploy validation:

```bash
jenkins hetzner -i <inst> version      # Confirm version + MD5
jenkins hetzner -i <inst> templates    # Confirm 15 templates present
jenkins hetzner -i <inst> health       # Confirm breakers CLOSED
```

## Original README

The Hetzner cloud plugin enables [Jenkins CI](https://www.jenkins.io/) to schedule builds on dynamically provisioned VMs in [Hetzner Cloud](https://www.hetzner.com/cloud).
Servers in Hetzner cloud are provisioned as they are needed, based on labels assigned to them.
Jobs must have same label specified in their configuration in order to be scheduled on provisioned servers.

# Developed by

<a href="https://dNation.cloud/"><img src="https://cdn.ifne.eu/public/icons/dnation.png" width="250" alt="dNationCloud"></a>

## Installation

### Installation from update center

- Open your Jenkins instance in browser (as Jenkins administrator)
- Go to `Manage Jenkins`
- Go to `Manage Plugins`
- Search for _Hetzner Cloud_ under `Available` tab
- Click `Install`
- Jenkins server might require restart after plugin is installed

### Installation from source

- Clone this git repository
- Build with maven `mvn clean package`
- Open your Jenkins instance in browser (as Jenkins administrator)
- Go to `Manage Jenkins`
- Go to `Manage Plugins`
- Click on `Advanced` tab
- Under `Upload Plugin` section, click on `Choose file` button and select `target/hetzner-cloud.hpi` file
- Jenkins server might require restart after plugin is installed

## Configuration

Regardless of configuration method, you will need API token to access your Hetzner Cloud project.
You can read more about creating API token in [official documentation](https://docs.hetzner.cloud/).

### Manual configuration

#### 1. Create credentials for API token

Go to `Dashboard` => `Manage Jenkins` => `Manage credentials` => `Global` => `Add credentials`, choose `Secret text` as a credentials kind:

![add-token](docs/add-token.png)

#### 2. Create cloud

Go to `Dashboard` => `Manage Jenkins` => `Manage Nodes and Clouds` => `Configure Clouds` => `Add a new cloud` and choose `Hetzner` from dropdown menu:

![add-cloud-button](docs/add-hcloud-button.png)

![add-cloud](docs/add-cloud.png)

Name of cloud should match pattern `[a-zA-Z0-9][a-zA-Z\-_0-9]`.


You can use `Test Connection` button to verify that token is valid and that plugin can use Hetzner API.

#### 3. Define server templates

![server-template](docs/server-template.png)


Following attributes are **required** for each server template:

- `Name` - name of template, should match regex `[a-zA-Z0-9][a-zA-Z\-_0-9]`

- `Connection method` this attribute specifies how Jenkins controller will connect to newly provisioned agent. These methods are supported:
  - `Connect as root` - SSH connection to provisioned server will be done as `root` user.
     This is convenient method due to fact, that Hetzner cloud allows us to specify SSH key for `root` user during server creation.
     Once connection is established, Jenkins agent will be launched by non-root user specified in chosen credentials.
     User must already exist.
  - `Connect as user specified in credentials` - again, that user must already be known to server and its `~/.ssh/authorized_keys` must contain public key counterpart of chosen SSH credentials.
     See bellow how server image can pre created using Hashicorp packer, which also can be used to populate public SSH key.

  In both cases, selection of IP address can be specified as one of
    - `Connect using private IPv4 address if available, otherwise using public IPv4 address`
    - `Connect using private IPv4 address if available, otherwise using public IPv6 address`
    - `Connect using public IPv4 address only`
    - `Connect using public IPv6 address only`

  Default SSH port (`22`) can be overridden to other value.
  Agent VM must be configured for such port already, otherwise SSH connection won't be established.

- `Labels` - Labels that identifies jobs that could run on node created from this template.
  Multiple values can be specified when separated by space.
  When no labels are specified and usage mode is set to <strong>Use this node as much as possible</strong>,
  then no restrictions will apply and node will be eligible to execute any job.

- `Usage` - Controls how Jenkins schedules builds on this node.
  - `Use this node as much as possible` - In this mode, Jenkins uses this node freely.
     Whenever there is a build that can be done by using this node, Jenkins will use it.
  - `Only build jobs with label expressions matching this node` - In this mode, Jenkins will only build a project on this node when that project is restricted to certain nodes using a label expression, and that expression matches this node's name and/or labels.
    This allows a node to be reserved for certain kinds of jobs. For example, if you have jobs that run performance tests, you may want them to only run on a specially configured machine,
    while preventing all other jobs from using that machine. To do so, you would restrict where the test jobs may run by giving them a label expression matching that machine.
    Furthermore, if you set the # of executors value to 1, you can ensure that only one performance test will execute at any given time on that machine; no other builds will interfere.

- `Image ID or label expression` - identifier of server image. It could be ID of image (integer) or label expression.
  In case of label expression, it is assumed that expression resolve into exactly one result.
  Either case, image **must have JRE already installed**.
- `Server type` - type of server

- `Location` - this could be either datacenter name or location name. Distinction is made using presence of character `-` in value, which is meant for datacenter.

These additional attributes can be specified, but are not required:

- `Network` - Network ID (integer) or label expression that resolves into single network. When specified, **private IP address will be used instead of public**,
   so Jenkins controller must be part of same network (or have other means) to communicate with newly created server

- `Firewall` - Firewall ID (integer) or a label expression that resolves into single firewall.
  No support for multiple firewalls is planned.
  If you need to apply multiple rules, combine then into single firewall instance.

- `Prefix` - optional name prefix for provisioned node. Must match regular expression `^[a-z][\w_-]+$`.
  When omitted or if specified value does not match pattern above, then `hcloud` will be used instead.

- `Remote directory` - agent working directory. When omitted, default value of `/home/jenkins` will be used.
  **This path must exist on agent node prior to launch.**

- `Agent JVM options` - Additional JVM options for Jenkins agent

- `Boot deadline minutes` - Maximum amount of time (in minutes) to wait for newly created server to be in `running` state. 

- `Number of Executors`

- `Shutdown policy` - Defines how agent will be shut down after it becomes idle
  - `Removes server after it's idle for period of time` - you can define how many minutes will idle agent kept around
  - `Removes idle server just before current hour of billing cycle completes`

- `Primary IP` - Defines how Primary IP is allocated to the server
  - `Use default behavior` - use Hetzner cloud's default behavior
  - `Allocate primary IPv4 using label selector, fail if none is available` - Primary IP is searched using provided label selector in same location as server. 
     If no address is available or any error occurs, problem is propagated and provisioning of agent will fail.
  - `Allocate primary IPv4 using label selector, ignore any error` - Primary IP is searched using provided label selector in same location as server.
    If no address is available or any error occurs, problem is logged, but provisioning of agent will continue without Primary IP being allocated.

- `Connectivity` - defines how network connectivity will be configured on newly created server
  - `Only private networking will be used` - network ID or labels expression must be provided
  - `Only public networking will be allocated` - public IPv4/IPv6 addresses will be allocated to the server
  - `Only public IPv6 networking will be allocated` - public IPv6 address will be allocated to the server
  - `Configure both private and public networking` - public IPv4/IPv6 addresses will be allocated. Network ID or labels expression must be provided.
  - `Configure both private and public IPv6 networking` - public IPv6 address will be allocated. Network ID or labels expression must be provided.

  Make sure this field is aligned with `Connection method`.

- `Automount volumes` - Auto-mount volumes after attach.

- `Volume IDs to attach` - Volume IDs which should be attached to the Server at the creation time. Volumes must be in the same Location.
  Note that volumes can be mounted into **single server** at the time.

### Scripted configuration using Groovy

```groovy
import cloud.dnation.jenkins.plugins.hetzner.*
import cloud.dnation.jenkins.plugins.hetzner.launcher.*

def cloudName = "hcloud-01"

def templates = [
        new HetznerServerTemplate("ubuntu20-cx21", "java", "name=ubuntu20-docker", "fsn1", "cx21"),
        new HetznerServerTemplate("ubuntu20-cx31", "java", "name=ubuntu20-docker", "fsn1", "cx31")
]

templates.each { it -> it.setConnector(new SshConnectorAsRoot("my-private-ssh-key")) }

def cloud = new HetznerCloud(cloudName, "hcloud-token", "10", templates)

def jenkins = Jenkins.get()

jenkins.clouds.remove(jenkins.clouds.getByName(cloudName))
jenkins.clouds.add(cloud)
jenkins.save()
```

### Configuration as a code

Here is sample of CasC file

```yaml
---
jenkins:
  clouds:
    - hetzner:
        name: "hcloud-01"
        credentialsId: "hcloud-api-token"
        instanceCapStr: "10"
        serverTemplates:
          - name: ubuntu2-cx21
            serverType: cx21
            remoteFs: /var/lib/jenkins
            location: fsn1
            image: name=jenkins
            mode: NORMAL
            numExecutors: 1
            placementGroup: "key1=value1&key2=value2"
            connector:
              root:
                sshCredentialsId: 'ssh-private-key'
                sshPort: 1022
                connectionMethod: "default"
            shutdownPolicy: "hour-wrap"
          - name: ubuntu2-cx31
            serverType: cx31
            remoteFs: /var/lib/jenkins
            location: fsn1
            image: name=jenkins
            mode: EXCLUSIVE
            network: subsystem=cd
            labelStr: java
            numExecutors: 3
            placementGroup: "1000656"
            connectivity: "public-only"
            automountVolumes: true
            volumeIds:
              - 12345678
            connector:
              root:
                sshCredentialsId: 'ssh-private-key'
                connectionMethod: "public"
            shutdownPolicy:
              idle:
                idleMinutes: 10
credentials:
  system:
    domainCredentials:
      - credentials:
          - string:
              scope: SYSTEM
              id: "hcloud-api-token"
              description: "Hetzner cloud API token"
              secret: "abcdefg12345678909876543212345678909876543234567"
          - basicSSHUserPrivateKey:
              scope: SYSTEM
              id: "ssh-private-key"
              username: "jenkins"
              privateKeySource:
                directEntry:
                  privateKey: |
                    -----BEGIN OPENSSH PRIVATE KEY-----
                    b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
                      ... truncated ...
                    baewZMKBL1QECTolAAAADHJrb3NlZ2lAbDQ4MAECAwQFBg==
                    -----END OPENSSH PRIVATE KEY-----
```

### Server details

Plugin is able to report server details for any provisioned node

![server details](docs/server-detail.png)

### Create server image using Packer

It's possible to create images in Hetzner Cloud using Packer.

- Get [Hashicorp Packer](https://www.packer.io/downloads)

- Create image template, see an [example](docs/template.pkr.hcl)

- Build using `packer build -force template.pkr.hcl`
  You should see output similar to this (truncated):
  ```
   ==> Builds finished. The artifacts of successful builds are:
   --> hcloud.jenkins: A snapshot was created: 'ubuntu20-docker' (ID: 537465784)
  ```

### Known limitations

- there is no known way of verifying SSH host keys on newly provisioned VMs
- modification of SSH credentials used to connect to VMs require manual removal of key from project's security settings.
  Plugin will automatically create new SSH key in project after it's removed.
- JRE must already be installed on image that is used to create new server instance.
- Working directory of agent on newly provisioned server must already exist and must be accessible by
  user used to launch agent
- There is possibility of race condition when build executors are demanded in burst,
  resulting in creation of more VMs than configured cap allows.
  No known fix exist at this time, but there is possible workaround mentioned [in comment in reported issue](https://github.com/jenkinsci/hetzner-cloud-plugin/issues/85#issuecomment-2310926683)

### Common problems

- **Symptom** : Jenkins failed to create new server in cloud because of `Invalid API response : 409`

  **Cause** : SSH key with same signature already exists in project's security settings.

  **Remedy** : Remove offending key from project security settings, it will be automatically created using correct labels.

### How to debug API calls

To enable debug logging for API calls, [configure log recorder](https://www.jenkins.io/doc/book/system-administration/viewing-logs/#logs-in-jenkins) for logger `cloud.dnation.hetznerclient`.

- Go to `Manage Jenkins` => `Log Recorders`
- Click on `Add new log recorder`
- choose any name that make sense for you, like `hetzner-cloud`
- Add logger with name `cloud.dnation.hetznerclient`
- save
- now any headers, request and response body will be logged

![add-log-recorder](docs/add-log-recorder.png)