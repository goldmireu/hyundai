#!/bin/bash
# 자동 시연을 실행하고 에뮬레이터 화면을 녹화해 demo.mp4로 저장한다.
# 실행 전: 에뮬레이터가 켜져 있어야 한다. (프로젝트 폴더에서) bash tools/record_demo.sh
set -e
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
cd "$(dirname "$0")/.."

echo "1/5 앱 빌드·설치"
./gradlew -q :app:installDebug

echo "2/5 모의 에이전트 서버 시작"
python3 tools/mock_agent.py > /tmp/byoa_mock_agent.log 2>&1 &
AGENT_PID=$!
trap 'kill $AGENT_PID 2>/dev/null || true' EXIT
sleep 1

echo "3/5 녹화 시작 (약 60초)"
"$ADB" shell rm -f /sdcard/byoa_demo.mp4
"$ADB" shell screenrecord --size 1920x1080 --time-limit 62 /sdcard/byoa_demo.mp4 &
REC_PID=$!
sleep 1

echo "4/5 자동 시연 실행"
"$ADB" shell am force-stop ai.byoa.probe
"$ADB" logcat -c
"$ADB" shell am start -n ai.byoa.probe/.MainActivity --ez demo true > /dev/null
wait $REC_PID || true
sleep 2

echo "5/5 영상·기록 가져오기"
"$ADB" pull /sdcard/byoa_demo.mp4 ./demo.mp4 > /dev/null
"$ADB" logcat -d -s BYOA > ./demo-log.txt
echo "완료: $(pwd)/demo.mp4  (기록: demo-log.txt)"
echo "서버가 받은 요청:"; grep "요청" /tmp/byoa_mock_agent.log || true
