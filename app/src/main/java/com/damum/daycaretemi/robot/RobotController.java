package com.damum.daycaretemi.robot;

import java.util.List;

/**
 * 앱 전체가 쓰는 로봇 제어 인터페이스. 앱 코드는 temi SDK(Robot 등)를 직접 부르지 말고 이것만 쓴다.
 * <ul>
 *   <li>temi 빌드: {@code TemiRobotController} (src/temi)</li>
 *   <li>emulator 빌드: {@code FakeRobotController} (src/emulator)</li>
 * </ul>
 * 인스턴스는 {@code DaycareApp}이 하나만 만든다 → {@code DaycareApp.robot(context)}.
 * 새 기능은 여기에 먼저 추가하고 두 구현을 모두 채운다.
 */
public interface RobotController {

    int TILT_MIN = -25;
    int TILT_MAX = 55;

    /** SDK 리스너 등록 등 초기화. DaycareApp이 앱 시작 시 한 번만 호출한다. Activity에서 부르지 말 것. */
    void start();

    /** temi SDK 연결 상태 */
    boolean isReady();

    /** TTS. 여러 번 부르면 순서대로 말한다. 진행 상태는 {@link Listener#onSpeechStatus} */
    void speak(String text);

    /** 저장된 위치로 이동. 진행 상태는 {@link Listener#onGoToStatus}, 도착 = {@link MoveStatus#COMPLETE} */
    void goTo(String location);

    /** 제자리 회전. +는 왼쪽(반시계), -는 오른쪽. 진행 상태는 {@link Listener#onTurnStatus} */
    void turnBy(int degrees);

    /** 머리 틸트(절대 각도). {@link #TILT_MIN}~{@link #TILT_MAX}로 제한된다. */
    void tiltHead(int degrees);

    /** 이동·회전 정지 */
    void stopMovement();

    /** 저장된 위치 목록 (temi 앱에서 저장한 이름, "home base" 포함) */
    List<String> getLocations();

    /** 등록 직후 현재 연결 상태가 {@link Listener#onReadyChanged}로 한 번 전달된다. */
    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** 모든 콜백은 메인 스레드에서 호출된다. 필요한 것만 구현하면 된다. */
    interface Listener {
        default void onReadyChanged(boolean ready) {}

        default void onGoToStatus(String location, MoveStatus status, String detail) {}

        default void onTurnStatus(MoveStatus status) {}

        default void onSpeechStatus(String text, SpeechStatus status) {}

        default void onLocationsUpdated(List<String> locations) {}

        /** 사람이 temi 앞에 감지됨(true) / 사라짐(false) */
        default void onPersonDetected(boolean detected) {}
    }

    enum MoveStatus { START, CALCULATING, GOING, REPOSING, OBSTACLE, COMPLETE, ABORT }

    enum SpeechStatus { STARTED, COMPLETED, ERROR, CANCELED }
}
