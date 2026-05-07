# Changelog

All notable Percona patches to [hetzner-cloud-plugin](https://github.com/jenkinsci/hetzner-cloud-plugin) are documented here.

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
