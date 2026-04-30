#!/data/data/com.termux/files/usr/bin/bash
# dashboard/stop.sh — kontrakt: stoppar tjänsten rent
# Notis-ID: dashboard_pro (befintligt stop.sh i upstream använder fel IDs 43/44/45 — fixat här)

STOP_FLAG="$HOME/STOP_DASHBOARD"

touch "$STOP_FLAG"
pkill -f "python3 dashboard.py" 2>/dev/null || true
pkill -f "battery_monitor.sh" 2>/dev/null || true
pkill -f "phone-dashboard/start.sh" 2>/dev/null || true
termux-notification-remove dashboard_pro 2>/dev/null || true
termux-wake-unlock 2>/dev/null || true

exit 0
