#!/usr/bin/env bash
# Builds the backend and runs driver loading through Boot's executable-jar classloader.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -pl backend/web -am -DskipTests -Dmaven.antrun.skip=true package
jar_path=backend/web/target/web-0.0.1.jar
java -Dloader.main=TestDriverLoading -Dloader.path=backend/web/target/test-classes \
  -cp "$jar_path" org.springframework.boot.loader.launch.PropertiesLauncher --packaged
# Exercise the production renewal wiring from the same executable artifact.
java -Dloader.main=TestSessionRenewalRpc -Dloader.path=backend/web/target/test-classes \
  -cp "$jar_path" org.springframework.boot.loader.launch.PropertiesLauncher
# Verify guest engine, resource bindings and child JVM launch from the shipped fat jar.
java -Dloader.main=TestMongoScriptWorker -Dloader.path=backend/modules/target/test-classes \
  -cp "$jar_path" org.springframework.boot.loader.launch.PropertiesLauncher
java -Dloader.main=TestU06DriverContract -Dloader.path=backend/web/target/test-classes \
  -cp "$jar_path" org.springframework.boot.loader.launch.PropertiesLauncher
