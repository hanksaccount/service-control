#!/data/data/com.termux/files/usr/bin/bash
# dashboard/status.sh — kontrakt: returnerar RUNNING eller STOPPED
# Stdout används av appen. Exit alltid 0.

if curl -s --max-time 1 http://127.0.0.1:5000 > /dev/null 2>&1; then
    echo "RUNNING"
else
    echo "STOPPED"
fi

exit 0
