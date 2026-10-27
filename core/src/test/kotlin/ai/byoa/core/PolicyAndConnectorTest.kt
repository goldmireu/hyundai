package ai.byoa.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndConnectorTest {

    @Test
    fun `남은 시간이 짧을수록 적게 읽고 나머지는 결과 화면으로 넘긴다`() {
        val sentences = (1..6).map { "문장 $it." }
        val short = BriefingPolicy.plan(sentences, remainMinutes = 3)
        assertEquals(1, short.spoken.size)
        assertEquals(5, short.deferred.size)

        val long = BriefingPolicy.plan(sentences, remainMinutes = 40)
        assertEquals(6, long.spoken.size)
    }

    @Test
    fun `너무 긴 문장은 주행 중에 읽지 않는다`() {
        val longSentence = "가".repeat(BriefingPolicy.MAX_SENTENCE_CHARS + 1)
        val plan = BriefingPolicy.plan(listOf("짧은 문장.", longSentence), remainMinutes = 40)
        assertEquals(listOf("짧은 문장."), plan.spoken)
        assertEquals(listOf(longSentence), plan.deferred)
    }

    @Test
    fun `모든 문장이 길면 짧은 안내만 읽는다`() {
        val longSentence = "가".repeat(BriefingPolicy.MAX_SENTENCE_CHARS + 1)
        val plan = BriefingPolicy.plan(listOf(longSentence), remainMinutes = 40)
        assertEquals(listOf(BriefingPolicy.DEFERRED_NOTICE), plan.spoken)
        assertEquals(listOf(longSentence), plan.deferred)
    }

    @Test
    fun `문장 단위로 나눈다`() {
        assertEquals(
            listOf("오늘 일정은 두 개입니다.", "첫째는 회의입니다!", "괜찮나요?"),
            BriefingPolicy.split("오늘 일정은 두 개입니다. 첫째는 회의입니다! 괜찮나요?"),
        )
    }

    @Test
    fun `상태를 모르면 주행 제한을 유지한다`() {
        assertTrue(DrivingState.UNKNOWN.restrictsUi)
        assertTrue(DrivingState.DRIVING.restrictsUi)
        assertFalse(DrivingState.PARKED.restrictsUi)
    }

    @Test
    fun `권한 게이트는 허용하지 않은 항목을 지운다`() {
        val context = DriveContext(remainMinutes = 20, destinationName = "회사", drivingState = DrivingState.DRIVING)
        val filtered = PermissionGate.filter(context, setOf(ContextField.REMAIN_TIME))
        assertEquals(20, filtered.remainMinutes)
        assertNull(filtered.destinationName)
        assertEquals(DrivingState.DRIVING, filtered.drivingState)
    }

    @Test
    fun `웹훅 요청에는 허용된 항목만 들어간다`() {
        val filtered = PermissionGate.filter(
            DriveContext(20, "회사 \"본관\"", DrivingState.UNKNOWN),
            setOf(ContextField.DESTINATION),
        )
        assertEquals(
            "{\"destination\":\"회사 \\\"본관\\\"\",\"driving_state\":\"UNKNOWN\"}",
            WebhookConnector.toJson(filtered),
        )
    }

    @Test
    fun `모의 일정은 지각을 단정하지 않고 도착 예정 시각만 알려준다`() {
        val connector = MockCalendarConnector(
            events = listOf(MockEvent("주간 회의", 9 * 60 + 30), MockEvent("고객 미팅", 14 * 60)),
            nowMinuteOfDay = { 9 * 60 },
        )
        val late = connector.fetch(DriveContext(40, null, DrivingState.DRIVING))
        assertEquals("다음 일정은 9시 30분 주간 회의입니다.", late.sentences[0])
        assertEquals("현재 목적지 도착 예정은 9시 40분입니다.", late.sentences[1])
        assertEquals("이어서 14시 고객 미팅이 있습니다.", late.sentences[2])

        val early = connector.fetch(DriveContext(20, null, DrivingState.DRIVING))
        assertEquals("현재 목적지 도착 예정은 9시 20분입니다.", early.sentences[1])
    }
}
