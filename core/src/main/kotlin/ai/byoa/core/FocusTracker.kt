package ai.byoa.core

/**
 * 오디오 포커스 보유 상태 판정(포커스 연동 실험용).
 *
 * - 포커스를 실제로 받았다고 확인되기 전에는 "보유하지 않음"으로 본다(요청 거절·결과 불명확 포함).
 * - 관찰 모드에서도 상태는 계속 갱신한다. 연동(GATE) 모드로 바꾸는 순간 현재 상태를 게이트에 맞춘다.
 * - 연동 모드에서는 보유하지 않으면 재생을 막고, 보유가 확인되면 막힘을 푼다.
 *
 * 반환값: 게이트에 보낼 신호(LOST=막기, REGAINED=풀기). 바뀐 것이 없으면 null.
 */
class FocusTracker {
    enum class Signal { LOST, REGAINED }

    var hasFocus = false
        private set
    var gating = false
        private set
    /** 게이트에 "막힘"을 걸어 둔 상태인지 */
    private var blocked = false

    /** requestAudioFocus 결과. granted가 아니면(거절·지연·불명확) 보유하지 않은 것으로 본다. */
    fun onRequestResult(granted: Boolean): Signal? { hasFocus = granted; return sync() }

    fun onGain(): Signal? { hasFocus = true; return sync() }

    /** 일시적·영구 상실 모두 */
    fun onLoss(): Signal? { hasFocus = false; return sync() }

    /** 포커스를 반납했을 때(탐침 끄기) */
    fun onAbandon(): Signal? { hasFocus = false; return setGating(false) }

    fun setGating(on: Boolean): Signal? {
        gating = on
        return sync()
    }

    private fun sync(): Signal? {
        val shouldBlock = gating && !hasFocus
        if (shouldBlock == blocked) return null
        blocked = shouldBlock
        return if (shouldBlock) Signal.LOST else Signal.REGAINED
    }
}
