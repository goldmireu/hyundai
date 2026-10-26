package ai.byoa.probe

import ai.byoa.core.BriefingSession
import ai.byoa.core.ContextField
import ai.byoa.core.DriveContext
import ai.byoa.core.DrivingState
import ai.byoa.core.ExperimentLog
import ai.byoa.core.MockCalendarConnector
import ai.byoa.core.MockEvent
import ai.byoa.core.ReadOnlyConnector
import ai.byoa.core.SpeechGate
import ai.byoa.core.WebhookConnector
import android.app.Activity
import android.util.Log
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 구동 실험 화면.
 *
 * 위: 운전자에게 보이는 화면(주행 상태가 PARKED가 아니면 최소 화면만)
 * 아래: 실험 패널(실험자가 내비 이벤트·주행 상태를 흉내 내는 버튼과 기록)
 *
 * 실험 패널은 시연용 화면이 아니다. 실차·Virtual Console 실험 때만 쓴다.
 */
class MainActivity : Activity(), NaviEvents {

    private val main = Handler(Looper.getMainLooper())
    private val uiExecutor = Executor { main.post(it) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var log: ExperimentLog
    private lateinit var speaker: PleosTtsSpeaker
    private lateinit var gate: SpeechGate
    private lateinit var session: BriefingSession
    private lateinit var focusProbe: AudioFocusProbe
    private val manualNavi = ManualNaviSource()
    private lateinit var pleosNavi: PleosNaviSource

    private var drivingState = DrivingState.UNKNOWN   // 확인 전에는 주행 중으로 간주
    private var remainMinutes: Int? = 35
    private var destination: String? = "회사"

    // 에뮬레이터에서 PC의 tools/mock_agent.py 를 부르는 주소
    private val agents: List<ReadOnlyConnector> = listOf(
        MockCalendarConnector(
            events = listOf(MockEvent("주간 회의", 9 * 60 + 30), MockEvent("고객 미팅", 14 * 60)),
            nowMinuteOfDay = { 9 * 60 },   // 시연용 고정 시각(오전 9시). 에뮬레이터 시계는 UTC라 실제 시각을 쓰면 문장이 어색하다
        ),
        WebhookConnector(
            name = "내 에이전트",
            url = "http://10.0.2.2:8080/brief",
            allowedFields = setOf(ContextField.REMAIN_TIME),
        ),
    )
    private var agentIndex = 0

    private lateinit var driverView: TextView
    private lateinit var captionView: TextView
    private var demoMode = false
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ExperimentLog(sink = { line -> Log.i("BYOA", line); main.post { appendLog(line) } })
        speaker = PleosTtsSpeaker(
            context = this,
            log = log,
            onUtteranceStarted = { gate.onSpeechStarted(); refresh() },
            onUtteranceDone = { gate.onSpeechDone(); refresh() },
            onUtteranceError = { gate.onSpeechFailed(); refresh() },
        )
        gate = SpeechGate(speaker, log)
        session = BriefingSession(gate, log, io, uiExecutor)
        focusProbe = AudioFocusProbe(this, log)
        pleosNavi = PleosNaviSource(this, log)

        setContentView(buildUi())
        manualNavi.start(this)
        demoMode = intent?.getBooleanExtra("demo", false) == true
        // 자동 시연에서는 실제 내비를 연결하지 않는다. 경로 안내가 켜져 있으면 정숙 구간 때문에 장면이 멈추기 때문.
        if (!demoMode) pleosNavi.start(this)
        speaker.initialize { ok ->
            if (!ok) log.mark("note", "TTS 초기화 실패: 권한·에뮬레이터 설정 확인")
            refresh()
            if (ok && demoMode) main.postDelayed({ runDemo() }, 2_000)
        }
        refresh()
    }

    override fun onDestroy() {
        pleosNavi.stop()
        manualNavi.stop()
        focusProbe.release()
        speaker.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- NaviEvents: 수동 버튼, 오디오 포커스, (E3 이후) NaviHelper가 모두 여기로 들어온다 ----

    override fun onGuidanceStarted() { gate.onNaviGuidanceStarted(); refresh() }
    override fun onGuidanceEnded() { gate.onNaviGuidanceEnded(); refresh() }
    override fun onRemainMinutes(minutes: Int) { remainMinutes = minutes; log.mark("navi.remain", "${minutes}분"); refresh() }
    override fun onDestination(name: String) { destination = name; refresh() }

    // ---- 동작 ----

    /**
     * 자동 시연: 실험 패널 버튼을 정해진 순서로 누르고 화면 위쪽에 자막을 띄운다.
     * 실행: adb shell am start -n ai.byoa.probe/.MainActivity --ez demo true
     */
    private fun runDemo() {
        log.mark("demo.start")
        val steps = listOf<Pair<Long, () -> Unit>>(
            0L to { caption("① 출근길 일정 브리핑\n운전자가 세션을 시작하면 오늘 일정과 남은 시간을 대조해 읽어 줍니다"); startBriefing(userInitiated = true) },
            1_500L to { caption("② 내비 안내가 끼어들면 즉시 정지\n(내비 안내 시점은 수동 이벤트로 시험)"); onGuidanceStarted() },
            5_000L to { caption("③ 안내가 끝나면 멈춘 문장을 처음부터 다시 읽습니다"); onGuidanceEnded() },
            17_000L to {
                agentIndex = agents.indexOfFirst { it is WebhookConnector }.coerceAtLeast(0)
                log.mark("agent.switch", agents[agentIndex].name)
                caption("④ 내 에이전트로 교체해도 같은 규칙\n허용한 정보(남은 시간)만 전달하고, 문장만 받아 읽습니다")
                startBriefing(userInitiated = true)
            },
            33_000L to {
                agentIndex = 0
                caption("⑤ 안전이 확인되지 않으면 차가 먼저 말하지 않습니다\n자동 트리거 → 보류")
                startBriefing(userInitiated = false)
            },
            38_000L to { caption("⑤ 운전자가 눌렀을 때만 재생"); gate.releaseHeld(); refresh() },
            49_000L to { gate.cancel(); caption("내 에이전트를, 차 안에서 안전하게\n차량 에이전트 허브 · Pleos Connect Emulator 구동 실험"); log.mark("demo.end") },
        )
        steps.forEach { (at, action) -> main.postDelayed({ action(); refresh() }, at) }
    }

    private fun caption(text: String) {
        captionView.text = text
        log.mark("demo.caption", text.substringBefore("\n"))
    }

    private fun startBriefing(userInitiated: Boolean) {
        val context = DriveContext(remainMinutes, destination, drivingState)
        session.run(agents[agentIndex], context, userInitiated) { refresh() }
        refresh()
    }

    private fun saveLog() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.KOREA).format(Date())
        val file = File(filesDir, "probe-log-$stamp.txt")
        file.writeText(log.dump() + "\n\n" + latencySummary())
        log.mark("log.saved", file.absolutePath)
    }

    private fun latencySummary(): String = buildString {
        appendLine("[지연 요약]")
        appendLine("세션 시작 → 외부 읽기 완료: ${log.between("read.request", "read.done") ?: "-"}ms")
        appendLine("외부 읽기 완료 → 첫 음성 시작: ${log.between("read.done", "tts.started") ?: "-"}ms")
        appendLine("내비 안내 시작 → TTS 정지 확인: ${log.between("navi.start", "tts.stopped") ?: "-"}ms")
        appendLine("내비 안내 종료 → 음성 재개: ${log.between("navi.end", "tts.started") ?: "-"}ms")
    }

    // ---- 화면 ----

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val agent = agents[agentIndex].name
        // 운전자 화면: 주행 중(또는 상태 불명)이면 지금 말하는 모듈과 상태만
        driverView.text = if (drivingState.restrictsUi) {
            "$agent\n${gateLabel()}"
        } else {
            "$agent · ${gateLabel()}\n남은 시간: ${remainMinutes?.let { "${it}분" } ?: "알 수 없음"}\n" +
                "읽지 않은 문장: ${gate.unspoken.size + session.deferred.size}개"
        }
        statusView.text = "주행 상태=$drivingState · 게이트=${gate.state} · 우선순위 검증=${gate.priorityVerified} · 포커스=${focusProbe.mode}"
    }

    private fun gateLabel(): String = when (gate.state) {
        SpeechGate.State.IDLE -> "대기"
        SpeechGate.State.SPEAKING -> "말하는 중"
        SpeechGate.State.PAUSED -> "내비 안내 시 일시정지"
        SpeechGate.State.HELD -> "자동 발화 보류됨 (눌러서 듣기)"
        SpeechGate.State.FINISHED -> "브리핑 완료"
    }

    private fun appendLog(line: String) {
        if (!::logView.isInitialized) return
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        captionView = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFD54F.toInt())
            setPadding(0, 0, 0, 24)
        }
        root.addView(captionView)
        driverView = TextView(this).apply { textSize = 40f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 24) }
        root.addView(driverView)
        root.addView(button("세션 시작 (운전자가 누름)") { startBriefing(userInitiated = true) })
        root.addView(button("보류된 브리핑 듣기") { gate.releaseHeld(); refresh() })

        root.addView(TextView(this).apply { text = "— 실험 패널 —"; setPadding(0, 32, 0, 8) })
        statusView = TextView(this).apply { textSize = 22f }
        root.addView(statusView)

        val row1 = row()
        row1.addView(button("내비 안내 시작") { manualNavi.events?.onGuidanceStarted() })
        row1.addView(button("내비 안내 종료") { manualNavi.events?.onGuidanceEnded() })
        root.addView(row1)

        val row2 = row()
        row2.addView(button("자동 트리거 흉내") { startBriefing(userInitiated = false) })
        row2.addView(button("에이전트 교체") {
            agentIndex = (agentIndex + 1) % agents.size
            log.mark("agent.switch", agents[agentIndex].name)
            refresh()
        })
        root.addView(row2)

        val row3 = row()
        row3.addView(button("주행 상태 바꾸기") {
            drivingState = DrivingState.entries[(drivingState.ordinal + 1) % DrivingState.entries.size]
            log.mark("driving.state", drivingState.name)
            refresh()
        })
        row3.addView(button("남은 시간 바꾸기") {
            remainMinutes = when (remainMinutes) { 35 -> 12; 12 -> 3; 3 -> null; else -> 35 }
            log.mark("remain.manual", remainMinutes?.toString() ?: "unknown")
            refresh()
        })
        root.addView(row3)

        val row4 = row()
        row4.addView(button("포커스 탐침 OFF→관찰→연동") {
            val next = AudioFocusProbe.Mode.entries[(focusProbe.mode.ordinal + 1) % AudioFocusProbe.Mode.entries.size]
            focusProbe.setMode(next, this)
            refresh()
        })
        row4.addView(button("우선순위 검증 표시") {
            gate.priorityVerified = !gate.priorityVerified
            log.mark("priority.verified", gate.priorityVerified.toString())
            refresh()
        })
        root.addView(row4)

        val row5 = row()
        row5.addView(button("브리핑 끄기") { gate.cancel(); refresh() })
        row5.addView(button("기록 저장") { saveLog() })
        root.addView(row5)

        logView = TextView(this).apply { textSize = 20f; typeface = Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setOnClickListener { onClick() }
    }
}
