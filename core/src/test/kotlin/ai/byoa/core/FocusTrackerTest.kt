package ai.byoa.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTrackerTest {

    @Test
    fun `포커스 요청이 거절된 채 연동 모드에 들어가면 재생을 막는다`() {
        val t = FocusTracker()
        assertNull(t.onRequestResult(granted = false))          // 관찰 모드: 기록만
        assertEquals(FocusTracker.Signal.LOST, t.setGating(true))
        assertEquals(FocusTracker.Signal.REGAINED, t.onGain())  // 획득 확인 후에만 해제
    }

    @Test
    fun `관찰 모드에서 포커스를 잃은 뒤 연동 모드로 바꿔도 막는다`() {
        val t = FocusTracker()
        t.onRequestResult(granted = true)
        assertNull(t.onLoss())                                   // 관찰 중 상실
        assertEquals(FocusTracker.Signal.LOST, t.setGating(true))
    }

    @Test
    fun `포커스를 가진 채 연동하면 막지 않고, 상실과 회복을 그대로 전달한다`() {
        val t = FocusTracker()
        t.onRequestResult(granted = true)
        assertNull(t.setGating(true))
        assertEquals(FocusTracker.Signal.LOST, t.onLoss())
        assertNull(t.onLoss())                                   // 중복 상실은 한 번만
        assertEquals(FocusTracker.Signal.REGAINED, t.onGain())
    }

    @Test
    fun `탐침을 끄면 걸어 둔 막힘을 푼다`() {
        val t = FocusTracker()
        t.onRequestResult(granted = false)
        t.setGating(true)
        assertEquals(FocusTracker.Signal.REGAINED, t.onAbandon())
    }
}
