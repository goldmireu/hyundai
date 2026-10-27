# 차량 에이전트 허브 (BYOA)

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품작 **「차량 에이전트 허브」**의 소스와 실험 기록입니다.

> 음성을 지원하는 AI 서비스라면 무엇이든 차에 연결해, 필요에 따라 모듈처럼 바꿔 끼워 쓰는 차량용 AI 에이전트 허브.

허브는 에이전트를 직접 만들어 제공하지 않습니다. 기본으로 들어 있는 것은 일정 브리핑 하나이고,
나머지 에이전트는 사용자가 직접 만들거나 앱 공유 플랫폼에서 무료·유료로 받아 연결합니다(BYOA, Bring Your Own Agent).
허브는 어떤 에이전트든 차량 이벤트로 부르고, 허용한 데이터만 넘기고, **같은 주행 안전 규칙**으로 들려줍니다.

이 저장소에는 두 가지 앱 화면이 있습니다.

| 화면 | 용도 | 실행 |
| --- | --- | --- |
| `MainActivity` | 구동 실험: 핵심 흐름이 Pleos Connect 환경에서 실제로 도는지 확인 | 앱 아이콘, 또는 `--ez demo true`로 자동 시연 |
| `ScenarioActivity` | 시나리오 시연: 뉴스 요약·여행 동선·메시지 연락 에이전트를 바꿔 끼워 쓰는 장면 | `adb shell am start -n ai.byoa.probe/.ScenarioActivity` |

```
차량 이벤트 수신 → 외부 에이전트 읽기(권한 확인) → 음성 출력 → 내비 안내 시 중단·복귀
```

## 구동 실험 결과 (2026.10.27, Pleos Connect Emulator v2.1.0)

실차가 아닌 에뮬레이터 기준입니다. 측정값은 시연 실행 1회의 원본 기록 [`docs/demo-log.txt`](docs/demo-log.txt)에서 계산한 **SDK 이벤트 간격**이며, 스피커 가청 지연이나 원격 서버 응답 시간이 아닙니다. 중단·복귀는 수동 이벤트로 시험했고, 실제 내비 안내와 연동한 자동 전체 흐름은 아직 검증하지 않았습니다.

| 실험 | 결과 | 측정값 |
| --- | --- | --- |
| 음성 출력 (TextToSpeech SDK, ON_DEVICE) | 통과 | 요청 → 음성 시작 이벤트 5~48ms |
| 외부 에이전트 읽기·교체 | 통과 | 읽기 73ms(로컬 웹훅), 허용 항목만 전달(목적지 제외) |
| 내비 연결 (NaviHelper SDK) | 부분 통과 (별도 실행) | 경로 상태·남은 시간·회전 거리 수신, 정숙 구간 진입 확인, 이탈은 미확인 |
| 내비 안내 시 중단·복귀 | 통과 (수동 이벤트) | 정지 27ms, 재개 42ms, 멈춘 문장 처음부터 다시 읽음 |
| 자동 호출 결과 보류 | 통과 | 운전자가 요청할 때만 재생(시연에서는 화면 버튼) |

실험에서 확인한 사실
- NaviHelper에는 "음성 안내가 지금 나온다"는 콜백이 없습니다. 그래서 **다음 회전 300m 전부터 말하지 않는 예측형 정숙 구간**으로 구현했습니다.
- 회전 목록(`onTBTInfo`)은 가까운 순서로 오고, 거리 0인 먼 시설(톨게이트 등)이 섞여 있어 제외 처리가 필요합니다.
- Pleos TTS는 `stop()` 뒤에 완료 신호를 보내지 않습니다.

## 시나리오 시연 (`ScenarioActivity`)

음성을 지원하는 AI 서비스라면 무엇이든 바꿔 끼워 쓸 수 있다는 것을 세 가지 예시 에이전트로 보여줍니다.
대본은 [`ScenarioScript.kt`](app/src/main/kotlin/ai/byoa/probe/ScenarioScript.kt), 실행 기록은 [`docs/scenario-log.txt`](docs/scenario-log.txt)입니다.

1. 출근길 · 뉴스 요약 에이전트: 요약 3건 → "두 번째 기사 자세히" → 전체 본문, 읽는 중 내비 안내에 정지 후 같은 문장부터 재개
2. 주말 여행 · 여행 동선 에이전트: 자동 트리거는 보류 → "브리핑 들려줘" → 동선·맛집·카페·숙소 → 결제는 차가 멈춘 뒤 화면에서 사진·취소 규정 확인 후 진행
3. 퇴근길 · 메시지·연락 에이전트: 부재중·메시지 읽기 → 음성 답장 확인 후 발송 → 전화 연결
4. 주차 후 · 오늘의 기록

| 구분 | 내용 |
| --- | --- |
| 실제 동작 | Pleos 차량 음성 출력(TextToSpeech SDK), 내비 안내 시 정지·재개(SpeechGate), 자동 발화 보류 |
| 재현 | 에이전트 응답(모의 데이터), 운전자 음성 요청(말풍선), 내비 안내 시점(정해진 문장) |
| 실행하지 않음 | 예약·결제, 답장 발송, 전화 연결(확인 화면까지만) |

녹화: `bash tools/record_scenario.sh` (에뮬레이터 내장 녹화로 화면과 소리를 함께 저장, `scenario.webm`)

## 설계 원칙 (코드에 그대로 구현)

- **주행 상태를 모르면 주행 중으로 간주**: 최소 화면만 표시 (`DrivingState.UNKNOWN`)
- **자동 호출 결과는 먼저 말하지 않음**: 운전자가 요청해야 재생 (`SpeechGate.HELD`)
- **재생을 막는 사유는 따로 관리**: 내비 정숙 구간·오디오 포커스가 모두 풀려야 재개 (`SpeechGate.Block`)
- **응답 대기 중 끄면 늦은 응답은 버림**: 요청 번호로 이전 요청 무효화 (`BriefingSession.cancel`)
- **내비 정보가 비면 판단 보류**: 일시적 누락으로 정숙 구간을 풀지 않음 (`QuietZoneTracker`)
- **외부 에이전트는 읽기 전용**: 허용한 컨텍스트만 전달하고 문장만 받아 읽음 (`PermissionGate`, `ReadOnlyConnector`)
- **내비 안내가 끼어들면 정지, 끝나면 그 문장부터 재개** (`SpeechGate`)
- **남은 시간에 맞춰 분량 조절**: 읽지 못한 내용은 도착 후 화면으로 (`BriefingPolicy`)

## 구성

| 경로 | 내용 |
| --- | --- |
| `core/` | 중단·복귀 게이트, 정숙 구간 판정, 분량 규칙, 권한 게이트, 커넥터, 실험 기록. Android·Pleos 의존 없음, 단위 테스트 22개 |
| `app/` | 실험 화면, 시나리오 시연 화면, TextToSpeech 연결, NaviHelper 연결(예측형 정숙 구간), 오디오 포커스 탐침 |
| `tools/mock_agent.py` | '내 에이전트' 역할 웹훅 서버 (파이썬 표준 라이브러리) |
| `tools/record_demo.sh` | 구동 실험 자동 시연 + 에뮬레이터 화면 녹화 |
| `tools/record_scenario.sh` | 시나리오 시연 + 에뮬레이터 내장 녹화(소리 포함) |
| `docs/EXPERIMENTS.md` | 실험 체크리스트(E0~E6)와 결과 기록표 |

## 설치 파일

[Releases](https://github.com/goldmireu/hyundai/releases)에 디버그 APK가 있습니다. Pleos Connect Emulator v2.1.0(API 34)에 `adb install app-debug.apk`로 설치합니다.

## 실행

요구사항: Android Studio, Pleos Connect Emulator v2.1.0(API 34), Pleos Playground SDK 사용 신청(TextToSpeech, NaviHelper)

```bash
./gradlew :core:test          # 핵심 로직 단위 테스트
./gradlew :app:installDebug   # 에뮬레이터에 설치
bash tools/record_demo.sh     # 구동 실험 자동 시연 + 녹화 → demo.mp4
bash tools/record_scenario.sh # 시나리오 시연 + 녹화(소리 포함) → scenario.webm
```

사용 SDK: `ai.pleos.playground:TextToSpeech:3.3.1`, `ai.pleos.playground:NaviHelper:3.0.5`
(저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`)

## 아직 확인하지 않은 것

- 앱을 백그라운드로 내렸을 때의 음성 출력
- 모의 주행 중 정숙 구간 이탈
- 기어·차속(AAOS Car API) 수신
- Gleo Interface를 통한 글레오 호출
