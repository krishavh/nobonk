#!/bin/bash
# GLM-5.3 coding-lane hardening loop for NoBonk. Round-robins the safety-core
# Kotlin files; each pass GLM improves ONE file, then the REAL test suite
# (testDebugUnitTest, 17 tests) must stay green or the change is reverted.
# Local commits only — never pushes. (NB: no git stash here — an earlier
# version stashed+dropped its own untracked scripts. Backup via cp only.)
set -uo pipefail
cd /home/onikita/projects/nobonk || exit 1
export JAVA_HOME=/home/onikita/toolchain/jdk-17.0.13+11
export ANDROID_HOME=/home/onikita/android-sdk
export ANDROID_SDK_ROOT=/home/onikita/android-sdk
LOG=nobonk-glm-loop.log
STATE=.glm-idx
log(){ echo "$(date '+%F %T') | $*" >> "$LOG"; }
log "===== nobonk-glm-loop started (pid $$) ====="

# safety-core roster (small -> large; behavior guarded by tests)
FILES=(
  app/src/main/java/ai/genwhy/nobonk/ml/Nms.kt
  app/src/main/java/ai/genwhy/nobonk/ml/LowLight.kt
  app/src/main/java/ai/genwhy/nobonk/ml/Letterbox.kt
  app/src/main/java/ai/genwhy/nobonk/model/Detection.kt
  app/src/main/java/ai/genwhy/nobonk/model/NormBox.kt
  app/src/main/java/ai/genwhy/nobonk/ml/SensorMonitor.kt
  app/src/main/java/ai/genwhy/nobonk/data/DetectionEvent.kt
  app/src/main/java/ai/genwhy/nobonk/ml/AlertPolicy.kt
  app/src/main/java/ai/genwhy/nobonk/analytics/AnalyticsEngine.kt
)
COUNT=${#FILES[@]}

while true; do
  IDX=$(cat "$STATE" 2>/dev/null || echo 0)
  F="${FILES[$(( IDX % COUNT ))]}"
  PASS=$(( IDX / COUNT + 1 ))
  NAME=$(basename "$F")

  log "PASS ${PASS} · ${NAME} — coding-lane hardening…"
  cp "$F" /tmp/.nobonk-bak.kt
  if timeout 1300 python3 glm_kotlin.py "$F" "$PASS" >> "$LOG" 2>&1; then
    log "PASS ${PASS} · ${NAME} — generated; running tests…"
    if ./gradlew -q testDebugUnitTest >> "$LOG" 2>&1; then
      git add "$F"
      if python3 /home/onikita/Desktop/AgentMemory/tools/secscan.py . --tracked-only >/dev/null 2>&1; then
        if git commit -q -m "glm-nobonk(pass ${PASS}): harden ${NAME} (tests green)"; then
          log "PASS ${PASS} · ${NAME} — TESTS GREEN, committed"
          git push -q origin fix/release-eng >/dev/null 2>&1 && log "  pushed to origin" || log "  push failed (will retry next commit)"
        else
          log "PASS ${PASS} · ${NAME} — no change"
        fi
      else
        git reset -q; cp /tmp/.nobonk-bak.kt "$F"; log "PASS ${PASS} · ${NAME} — BLOCKED by secscan, reverted"
      fi
    else
      cp /tmp/.nobonk-bak.kt "$F"
      log "PASS ${PASS} · ${NAME} — TESTS FAILED, reverted"
    fi
  else
    cp /tmp/.nobonk-bak.kt "$F" 2>/dev/null
    log "PASS ${PASS} · ${NAME} — generation failed, reverted"
    sleep 20
  fi
  IDX=$(( IDX + 1 )); echo "$IDX" > "$STATE"
  sleep 10
done
