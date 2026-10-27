package ai.byoa.probe

import ai.byoa.core.ExperimentLog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 내비 음성 안내를 오디오 포커스 변화로 감지할 수 있는지 보는 탐침 (실험 E3).
 *
 * 가설: 내비가 안내 음성을 낼 때 우리 앱이 포커스를 잃는다(LOSS_TRANSIENT).
 *
 * 주의: 허브의 TTS는 Pleos Speech Service가 재생한다. 그 재생이 우리 앱의 포커스를 뺏으면
 * 내비가 아닌 우리 TTS 때문에 일시정지가 걸리는 오탐이 생긴다.
 * 그래서 기본값은 OBSERVE(기록만)이고, E3에서 오탐이 없다고 확인한 뒤에만 GATE로 바꾼다.
 */
/** 오디오 포커스 변화를 허브에 알린다. 내비 정숙 구간과는 별개의 중단 사유로 다룬다. */
interface FocusEvents {
    fun onFocusLost()
    fun onFocusRegained()
}

class AudioFocusProbe(
    context: Context,
    private val log: ExperimentLog,
) {
    enum class Mode { OFF, OBSERVE, GATE }

    var mode: Mode = Mode.OFF
        private set

    private var events: FocusEvents? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
        .build()

    fun setMode(newMode: Mode, focusEvents: FocusEvents) {
        if (newMode == mode) return
        if (mode == Mode.OFF) {
            val result = audioManager.requestAudioFocus(request)
            log.mark("focus.request", if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "result=$result")
        }
        if (newMode == Mode.OFF) {
            audioManager.abandonAudioFocusRequest(request)
            log.mark("focus.abandon")
        }
        events = focusEvents
        mode = newMode
        log.mark("focus.mode", newMode.name)
    }

    private fun onFocusChange(change: Int) {
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "code=$change"
        }
        log.mark("focus.change", name)
        if (mode != Mode.GATE) return
        when (change) {
            // 영구 상실(LOSS)도 차단한다. 포커스를 다시 얻기(GAIN) 전까지 말하지 않는다.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> events?.onFocusLost()
            AudioManager.AUDIOFOCUS_GAIN -> events?.onFocusRegained()
        }
    }

    fun release() {
        if (mode != Mode.OFF) audioManager.abandonAudioFocusRequest(request)
    }
}
