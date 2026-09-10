#!/usr/bin/env bash
# Disposable isolated services; no published ports or customer credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
fixture="chen-s09-$$"
cleanup() {
  docker rm -fv "$fixture-ch" "$fixture-maria" >/dev/null 2>&1 || true
  docker network rm "$fixture" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create "$fixture" >/dev/null
docker run -d --name "$fixture-ch" --network "$fixture" --network-alias s09-ch \
  -e CLICKHOUSE_SKIP_USER_SETUP=1 clickhouse/clickhouse-server:24.8 >/dev/null
docker run -d --name "$fixture-maria" --network "$fixture" --network-alias s09-maria \
  -e MARIADB_ROOT_PASSWORD=s09-fixture-only mariadb:11.4 >/dev/null
for service in ch maria; do
  ready=false
  for attempt in $(seq 1 60); do
    if { [ "$service" = ch ] && docker exec "$fixture-ch" clickhouse-client --query 'SELECT 1' >/dev/null 2>&1; } || \
       { [ "$service" = maria ] && docker exec "$fixture-maria" mariadb -uroot -ps09-fixture-only -e 'SELECT 1' >/dev/null 2>&1; }; then ready=true;break;fi
    sleep 1
  done
  "$ready" || { echo "$service did not start" >&2;exit 1; }
done
docker run --rm --network "$fixture" -v "$PWD":/w \
  -v "${S09_MAVEN_CACHE:?Set S09_MAVEN_CACHE}":/root/.m2 -w /w maven:3.9.9-eclipse-temurin-17 bash -c '
  set -euo pipefail
  mvn -q -pl backend/web -am -DskipTests -Dmaven.antrun.skip=true test-compile dependency:build-classpath -Dmdep.outputFile=target/cp-phase2.txt -Dmdep.includeScope=test
  cp="backend/web/target/test-classes:backend/web/target/classes:backend/modules/target/classes:backend/framework/target/classes:backend/wisp/target/classes:$(cat backend/web/target/cp-phase2.txt)"
  java -cp "$cp" TestS09AdapterIntegration
'
