package ai.byoa.probe

import ai.byoa.core.ExperimentLog
import ai.byoa.core.SpeechGate
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 시나리오 시연 화면 (운전자 화면만, 실험 패널 없음).
 *
 * 실행: adb shell am start -n ai.byoa.probe/.ScenarioActivity
 *
 * 실제로 동작하는 것: Pleos TTS 음성 출력, SpeechGate의 내비 안내 시 정지·재개와 자동 발화 보류.
 * 재현한 것: 모듈 응답(모의 데이터), 운전자 음성 요청(말풍선), 내비 회전 안내 시점(수동 이벤트).
 * 실행하지 않는 것: 예약·결제, 답장 발송, 전화 연결(확인 카드까지만 보여준다).
 */
class ScenarioActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: ExperimentLog
    private lateinit var speaker: PleosTtsSpeaker
    private lateinit var gate: SpeechGate

    private lateinit var chapterView: TextView
    private lateinit var contextView: TextView
    private lateinit var moduleView: TextView
    private lateinit var allowedView: TextView
    private lateinit var statusView: TextView
    private lateinit var sentenceView: TextView
    private lateinit var driverView: TextView
    private lateinit var cardView: TextView
    private lateinit var naviView: TextView
    private lateinit var lodgingView: LinearLayout
    private lateinit var lodgingName: TextView
    private lateinit var lodgingDetail: TextView
    private lateinit var lodgingButton: TextView

    private var index = -1
    private var moduleColor = 0xFF3D7BD9.toInt()
    private var startedInStep = 0
    private var naviPlan: Step.Say? = null
    private var naviFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ExperimentLog(sink = { line -> Log.i("BYOA", line) })
        speaker = PleosTtsSpeaker(
            context = this,
            log = log,
            onUtteranceStarted = { gate.onSpeechStarted(); onSentenceStarted() },
            onUtteranceDone = { gate.onSpeechDone(); render() },
            onUtteranceError = { gate.onSpeechFailed(); render() },
        )
        gate = SpeechGate(speaker, log)   // priorityVerified=false: 자동 발화는 보류된다
        setContentView(buildUi())
        speaker.initialize { ok ->
            log.mark("scenario.tts", if (ok) "ok" else "fail")
            main.postDelayed({ log.mark("scenario.start"); next() }, 1_500)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speaker.release()
        super.onDestroy()
    }

    // ---- 대본 진행 ----

    private fun next() {
        index++
        val step = ScenarioScript.steps.getOrNull(index) ?: run { log.mark("scenario.end"); return }
        when (step) {
            is Step.Chapter -> {
                log.mark("scenario.chapter", step.title)
                chapterView.text = step.title; contextView.text = step.context
                sentenceView.text = ""; driverView.visibility = View.GONE; cardView.visibility = View.GONE
                lodgingView.visibility = View.GONE; sentenceView.visibility = View.VISIBLE
                statusView.text = "대기"
                after(1_500)
            }
            is Step.Module -> {
                moduleColor = step.color
                moduleView.text = step.name; allowedView.text = step.allowed
                (moduleView.background as GradientDrawable).setColor(step.color)
                after(800)
            }
            is Step.Driver -> {
                log.mark("scenario.driver", step.text)
                cardView.visibility = View.GONE
                driverView.text = (if (step.releaseHeld || index == firstDriverOfChapter()) "운전자 · 스티어링 음성 버튼 → 글레오" else "운전자 음성") + "\n“${step.text}”"
                driverView.visibility = View.VISIBLE
                if (step.releaseHeld) {
                    main.postDelayed({ gate.releaseHeld(); render(); waitUntilFinished() }, 2_200)
                } else after(2_600)
            }
            is Step.Say -> {
                startedInStep = 0; naviFired = false; naviPlan = step.takeIf { it.naviAt != null }
                if (!step.auto) driverView.postDelayed({ driverView.visibility = View.GONE }, 300)
                gate.start(step.sentences, userInitiated = !step.auto)
                render()
                if (gate.state == SpeechGate.State.HELD) {
                    showCard("브리핑 준비됨 · 차가 먼저 말하지 않습니다", "스티어링 음성 버튼을 누르고 \"브리핑 들려줘\"라고 말하면 재생")
                    after(1_000)
                } else waitUntilFinished()
            }
            is Step.Card -> {
                log.mark("scenario.card", "${step.title} (모의, 실제 실행 안 함)")
                showCard(step.title, step.body)
                after(2_800)
            }
            is Step.Pause -> after(step.ms)
            is Step.Lodging -> {
                log.mark("scenario.lodging", step.name)
                driverView.visibility = View.GONE; cardView.visibility = View.GONE
                sentenceView.visibility = View.GONE
                lodgingName.text = step.name
                lodgingDetail.text = step.detail.joinToString("\n")
                lodgingButton.alpha = 1f
                lodgingView.visibility = View.VISIBLE
                gate.start(step.say, userInitiated = true)
                statusView.text = "정차 중 · 상세 화면 표시 가능"
                main.postDelayed({
                    log.mark("scenario.card", "정차 중 예약하기 선택 (모의, 실제 결제 안 함)")
                    lodgingButton.text = "예약 요청 완료 · 시연이라 실제 결제는 하지 않음"
                    (lodgingButton.background as GradientDrawable).setColor(0xFF7FD1AE.toInt())
                }, 5_500)
                after(9_000)
            }
            is Step.Summary -> {
                chapterView.text = "주차 후 · 오늘의 기록"; contextView.text = "주행 중 숨겼던 상세 내용은 정차 중에만 표시"
                moduleView.text = "차량 에이전트 허브"; allowedView.text = "에이전트는 사용자가 고르고, 허브는 연결하고 지킨다"
                (moduleView.background as GradientDrawable).setColor(0xFF33475B.toInt())
                statusView.text = "정차 중"
                driverView.visibility = View.GONE; cardView.visibility = View.GONE
                lodgingView.visibility = View.GONE; sentenceView.visibility = View.VISIBLE
                sentenceView.textSize = 38f
                sentenceView.text = step.lines.joinToString("\n\n") { "· $it" }
                log.mark("scenario.summary")
                after(6_000)
            }
        }
    }

    /** 장의 첫 운전자 요청이면 버튼으로 글레오를 부르는 장면으로 표시한다. 이어지는 대답은 대화 중 음성으로 본다. */
    private fun firstDriverOfChapter(): Int {
        var i = index
        while (i > 0 && ScenarioScript.steps[i - 1] !is Step.Chapter) i--
        return (i until ScenarioScript.steps.size).firstOrNull { ScenarioScript.steps[it] is Step.Driver } ?: -1
    }

    private fun after(ms: Long) { main.postDelayed({ next() }, ms) }

    private fun waitUntilFinished() {
        main.postDelayed({
            if (gate.state == SpeechGate.State.FINISHED) { render(); after(700) } else waitUntilFinished()
        }, 150)
    }

    /** 문장 재생이 시작될 때마다 호출. 지정한 문장에서 내비 회전 안내를 끼워 넣는다(수동 이벤트). */
    private fun onSentenceStarted() {
        startedInStep++
        render()
        val plan = naviPlan ?: return
        if (!naviFired && startedInStep == (plan.naviAt ?: return) + 1) {
            naviFired = true
            main.postDelayed({
                naviView.text = "내비 안내 · ${plan.naviText}"
                naviView.visibility = View.VISIBLE
                gate.onNaviGuidanceStarted(); render()
                main.postDelayed({
                    naviView.visibility = View.GONE
                    gate.onNaviGuidanceEnded(); render()
                }, 3_200)
            }, 1_400)
        }
    }

    // ---- 화면 ----

    private fun render() {
        statusView.text = when (gate.state) {
            SpeechGate.State.IDLE -> "대기"
            SpeechGate.State.SPEAKING -> "말하는 중"
            SpeechGate.State.PAUSED -> "내비 안내 중 · 일시정지 (끝나면 이 문장부터 다시)"
            SpeechGate.State.HELD -> "자동 발화 보류"
            SpeechGate.State.FINISHED -> "완료"
        }
        if (gate.state == SpeechGate.State.SPEAKING || gate.state == SpeechGate.State.PAUSED) {
            gate.unspoken.firstOrNull()?.let { sentenceView.text = it }
        }
        sentenceView.alpha = if (gate.state == SpeechGate.State.PAUSED) 0.45f else 1f
    }

    private fun showCard(title: String, body: String) {
        cardView.text = "$title\n$body"
        cardView.visibility = View.VISIBLE
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1622.toInt())
            setPadding(56, 40, 56, 32)
        }
        chapterView = text(46f, 0xFFFFD54F.toInt(), bold = true)
        contextView = text(30f, 0xFF9FB3C8.toInt()).apply { setPadding(0, 6, 0, 28) }
        root.addView(chapterView); root.addView(contextView)

        val moduleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        moduleView = text(34f, Color.WHITE, bold = true).apply {
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(moduleColor) }
        }
        allowedView = text(28f, 0xFF9FB3C8.toInt()).apply { setPadding(24, 0, 0, 0) }
        moduleRow.addView(moduleView); moduleRow.addView(allowedView)
        root.addView(moduleRow)

        statusView = text(30f, 0xFF7FD1AE.toInt()).apply { setPadding(0, 24, 0, 12) }
        root.addView(statusView)

        naviView = text(36f, Color.WHITE, bold = true).apply {
            setPadding(28, 16, 28, 16); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 16f; setColor(0xFF1F6FEB.toInt()) }
        }
        root.addView(naviView)

        sentenceView = text(54f, Color.WHITE, bold = true).apply { setPadding(0, 20, 0, 24); setLineSpacing(10f, 1f) }
        root.addView(sentenceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        lodgingView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 16, 0, 0) }
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(LodgingTileView.Kind.EXTERIOR to "외관", LodgingTileView.Kind.ROOM to "객실", LodgingTileView.Kind.VIEW to "전망").forEach { (k, label) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LodgingTileView(this, k), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 230))
            col.addView(text(24f, 0xFF9FB3C8.toInt()).apply { this.text = label; setPadding(4, 8, 0, 0) })
            tiles.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 20 })
        }
        lodgingView.addView(tiles)
        lodgingView.addView(text(20f, 0xFF6F8399.toInt()).apply { this.text = "예시 일러스트 · 실제 사진은 연결한 숙소 에이전트가 제공"; setPadding(4, 4, 0, 16) })
        lodgingName = text(40f, Color.WHITE, bold = true)
        lodgingDetail = text(30f, 0xFFC8D4E0.toInt()).apply { setPadding(0, 8, 0, 20); setLineSpacing(8f, 1f) }
        lodgingButton = text(34f, 0xFF0B1622.toInt(), bold = true).apply {
            this.text = "예약하기"; setPadding(60, 18, 60, 18)
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(0xFFFFD54F.toInt()) }
        }
        lodgingView.addView(lodgingName); lodgingView.addView(lodgingDetail); lodgingView.addView(lodgingButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(lodgingView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        driverView = text(36f, Color.WHITE).apply {
            setPadding(28, 18, 28, 18); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(0xFF2A3F55.toInt()) }
        }
        root.addView(driverView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; bottomMargin = 16
        })

        cardView = text(36f, Color.WHITE, bold = true).apply {
            setPadding(28, 18, 28, 18); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(0xFF14324F.toInt()); setStroke(3, 0xFFFFD54F.toInt()) }
        }
        root.addView(cardView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })

        root.addView(text(22f, 0xFF6F8399.toInt()).apply {
            text = "시나리오 시연 · 세 에이전트는 교체 가능한 예시(허브가 제공하지 않음) · 응답은 모의 데이터 · 운전자 음성 요청과 내비 안내 시점은 재현 · 예약·결제·발송·통화는 실제로 실행하지 않음"
        })
        return root
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
