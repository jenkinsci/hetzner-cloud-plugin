# Changelog

All notable Percona patches to [hetzner-cloud-plugin](https://github.com/jenkinsci/hetzner-cloud-plugin) are documented here.

## v103.percona.15 (2026-05-15)

Surface the actual running Jenkins core version on `hetzner_plugin_info`.

### Changed
- `hetzner_plugin_info` metric label `jenkins_baseline` renamed to `jenkins_version`. The label value was previously the hard-coded string `"2.479"` (matching `<jenkins.baseline>` in `pom.xml` only by convention); it now reads from `Jenkins.getVersion().toString()` at class init, so it reports the real running core version on each master (e.g. `2.528.3`, `2.541.3`). Fully guarded with try/catch; value is `"unknown"` if the call throws or returns null.

### Why
- Field deployment of `103.percona.14` to the 10-master Percona Jenkins fleet exposed that every master emitted `jenkins_baseline="2.479"` regardless of its actual running core version, which made the label useless for dashboards intending to break out per-core-version behaviour.

### Migration note
- Any Grafana queries or alerts referencing the old `jenkins_baseline` label must be updated to `jenkins_version`. Within Percona's repos this is only the panel-104 transform `renameByName` in `percona-ci-platform/resources/addons/grafana/dashboards/hetzner-plugin.json`.

## v103.percona.14 (2026-05-12)

Wait for cloud-init to finish before launching the remoting JVM.

### Fixed
- `HetznerServerComputerLauncher` generates an `.agent.start.sh` that calls `cloud-init status --wait` (when present) and verifies `java` exists on PATH before `exec`'ing the remoting JVM. Previously the script ran `java -jar remoting.jar` immediately; on stock images (e.g. Hetzner debian-12) where the user-data script installs openjdk via apt during cloud-init's `modules-final` stage, java was not yet on PATH and the channel EOFed instantly. Symptom: chronic `java.io.EOFException: unexpected stream termination` at `HetznerServerComputerLauncher.launchAgent` with a high `bootstrap_io` rate (verified on pg.cd and psmdb.cd; cpx62 reproduction showed cloud-init `modules-final` taking ~49 seconds during which `which java` returned not-found).
- The script logs to stderr only (stdout is the remoting channel) and exits with a clear nonzero code on cloud-init failure or missing java, instead of letting the EOF surface as an opaque bootstrap failure.
- Controller-side `logger.info` line in `launchAgent()` now states that the launch script waits for cloud-init, so the Jenkins log shows expected behavior even when remote stderr is not wired through.

### Context
- Pre-baked images (no `cloud-init` on PATH, java already installed) skip the wait and fall straight through. The aarch64 snapshot path is unaffected.
- Outer bound is unchanged: `NodeCallable.doProvision` still wraps `computer.connect(false)` in a Future bounded by the template's `bootDeadline` minutes.
- A long-term follow-up is to pre-bake a Hetzner snapshot with java/docker/awscli installed; that is orthogonal to this fix and will reduce provisioning latency by the cloud-init `modules-final` time on top of fixing the race.

## v103.percona.11 (2026-05-08)

Make `/hetzner-prometheus` an `UnprotectedRootAction` so anonymous loopback callers are not blocked by Jenkins core authorization.

### Changed
- `HetznerPrometheusEndpoint` now `implements UnprotectedRootAction` (instead of `RootAction`). v103.percona.10 dropped the `Jenkins.SYSTEM_READ` check inside `doIndex()`, but the request was still rejected with HTTP 403 by `GlobalMatrixAuthorizationStrategy` before ever reaching `doIndex()`. Verified anonymous `curl http://127.0.0.1:8080/hetzner-prometheus` returned 403 on ps3.cd with v103.percona.10 active.
- Javadoc updated to reflect that the trust boundary is the Jenkins 8080 loopback bind, not core ACLs.

### Context
- Required for ADR 0013's master-side Alloy push model (PS-10997 Phase 2). The Alloy systemd unit on each EC2 master scrapes the endpoint with no credentials; auth lives at the in-cluster `alloy-gateway` instead.

## v103.percona.10 (2026-05-07)

Drop SYSTEM_READ permission gate on `/hetzner-prometheus` for the push-model rollout.

### Changed
- `HetznerPrometheusEndpoint.doIndex()` no longer calls `Jenkins.get().checkPermission(Jenkins.SYSTEM_READ)`. The endpoint is unreachable from external clients (Jenkins binds 8080 to 127.0.0.1 on Percona masters); the master-side Grafana Alloy systemd unit is the only consumer. Auth lives at the in-cluster `alloy-gateway` (NGINX bearer-token sidecar + ALB inbound-CIDRs allowlist), not at the Jenkins endpoint.
- Javadoc updated to reflect the localhost-only contract and reference ADR 0013 / PS-10997 Phase 2.

### Context
- Companion repo: `nogueiraanderson/percona-ci-platform` (alloy-gateway addon).
- Supersedes the prior plan that ran a `prom-scraper-svc` Jenkins user with API-token basic auth for in-cluster Prometheus to scrape this endpoint over public DNS.

## v103.percona.5 (2026-04-01)

Retry infrastructure and template error suppression.

### Added
- `RetryInterceptor`: OkHttp interceptor with exponential backoff and jitter for transient errors (429, 502, 504, socket timeouts). Max 3 retries, honors `Retry-After` header.
- `TemplateErrorTracker`: suppresses templates with persistent config errors (e.g., deprecated image IDs). 3-failure threshold, 30-minute suppression, half-open probe on expiry.
- Config error detection (`invalid_input`) aborts DC failover immediately.
- Rate-limit (429) during DC failover aborts the entire provisioning attempt (token-scoped).

### Fixed
- Boot status polling skips API calls when rate-limited.
- Improved log coverage, context, and consistency across rate-limit paths.

## v103.percona.4 (2026-03-22)

Rate-limit infrastructure and API caching.

### Added
- `HetznerApiClient`: per-token API client wrapper with rate-limit state tracking, lazy auth via `AuthInterceptor`, and HTTP 401 client invalidation for token rotation support.
- `RateLimitInterceptor`: OkHttp interceptor parsing `RateLimit-Limit/Remaining/Reset` headers from every API response.
- Guava response caches: SSH keys (30min TTL), label IDs (15min TTL), server lists (30sec TTL) with `recordStats()`.
- `checkRateLimit()` guard before API-intensive operations (`createServer`, `getOrCreateSshKey`, `fetchAllServers`).
- `HetznerCloudResourceManager.getCacheStats()` for Script Console observability.
- `OrphanedNodesCleaner` skips cleanup cycle when rate-limited.

### Fixed
- `destroyServer()` catches `Exception` (not just `IOException`), hardening the CRW timer death fix for all exception types.

## v103.percona.3 (2026-03-19)

Per-datacenter circuit breaker failover.

### Added
- `DcCircuitBreaker`: per-DC state machine (CLOSED / OPEN / HALF_OPEN). 2-failure threshold trips breaker for 5 minutes, auto-reset to HALF_OPEN for single probe.
- `DcHealthTracker`: static registry of breakers per location with `sortByHealth()` for template ranking. Shuffles within health partitions for load distribution.
- `HetznerProvisioningException`: typed RuntimeException carrying HTTP status, Hetzner error code, and DC location. Methods: `isRateLimited()`, `isConfigError()`, `isResourceUnavailable()`.
- `HetznerCloud.rankTemplatesByHealth()` replaces random `pickTemplate()`.
- `NodeCallable` rewritten with DC failover loop: iterates ranked templates, records success/failure in `DcHealthTracker`, cleans up leaked servers on bootstrap failure.
- CLI observability via `jenkins hetzner` commands (`health`, `status`, `nodes`, `servers`, `templates`, `version`, `orphans`, `reset`, `trip`).
- `dc-health-check.groovy` for Script Console inspection.

### Changed
- `Helper.assertValidResponse()` throws `HetznerProvisioningException` on error responses (typed instead of generic `IllegalStateException`).

## v103.percona.2 (2026-03-19)

Architecture validation, null-safety, and deployment tooling.

### Added
- Architecture validation via `NodeCallable.inferArchFromServerType()` (CAX* = arm64, else x86_64) and post-boot `uname -m` check via `UnameCallable`.
- Deploy (`scripts/deploy.sh`), check (`scripts/check.sh`), and verify (`scripts/verify.sh`) scripts.
- `justfile` recipes: `deploy`, `deploy-all`, `check`, `verify`.

### Fixed
- Launcher null-safety across `DefaultConnectionMethod`, `DefaultV6ConnectionMethod`, `PublicAddressOnly`, `PublicV6AddressOnly` with descriptive error messages instead of NPE.
- `AbstractByLabelSelector`: `.findFirst().get()` replaced with `.findFirst().orElseThrow()` with descriptive error including selector and location.
- `OrphanedNodesCleaner`: removed `setTemporarilyOffline()` call for ghost nodes, added race guard.

## v103.percona.1 (2026-03-19)

Retention bug fixes for idle VM accumulation.

### Fixed
- **CRW timer death** (critical): `destroyServer()` wrapped `IOException` in unchecked `IllegalStateException`, killing the `ComputerRetentionWork` periodic timer permanently. Changed to log-and-return. Confirmed: CRW was dead for 28 hours on two production instances.
- **One-directional orphan cleanup**: `OrphanedNodesCleaner` now removes both VMs without Jenkins nodes AND Jenkins nodes without VMs (ghost nodes). Per-item try-catch prevents one failure from blocking cleanup of remaining items.
- **Null transient fields after restart**: `cloud`, `template`, `serverInstance` are transient and null after deserialization. Added null guards in `_terminate()`, `isAlive()`, `getDisplayName()`.
- `HetznerCloudResourceManager.refreshServerInfo()` throws `IOException` (checked) instead of `IllegalStateException`.
- `Helper.assertValidResponse()` null body guard.
