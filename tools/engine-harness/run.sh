#!/bin/bash
# Runs the REAL CallEngine + CallStateMonitor + CallSequencer on the JVM against Android stand-ins.
# Needs kotlinc and junit4 jars on the machine:  ./run.sh /path/to/kotlinc/bin /usr/share/java
set -e
KOTLINC=${1:-kotlinc}; JARS=${2:-/usr/share/java}
HERE=$(cd "$(dirname "$0")" && pwd); SRC="$HERE/../../app/src/main/java/com/autodialer/app"
CP="$JARS/junit4.jar:$JARS/hamcrest-core.jar"; OUT=$(mktemp -d)
"$KOTLINC/kotlinc" -nowarn "$HERE"/*.kt "$SRC"/CallEngine.kt "$SRC"/SecurePrefs.kt "$SRC"/PiiScrubber.kt "$SRC"/SafeLog.kt "$SRC"/DataCipher.kt "$SRC"/AndroidCrypto.kt "$SRC"/SessionStore.kt "$SRC"/FileStore.kt "$SRC"/DurableFile.kt "$SRC"/DialJournal.kt "$SRC"/RecoveryReconciler.kt "$SRC"/KeepAlive.kt "$SRC"/CallSequencer.kt "$SRC"/CallStateMonitor.kt \
  "$SRC"/CallStateTracker.kt "$SRC"/DialResolver.kt "$SRC"/PhoneUtils.kt -cp "$CP:$JARS/json-android.jar" -d "$OUT" 2>&1 | grep -v "^Picked" || true
java -cp "$OUT:$CP:$JARS/json-android.jar:$KOTLINC/../lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore com.autodialer.app.EngineHarnessTest com.autodialer.app.ProcessDeathHarnessTest com.autodialer.app.EncryptionHarnessTest com.autodialer.app.SecurePrefsHarnessTest com.autodialer.app.UnifiedSystemHarnessTest com.autodialer.app.SafeLogHarnessTest 2>&1 | grep -v "^Picked"
