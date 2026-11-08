#!/usr/bin/env bash
# Builds if needed, then keeps searching every few hours and serves the dashboard at http://localhost:8080
cd "$(dirname "$0")"
[ -f jobhunter.jar ] || ./build.sh
exec java -jar jobhunter.jar daemon --every-hours "${1:-3}"
