package ai.byoa.core

/**
 * 예측형 정숙 구간 판정.
 *
 * NaviHelper에는 "안내 음성이 지금 나온다"는 신호가 없으므로 다음 회전까지의 거리로 안내 시점을 예측한다.
 * - 다음 회전이 startM 이내로 가까워지면 정숙 구간에 들어간다.
 * - 회전을 지나 다음 회전이 endM 이상 멀어졌을 때, 또는 경로 안내가 끝났을 때만 나온다.
 * - 회전 정보가 비어 있으면(일시적 누락일 수 있음) 판단하지 않고 지금 상태를 유지한다.
 *   정보가 불명확할 때 정숙 구간을 풀면 안내와 겹칠 수 있기 때문이다.
 */
class QuietZoneTracker(
    private val startM: Double = 300.0,
    private val endM: Double = 400.0,
) {
    enum class Change { ENTER, EXIT }

    var inQuietZone = false
        private set

    /** 연속으로 회전 정보가 비어 있던 횟수(기록용) */
    var missingCount = 0
        private set

    /** @param nextDistanceM 다음 회전까지 거리(m). 유효한 항목이 없으면 null */
    fun onTbt(nextDistanceM: Double?): Change? {
        if (nextDistanceM == null) {
            missingCount++
            return null
        }
        missingCount = 0
        if (!inQuietZone && nextDistanceM <= startM) {
            inQuietZone = true
            return Change.ENTER
        }
        if (inQuietZone && nextDistanceM >= endM) {
            inQuietZone = false
            return Change.EXIT
        }
        return null
    }

    /** 경로 안내가 끝났거나 취소됐을 때 */
    fun onRouteEnded(): Change? {
        missingCount = 0
        if (!inQuietZone) return null
        inQuietZone = false
        return Change.EXIT
    }
}
