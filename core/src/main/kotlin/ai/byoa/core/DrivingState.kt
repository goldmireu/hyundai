package ai.byoa.core

/**
 * 차량 주행 상태.
 *
 * 원칙: 상태를 확인할 수 없으면(UNKNOWN) 주행 중으로 간주한다.
 * 앱을 실행했다는 사실만으로 정차 상태라고 판단하지 않는다.
 */
enum class DrivingState {
    PARKED,
    DRIVING,
    UNKNOWN;

    /** 주행 제한(최소 화면, 상세 화면 차단)을 적용해야 하는가 */
    val restrictsUi: Boolean get() = this != PARKED
}
