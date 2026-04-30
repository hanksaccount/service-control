#!/data/data/com.termux/files/usr/bin/bash
# dashboard/start.sh — kontrakt: startar tjänsten
# Tar bort STOP-flagga och delegerar till det befintliga start-skriptet.

STOP_FLAG="$HOME/STOP_DASHBOARD"
UPSTREAM="/data/data/com.termux/files/home/projects/phone-dashboard/start.sh"

rm -f "$STOP_FLAG"
exec bash "$UPSTREAM"
