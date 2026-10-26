package ai.byoa.core

/** 이번 주행에서 읽을 문장과, 도착 후 결과 화면으로 넘길 문장 */
data class BriefingPlan(
    val spoken: List<String>,
    val deferred: List<String>,
)

/**
 * 남은 이동 시간에 맞춰 브리핑 분량을 정하는 규칙.
 *
 * 수치는 실험 전 제안값이다. 구동 실험(E4) 결과로 조정한다.
 */
object BriefingPolicy {

    /** 남은 시간(분)별 최대 문장 수. 남은 시간을 모르면 보수적으로 2문장. */
    fun maxSentences(remainMinutes: Int?): Int = when {
        remainMinutes == null -> 2
        remainMinutes < 5 -> 1
        remainMinutes < 15 -> 2
        remainMinutes < 30 -> 4
        else -> 6
    }

    /** 한 문장이 너무 길면 주행 중 듣기 어렵다. 이 길이를 넘는 문장은 뒤로 미룬다. */
    const val MAX_SENTENCE_CHARS = 80

    /** 문장 단위로 나눈다. 중단 후 복귀할 때 이 단위로 다시 읽는다. */
    fun split(text: String): List<String> =
        text.split(Regex("(?<=[.!?。])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 읽을 수 있는 짧은 문장이 하나도 없을 때 대신 읽는 안내 */
    const val DEFERRED_NOTICE = "자세한 내용은 도착 후 화면에서 확인하세요."

    fun plan(sentences: List<String>, remainMinutes: Int?): BriefingPlan {
        val (short, tooLong) = sentences.partition { it.length <= MAX_SENTENCE_CHARS }
        if (short.isEmpty() && tooLong.isNotEmpty()) {
            return BriefingPlan(spoken = listOf(DEFERRED_NOTICE), deferred = tooLong)
        }
        val limit = maxSentences(remainMinutes)
        return BriefingPlan(
            spoken = short.take(limit),
            deferred = short.drop(limit) + tooLong,
        )
    }
}
