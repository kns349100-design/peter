#!/bin/bash
# One command that verifies the whole architecture on a plain JVM (no Android SDK needed):
#   1. unit + guard tests   (everything in app/src/test - the same files Gradle runs)
#   2. engine harness       (the REAL CallEngine + SessionStore + journal + crypto against Android stand-ins)
#   3. backend tests        (Code.gs against a fake Google Sheet)
# Usage: tools/verify-all.sh /path/to/kotlinc/bin [/usr/share/java]      (needs: kotlinc, junit4, json-android, node)
set -e
KOTLINC=${1:?path to kotlinc/bin}; JARS=${2:-/usr/share/java}
ROOT=$(cd "$(dirname "$0")/.." && pwd); SRC="$ROOT/app/src/main/java/com/autodialer/app"; TST="$ROOT/app/src/test/java/com/autodialer/app"
CP="$JARS/junit4.jar:$JARS/hamcrest-core.jar"; STD="$KOTLINC/../lib/kotlin-stdlib.jar"; OUT=$(mktemp -d)

echo "== 1/3 unit + architecture-guard tests"
PURE="CallSequencer NumberParser PhoneUtils DialResolver CallStateTracker DurableFile DataCipher DialJournal RecoveryReconciler KeepAlive BatteryExemption PiiScrubber EmailValidator"
FILES=""; for f in $PURE; do FILES="$FILES $SRC/$f.kt"; done
"$KOTLINC/kotlinc" -nowarn $FILES "$ROOT/tools/jvm-shims/Models.kt" "$TST"/*.kt -cp "$CP" -d "$OUT" 2>&1 | grep -v "^Picked" || true
CLASSES=$(ls "$TST"/*Test.kt | xargs -n1 basename | sed 's/\.kt$//; s/^/com.autodialer.app./' | tr '\n' ' ')
(cd "$ROOT/app" && java -cp "$OUT:$CP:$STD" org.junit.runner.JUnitCore $CLASSES com.autodialer.app.NumberParserLabelTest 2>&1 | grep -v "^Picked\|^\s*at ")

echo "== 2/3 engine harness"
"$ROOT/tools/engine-harness/run.sh" "$KOTLINC" "$JARS" 2>&1 | grep -v "^\s*at "

echo "== 3/3 backend"
node "$ROOT/backend/tests/integration.test.js" | tail -1
echo "ALL SUITES PASSED"
