#!/usr/bin/env bash
# Called by the server when an admin issues a Deploy command.
# 1. Commits and pushes any local server-side changes
# 2. Pulls latest code from remote
# 3. Rebuilds the server JAR
# 4. Exits with code 42 so restart.sh loops and relaunches the new build
#
# Must be run from the project root (restart.sh handles this).

set -e

echo "[deploy.sh] Starting deploy at $(date)"

# 1. Commit any local changes (audio assets, config, etc.)
git add -A
if ! git diff --cached --quiet; then
    git commit -m "auto: server-side changes [$(date '+%Y-%m-%d %H:%M:%S')]"
    echo "[deploy.sh] Committed local changes"
else
    echo "[deploy.sh] No local changes to commit"
fi

# 2. Push
git push && echo "[deploy.sh] Pushed to remote" || echo "[deploy.sh] Push failed (continuing)"

# 3. Pull latest
git pull --rebase && echo "[deploy.sh] Pulled latest code"

# 4. Rebuild server and client JARs
./gradlew :server:jar :client:jar --quiet && echo "[deploy.sh] Build complete"

# 5. Copy client JAR to assets/client/ so the sync app can distribute it
mkdir -p assets/client
cp client/build/libs/game-client.jar assets/client/
echo "[deploy.sh] Client JAR copied to assets/client/"

# 6. Ensure server.properties includes 'client' in server.client.sync.types
PROPS="server.properties"
if [ -f "$PROPS" ]; then
    CURRENT=$(grep "^server.client.sync.types" "$PROPS" | cut -d= -f2)
    if [ -n "$CURRENT" ] && [[ "$CURRENT" != *"client"* ]]; then
        sed -i "s|^server.client.sync.types=.*|server.client.sync.types=${CURRENT},client|" "$PROPS"
        echo "[deploy.sh] Added 'client' to server.client.sync.types in $PROPS"
    fi
fi

echo "[deploy.sh] Deploy complete — signalling restart"
# Exit code 42 is caught by restart.sh
exit 42
