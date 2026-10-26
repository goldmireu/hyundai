package ai.byoa.core

/**
 * 구동 실험 기록. 이벤트마다 시각(ms)을 남기고, 두 이벤트 사이 지연을 계산한다.
 *
 * 예: between("navi.start", "tts.stopped") = 내비 안내 시작 후 TTS가 실제로 멈추기까지 걸린 시간
 */
class ExperimentLog(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (String) -> Unit = {},
) {
    data class Entry(val atMs: Long, val event: String, val detail: String)

    private val entries = mutableListOf<Entry>()
    private val startedAt = clock()

    val all: List<Entry> @Synchronized get() = entries.toList()

    /** 네트워크 스레드에서도 호출될 수 있어 동기화한다. sink는 호출한 스레드에서 실행된다. */
    fun mark(event: String, detail: String = "") {
        val entry = synchronized(this) {
            Entry(clock(), event, detail).also { entries += it }
        }
        sink(format(entry))
    }

    /** from 이벤트의 마지막 발생 이후 처음 나온 to 이벤트까지의 지연(ms). 없으면 null. */
    @Synchronized
    fun between(from: String, to: String): Long? {
        val start = entries.lastOrNull { it.event == from } ?: return null
        val end = entries.firstOrNull { it.event == to && it.atMs >= start.atMs } ?: return null
        return end.atMs - start.atMs
    }

    fun format(entry: Entry): String {
        val t = entry.atMs - startedAt
        return if (entry.detail.isEmpty()) "+${t}ms ${entry.event}" else "+${t}ms ${entry.event} | ${entry.detail}"
    }

    @Synchronized
    fun dump(): String = entries.joinToString("\n") { format(it) }
}
