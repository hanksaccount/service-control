#!/data/data/com.termux/files/usr/bin/bash
# autosort/stop.sh - stable Service Control contract stop.

PROJECT_DIR="/data/data/com.termux/files/home/projects/autosort"
STOP_FLAG="$HOME/STOP_AUTOSORT"

touch "$STOP_FLAG"
pkill -9 -f "autosort-daemon.sh" 2>/dev/null || true
pkill -9 -f "python3 -m src.main" 2>/dev/null || true
pkill -9 -f "src/panel.py" 2>/dev/null || true
rm -f "$PROJECT_DIR/data/autosort-daemon.pid" "$PROJECT_DIR/data/autosort-daemon.heartbeat"
termux-notification-remove autosort-main 2>/dev/null || true
termux-notification-remove autosort-error 2>/dev/null || true
termux-notification-remove autosort-stopped 2>/dev/null || true

exit 0
