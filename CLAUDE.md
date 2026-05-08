# hetzner-cloud-plugin (Claude bootstrap notes)

Percona fork of `jenkinsci/hetzner-cloud-plugin`. Human-facing docs live in
`README.md` and `CHANGELOG.md`. This file captures non-obvious traps so a fresh
session can avoid the slow re-discovery path.

## Build

- Standard path: `just build` (uses Docker on m3 with `hetzner-m2-cache` volume,
  ~2min cached).
- Version is set via Maven CI-friendly `-Dchangelist=`, NOT by editing `pom.xml`.
  The pom uses `<version>${changelist}</version>` with default `999999-SNAPSHOT`.
  If invoking `mvn` directly outside `just`:
  `mvn package -DskipTests -Dchangelist=103.percona.N`.
- m3 Docker is unreliable (the Tailscale `docker` node has been offline since
  ~2026-04-15). Fallback to local Maven on the Linux VM:
  - `sudo apt install -y openjdk-21-jdk-headless` provides `javac`. The JRE-only
    headless package does NOT include javac and the build will fail with
    `release version 17 not supported`.
  - JDK 21 builds the Jenkins 2.479.3 baseline cleanly, verified 2026-05-08.
  - Apache Maven 3.9.9 from the official archive works; no system maven needed.
  - Full build with cold dep cache: ~1min, output at `target/hetzner-cloud.hpi`.

## Endpoints (Stapler)

- For endpoints scraped by master-side Alloy over loopback, use
  `implements UnprotectedRootAction` (`hudson.model.UnprotectedRootAction`),
  NOT `RootAction`. Removing `Jenkins.get().checkPermission(SYSTEM_READ)` inside
  `doIndex()` is **insufficient**: `GlobalMatrixAuthorizationStrategy` (any
  strategy that doesn't grant `Jenkins.READ` to anonymous) returns 403 before
  `doIndex` runs. This was the v10 -> v11 fix. Trust boundary is the 8080
  loopback bind, not core ACLs.
- `curl http://127.0.0.1:8080/hetzner-prometheus` returns 302 -> `/hetzner-prometheus/`.
  Alloy `prometheus.scrape` follows redirects by default, but configure the URL
  with the explicit trailing slash to skip the round-trip.
- AL2023 (the canonical master OS) does not ship `unzip`. To inspect the
  installed plugin's MANIFEST without installing extra packages, use
  `python3 -c "import zipfile; ..."`.

## Deploy

- `just deploy <inst>` / `just deploy-all`. `scripts/deploy.sh` downloads the
  HPI from `nogueiraanderson/hetzner-cloud-plugin` GitHub Release, NOT the local
  build artifact. Cut a Release first: `gh release create v103.percona.N
  hetzner-cloud-103.percona.N.hpi --repo nogueiraanderson/hetzner-cloud-plugin`.
- Verify: `jenkins hetzner -i <inst> version` (MD5 + version) and
  `jenkins hetzner fleet versions` for the cross-fleet view.

## Push-model integration

The plugin's `/hetzner-prometheus` endpoint is the source for the LGTM push
pipeline in `nogueiraanderson/percona-ci-platform`. Master-side Grafana Alloy
systemd units scrape it locally and push samples to
`https://mimir-push.cd.percona.com/api/v1/metrics/write` (NOT `/api/v1/push`).
Verify end-to-end with:

```bash
~/workspace/nogueiraanderson/percona-ci-platform/scripts/verify-observability.sh <inst>
```

## See also

- Parent: `~/workspace/nogueiraanderson/CLAUDE.md` (collection-level orientation).
- Skill: `percona-jenkins-hetzner` (runtime ops, fleet ops, DC failover).
- Skill: `percona-jenkins` (plugin-upgrade mechanics, Script Console patterns).
- Skill: `percona-observability` (LGTM stack, push-model architecture, ADRs 0013/0014/0015).
