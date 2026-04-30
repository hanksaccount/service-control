#!/data/data/com.termux/files/usr/bin/bash
# elpris/status.sh - stable Service Control contract status.

if curl -s --max-time 1 http://127.0.0.1:5100 >/dev/null 2>&1; then
    echo "RUNNING"
else
    echo "STOPPED"
fi

exit 0
