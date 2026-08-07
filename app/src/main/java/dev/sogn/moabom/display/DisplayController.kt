package dev.sogn.moabom.display

import android.view.View

/** 화면 방식별 UI와 생명주기를 Activity에서 분리하는 계약입니다.
 * Defines the contract that separates mode-specific UI and lifecycle work from the Activity. */
interface DisplayController {
    val view: View
    val supportsManualRefresh: Boolean get() = false

    /** Activity 시작을 전달합니다. Notifies the controller that its Activity has started. */
    fun onStart()
    /** Activity 중지를 전달합니다. Notifies the controller that its Activity has stopped. */
    fun onStop()
    /** 화면 꺼짐을 전달합니다. Notifies the controller that the screen turned off. */
    fun onScreenOff()
    /** 화면 켜짐을 전달합니다. Notifies the controller that the screen turned on. */
    fun onScreenOn()
    /** 연결 상태 변화를 전달합니다. Delivers a network-state change. */
    fun onNetworkStateChanged(state: NetworkState)
    /** 초기 연결 상태를 부작용 없이 설정합니다. Sets initial network state without side effects. */
    fun initializeNetworkState(state: NetworkState) = onNetworkStateChanged(state)
    /** 사용자의 새로고침 요청을 처리합니다. Handles a user-triggered refresh. */
    fun refresh() = Unit
    /** 보유한 리소스를 해제합니다. Releases owned resources. */
    fun destroy()
}

/** 표시 controller가 필요로 하는 연결 상태입니다.
 * Network states used by display controllers. */
enum class NetworkState { UNAVAILABLE, AVAILABLE, VALIDATED }
