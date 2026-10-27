package ai.byoa.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietZoneTrackerTest {

    @Test
    fun `회전 정보가 잠시 비어도 정숙 구간을 풀지 않는다`() {
        val t = QuietZoneTracker()
        assertEquals(QuietZoneTracker.Change.ENTER, t.onTbt(250.0))
        assertNull(t.onTbt(null))                       // 일시적 누락
        assertNull(t.onTbt(null))
        assertTrue(t.inQuietZone)
        assertEquals(2, t.missingCount)
        assertNull(t.onTbt(350.0))                      // 경계 사이: 유지
        assertEquals(QuietZoneTracker.Change.EXIT, t.onTbt(450.0))   // 확인된 이탈
    }

    @Test
    fun `경로 안내가 끝나면 정숙 구간을 푼다`() {
        val t = QuietZoneTracker()
        t.onTbt(100.0)
        assertEquals(QuietZoneTracker.Change.EXIT, t.onRouteEnded())
        assertNull(t.onRouteEnded())
    }
}
