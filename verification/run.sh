#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/../app/src/main/java"
mkdir -p "$ROOT/classes"
javac -encoding UTF-8 -d "$ROOT/classes" \
  "$ROOT"/stubs/android/os/*.java "$ROOT"/stubs/android/util/*.java "$ROOT"/stubs/androidx/annotation/*.java \
  "$SRC"/com/damum/daycaretemi/robot/RobotController.java \
  "$SRC"/com/damum/daycaretemi/game/HideAndSeekGame.java \
  "$SRC"/com/damum/daycaretemi/safety/SafetyEvent.java \
  "$SRC"/com/damum/daycaretemi/safety/SafetyEventBus.java \
  "$SRC"/com/damum/daycaretemi/server/CommandRunner.java   "$SRC"/com/damum/daycaretemi/play/MemoryGame.java   "$SRC"/com/damum/daycaretemi/play/OfflineMemoryPlanner.java   "$SRC"/com/damum/daycaretemi/play/PlaySupportCoach.java   "$ROOT"/test/LiteGameScenarios.java \
  "$ROOT"/test/TestScenarios.java \
  "$ROOT"/test/CommandRunnerScenarios.java
java -cp "$ROOT/classes" TestScenarios
java -cp "$ROOT/classes" CommandRunnerScenarios
java -cp "$ROOT/classes" LiteGameScenarios
