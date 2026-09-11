#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
yarn install --frozen-lockfile
yarn build
cd ..
mvn clean package -DskipTests
jars=(backend/web/target/web-*.jar)
[[ ${#jars[@]} -eq 1 && -f "${jars[0]}" ]]
exec java -Dspring.profiles.active=dev -jar "${jars[0]}"
