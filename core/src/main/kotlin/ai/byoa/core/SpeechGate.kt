package ai.byoa.core

/** 실제 음성 출력 장치. 앱에서는 Pleos TextToSpeech SDK를 감싼 구현을 넣는다. */
interface Speaker {
    fun speak(text: String)
    fun stop()
}

/**
 * 허브의 음성 출력 관문.
 *
 * 구동 실험의 핵심인 "내비 안내 시 중단 → 안내 종료 후 복귀"를 담당한다.
 * - 내비 안내가 시작되면 지금 읽던 문장을 멈추고, 그 문장을 대기열 맨 앞에 되돌린다.
 * - 안내가 끝나면 멈췄던 문장부터 다시 읽는다(문장 단위 복귀).
 * - 내비·오디오 우선순위가 검증되지 않았으면(priorityVerified=false)
 *   사용자가 직접 시작하지 않은 자동 발화는 보류(HELD)한다.
 *
 * 스레드 안전하지 않다. 모든 호출은 같은 스레드(앱에서는 메인 스레드)에서 한다.
 */
class SpeechGate(
    private val speaker: Speaker,
    private val log: ExperimentLog,
) {
    enum class State { IDLE, SPEAKING, PAUSED, HELD, FINISHED }

    var state: State = State.IDLE
        private set

    /** 실험 E3·E4에서 내비 우선순위 처리가 확인되면 true로 바꾼다. */
    var priorityVerified: Boolean = false

    private val queue = ArrayDeque<String>()
    private var current: String? = null
    private var naviActive = false

    /**
     * speak() 요청 뒤 아직 "재생 시작" 콜백을 받지 못한 상태.
     * 멈춘 문장의 완료 신호가 늦게 도착해 다음 문장을 건너뛰는 일을 막는다.
     * (SDK가 stop() 뒤에도 onDone을 보내는지는 실험 E4에서 확인)
     */
    private var awaitingStart = false

    /** 아직 읽지 않은 문장(현재 읽는 문장 포함). 도착 후 결과 화면에 넘긴다. */
    val unspoken: List<String> get() = listOfNotNull(current) + queue

    /**
     * 브리핑을 시작한다.
     * @param userInitiated 운전자가 직접 세션을 시작했으면 true, 차량 이벤트 자동 호출이면 false
     */
    fun start(sentences: List<String>, userInitiated: Boolean) {
        speakerStopIfSpeaking()
        queue.clear()
        queue.addAll(sentences)
        current = null
        if (!userInitiated && !priorityVerified) {
            state = State.HELD
            log.mark("gate.held", "자동 발화 보류: 내비 우선순위 미검증")
            return
        }
        log.mark("gate.start", "문장 ${sentences.size}개, userInitiated=$userInitiated")
        next()
    }

    /** 보류된 브리핑을 운전자가 직접 재생한다. */
    fun releaseHeld() {
        if (state != State.HELD) return
        log.mark("gate.release", "운전자가 보류 해제")
        next()
    }

    fun onNaviGuidanceStarted() {
        naviActive = true
        log.mark("navi.start")
        if (state == State.SPEAKING) {
            speaker.stop()
            current?.let { queue.addFirst(it) }
            current = null
            state = State.PAUSED
            log.mark("gate.pause", "읽던 문장을 대기열 앞으로 되돌림")
        }
    }

    fun onNaviGuidanceEnded() {
        naviActive = false
        log.mark("navi.end")
        if (state == State.PAUSED) {
            log.mark("gate.resume")
            next()
        }
    }

    /** TTS가 재생을 시작했을 때(EventListener.onStart) */
    fun onSpeechStarted() {
        awaitingStart = false
    }

    /** TTS가 한 문장을 끝까지 읽었을 때(EventListener.onDone) */
    fun onSpeechDone() {
        if (state != State.SPEAKING) return
        if (awaitingStart) {
            log.mark("tts.done.ignored", "현재 문장 시작 전에 도착한 완료 신호")
            return
        }
        log.mark("tts.done")
        current = null
        next()
    }

    /** TTS 오류 또는 엔진 미준비. 그 문장은 건너뛰고 다음 문장으로 넘어가 멈춰 있지 않게 한다. */
    fun onSpeechFailed() {
        if (state != State.SPEAKING) return
        log.mark("tts.fail", current ?: "")
        awaitingStart = false
        current = null
        next()
    }

    /** 운전자가 브리핑을 끈다. 남은 문장은 결과 화면으로 넘긴다. */
    fun cancel() {
        speakerStopIfSpeaking()
        log.mark("gate.cancel", "남은 문장 ${unspoken.size}개")
        state = State.FINISHED
    }

    private fun next() {
        if (naviActive) {
            state = State.PAUSED
            return
        }
        val sentence = queue.removeFirstOrNull()
        if (sentence == null) {
            state = State.FINISHED
            log.mark("gate.finished")
            return
        }
        current = sentence
        state = State.SPEAKING
        awaitingStart = true
        log.mark("tts.request", sentence)
        speaker.speak(sentence)
    }

    private fun speakerStopIfSpeaking() {
        if (state == State.SPEAKING) speaker.stop()
    }
}
