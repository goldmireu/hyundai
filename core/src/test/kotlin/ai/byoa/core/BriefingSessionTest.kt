package ai.byoa.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class BriefingSessionTest {

    private val direct = Executor { it.run() }

    private class RecordingSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text }
        override fun stop() {}
    }

    private class RecordingConnector(private val reply: List<String>) : ReadOnlyConnector {
        override val name = "테스트 에이전트"
        override val allowedFields = setOf(ContextField.REMAIN_TIME)
        var received: DriveContext? = null
        override fun fetch(context: DriveContext): AgentReply {
            received = context
            return AgentReply(name, reply)
        }
    }

    @Test
    fun `에이전트를 바꿔도 같은 권한과 분량 규칙이 적용된다`() {
        val context = DriveContext(remainMinutes = 10, destinationName = "회사", drivingState = DrivingState.DRIVING)
        val agents = listOf(
            RecordingConnector(listOf("A1. A2. A3.")),
            RecordingConnector(listOf("B1.", "B2.", "B3.")),
        )
        agents.forEach { agent ->
            val speaker = RecordingSpeaker()
            val log = ExperimentLog()
            val session = BriefingSession(SpeechGate(speaker, log), log, direct, direct)
            session.run(agent, context, userInitiated = true)

            assertEquals(null, agent.received?.destinationName)   // 허용하지 않은 목적지는 전달 안 됨
            assertEquals(1, speaker.spoken.size)                    // 남은 10분 → 2문장 중 첫 문장 읽는 중
            assertEquals(1, session.deferred.size)                  // 3문장 중 1개는 결과 화면으로
        }
    }

    @Test
    fun `연결에 실패하면 실패를 짧게 알린다`() {
        val broken = object : ReadOnlyConnector {
            override val name = "고장 에이전트"
            override val allowedFields = emptySet<ContextField>()
            override fun fetch(context: DriveContext): AgentReply = throw java.io.IOException("timeout")
        }
        val speaker = RecordingSpeaker()
        val log = ExperimentLog()
        BriefingSession(SpeechGate(speaker, log), log, direct, direct)
            .run(broken, DriveContext(null, null, DrivingState.UNKNOWN), userInitiated = true)
        assertEquals(listOf("고장 에이전트에 연결하지 못했습니다."), speaker.spoken)
    }

    /** 응답을 바로 처리하지 않고 쌓아 두는 실행기. 네트워크 대기를 흉내 낸다. */
    private class PendingExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() = tasks.removeFirst().run()
    }

    @Test
    fun `응답을 기다리는 동안 끄면 늦게 온 응답을 재생하지 않는다`() {
        val io = PendingExecutor()
        val speaker = RecordingSpeaker()
        val log = ExperimentLog()
        val gate = SpeechGate(speaker, log)
        val session = BriefingSession(gate, log, io, direct)
        session.run(RecordingConnector(listOf("늦은 응답.")), DriveContext(30, null, DrivingState.DRIVING), userInitiated = true)

        session.cancel("운전자가 끔")
        io.runNext()                                   // 응답이 이제야 도착

        assertEquals(emptyList<String>(), speaker.spoken)
        assertEquals(SpeechGate.State.FINISHED, gate.state)
    }

    @Test
    fun `새 요청이 들어오면 이전 요청의 응답은 버린다`() {
        val io = PendingExecutor()
        val speaker = RecordingSpeaker()
        val log = ExperimentLog()
        val session = BriefingSession(SpeechGate(speaker, log), log, io, direct)
        val context = DriveContext(30, null, DrivingState.DRIVING)
        session.run(RecordingConnector(listOf("이전 에이전트.")), context, userInitiated = true)
        session.run(RecordingConnector(listOf("새 에이전트.")), context, userInitiated = true)

        io.runNext()                                   // 이전 요청 응답 도착 → 버림
        assertEquals(emptyList<String>(), speaker.spoken)
        io.runNext()                                   // 최신 요청 응답 → 재생
        assertEquals(listOf("새 에이전트."), speaker.spoken)
    }
}
