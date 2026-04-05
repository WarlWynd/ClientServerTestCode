#!/usr/bin/env bash
# Wrapper that manages server restarts and deploys.
# Usage: bash restart.sh [jar-path]
#
# Exit codes from the server process:
#   42  — simple restart (loop immediately)
#   43  — deploy requested: run deploy.sh, then restart
#   any other — clean stop or crash (exit the loop)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR="${1:-$PROJECT_ROOT/server/build/libs/game-server.jar}"

cd "$PROJECT_ROOT"

while true; do
    echo "[restart.sh] Starting server: java -jar $JAR"
    java -jar "$JAR"; CODE=$?

    if [ "$CODE" -eq 42 ]; then
        echo "[restart.sh] Restart requested — restarting in 2s..."
        sleep 2

    elif [ "$CODE" -eq 43 ]; then
        echo "[restart.sh] Deploy requested — running deploy.sh..."
        bash "$SCRIPT_DIR/deploy.sh"
        echo "[restart.sh] Deploy finished — restarting..."
        sleep 1

    else
        echo "[restart.sh] Server exited with code $CODE — stopping."
        exit "$CODE"
    fi
done
