#!/usr/bin/env bash
# Deploy hetzner-cloud plugin to a single Jenkins instance via Script Console.
# Downloads HPI from GitHub release, pins it, and smart-restarts.
# Usage: ./scripts/deploy.sh <instance> <version>
set -euo pipefail

inst="${1:?Usage: deploy.sh <instance> <version>}"
version="${2:?Usage: deploy.sh <instance> <version>}"

echo "=== $inst ==="

# Deploy via Script Console (download from GitHub release)
groovy_script=$(cat <<GROOVY
def version = "${version}"
def url = "https://github.com/nogueiraanderson/hetzner-cloud-plugin/releases/download/v\${version}/hetzner-cloud-\${version}.hpi"
def pluginDir = Jenkins.instance.pluginManager.rootDir
def targetFile = new File(pluginDir, "hetzner-cloud.jpi")
def backupFile = new File(pluginDir, "hetzner-cloud.jpi.bak")
def pinFile = new File(pluginDir, "hetzner-cloud.jpi.pinned")

if (targetFile.exists()) {
    if (backupFile.exists()) backupFile.delete()
    targetFile.renameTo(backupFile)
    println "backed-up"
}

def conn = new URL(url).openConnection()
conn.setInstanceFollowRedirects(true)
def response = conn.responseCode
if (response == 302 || response == 301) {
    conn = new URL(conn.getHeaderField("Location")).openConnection()
}
def bytes = conn.inputStream.bytes
targetFile.bytes = bytes
pinFile.text = "pinned"

def md5 = java.security.MessageDigest.getInstance("MD5")
md5.update(bytes)
def hash = md5.digest().collect { String.format("%02x", it) }.join("")
println "deployed|\${bytes.length}|\${hash}"
GROOVY
)

result=$(jenkins admin -i "$inst" groovy -e "$groovy_script" 2>&1 \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('message','FAIL'))" 2>/dev/null || echo "FAIL")
echo "  $result"

# Smart restart. Quiet-down is banned (blocks new builds + queues a
# surprise restart that lands mid-shift). For busy masters we leave the
# .jpi.pinned in place and wait for the next natural JVM restart.
busy=$(jenkins admin -i "$inst" executors -r 2>&1 | tail -n +2 | wc -l)
if [[ "$busy" -eq 0 ]]; then
    echo "  Idle. Force restarting..."
    jenkins admin -i "$inst" restart --force 2>/dev/null || true
else
    echo "  $busy busy executors. Pin-only; .jpi.pinned waits for next JVM restart."
fi
echo ""
