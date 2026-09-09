#!/usr/bin/env bash
# Isolated, disposable Linux Docker fixtures; never uses business DBs or Azure credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
source_dir="$PWD"
fixture_dir=$(mktemp -d /tmp/chen-s02b-fixtures.XXXXXX)
run_id="chen-s02b-$(date +%s)-$$"
network="$run_id"
maven_cache=${S02_MAVEN_CACHE:-/tmp/chen-phase2-20260909/m2}
cleanup() {
  for db in pg mysql mongo sql; do docker rm -fv "$run_id-$db" >/dev/null 2>&1 || true; done
  docker network rm "$network" >/dev/null 2>&1 || true
  docker run --rm -u 0 -v "$fixture_dir:/fixtures" postgres:16-alpine sh -c 'rm -rf /fixtures/*' >/dev/null 2>&1 || true
  rmdir "$fixture_dir" 2>/dev/null || true
}
trap cleanup EXIT
for name in ca wrong-ca; do
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture_dir/$name.key" -out "$fixture_dir/$name.crt" -days 2 -subj "/CN=$name" > /dev/null 2>&1
done
printf '%s\n' 'subjectAltName=DNS:pg.fixture,DNS:mysql.fixture,DNS:mongo.fixture,DNS:sql.fixture,DNS:ghost.fixture' 'extendedKeyUsage=serverAuth,clientAuth' > "$fixture_dir/server.ext"
printf '%s\n' 'extendedKeyUsage=clientAuth' > "$fixture_dir/client.ext"
for name in server client; do
  cn=$name
  if [ "$name" = client ]; then cn=fixture; fi
  openssl req -newkey rsa:2048 -nodes -keyout "$fixture_dir/$name.key" -out "$fixture_dir/$name.csr" -subj "/CN=$cn" >/dev/null 2>&1
  openssl x509 -req -in "$fixture_dir/$name.csr" -CA "$fixture_dir/ca.crt" -CAkey "$fixture_dir/ca.key" -CAcreateserial -out "$fixture_dir/$name.crt" -days 2 -extfile "$fixture_dir/$name.ext" >/dev/null 2>&1
done
cat "$fixture_dir/server.crt" "$fixture_dir/server.key" > "$fixture_dir/server.pem"
# Keys belong only to this disposable fixture. PG requires private key mode 0600.
chmod 755 "$fixture_dir"
chmod 644 "$fixture_dir"/*
mkdir "$fixture_dir/pg"
cp "$fixture_dir/server.key" "$fixture_dir/pg/server.key"
docker run --rm -u 0 -v "$fixture_dir:/fixtures" postgres:16-alpine sh -c 'chown 70:70 /fixtures/pg/server.key; chmod 600 /fixtures/pg/server.key'
cat > "$fixture_dir/pg_hba.conf" <<'HBA'
local all all trust
hostssl all all 0.0.0.0/0 scram-sha-256 clientcert=verify-full
hostssl all all ::/0 scram-sha-256 clientcert=verify-full
HBA
cat > "$fixture_dir/mysql-init.sql" <<'SQL'
ALTER USER 'fixture'@'%' REQUIRE X509;
GRANT ALL ON *.* TO 'fixture'@'%';
SQL
cat > "$fixture_dir/mssql.conf" <<'CONF'
[network]
tlscert = /fixtures/server.crt
tlskey = /fixtures/server.key
forceencryption = 1
CONF
cat > "$fixture_dir/logback.xml" <<'XML'
<configuration><appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="OFF"><appender-ref ref="STDOUT"/></root></configuration>
XML
docker network create "$network" >/dev/null
docker run -d --name "$run_id-pg" --network "$network" --network-alias pg.fixture -v "$fixture_dir:/fixtures:ro" \
  -e POSTGRES_USER=fixture -e 'POSTGRES_PASSWORD=FixturePass9!' -e POSTGRES_DB=fixture postgres:16-alpine \
  -c ssl=on -c ssl_cert_file=/fixtures/server.crt -c ssl_key_file=/fixtures/pg/server.key -c ssl_ca_file=/fixtures/ca.crt -c hba_file=/fixtures/pg_hba.conf >/dev/null
docker run -d --name "$run_id-mysql" --network "$network" --network-alias mysql.fixture -v "$fixture_dir:/fixtures:ro" \
  -v "$fixture_dir/mysql-init.sql:/docker-entrypoint-initdb.d/fixture.sql:ro" \
  -e 'MYSQL_ROOT_PASSWORD=FixturePass9!' -e MYSQL_USER=fixture -e 'MYSQL_PASSWORD=FixturePass9!' -e MYSQL_DATABASE=fixture mysql:8.0 \
  --ssl-ca=/fixtures/ca.crt --ssl-cert=/fixtures/server.crt --ssl-key=/fixtures/server.key --require-secure-transport=ON >/dev/null
docker run -d --name "$run_id-mongo" --network "$network" --network-alias mongo.fixture -v "$fixture_dir:/fixtures:ro" \
  -e MONGO_INITDB_ROOT_USERNAME=fixture -e 'MONGO_INITDB_ROOT_PASSWORD=Fixture + /?@Pass9' mongo:7 \
  --tlsMode requireTLS --tlsCertificateKeyFile /fixtures/server.pem --tlsCAFile /fixtures/ca.crt --tlsAllowConnectionsWithoutCertificates >/dev/null
docker run -d --name "$run_id-sql" --network "$network" --network-alias sql.fixture -v "$fixture_dir:/fixtures:ro" \
  -v "$fixture_dir/mssql.conf:/var/opt/mssql/mssql.conf:ro" \
  -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=FixturePass9!' -e MSSQL_MEMORY_LIMIT_MB=3072 mcr.microsoft.com/mssql/server:2022-latest >/dev/null
wait_ready() {
  local label=$1; shift
  for attempt in $(seq 1 120); do
    if "$@" >/dev/null 2>&1; then echo "Ready: $label"; return; fi
    sleep 2
  done
  echo "Fixture did not become ready: $label" >&2
  docker logs --tail 30 "$run_id-$label" >&2
  return 1
}
wait_ready pg docker exec "$run_id-pg" pg_isready -U fixture
wait_ready mysql docker exec "$run_id-mysql" mysql -uroot '-pFixturePass9!' -e 'SELECT 1'
wait_ready mongo docker exec "$run_id-mongo" mongosh --quiet --tls --tlsAllowInvalidCertificates -u fixture -p 'Fixture + /?@Pass9' --eval 'db.runCommand({ping:1})'
wait_ready sql docker exec "$run_id-sql" /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'FixturePass9!' -Q 'SELECT 1'
docker run --rm --network "$network" -v "$source_dir:/w" -v "$fixture_dir:/fixtures" -v "$maven_cache:/root/.m2" -w /w \
  maven:3.9.9-eclipse-temurin-17 bash -c '
    set -euo pipefail
    keytool -importcert -noprompt -alias fixture -file /fixtures/ca.crt -keystore /fixtures/trust.jks -storepass fixturepass >/dev/null 2>&1
    mvn -q -pl backend/web -am -DskipTests -Dmaven.antrun.skip=true test-compile dependency:build-classpath -Dmdep.outputFile=target/cp-phase2.txt -Dmdep.includeScope=test
    probe_cp="backend/web/target/test-classes:backend/web/target/classes:backend/modules/target/test-classes:backend/modules/target/classes:backend/framework/target/classes:backend/wisp/target/classes:$(cat backend/web/target/cp-phase2.txt)"
    timeout 240 java -Dlogback.configurationFile=/fixtures/logback.xml -Djavax.net.ssl.trustStore=/fixtures/trust.jks -Djavax.net.ssl.trustStorePassword=fixturepass -cp "$probe_cp" TestConnectionTlsIntegration
  '
