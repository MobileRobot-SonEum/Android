package com.damum.daycaretemi.robot;

import android.util.Log;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnLocationsUpdatedListener;
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** temi SDK 0.10.80 구현. temi 빌드에만 포함된다. SDK 콜백(바인더 스레드)은 Base가 메인 스레드로 넘긴다. */
public final class TemiRobotController extends BaseRobotController implements
        OnRobotReadyListener,
        OnGoToLocationStatusChangedListener,
        OnMovementStatusChangedListener,
        OnLocationsUpdatedListener,
        OnDetectionStateChangedListener,
        Robot.TtsListener {

    private static final String TAG = "TemiRobot";
    private static final float TILT_SPEED = 0.5f;

    private final Robot robot = Robot.getInstance();

    @Override
    public void start() {
        robot.addOnRobotReadyListener(this);
        robot.addOnGoToLocationStatusChangedListener(this);
        robot.addOnMovementStatusChangedListener(this);
        robot.addOnLocationsUpdatedListener(this);
        robot.addOnDetectionStateChangedListener(this);
        robot.addTtsListener(this);
        if (robot.isReady()) onRobotReady(true);
    }

    // ------------------------------------------------------------ 명령

    @Override
    public void speak(String text) {
        robot.speak(TtsRequest.create(text, false));
    }

    @Override
    public void goTo(String location) {
        robot.goTo(location);
    }

    @Override
    public void turnBy(int degrees) {
        robot.turnBy(degrees);
    }

    @Override
    public void tiltHead(int degrees) {
        robot.tiltAngle(clampTilt(degrees), TILT_SPEED);
    }

    @Override
    public void stopMovement() {
        robot.stopMovement();
    }

    @Override
    public List<String> getLocations() {
        List<String> l = robot.getLocations();
        return l == null ? Collections.emptyList() : new ArrayList<>(l);
    }

    // ------------------------------------------------------------ SDK 콜백 → 공통 이벤트

    @Override
    public void onRobotReady(boolean isReady) {
        Log.i(TAG, "onRobotReady: " + isReady);
        if (isReady) {
            // 사람 감지 이벤트(OnDetectionStateChanged)는 감지 모드가 켜져 있어야 온다
            try {
                robot.setDetectionModeOn(true);
            } catch (Throwable t) {
                Log.e(TAG, "setDetectionModeOn 실패", t);
            }
        }
        notifyReady(isReady);
    }

    @Override
    public void onGoToLocationStatusChanged(String location, String status, int descriptionId, String description) {
        MoveStatus s = mapGoTo(status);
        if (s != null) notifyGoTo(location, s, description);
    }

    @Override
    public void onMovementStatusChanged(String type, String status) {
        if (!OnMovementStatusChangedListener.TYPE_TURN_BY.equals(type)) return;
        MoveStatus s = mapMovement(status);
        if (s != null) notifyTurn(s);
    }

    @Override
    public void onLocationsUpdated(List<String> locations) {
        notifyLocations(locations == null ? Collections.emptyList() : new ArrayList<>(locations));
    }

    @Override
    public void onDetectionStateChanged(int state) {
        if (state == OnDetectionStateChangedListener.DETECTED) notifyPerson(true);
        else if (state == OnDetectionStateChangedListener.LOST) notifyPerson(false);
        // IDLE: 감지 대기 상태 → 무시
    }

    @Override
    public void onTtsStatusChanged(TtsRequest req) {
        SpeechStatus s;
        switch (req.getStatus()) {
            case STARTED: s = SpeechStatus.STARTED; break;
            case COMPLETED: s = SpeechStatus.COMPLETED; break;
            case CANCELED: s = SpeechStatus.CANCELED; break;
            case ERROR:
            case NOT_ALLOWED: s = SpeechStatus.ERROR; break;
            default: return;   // PENDING, PROCESSING
        }
        notifySpeech(req.getSpeech(), s);
    }

    private static MoveStatus mapGoTo(String status) {
        if (status == null) return null;
        switch (status) {
            case OnGoToLocationStatusChangedListener.START: return MoveStatus.START;
            case OnGoToLocationStatusChangedListener.CALCULATING: return MoveStatus.CALCULATING;
            case OnGoToLocationStatusChangedListener.GOING: return MoveStatus.GOING;
            case OnGoToLocationStatusChangedListener.REPOSING: return MoveStatus.REPOSING;
            case OnGoToLocationStatusChangedListener.COMPLETE: return MoveStatus.COMPLETE;
            case OnGoToLocationStatusChangedListener.ABORT: return MoveStatus.ABORT;
            default: return null;
        }
    }

    private static MoveStatus mapMovement(String status) {
        if (status == null) return null;
        switch (status) {
            case OnMovementStatusChangedListener.STATUS_START: return MoveStatus.START;
            case OnMovementStatusChangedListener.STATUS_CALCULATING: return MoveStatus.CALCULATING;
            case OnMovementStatusChangedListener.STATUS_GOING: return MoveStatus.GOING;
            case OnMovementStatusChangedListener.STATUS_OBSTACLE_DETECTED: return MoveStatus.OBSTACLE;
            case OnMovementStatusChangedListener.STATUS_COMPLETE: return MoveStatus.COMPLETE;
            case OnMovementStatusChangedListener.STATUS_ABORT:
            case OnMovementStatusChangedListener.STATUS_NODE_INACTIVE: return MoveStatus.ABORT;
            default: return null;
        }
    }
}
