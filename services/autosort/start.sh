#!/data/data/com.termux/files/usr/bin/bash
# autosort/start.sh - stable Service Control contract start.

PROJECT_DIR="/data/data/com.termux/files/home/projects/autosort"
STOP_FLAG="$HOME/STOP_AUTOSORT"
LOG_DIR="$PROJECT_DIR/data/logs"

rm -f "$STOP_FLAG"
mkdir -p "$LOG_DIR"

pkill -f "projects/autosort/scripts/autosort-daemon.sh" 2>/dev/null || true
pkill -f "src/panel.py" 2>/dev/null || true

bash "$PROJECT_DIR/scripts/panel-start.sh" >/dev/null 2>&1 || true
nohup /data/data/com.termux/files/usr/bin/bash "$PROJECT_DIR/scripts/autosort-daemon.sh" >> "$PROJECT_DIR/data/logs/daemon.out" 2>&1 &
exit 0
