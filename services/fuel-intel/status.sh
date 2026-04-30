#!/data/data/com.termux/files/usr/bin/bash
# fuel-intel/status.sh - returns RUNNING, DEGRADED, or STOPPED.

backend=0
frontend=0

curl -s --max-time 1 http://127.0.0.1:5201 >/dev/null 2>&1 && backend=1
curl -s --max-time 1 http://127.0.0.1:5210 >/dev/null 2>&1 && frontend=1

if [ "$backend" -eq 1 ] && [ "$frontend" -eq 1 ]; then
    echo "RUNNING"
elif [ "$backend" -eq 1 ] || [ "$frontend" -eq 1 ]; then
    echo "DEGRADED"
else
    echo "STOPPED"
fi

exit 0
