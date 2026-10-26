package ai.byoa.core

import java.util.concurrent.Executor

/**
 * 한 번의 브리핑 흐름: 권한 확인 → 외부 읽기 → 분량 조절 → 음성 출력.
 *
 * @param io 네트워크용 실행기(백그라운드 스레드)
 * @param main 게이트를 다루는 실행기(앱에서는 메인 스레드)
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

    fun run(
        connector: ReadOnlyConnector,
        context: DriveContext,
        userInitiated: Boolean,
        onPlanned: (BriefingPlan) -> Unit = {},
    ) {
        val allowed = PermissionGate.filter(context, connector.allowedFields)
        log.mark("read.request", "${connector.name} 허용=${connector.allowedFields}")
        io.execute {
            val reply = try {
                connector.fetch(allowed)
            } catch (e: Exception) {
                AgentReply(connector.name, listOf("${connector.name}에 연결하지 못했습니다."))
                    .also { log.mark("read.error", e.javaClass.simpleName) }
            }
            main.execute {
                log.mark("read.done", "${reply.agentName} 문장 ${reply.sentences.size}개")
                val sentences = reply.sentences.flatMap { BriefingPolicy.split(it) }
                val plan = BriefingPolicy.plan(sentences, context.remainMinutes)
                deferred = plan.deferred
                onPlanned(plan)
                gate.start(plan.spoken, userInitiated)
            }
        }
    }
}
