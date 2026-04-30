#!/data/data/com.termux/files/usr/bin/bash
# fuel-intel/stop.sh - stable Service Control contract stop.

touch "$HOME/STOP_FUEL_INTEL"
pkill -f "server/index.js" 2>/dev/null || true
pkill -f "node_modules/vite/bin/vite.js" 2>/dev/null || true
pkill -f "fuel_service.sh" 2>/dev/null || true
termux-notification-remove fuel-intel 2>/dev/null || true

exit 0
