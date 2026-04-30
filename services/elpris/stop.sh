#!/data/data/com.termux/files/usr/bin/bash
# elpris/stop.sh - stable Service Control contract stop.

STOP_FLAG="$HOME/STOP_ELPRIS"

touch "$STOP_FLAG"
pkill -f "projects/elpris-server/server.py" 2>/dev/null || true
pkill -f "projects/elpris-server/elpris_service.sh" 2>/dev/null || true
termux-notification-remove 42 2>/dev/null || true

exit 0
