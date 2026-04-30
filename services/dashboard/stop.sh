#!/data/data/com.termux/files/usr/bin/bash
# dashboard/stop.sh — kontrakt: stoppar tjänsten rent
# Notis-ID: dashboard_pro (befintligt stop.sh i upstream använder fel IDs 43/44/45 — fixat här)

pkill -f "projects/phone-dashboard/dashboard.py" 2>/dev/null || true
pkill -f "projects/phone-dashboard/battery_monitor.sh" 2>/dev/null || true
termux-notification-remove dashboard_pro 2>/dev/null || true
termux-wake-unlock 2>/dev/null || true

exit 0
