#!/bin/bash
# 시나리오 시연을 실행하고 에뮬레이터 내장 녹화로 화면과 소리를 함께 저장한다(최대 180초).
# 실행 전: 에뮬레이터가 켜져 있어야 한다. (프로젝트 폴더에서) bash tools/record_scenario.sh
set -e
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
cd "$(dirname "$0")/.."
OUT="$(pwd)/scenario.webm"

echo "1/4 앱 빌드·설치"
./gradlew -q :app:installDebug

echo "2/4 녹화 시작 (에뮬레이터 내장 녹화, 화면+소리)"
"$ADB" shell am force-stop ai.byoa.probe
"$ADB" logcat -c
rm -f "$OUT"
"$ADB" emu screenrecord start --time-limit 178 "$OUT"
sleep 2

echo "3/4 시나리오 실행 (약 2분 40초, 끝나면 자동으로 녹화 종료)"
"$ADB" shell am start -n ai.byoa.probe/.ScenarioActivity > /dev/null
for i in $(seq 1 175); do
  if "$ADB" logcat -d -s BYOA | grep -q "scenario.end"; then break; fi
  sleep 1
done
sleep 2
"$ADB" emu screenrecord stop || true
sleep 3

echo "4/4 기록 저장"
"$ADB" logcat -d -s BYOA > ./scenario-log.txt
ls -la "$OUT"
echo "완료: $OUT  (기록: scenario-log.txt)"
