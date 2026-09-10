#!/bin/bash
#

set -euo pipefail
: "${CORE_HOST:?CORE_HOST is required}"
attempts="${CORE_WAIT_ATTEMPTS:-90}"
[[ "$attempts" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid CORE_WAIT_ATTEMPTS" >&2; exit 1; }
ready=false
for ((attempt=1; attempt<=attempts; attempt++)); do
    if [[ "$(curl --connect-timeout 5 --max-time 10 -o /dev/null -s -w '%{http_code}' "${CORE_HOST%/}/api/health/" || true)" == 200 ]]; then
        ready=true; break
    fi
    echo "Waiting for Core readiness ($attempt/$attempts)"
    sleep 2
done
[[ "$ready" == true ]] || { echo "Core readiness timed out" >&2; exit 1; }

export WORK_DIR=/opt/chen
export COMPONENT_NAME=chen
export WISP_TRACE_PROCESS=1
export EXECUTE_PROGRAM="java -Dfile.encoding=utf-8 --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError -jar /opt/chen/chen.jar --mock.enable=false"

if [ -z "${LOG_LEVEL:-}" ]; then
    LOG_LEVEL=ERROR
fi

case "$LOG_LEVEL" in TRACE|DEBUG|INFO|WARN|ERROR|OFF) ;; *) echo "Invalid LOG_LEVEL" >&2; exit 1 ;; esac
sed -i "s@level=\"INFO\"@level=\"${LOG_LEVEL}\"@g" /opt/chen/config/logback.xml

echo
date
echo "CHEN Version ${VERSION:-unknown}, more see https://www.jumpserver.org"
echo "Quit the server with CONTROL-C."
echo

cd /opt/chen || exit 1
exec wisp
