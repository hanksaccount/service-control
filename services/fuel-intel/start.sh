#!/data/data/com.termux/files/usr/bin/bash
# fuel-intel/start.sh - stable Service Control contract start.

PROJECT_DIR="/data/data/com.termux/files/home/projects/fuel-intel"
STOP_FLAG="$HOME/STOP_FUEL_INTEL"
SERVICE="$PROJECT_DIR/fuel_service.sh"
LOG="$PROJECT_DIR/startup.log"

rm -f "$STOP_FLAG"
cd "$PROJECT_DIR" || exit 1

pkill -f "server/index.js" 2>/dev/null || true
pkill -f "node_modules/vite/bin/vite.js" 2>/dev/null || true
pkill -f "fuel_service.sh" 2>/dev/null || true

nohup /data/data/com.termux/files/usr/bin/bash "$SERVICE" >> "$LOG" 2>&1 &
exit 0
