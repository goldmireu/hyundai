# 차량 에이전트 허브 (BYOA) — 구동 실험

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품 기획안 **「차량 에이전트 허브」**의 구동 실험 코드입니다.

> 출근길 운전자가 폰을 집지 않고도, 자기가 쓰던 AI 에이전트의 브리핑을 차 안에서 안전하게 듣는다.

허브는 사용자가 연결한 에이전트(BYOA, Bring Your Own Agent)를 차량 이벤트로 부르고,
어떤 에이전트든 **같은 주행 안전 규칙**을 적용해 음성으로 들려줍니다.
이 저장소는 그 핵심 흐름이 Pleos Connect 환경에서 실제로 도는지 확인한 최소 앱입니다.

```
차량 이벤트 수신 → 외부 에이전트 읽기(권한 확인) → 음성 출력 → 내비 안내 시 중단·복귀
```

## 구동 실험 결과 (2026.10.26, Pleos Connect Emulator v2.1.0)

실차가 아닌 에뮬레이터 기준입니다. 원본 기록: [`docs/demo-log.txt`](docs/demo-log.txt)

| 실험 | 결과 | 측정값 |
| --- | --- | --- |
| 음성 출력 (TextToSpeech SDK, ON_DEVICE) | 통과 | 요청 → 음성 시작 9~22ms |
| 외부 에이전트 읽기·교체 | 통과 | 읽기 26ms(로컬 웹훅), 허용 항목만 전달(목적지 제외) |
| 내비 연결 (NaviHelper SDK) | 부분 통과 | 연결 142ms, 경로 상태·남은 시간·회전 거리 수신, 정숙 구간 진입 확인 |
| 내비 안내 시 중단·복귀 | 통과 (수동 이벤트) | 정지 21ms, 재개 13ms, 멈춘 문장 처음부터 다시 읽음 |
| 안전 미확인 시 자동 발화 보류 | 통과 | 보류 후 운전자가 눌렀을 때만 재생 |

실험에서 확인한 사실
- NaviHelper에는 "음성 안내가 지금 나온다"는 콜백이 없습니다. 그래서 **다음 회전 300m 전부터 말하지 않는 예측형 정숙 구간**으로 구현했습니다.
- 회전 목록(`onTBTInfo`)은 가까운 순서로 오고, 거리 0인 먼 시설(톨게이트 등)이 섞여 있어 제외 처리가 필요합니다.
- Pleos TTS는 `stop()` 뒤에 완료 신호를 보내지 않습니다.

## 설계 원칙 (코드에 그대로 구현)

- **주행 상태를 모르면 주행 중으로 간주**: 최소 화면만 표시 (`DrivingState.UNKNOWN`)
- **내비 우선순위가 검증되지 않으면 자동 발화 보류**: 운전자가 눌러야 재생 (`SpeechGate.HELD`)
- **외부 에이전트는 읽기 전용**: 허용한 컨텍스트만 전달하고 문장만 받아 읽음 (`PermissionGate`, `ReadOnlyConnector`)
- **내비 안내가 끼어들면 정지, 끝나면 그 문장부터 재개** (`SpeechGate`)
- **남은 시간에 맞춰 분량 조절**: 읽지 못한 내용은 도착 후 화면으로 (`BriefingPolicy`)

## 구성

| 경로 | 내용 |
| --- | --- |
| `core/` | 중단·복귀 게이트, 분량 규칙, 권한 게이트, 커넥터, 실험 기록. Android·Pleos 의존 없음, 단위 테스트 포함 |
| `app/` | 실험 화면, TextToSpeech 연결, NaviHelper 연결(예측형 정숙 구간), 오디오 포커스 탐침, 자동 시연 모드 |
| `tools/mock_agent.py` | '내 에이전트' 역할 웹훅 서버 (파이썬 표준 라이브러리) |
| `tools/record_demo.sh` | 자동 시연 실행 + 에뮬레이터 화면 녹화 |
| `docs/EXPERIMENTS.md` | 실험 체크리스트(E0~E6)와 결과 기록표 |

## 실행

요구사항: Android Studio, Pleos Connect Emulator v2.1.0(API 34), Pleos Playground SDK 사용 신청(TextToSpeech, NaviHelper)

```bash
./gradlew :core:test          # 핵심 로직 단위 테스트
./gradlew :app:installDebug   # 에뮬레이터에 설치
bash tools/record_demo.sh     # 자동 시연 + 녹화 → demo.mp4
```

사용 SDK: `ai.pleos.playground:TextToSpeech:3.3.1`, `ai.pleos.playground:NaviHelper:3.0.5`
(저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`)

## 아직 확인하지 않은 것

- 앱을 백그라운드로 내렸을 때의 음성 출력
- 모의 주행 중 정숙 구간 이탈
- 기어·차속(AAOS Car API) 수신
- Gleo Interface를 통한 글레오 호출
