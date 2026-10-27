package ai.byoa.core

/** 실제 음성 출력 장치. 앱에서는 Pleos TextToSpeech SDK를 감싼 구현을 넣는다. */
interface Speaker {
    fun speak(text: String)
    fun stop()
}

/**
 * 허브의 음성 출력 관문.
 *
 * - 재생을 막는 사유(내비 정숙 구간, 오디오 포커스 상실)를 각각 따로 관리한다.
 *   사유가 하나라도 남아 있으면 말하지 않고, 모든 사유가 풀렸을 때만 멈춘 문장부터 다시 읽는다.
 * - 차량 이벤트로 자동 호출된 결과는 바로 말하지 않고 보류(HELD)한다.
 *   운전자가 요청(releaseHeld)했을 때만 재생한다. 오디오 상태가 재생 가능하더라도 마찬가지다.
 *
 * 스레드 안전하지 않다. 모든 호출은 같은 스레드(앱에서는 메인 스레드)에서 한다.
 */
class SpeechGate(
    private val speaker: Speaker,
    private val log: ExperimentLog,
) {
    enum class State { IDLE, SPEAKING, PAUSED, HELD, FINISHED }

    /** 재생을 막는 사유 */
    enum class Block { NAVI, AUDIO_FOCUS }

    var state: State = State.IDLE
        private set

    private val queue = ArrayDeque<String>()
    private var current: String? = null
    private val blocks = mutableSetOf<Block>()

    /** 지금 재생을 막고 있는 사유 */
    val activeBlocks: Set<Block> get() = blocks.toSet()

    /**
     * speak() 요청 뒤 아직 "재생 시작" 콜백을 받지 못한 상태.
     * 멈춘 문장의 완료 신호가 늦게 도착해 다음 문장을 건너뛰는 일을 막는다.
     */
    private var awaitingStart = false

    /** 아직 읽지 않은 문장(현재 읽는 문장 포함). 도착 후 결과 화면에 넘긴다. */
    val unspoken: List<String> get() = listOfNotNull(current) + queue

    /**
     * 브리핑을 시작한다.
     * @param userInitiated 운전자가 직접 요청했으면 true, 차량 이벤트 자동 호출이면 false
     */
    fun start(sentences: List<String>, userInitiated: Boolean) {
        speakerStopIfSpeaking()
        queue.clear()
        queue.addAll(sentences)
        current = null
        if (!userInitiated) {
            state = State.HELD
            log.mark("gate.held", "자동 호출 결과: 운전자가 요청할 때만 재생")
            return
        }
        log.mark("gate.start", "문장 ${sentences.size}개, userInitiated=true")
        next()
    }

    /** 보류된 브리핑을 운전자 요청으로 재생한다. */
    fun releaseHeld() {
        if (state != State.HELD) return
        log.mark("gate.release", "운전자가 요청")
        next()
    }

    fun onNaviGuidanceStarted() { log.mark("navi.start"); block(Block.NAVI) }
    fun onNaviGuidanceEnded() { log.mark("navi.end"); unblock(Block.NAVI) }
    fun onAudioFocusLost() { log.mark("focus.block"); block(Block.AUDIO_FOCUS) }
    fun onAudioFocusRegained() { log.mark("focus.unblock"); unblock(Block.AUDIO_FOCUS) }

    private fun block(reason: Block) {
        if (!blocks.add(reason)) return
        if (state == State.SPEAKING) {
            speaker.stop()
            current?.let { queue.addFirst(it) }
            current = null
            awaitingStart = false
            state = State.PAUSED
            log.mark("gate.pause", "사유=$reason, 읽던 문장을 대기열 앞으로 되돌림")
        }
    }

    private fun unblock(reason: Block) {
        if (!blocks.remove(reason)) return
        if (blocks.isNotEmpty()) {
            log.mark("gate.still.blocked", blocks.joinToString())
            return
        }
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
        if (blocks.isNotEmpty()) {
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
