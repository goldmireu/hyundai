package ai.byoa.probe

/**
 * 시나리오 시연 대본.
 *
 * 허브에 여러 종류의 개인 에이전트(모듈)를 연결했을 때의 사용 흐름을 보여준다.
 * - 모듈 응답은 모의 데이터다(실제 뉴스·숙소·연락처가 아님).
 * - 운전자 음성 요청은 화면 말풍선으로 재현한다(음성 인식은 2차 PT 구현 범위).
 * - 예약·답장 발송·전화 연결은 "확인 후 실행" 흐름만 보여주고 실제로 실행하지 않는다.
 * - 음성 출력, 내비 안내 시 정지·재개, 자동 발화 보류는 실제 SpeechGate와 Pleos TTS로 동작한다.
 */
sealed interface Step {
    /** 장(章) 전환. 화면 위쪽에 상황을 표시한다. */
    data class Chapter(val title: String, val context: String) : Step

    /** 지금 말하는 모듈과, 그 모듈에 허용한 데이터 */
    data class Module(val name: String, val color: Int, val allowed: String) : Step

    /** 운전자 음성 요청(재현). releaseHeld=true면 보류된 브리핑을 이 요청으로 재생한다. */
    data class Driver(val text: String, val releaseHeld: Boolean = false) : Step

    /**
     * 모듈 응답을 허브가 읽는다.
     * @param auto 차량 이벤트로 자동 호출된 응답이면 true. 내비 우선순위 미검증이면 보류된다.
     * @param naviAt 이 번호(0부터)의 문장을 읽는 중에 내비 회전 안내가 끼어든다.
     */
    data class Say(
        val sentences: List<String>,
        val auto: Boolean = false,
        val naviAt: Int? = null,
        val naviText: String = "300m 앞에서 오른쪽 방향입니다",
    ) : Step

    /** 실행 전 확인 카드(예약 요청·발송·전화). 실제로 실행하지 않는다. */
    data class Card(val title: String, val body: String) : Step

    data class Pause(val ms: Long) : Step

    /** 주차 후 결과 화면 */
    data class Summary(val lines: List<String>) : Step

    /** 정차 후 숙소 확인 화면(사진·가격·취소 규정). 주행 중에는 띄우지 않는다. */
    data class Lodging(
        val say: List<String>,
        val name: String,
        val detail: List<String>,
    ) : Step
}

object ScenarioScript {
    private const val NEWS = 0xFF3D7BD9.toInt()
    private const val TRIP = 0xFF2E9E6B.toInt()
    private const val CHAT = 0xFFE08A2E.toInt()

    val steps: List<Step> = listOf(
        // 1. 뉴스: 요약 → 사용자 요청으로 전체 듣기 → 내비 안내 시 정지·재개
        Step.Chapter("① 출근길 · 뉴스 요약 에이전트", "평일 오전 8시 40분 · 회사까지 35분"),
        Step.Module("뉴스 요약 에이전트", NEWS, "허용: 관심 분야 · 남은 시간"),
        Step.Driver("오늘 AI 뉴스 들려줘"),
        Step.Say(listOf(
            "오늘 관심 분야 AI 뉴스는 세 건입니다.",
            "첫째, 자동차 업계가 차량용 AI 에이전트 연동 표준을 논의하기 시작했습니다.",
            "둘째, 인터넷 없이 기기 안에서 동작하는 음성 모델이 차량으로 확산되고 있습니다.",
            "셋째, 생성형 AI 저작권 가이드라인 개정안이 다음 달 시행됩니다.",
        )),
        Step.Driver("두 번째 기사 자세히 읽어 줘"),
        Step.Say(listOf(
            "두 번째 기사 전체입니다.",
            "온디바이스 음성 모델은 인터넷 연결 없이 기기 안에서 말을 알아듣고 답합니다.",
            "응답이 빠르고, 대화 내용이 기기 밖으로 나가지 않는다는 장점이 있습니다.",
            "업계는 터널이나 지하 주차장처럼 통신이 끊기는 곳에서 특히 유용할 것으로 봅니다.",
            "남은 내용은 도착 후 폰으로 이어 볼 수 있습니다.",
        ), naviAt = 2),
        Step.Pause(1_200),

        // 2. 여행: 자동 트리거 → 보류 → 음성 요청으로 재생 → 동선·맛집·카페 → 숙소 예약 확인
        Step.Chapter("② 주말 여행 · 여행 동선 에이전트", "토요일 오전 · 여수 도착 30분 전"),
        Step.Module("여행 동선 에이전트", TRIP, "허용: 현재 위치 · 목적지 · 남은 시간"),
        Step.Say(listOf(
            "여수 도착까지 30분 남았습니다.",
            "점심은 도착 직후 바닷가 게장 백반집을 추천합니다. 지금 대기는 15분입니다.",
            "오후에는 오동도 산책 뒤, 걸어서 10분 거리의 바다 전망 카페로 이어지는 동선입니다.",
            "오늘 묵을 숙소가 아직 없습니다. 오동도 근처에 오늘 밤 예약 가능한 곳이 두 곳 있습니다.",
            "첫째, 바다 전망 호텔 1박 13만 원. 둘째, 한옥 스테이 1박 9만 원입니다.",
            "예약해 드릴까요?",
        ), auto = true),
        Step.Pause(2_500),
        Step.Driver("브리핑 들려줘", releaseHeld = true),
        Step.Driver("첫 번째로 예약해 줘"),
        Step.Say(listOf(
            "바다 전망 호텔, 오늘 1박, 13만 원입니다.",
            "결제는 차가 멈춘 뒤에 진행합니다. 도착하면 사진과 취소 규정을 화면에 띄워 드릴게요.",
        )),
        Step.Card("예약 대기 · 정차 후 확인", "바다 전망 호텔 · 오늘 1박 · 130,000원 · 결제는 정차 후"),
        Step.Pause(1_200),
        Step.Chapter("② 도착 · 차가 멈춘 뒤", "여수 도착 · 주차 완료 · 이제 화면으로 확인"),
        Step.Lodging(
            say = listOf("주차했습니다. 예약할 숙소를 화면에 띄웠습니다."),
            name = "바다 전망 호텔 · 오늘 1박 · 130,000원",
            detail = listOf("오동도 입구까지 걸어서 5분 · 오션뷰 더블룸", "체크인 전날 23시까지 무료 취소"),
        ),
        Step.Pause(800),

        // 3. 연락: 부재중·메시지 읽기 → 음성 답장 → 확인 후 발송 → 전화 연결
        Step.Chapter("③ 퇴근길 · 메시지·연락 에이전트", "평일 오후 6시 20분 · 집까지 25분"),
        Step.Module("메시지·연락 에이전트", CHAT, "허용: 연락처 이름 · 메시지 본문"),
        Step.Driver("놓친 연락 있어?"),
        Step.Say(listOf(
            "부재중 전화 한 통과 메시지 한 개가 있습니다.",
            "김 팀장님 메시지입니다. 내일 회의 자료, 몇 시까지 가능할까요?",
        )),
        Step.Driver("답장해 줘. 내일 오전 10시까지 보내겠습니다."),
        Step.Say(listOf("김 팀장님께, 내일 오전 10시까지 보내겠습니다, 라고 보낼까요?")),
        Step.Card("보내기 전 확인", "받는 사람: 김 팀장님\n내일 오전 10시까지 보내겠습니다."),
        Step.Driver("응, 보내 줘"),
        Step.Say(listOf(
            "보냈습니다.",
            "부재중 전화는 박 대리님입니다. 지금 전화 연결할까요?",
        )),
        Step.Driver("응, 연결해 줘"),
        Step.Say(listOf("박 대리님께 전화를 연결합니다.")),
        Step.Card("전화 연결", "박 대리님"),
        Step.Pause(1_500),

        // 4. 도착 후: 오늘의 기록
        Step.Summary(listOf(
            "뉴스 요약 에이전트 · 요약 3건, 전체 듣기 1건 (내비 안내로 1회 정지 후 재개)",
            "여행 동선 에이전트 · 동선·맛집·카페 추천, 숙소는 정차 후 사진·취소 규정 확인 뒤 예약",
            "메시지·연락 에이전트 · 답장 1건 발송 확인, 전화 연결 1건",
        )),
    )
}
