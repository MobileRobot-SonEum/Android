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

    /** 말하는 중·대기 중인 TTS를 모두 취소 */
    void cancelSpeech();

    /**
     * 저장된 위치로 이동. 진행 상태는 {@link Listener#onGoToStatus}, 도착 = {@link MoveStatus#COMPLETE}
     * 속도·장애물 회피는 {@link #setCautiousNavigation}으로 정한다.
     */
    void goTo(String location);

    /**
     * 조심 주행 모드. true = 느린 속도 + 장애물 회피 높음, false = temi 기본값으로 되돌림.
     * temi 시스템 설정에 남으므로 켠 기능이 끝날 때 반드시 false로 되돌린다.
     * @throws IllegalStateException true로 켜는데 SDK SETTINGS 권한이 없을 때 (켜지 못하면 이동하지 말 것)
     */
    void setCautiousNavigation(boolean on);

    /** 제자리 회전. +는 왼쪽(반시계), -는 오른쪽. 진행 상태는 {@link Listener#onTurnStatus} */
    void turnBy(int degrees);

    /** 머리 틸트(절대 각도). {@link #TILT_MIN}~{@link #TILT_MAX}로 제한된다. */
    void tiltHead(int degrees);

    /** 이동·회전 정지 */
    void stopMovement();

    /** 저장된 위치 목록 (temi 앱에서 저장한 이름, "home base" 포함) */
    List<String> getLocations();

    /**
     * 사람 감지 준비: SDK SETTINGS 권한을 요청하고 받으면 감지 모드를 켠다.
     * temi 화면에 권한 창이 뜨므로 교사가 누른 버튼에서만 호출한다. 결과는 {@link Listener#onDetectionConfigurationChanged}
     */
    void requestDetectionPermission();

    /** 권한을 얻고 감지 모드를 켜도록 요청했는지 (실제 사람 감지 성공을 보장하지 않음) */
    boolean isDetectionConfigured();

    /** 감지 준비 상태 설명 (화면 표시용) */
    String detectionStatus();

    /** 등록 직후 현재 연결 상태({@link Listener#onReadyChanged})와 감지 준비 상태({@link Listener#onDetectionConfigurationChanged})가 한 번 전달된다. */
    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** 모든 콜백은 메인 스레드에서 호출된다. 필요한 것만 구현하면 된다. */
    interface Listener {
        default void onReadyChanged(boolean ready) {}

        /** 사람 감지 준비 상태 변화 ({@link #requestDetectionPermission} 결과, 연결 끊김 등) */
        default void onDetectionConfigurationChanged(boolean configured, String detail) {}

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
