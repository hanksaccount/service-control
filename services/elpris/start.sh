#!/data/data/com.termux/files/usr/bin/bash
# elpris/start.sh - stable Service Control contract start.

PROJECT_DIR="/data/data/com.termux/files/home/projects/elpris-server"
STOP_FLAG="$HOME/STOP_ELPRIS"
SERVICE="$PROJECT_DIR/elpris_service.sh"
LOG="$PROJECT_DIR/startup.log"

rm -f "$STOP_FLAG"
cd "$PROJECT_DIR" || exit 1

pkill -f "projects/elpris-server/server.py" 2>/dev/null || true
pkill -f "projects/elpris-server/elpris_service.sh" 2>/dev/null || true

nohup /data/data/com.termux/files/usr/bin/bash "$SERVICE" >> "$LOG" 2>&1 &
exit 0
