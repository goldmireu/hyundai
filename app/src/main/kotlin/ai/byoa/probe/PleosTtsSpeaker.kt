package ai.byoa.probe

import ai.byoa.core.ExperimentLog
import ai.byoa.core.Speaker
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
// 확인 필요: 문서 예제에 EventListener의 import 경로가 없다. 빌드가 실패하면
// Android Studio 자동 import(Alt+Enter)로 실제 패키지를 고른다.
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pleos TextToSpeech SDK v3.3.1을 Speaker로 감싼다.
 *
 * 사용한 API는 모두 공식 문서에서 확인한 것이다:
 * TextToSpeech(context, Mode), initialize(OnInitializeListener), addEventListener,
 * speak(text), stop(), removeEventListener, release.
 *
 * ON_DEVICE 모드를 쓰는 이유: 서버 모드는 Pleos 앱 등록이 필요하고 네트워크에 좌우된다.
 * 1차 실험은 차량 안 오디오 동작만 보면 되므로 변수를 줄인다.
 *
 * SDK 콜백이 어느 스레드에서 오는지 문서에 없으므로 모두 메인 스레드로 옮긴다.
 */
class PleosTtsSpeaker(
    context: Context,
    private val log: ExperimentLog,
    private val onUtteranceStarted: () -> Unit,
    private val onUtteranceDone: () -> Unit,
    private val onUtteranceError: () -> Unit,
) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)

    /** initialize 성공 전에는 speak를 보내지 않는다 */
    private var ready = false

    private val listener = object : EventListener {
        override fun onReady() { main.post { log.mark("tts.ready") } }
        override fun onStart() { main.post { log.mark("tts.started"); onUtteranceStarted() } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { main.post { onUtteranceDone() } }
        override fun onStop() { main.post { log.mark("tts.stopped") } }
        override fun onError(errorType: ErrorType) {
            main.post {
                log.mark("tts.error", errorType.toString())
                onUtteranceError()
            }
        }
    }

    fun initialize(onResult: (Boolean) -> Unit) {
        log.mark("tts.init.request", "mode=ON_DEVICE")
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    tts.addEventListener(listener)
                    ready = true
                    log.mark("tts.init.ok")
                    onResult(true)
                }
            }

            override fun onFailure(errorType: ErrorType) {
                main.post {
                    log.mark("tts.init.fail", errorType.toString())
                    onResult(false)
                }
            }
        })
    }

    override fun speak(text: String) {
        if (!ready) {
            log.mark("tts.not.ready", "초기화 전 요청은 실패로 처리")
            main.post { onUtteranceError() }   // 게이트가 같은 호출 안에서 다시 들어오지 않도록 다음 틱에
            return
        }
        tts.speak(text)
    }

    override fun stop() {
        if (!ready) return
        log.mark("tts.stop.request")
        tts.stop()
    }

    fun release() {
        if (ready) tts.removeEventListener(listener)
        tts.release()
    }
}
