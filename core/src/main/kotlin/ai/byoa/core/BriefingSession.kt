package ai.byoa.core

import java.util.concurrent.Executor

/**
 * 한 번의 브리핑 흐름: 권한 확인 → 외부 읽기 → 분량 조절 → 음성 출력.
 *
 * 요청마다 번호를 붙인다. 응답을 기다리는 동안 운전자가 끄거나(cancel) 새 요청을 보내면
 * 이전 요청은 무효가 되고, 늦게 도착한 응답은 버린다(재생하지 않는다).
 *
 * @param io 네트워크용 실행기(백그라운드 스레드)
 * @param main 게이트를 다루는 실행기(앱에서는 메인 스레드). run·cancel도 이 스레드에서 호출한다.
 */
class BriefingSession(
    private val gate: SpeechGate,
    private val log: ExperimentLog,
    private val io: Executor,
    private val main: Executor,
) {
    /** 마지막 브리핑에서 읽지 못하고 결과 화면으로 넘긴 문장 */
    var deferred: List<String> = emptyList()
        private set

    /** 가장 최근 요청 번호. 이 번호와 다른 응답은 버린다. */
    private var latestRequest = 0

    fun run(
        connector: ReadOnlyConnector,
        context: DriveContext,
        userInitiated: Boolean,
        onPlanned: (BriefingPlan) -> Unit = {},
    ) {
        val request = ++latestRequest
        val allowed = PermissionGate.filter(context, connector.allowedFields)
        log.mark("read.request", "#$request ${connector.name} 허용=${connector.allowedFields}")
        io.execute {
            val reply = try {
                connector.fetch(allowed)
            } catch (e: Exception) {
                AgentReply(connector.name, listOf("${connector.name}에 연결하지 못했습니다."))
                    .also { log.mark("read.error", e.javaClass.simpleName) }
            }
            main.execute {
                if (request != latestRequest) {
                    log.mark("read.stale", "#$request 응답 버림(취소되었거나 새 요청이 있음)")
                    return@execute
                }
                log.mark("read.done", "#$request ${reply.agentName} 문장 ${reply.sentences.size}개")
                val sentences = reply.sentences.flatMap { BriefingPolicy.split(it) }
                val plan = BriefingPolicy.plan(sentences, context.remainMinutes)
                deferred = plan.deferred
                onPlanned(plan)
                gate.start(plan.spoken, userInitiated)
            }
        }
    }

    /** 운전자가 끄거나, 에이전트를 바꾸거나, 화면을 닫을 때. 기다리던 응답도 무효로 한다. */
    fun cancel(reason: String) {
        latestRequest++
        log.mark("session.cancel", reason)
        gate.cancel()
    }
}
