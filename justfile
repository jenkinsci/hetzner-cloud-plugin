# Hetzner Cloud Plugin for Jenkins - Build, Test & Deploy
# Patched version with retention bug fixes + DC failover (Percona)

version := "103.percona.18"
image := "maven:3.9-eclipse-temurin-17"
container := "hetzner-build"
m2_volume := "hetzner-m2-cache"
instances := "rel ps80 psmdb pxc pxb pg ps57 pmm cloud ps3"

# Build plugin .hpi (skipping tests for speed)
build:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean package -DskipTests -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker cp {{container}}:/plugin/target/hetzner-cloud.hpi ./hetzner-cloud-{{version}}.hpi
    docker rm {{container}}
    ls -lh ./hetzner-cloud-{{version}}.hpi

# Build and run tests
test:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean verify -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker rm {{container}}

# Deploy to a single instance (upload + pin + smart restart)
deploy inst:
    ./scripts/deploy.sh {{inst}} {{version}}

# Deploy to all 10 instances
deploy-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for inst in {{instances}}; do
        ./scripts/deploy.sh "$inst" {{version}}
    done

# Check plugin version and executor status across all instances
check:
    ./scripts/check.sh "{{instances}}"

# Verify plugin on a single instance (create temp job, run, check, delete)
verify inst:
    ./scripts/verify.sh {{inst}}

# Verify plugin on all instances
verify-all:
    ./scripts/verify.sh {{instances}}

# Backup cloud config from a Jenkins instance
backup inst:
    ./scripts/backup.sh {{inst}}

# Restore cloud config to a Jenkins instance from backup
restore inst:
    ./scripts/restore.sh {{inst}}

# Show recent cloud plugin logs from Jenkins system log
# Usage: just logs <inst> [count] [filter]
#   filter: all (default), hetzner, ec2
logs inst n="30" filter="all":
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{filter}}" in
      hetzner) kw='["dnation", "hetzner", "HetznerCloud", "NodeCallable"]' ;;
      ec2)     kw='["ec2", "EC2Cloud", "EC2Computer", "EC2Fleet"]' ;;
      all)     kw='["dnation", "hetzner", "ec2", "EC2Cloud", "EC2Computer", "EC2Fleet", "NodeCallable", "HetznerCloud"]' ;;
      *)       echo "Unknown filter: {{filter}} (use: all, hetzner, ec2)"; exit 1 ;;
    esac
    jenkins admin -i {{inst}} groovy -e "def kw = ${kw}; def log = jenkins.model.Jenkins.instance.logRecords; def recs = log.findAll { r -> kw.any { r.loggerName?.contains(it) || r.message?.contains(it) } }.take({{n}}); recs.each { r -> println \"\${new Date(r.millis).format('HH:mm:ss')} [\${r.level}] \${r.loggerName?.tokenize('.')?.last()}: \${r.message?.take(200)}\" }; println '---'; println \"\${recs.size()} of \${log.size()} system log records\""

# Show DC circuit breaker health via Script Console
dc-health inst:
    jenkins admin -i {{inst}} groovy -f scripts/dc-health-check.groovy

# Verify DC failover by testing all 5 server types on an instance
verify-failover inst:
    #!/usr/bin/env bash
    set -euo pipefail
    labels=("launcher-x64" "docker-x64-min" "docker-x64" "docker-aarch64-min" "docker-aarch64")
    echo "=== DC Failover Verification on {{inst}} ==="
    for label in "${labels[@]}"; do
        echo "--- Testing label: $label ---"
        groovy=$(cat <<GROOVY
    import jenkins.model.Jenkins
    def job = Jenkins.instance.getItem("verify-failover-${label}")
    if (job) { job.delete() }
    def xml = """<?xml version='1.0' encoding='UTF-8'?>
    <flow-definition>
      <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition">
        <script>
    node('${label}') {
        sh 'uname -m'
        sh 'echo VERIFY_FAILOVER_OK'
    }
        </script>
        <sandbox>true</sandbox>
      </definition>
      <assignedNode>${label}</assignedNode>
    </flow-definition>"""
    def is = new ByteArrayInputStream(xml.getBytes("UTF-8"))
    def newJob = Jenkins.instance.createProjectFromXML("verify-failover-${label}", is)
    newJob.scheduleBuild2(0)
    println "TRIGGERED|${label}"
    GROOVY
    )
        jenkins admin -i {{inst}} groovy -e "$groovy" 2>&1 \
            | python3 -c "import json,sys; print(json.load(sys.stdin).get('message','FAIL'))" 2>/dev/null || echo "FAIL"
    done
    echo ""
    echo "Monitor builds in Jenkins UI. Clean up with:"
    echo "  for l in launcher-x64 docker-x64-min docker-x64 docker-aarch64-min docker-aarch64; do"
    echo "    jenkins job -i {{inst}} delete verify-failover-\$l"
    echo "  done"

# Create a GitHub release and tag.
#
# Idempotent: re-running after a partial failure skips the steps that already
# succeeded (e.g. if the tag pushed but `gh release create` failed because of
# a remote-detection bug, you can re-run without "tag already exists" errors).
#
# `--repo` is pinned to the Percona fork so `gh` does not pick up the
# `upstream` remote (jenkinsci/hetzner-cloud-plugin). Without the pin, gh
# tries to create the release in the upstream repo and fails on auth /
# tag-on-different-fork.
release:
    #!/usr/bin/env bash
    set -euo pipefail
    tag="v{{version}}"
    hpi="hetzner-cloud-{{version}}.hpi"
    fork_repo="nogueiraanderson/hetzner-cloud-plugin"
    if [[ ! -f "$hpi" ]]; then
        echo "HPI not found. Run: just build"
        exit 1
    fi
    if git rev-parse "$tag" >/dev/null 2>&1; then
        echo "Tag $tag already exists locally, skipping git tag"
    else
        git tag -s "$tag" -m "Release {{version}}"
    fi
    if git ls-remote --tags origin "$tag" 2>/dev/null | grep -q "$tag"; then
        echo "Tag $tag already on origin, skipping push"
    else
        git push origin "$tag"
    fi
    if gh release view "$tag" --repo "$fork_repo" >/dev/null 2>&1; then
        echo "Release $tag already exists on $fork_repo, uploading asset only"
        gh release upload "$tag" "$hpi" --repo "$fork_repo" --clobber
    else
        gh release create "$tag" "$hpi" --repo "$fork_repo" \
            --title "{{version}}" \
            --notes "Hetzner Cloud Plugin {{version}} (Percona patched)"
    fi
    echo "Released $tag with $hpi"

# Clean build artifacts and cache
clean:
    rm -f hetzner-cloud-*.hpi
    docker rm -f {{container}} 2>/dev/null || true

# Nuke Maven cache volume (forces full re-download)
clean-cache:
    docker volume rm {{m2_volume}} 2>/dev/null || true
