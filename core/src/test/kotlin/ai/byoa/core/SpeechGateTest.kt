package ai.byoa.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        override fun speak(text: String) { spoken += text }
        override fun stop() { stops++ }
    }

    private var now = 0L
    private val log = ExperimentLog(clock = { now })

    /** TTS가 지금 문장을 시작하고 끝까지 읽은 것처럼 콜백을 보낸다 */
    private fun SpeechGate.finishCurrent() {
        onSpeechStarted()
        onSpeechDone()
    }

    @Test
    fun `읽던 문장은 내비 안내 후 처음부터 다시 읽는다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("첫 문장.", "둘째 문장.", "셋째 문장."), userInitiated = true)
        gate.finishCurrent()                      // 첫 문장 완료 → 둘째 시작
        assertEquals("둘째 문장.", speaker.spoken.last())

        gate.onSpeechStarted()
        gate.onNaviGuidanceStarted()              // 둘째 읽는 도중 내비 안내
        assertEquals(SpeechGate.State.PAUSED, gate.state)
        assertEquals(1, speaker.stops)

        gate.onNaviGuidanceEnded()                // 안내 종료 → 둘째부터 다시
        assertEquals(listOf("첫 문장.", "둘째 문장.", "둘째 문장."), speaker.spoken)

        gate.finishCurrent()
        gate.finishCurrent()
        assertEquals(SpeechGate.State.FINISHED, gate.state)
        assertEquals("셋째 문장.", speaker.spoken.last())
    }

    @Test
    fun `멈춘 문장의 늦은 완료 신호는 다음 문장을 건너뛰게 하지 않는다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("A.", "B."), userInitiated = true)
        gate.onSpeechStarted()
        gate.onNaviGuidanceStarted()
        gate.onNaviGuidanceEnded()                // A를 다시 요청, 아직 시작 전
        gate.onSpeechDone()                       // 멈춘 A의 완료 신호가 늦게 도착
        assertEquals(SpeechGate.State.SPEAKING, gate.state)
        assertEquals(listOf("A.", "A."), speaker.spoken)   // B로 넘어가지 않음

        gate.finishCurrent()
        assertEquals("B.", speaker.spoken.last())
    }

    @Test
    fun `TTS 오류가 나면 그 문장을 건너뛰고 멈춰 있지 않는다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("A.", "B."), userInitiated = true)
        gate.onSpeechFailed()
        assertEquals("B.", speaker.spoken.last())
        gate.onSpeechFailed()
        assertEquals(SpeechGate.State.FINISHED, gate.state)
    }

    @Test
    fun `내비 안내 중에 시작하면 안내가 끝날 때까지 기다린다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.onNaviGuidanceStarted()
        gate.start(listOf("문장."), userInitiated = true)
        assertEquals(SpeechGate.State.PAUSED, gate.state)
        assertTrue(speaker.spoken.isEmpty())
        gate.onNaviGuidanceEnded()
        assertEquals(listOf("문장."), speaker.spoken)
    }

    @Test
    fun `자동 호출 결과는 운전자가 요청할 때만 재생한다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("자동 브리핑."), userInitiated = false)
        assertEquals(SpeechGate.State.HELD, gate.state)
        assertTrue(speaker.spoken.isEmpty())

        gate.onNaviGuidanceStarted(); gate.onNaviGuidanceEnded()   // 재생 조건이 바뀌어도
        gate.onAudioFocusLost(); gate.onAudioFocusRegained()
        assertEquals(SpeechGate.State.HELD, gate.state)            // 요청 전에는 말하지 않는다
        assertTrue(speaker.spoken.isEmpty())

        gate.releaseHeld()
        assertEquals(listOf("자동 브리핑."), speaker.spoken)
    }

    @Test
    fun `중단 사유가 겹치면 모든 사유가 풀릴 때까지 재생하지 않는다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("A.", "B."), userInitiated = true)
        gate.onSpeechStarted()
        gate.onNaviGuidanceStarted()          // 정숙 구간 진입
        gate.onAudioFocusLost()               // 이어서 오디오 포커스 상실
        gate.onAudioFocusRegained()           // 포커스는 돌아왔지만 아직 정숙 구간
        assertEquals(SpeechGate.State.PAUSED, gate.state)
        assertEquals(listOf("A."), speaker.spoken)
        assertEquals(setOf(SpeechGate.Block.NAVI), gate.activeBlocks)

        gate.onNaviGuidanceEnded()            // 마지막 사유가 풀려야 재개
        assertEquals(SpeechGate.State.SPEAKING, gate.state)
        assertEquals(listOf("A.", "A."), speaker.spoken)
    }

    @Test
    fun `취소하면 남은 문장이 결과 화면용으로 남는다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("A.", "B.", "C."), userInitiated = true)
        gate.cancel()
        assertEquals(listOf("A.", "B.", "C."), gate.unspoken)
        assertEquals(SpeechGate.State.FINISHED, gate.state)
    }

    @Test
    fun `중단 지연을 기록으로 잴 수 있다`() {
        val speaker = FakeSpeaker()
        val gate = SpeechGate(speaker, log)
        gate.start(listOf("A."), userInitiated = true)
        now = 1_000; gate.onNaviGuidanceStarted()
        now = 1_180; log.mark("tts.stopped")
        assertEquals(180L, log.between("navi.start", "tts.stopped"))
    }
}
