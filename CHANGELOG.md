# Changelog

All notable Percona patches to [hetzner-cloud-plugin](https://github.com/jenkinsci/hetzner-cloud-plugin) are documented here.

## v103.percona.21 (2026-05-19)

Phase 4a of the ps3 canary resilience plan ([PS-11173](https://perconadev.atlassian.net/browse/PS-11173)). DC circuit-breaker state now survives a Jenkins JVM restart, which closes the failure class where a fresh master after spot interrupt would re-attempt every breaker as CLOSED and stampede a still-sick DC.

### Added

- `DcHealthTracker.load()` reads `$JENKINS_HOME/hetzner-dc-health.xml` at `@Initializer(after=PLUGINS_STARTED)` and rehydrates the in-memory `BREAKERS` map. Missing file is a silent no-op so a clean install or a downgrade to v103.percona.20 is unaffected.
- `DcHealthTracker.save()` schedules a write via `jenkins.util.Timer.get()` whenever `recordFailure` or `recordSuccess` mutates state. An `AtomicBoolean` coalesces concurrent triggers so a burst produces a single write. Deferring to `Timer` keeps disk I/O off the `synchronized` breaker lock.
- `DcCircuitBreaker.afterLoad(fallbackLocation, now, staleOpenTtlMs)` runs on every breaker after deserialization to restore Prometheus gauges and apply a 30-min TTL: an `OPEN` breaker whose `openedAt` is older than 30 min loads as `CLOSED` with zero consecutive failures, so a transient incident from before the restart does not pin a DC out of rotation.
- `@XStreamAlias("dcCircuitBreaker")` on `DcCircuitBreaker` for a stable XML schema even if the class is later moved across packages.

### Changed

- `DcCircuitBreaker.location` is no longer `final`. XStream deserialization assigns it directly, and `afterLoad()` can fall back to the persisted map key if older XML omits the field. The `@Getter` and all `synchronized` accessors are unchanged.

### Compatibility

- Fresh upgrade from v103.percona.20: no migration needed; the XML file is created on first `recordFailure`/`recordSuccess` after upgrade.
- Downgrade to v103.percona.20: the leftover `hetzner-dc-health.xml` is ignored (the prior version never reads it). Safe rollback path.
- Persistence skips silently when `Jenkins.getInstanceOrNull()` returns `null` so existing unit tests that mock `Jenkins.get()` without stubbing `getRootDir()` continue to pass without touching the persistence layer.

### Added tests

- `DcHealthPersistenceTest` covering `savesOnFailure`, `loadsOnInit`, `ttl_resetsStaleOpen`, `missingFile_isNoOp`.

### Added metrics

- `hetzner_dc_health_loaded_breakers` (gauge) - number of breakers restored on the most recent `DcHealthTracker.load()` call.
- `hetzner_dc_health_saves_total` (counter) - successful XmlFile writes.
- `hetzner_dc_health_save_failures_total` (counter) - failed writes; non-zero rate is operationally actionable.
- `hetzner_dc_health_stale_open_resets_total{location}` (counter) - breakers reset from OPEN to CLOSED on load due to the 30-min stale-OPEN TTL.

## v103.percona.18 (2026-05-18)

Codex review follow-up on v103.percona.17. The new `HungBuildDetector` and its three metrics shipped to ps3.cd as a canary; the review surfaced two correctness blockers and one observability fault that would have made fleet rollout silently misleading. v18 fixes all four before promoting beyond ps3.

### Fixed

- `HungBuildDetector.scan()` is now `synchronized(this)`. The Jenkins `PeriodicWork` scheduler does not contractually forbid overlapping ticks if a single tick runs longer than the recurrence period (e.g. an executor walk that hangs on a stuck node lookup). The v17 implementation mutated the `seen` LinkedHashMap and called `STUCK_BUILDS_TOTAL.inc()` without a lock; two overlapping ticks could double-count a single hung build. Wrapping the whole `scan()` body in `synchronized(this)` keeps the dedup contract honest.
- `hetzner_oldest_build_age_seconds` now clears stale template children at the end of every tick. v17 populated `templatesObserved` but never used it; once a hung build finished, the gauge stayed pinned at the last observed peak forever, scaring operators into investigating builds that had already completed. New `clearStaleAgeChildren()` reads the family's existing samples via the simpleclient `collect()` API, and calls `Gauge.remove(template)` on any child whose `template` label is not in `templatesObserved` for the current tick.

### Changed

- Dropped the in-plugin `master` label from `hetzner_stuck_builds_total`, `hetzner_oldest_build_age_seconds`, and the renamed `hetzner_jenkins_real_busy_executors` gauge. Per ADR 0013 in the `percona-ci-platform` repo, master-side Grafana Alloy injects `master="<inst>.cd"` via relabel config on the push pipeline, so the plugin-side value was redundant; worse, Alloy's `external_labels` only fill MISSING labels, not existing empty ones, so the v17 fallback (empty string when `JenkinsLocationConfiguration.getUrl()` was unset) emitted series dashboards filtering `master=~".+\\.cd"` would silently drop. Removed `HetznerMetricProvider.masterLabel()` and the helper `deriveMasterFromUrl()` since neither has any other caller. All v16-and-earlier `hetzner_*` metrics already shipped without a plugin-emitted master label; v18 aligns the v17 newcomers with that convention.
- Renamed `hetzner_executor_busy_real` to `hetzner_jenkins_real_busy_executors`. The original name broke the `hetzner_<domain>_<thing>` convention used by every other Jenkins-side metric in the plugin; the rename keeps the family namespace clean and groups the gauge alongside other `hetzner_jenkins_*` metrics. No live Grafana panel referenced the v17 name yet (panels 210/211 use `hetzner_stuck_builds_total` and `hetzner_oldest_build_age_seconds`), so this is a safe rename.

### Added tests

- `HungBuildDetectorTest.concurrent_scan_does_not_double_count` -- spawns two threads racing on `scan()` with the same stuck build; asserts `STUCK_BUILDS_TOTAL` increments exactly once. Pins the v18 blocker-1 fix.
- `HungBuildDetectorTest.oldest_age_gauge_clears_when_build_finishes` -- tick 1 has a hung build on `unknown`; tick 2 has no hung builds; asserts the gauge family contains NO samples for `unknown` after tick 2. Pins the v18 blocker-2 fix.
- `HungBuildDetectorTest.oldest_age_gauge_clears_when_template_no_longer_observed` -- variant where a stale child for `old-tpl` is injected; the next tick must remove it while keeping the current template's child intact.
- `HungBuildDetectorTest.ttl_expiry_re_arms_dedup` -- injects a fake clock, advances 8 days past the 7-day TTL, and asserts the same hung build re-arms the counter (otherwise a build hung for >7 days would silently stop counting).

### Migration notes

- Any Grafana queries or alerts referencing the `master` label on `hetzner_stuck_builds_total`, `hetzner_oldest_build_age_seconds`, or `hetzner_executor_busy_real` must drop the `master=` filter. Alloy adds the same `master="<inst>.cd"` label downstream via relabel config on the push pipeline, so any external query that previously matched on the in-plugin label keeps working at the Mimir side once that filter is removed (Alloy's value is identical to what the in-plugin helper would have produced when `JenkinsLocationConfiguration.getUrl()` was set).
- Grafana queries or alerts referencing `hetzner_executor_busy_real` must be updated to `hetzner_jenkins_real_busy_executors`. Within Percona's repos there are no live references to the v17 name; the rename is a pre-emptive cleanup.

## v103.percona.17 (2026-05-16)

Detect long-running ("hung") builds where `Run.isBuilding()` keeps returning `true` for days, and emit Prometheus metrics so Mimir surfaces the condition before it accretes into a fleet-wide outage.

### Fixed
- New `HungBuildDetector` (PeriodicWork, period: 10 minutes by default) iterates the controller's executors on every tick, identifies still-building runs older than the configured threshold (24h by default), and increments a dedup-cached counter for each unique build. Without this, the v103.percona.16 + CLI `executors --real` pipeline trusted `Run.isBuilding()` to filter zombie executors; production traffic on rel.cd and pmm.cd revealed runs reporting `isBuilding=true` for 3.9 to 6.4 days, stalling the idle-master plugin-upgrade cron for 6+ hours on 2026-05-15/16.
- A new gauge `hetzner_executor_busy_real{master}` mirrors the CLI's `--real` count plugin-side, so readiness/is-idle probes can hit `/hetzner-prometheus` directly instead of round-tripping a Groovy snippet through Script Console for every check.

### Why
- The CLI's `executors --real` flag (shipped 2026-05-16) is the load-bearing filter for the idle-master cron. Trusting `Run.isBuilding()` alone is insufficient: layering an independent age threshold on top is necessary to distinguish real work from corrupted run state. The plugin owns the data Jenkins doesn't honestly report through its own APIs.

### Added metrics
- `hetzner_stuck_builds_total{master, template, threshold_hours}` -- Counter. Incremented exactly once per (build, threshold) when the build first crosses the threshold; a 7-day dedup TTL covers the longest plausible stuck-build lifetime before manual intervention. Use `increase()` / `rate()` over the total since a JVM restart drops the dedup cache.
- `hetzner_oldest_build_age_seconds{master, template}` -- Gauge. Set on every tick to the max elapsed time among still-building runs grouped by Hetzner template. Templates without in-flight builds at the tick boundary are absent from the series for that emit cycle.
- `hetzner_executor_busy_real{master}` -- Gauge. Count of executors whose currently-executing `Queue.Executable` is a `Run` with `isBuilding=true`.

### Configuration knobs (system properties)
- `hetzner.hung-build.threshold-hours` (default `24`) -- a build older than this is classified hung. Honored fresh on each tick.
- `hetzner.hung-build.poll-period-minutes` (default `10`) -- detector recurrence period. Read by Jenkins scheduler on each reschedule.
- `hetzner.master.label` (operator override) -- the `master` label value emitted on the new metrics. Falls back to the leading hostname of `JenkinsLocationConfiguration.getUrl()` with a `.cd` suffix (e.g. `rel.cd`), then empty (Alloy's `external_labels` on the push pipeline adds the value downstream).

### Implementation notes
- Iterates `Jenkins.getComputers()[].getExecutors()` rather than `Jenkins.getAllItems(Run.class)`: the executor-walking path is bounded by the executor count (typically <100 per master) and avoids touching cold historical run state.
- `templateOf(Computer)` walks `computer.getNode()` and casts to `HetznerServerAgent` to recover the template name. Non-Hetzner agents (built-in controller, EC2, manually-added nodes) and post-restart deserialized agents (template is transient) report `template="unknown"`; dashboards should filter `template!="unknown"` on per-template panels.
- A threshold tweak via system property re-arms the counter at the new boundary (the dedup key includes `thresholdHours`), but does NOT reset the existing dedup cache. A build that already crossed 24h does not re-arm if the threshold drops to 12h. Restart the JVM (or wait 7 days for cache expiry) to re-arm such builds explicitly.
- Defensive `try/catch(Throwable)` in `doRun()` mirrors `HetznerMetricsRefresher` / `OrphanedNodesCleaner`: a Jenkins-core or Hetzner-API exception cannot kill the timer.

## v103.percona.16 (2026-05-15)

Periodically refresh `hetzner_running_servers` and `hetzner_provisioning_pending` regardless of provisioning activity.

### Fixed
- `hetzner_running_servers` no longer pins to the last-known value when a cloud is idle. New `HetznerMetricsRefresher` (PeriodicWork, period: 1 minute) walks every configured `HetznerCloud` and calls a new `public HetznerCloud.refreshMetrics()` method, which re-queries the Hetzner API for the live server count and re-emits the in-memory pending counter. Observed before the fix: psmdb.cd reported `hetzner_running_servers=25` in Mimir while the Hetzner API showed only 2 running servers; the cloud had drained from a recent peak but no new provision attempts triggered a gauge update, so the metric was stuck for hours.
- The new `refreshMetrics()` method is also called defensively against `PROVISIONING_PENDING` from the in-memory `pendingProvisions` AtomicInteger, so a forgotten `set(...)` after an atomic mutation does not desync the gauge.

### Why
- Field deployment of `v103.percona.15` to the 10-master Percona Jenkins fleet exposed the discrepancy when validating the new dashboard panel 200 ("Active workers per master") at 2026-05-15 09:30 UTC. The new panel would show 25/100 utilisation for psmdb when the truth was 2/100, making the dashboard misleading.

### Rate-limit hygiene
- Per-cloud refresh consumes one Hetzner API call per minute (60/hour). Token budget is 3600/hour, so even 15 templates across 10 masters share the cost comfortably. The refresher skips refresh if the credentials' rate-limiter is open, same pattern as `OrphanedNodesCleaner`.

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
