package ai.byoa.core

import java.net.HttpURLConnection
import java.net.URL

/** 모듈에 넘길 수 있는 차량 컨텍스트 항목 */
enum class ContextField { REMAIN_TIME, DESTINATION }

/** 허브가 모듈에 넘기는 차량 컨텍스트. 허용되지 않은 항목은 null로 지운다. */
data class DriveContext(
    val remainMinutes: Int?,
    val destinationName: String?,
    val drivingState: DrivingState,
)

/** 모듈이 돌려주는 결과. 읽을 문장만 담는다. 실행 요청은 받지 않는다. */
data class AgentReply(
    val agentName: String,
    val sentences: List<String>,
)

/**
 * 읽기 전용 커넥터.
 *
 * MVP는 이 인터페이스만 지원한다. 허브는 문장을 읽어줄 뿐 어떤 동작도 실행하지 않는다.
 * 단, 연결된 외부 서버가 따로 가진 권한까지 허브가 통제하지는 않는다.
 *
 * fetch는 네트워크를 쓸 수 있으므로 메인 스레드에서 호출하지 않는다.
 */
interface ReadOnlyConnector {
    val name: String
    val allowedFields: Set<ContextField>
    fun fetch(context: DriveContext): AgentReply
}

/** 권한 게이트: 모듈별로 허용된 항목만 남긴다. 주행 상태는 안전 판단용이라 항상 전달한다. */
object PermissionGate {
    fun filter(context: DriveContext, allowed: Set<ContextField>): DriveContext = context.copy(
        remainMinutes = context.remainMinutes.takeIf { ContextField.REMAIN_TIME in allowed },
        destinationName = context.destinationName.takeIf { ContextField.DESTINATION in allowed },
    )
}

/** 1차 구동 실험용 모의 일정. 실제 Google 캘린더 연동은 2차 PT 목표. */
data class MockEvent(val title: String, val startMinuteOfDay: Int)

class MockCalendarConnector(
    private val events: List<MockEvent>,
    private val nowMinuteOfDay: () -> Int,
) : ReadOnlyConnector {
    override val name = "기본 일정 브리핑"
    override val allowedFields = setOf(ContextField.REMAIN_TIME)

    override fun fetch(context: DriveContext): AgentReply {
        val now = nowMinuteOfDay()
        val upcoming = events.filter { it.startMinuteOfDay >= now }.sortedBy { it.startMinuteOfDay }
        if (upcoming.isEmpty()) return AgentReply(name, listOf("오늘 남은 일정이 없습니다."))

        val first = upcoming.first()
        val sentences = mutableListOf("다음 일정은 ${clock(first.startMinuteOfDay)} ${first.title}입니다.")
        // 일정 장소와 내비 목적지가 같은지 아직 확인하지 않으므로 지각 여부는 판단하지 않는다.
        // 도착 예정 시각만 알려주고 판단은 운전자에게 맡긴다(장소 대조는 후속 단계).
        context.remainMinutes?.let { remain ->
            sentences += "현재 목적지 도착 예정은 ${clock((now + remain) % (24 * 60))}입니다."
        }
        upcoming.drop(1).forEach { sentences += "이어서 ${clock(it.startMinuteOfDay)} ${it.title}이 있습니다." }
        return AgentReply(name, sentences)
    }

    private fun clock(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return if (m == 0) "${h}시" else "${h}시 ${m}분"
    }
}

/**
 * 사용자가 등록한 외부 에이전트(웹훅).
 *
 * 요청: POST, JSON 본문 {"remain_minutes":..,"destination":..,"driving_state":..} (허용 항목만)
 * 응답: text/plain, 한 줄에 한 문장
 */
class WebhookConnector(
    override val name: String,
    private val url: String,
    override val allowedFields: Set<ContextField>,
    private val timeoutMs: Int = 4_000,
) : ReadOnlyConnector {

    override fun fetch(context: DriveContext): AgentReply {
        val connection = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(toJson(context).toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) {
                return AgentReply(name, listOf("$name 응답에 실패했습니다."))
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val sentences = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return AgentReply(name, sentences.ifEmpty { listOf("$name 에서 받은 내용이 없습니다.") })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun toJson(context: DriveContext): String {
            val fields = buildList {
                context.remainMinutes?.let { add("\"remain_minutes\":$it") }
                context.destinationName?.let { add("\"destination\":\"${escape(it)}\"") }
                add("\"driving_state\":\"${context.drivingState.name}\"")
            }
            return fields.joinToString(prefix = "{", postfix = "}", separator = ",")
        }

        private fun escape(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
