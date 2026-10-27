package ai.byoa.probe

import ai.byoa.core.ExperimentLog
import ai.byoa.core.FocusTracker
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

    /** 포커스 보유 상태. 받았다고 확인되기 전에는 보유하지 않은 것으로 본다. */
    private val tracker = FocusTracker()

    fun setMode(newMode: Mode, focusEvents: FocusEvents) {
        if (newMode == mode) return
        events = focusEvents
        if (mode == Mode.OFF) {
            val result = audioManager.requestAudioFocus(request)
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            log.mark("focus.request", if (granted) "GRANTED" else "result=$result (보유하지 않은 것으로 처리)")
            send(tracker.onRequestResult(granted))
        }
        if (newMode == Mode.OFF) {
            audioManager.abandonAudioFocusRequest(request)
            log.mark("focus.abandon")
            send(tracker.onAbandon())
        } else {
            // 연동 모드로 들어가는 순간 현재 보유 상태를 게이트에 맞춘다(관찰 중 상실했으면 바로 막힘)
            send(tracker.setGating(newMode == Mode.GATE))
        }
        mode = newMode
        log.mark("focus.mode", "${newMode.name}, 보유=${tracker.hasFocus}")
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
        // 관찰 모드에서도 보유 상태는 갱신한다. 게이트 신호는 연동 모드에서만 나온다(FocusTracker).
        when (change) {
            // 영구 상실(LOSS)도 차단한다. 포커스를 다시 얻기(GAIN) 전까지 말하지 않는다.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> send(tracker.onLoss())
            AudioManager.AUDIOFOCUS_GAIN -> send(tracker.onGain())
        }
    }

    private fun send(signal: FocusTracker.Signal?) {
        when (signal) {
            FocusTracker.Signal.LOST -> events?.onFocusLost()
            FocusTracker.Signal.REGAINED -> events?.onFocusRegained()
            null -> Unit
        }
    }

    fun release() {
        if (mode != Mode.OFF) audioManager.abandonAudioFocusRequest(request)
    }
}
