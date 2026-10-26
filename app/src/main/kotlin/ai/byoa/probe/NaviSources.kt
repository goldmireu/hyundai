package ai.byoa.probe

import ai.byoa.core.ExperimentLog
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/** 허브가 내비에서 받고 싶은 이벤트 */
interface NaviEvents {
    fun onGuidanceStarted()
    fun onGuidanceEnded()
    fun onRemainMinutes(minutes: Int)
    fun onDestination(name: String)
}

interface NaviSource {
    fun start(events: NaviEvents)
    fun stop()
}

/** 수동 내비 이벤트. 화면 버튼으로 "내비 안내 시작/종료"를 흉내 낸다. */
class ManualNaviSource : NaviSource {
    var events: NaviEvents? = null
        private set

    override fun start(events: NaviEvents) { this.events = events }
    override fun stop() { events = null }
}

/**
 * Pleos NaviHelper SDK v3.0.5 연결부 (실험 E3).
 *
 * SDK 소스(NaviHelper-3.0.5-sources.jar)로 확인한 사실:
 * - NaviHelperEventListener에는 "음성 안내가 지금 나온다"는 콜백이 없다.
 * - 대신 onTBTInfo(List<TBTInfo>)로 다음 회전까지 남은 거리(m)를 준다.
 * - DestinationInfo.remainTime, DrivingState.duration 단위는 초.
 * - 조회 API는 pleos.car.permission.NAVI_ROUTE 권한이 필요하다.
 *
 * 그래서 내비 안내 감지는 "예측형 정숙 구간"으로 한다:
 * 다음 회전까지 QUIET_START_M 이하로 가까워지면 안내 시작으로 보고,
 * 회전을 지나 QUIET_END_M 이상 멀어지면(또는 TBT가 비면) 안내 종료로 본다.
 * 실제 음성 재생을 감지하는 것이 아니므로 기획서에는 "예측"이라고 적는다.
 *
 * SDK 콜백 스레드가 문서에 없으므로 모두 메인 스레드로 옮긴다.
 */
class PleosNaviSource(
    context: Context,
    private val log: ExperimentLog,
) : NaviSource {

    companion object {
        const val QUIET_START_M = 300.0
        const val QUIET_END_M = 400.0      // 경계에서 깜빡이지 않도록 간격을 둔다
        const val POLL_MS = 2_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(context.applicationContext)
    private var events: NaviEvents? = null
    private var connected = false
    private var inQuietZone = false
    private var lastTbtLog = 0L
    private var lastRemainMinutes: Int? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!connected) return
            try {
                navi.getTBTInfo()
                navi.getDestinationInfo()
            } catch (e: SecurityException) {
                log.mark("navi.sdk.permission", e.message ?: "")
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            main.post { log.mark("navi.sdk.status", status.name) }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            main.post { log.mark("navi.sdk.route.started") }
        }

        override fun onRouteCancelled() {
            main.post { log.mark("navi.sdk.route.cancelled"); leaveQuietZone("경로 취소") }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            main.post {
                events?.onDestination(destinationInfo.name)
                reportRemain(destinationInfo.remainTime)
            }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            main.post { reportRemain(drivingInfo.destination.duration) }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            main.post { handleTbt(tbtInfoList) }
        }

        override fun onError(errorCode: NaviErrorCode) {
            main.post { log.mark("navi.sdk.error", errorCode.toString()) }
        }
    }

    override fun start(events: NaviEvents) {
        this.events = events
        log.mark("navi.sdk.init.request")
        navi.initialize(
            onConnected = {
                main.post {
                    connected = true
                    navi.addListener(listener)
                    log.mark("navi.sdk.connected")
                    main.post(poller)
                }
            },
            onFailed = { t -> main.post { log.mark("navi.sdk.failed", t.javaClass.simpleName + ": " + (t.message ?: "")) } },
        )
    }

    override fun stop() {
        main.removeCallbacks(poller)
        if (connected) navi.removeListener(listener)
        connected = false
        navi.release()
        events = null
    }

    private fun reportRemain(seconds: Int) {
        if (seconds <= 0) return
        val minutes = (seconds + 59) / 60
        if (minutes == lastRemainMinutes) return
        lastRemainMinutes = minutes
        events?.onRemainMinutes(minutes)
    }

    private fun handleTbt(list: List<TBTInfo>) {
        // 실험 결과: 목록에 거리 0인 먼 시설(예: 톨게이트)이 섞여 온다. 0 이하는 거리 미정으로 보고 제외하고,
        // 목록 순서상 첫 항목을 다음 안내로 쓴다.
        val next = list.firstOrNull { it.distance > 0.0 }
        val now = System.currentTimeMillis()
        if (now - lastTbtLog > 5_000) {          // 2초마다 오므로 5초에 한 번만 기록
            lastTbtLog = now
            val all = list.take(5).joinToString(" / ") { "${it.type}:${it.distance.toInt()}m" }
            log.mark("navi.sdk.tbt", if (list.isEmpty()) "없음" else "다음=${next?.let { "${it.type} ${it.distance.toInt()}m" } ?: "-"} | 목록 $all")
        }
        if (next == null) { leaveQuietZone("TBT 없음"); return }
        if (!inQuietZone && next.distance <= QUIET_START_M) {
            inQuietZone = true
            log.mark("navi.quiet.enter", "${next.type} ${next.distance.toInt()}m")
            events?.onGuidanceStarted()
        } else if (inQuietZone && next.distance >= QUIET_END_M) {
            leaveQuietZone("${next.distance.toInt()}m")
        }
    }

    private fun leaveQuietZone(reason: String) {
        if (!inQuietZone) return
        inQuietZone = false
        log.mark("navi.quiet.exit", reason)
        events?.onGuidanceEnded()
    }
}
