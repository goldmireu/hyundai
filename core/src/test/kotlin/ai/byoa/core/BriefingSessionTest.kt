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
}
