#!/usr/bin/env bash
# Deterministic probes only. No database credentials or running servers required.
set -euo pipefail
cd "$(dirname "$0")/.."
# Backend probes do not need the web module's frontend/dist copy step.
mvn -q -pl backend/web -am -DskipTests -Dmaven.antrun.skip=true test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/cp-phase2.txt -Dmdep.includeScope=test
probe_cp="backend/web/target/test-classes:backend/web/target/classes:backend/modules/target/test-classes:backend/modules/target/classes:backend/framework/target/test-classes:backend/framework/target/classes:backend/wisp/target/classes:$(cat backend/web/target/cp-phase2.txt)"
tests=(
  TestSqlTextFidelity TestTreeKeyBoundaries TestSqlExecutionLifecycle TestRelationalMetadataBoundaries TestConsoleTitleIdentity
  TestACLRuleBoundaries TestACLReviewLifecycle TestApprovalEstimate TestSessionExecutionBoundary TestResourceQueryBoundary
  TestMongoExecutionBoundaries TestMongoCommandParser TestMongoQueryLoader TestMongoRiskControl TestMongoAuditCoverage
  TestMongoExecutionStatsBuilder TestMongoEntraAuthSupport TestMongoDocumentCompleteness TestMongoDocumentExport
  TestQueryLimitBehavior TestSqlServerTop TestSqlPermissionErrorClassifier
  TestColumnSizeKeyResolver TestStreamingStatsBuilder TestStreamingSizeParity
  TestTlsResourceCleanup TestConnectionManagerLifecycle
  TestSessionRenewalRpc TestSessionTokenRenewal
  TestDriverLoading
  TestConnectionAuthBoundaries TestConnectionCreationCleanup TestConnectionConfiguration TestConnectionConfigurationBaseline
  TestSessionBoundaries TestConsoleOrdering TestSessionLifetime TestSessionHttpIntegration TestSessionTaskReconnect
  TestAuditTag TestSqlScriptParserAndValidator
  TestConsoleFiles TestQueryConsoleSecurity
  TestDataViewColumns TestDataViewExport TestRelationalAuthFlowHandler
  TestSqlServerAccessTokenBridge TestSslPropsDerivation
)
failed=0
for test in "${tests[@]}"; do
  echo "Running $test"
  if ! java -cp "$probe_cp" "$test"; then
    echo "FAILED: $test" >&2
    failed=$((failed + 1))
  fi
done
echo "Phase 2 probes: ${#tests[@]} executed, $failed failed"
test "$failed" -eq 0
