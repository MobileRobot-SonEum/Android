package com.damum.daycaretemi.server;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.damum.daycaretemi.robot.RobotController;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 서버 명령(Server/docs/control_protocol.md v1)을 RobotController로 실행하는 큐. 메인 스레드에서만 쓴다.
 * <ul>
 *   <li>받은 순서대로 하나씩 실행. 앞 명령이 failed/rejected면 남은 명령은 모두 rejected("앞 명령 실패로 취소")</li>
 *   <li>stop은 큐를 기다리지 않고 즉시: 정지 → stop completed → 하던 명령 failed → 큐는 rejected("stop으로 취소")</li>
 *   <li>연결이 끊기면 {@link #reset()}: 결과를 보내지 않고 모두 잊는다(서버가 이미 disconnected로 닫음)</li>
 * </ul>
 * WebSocket·JSON과 분리해 두어 verification/에서 JVM으로 시험한다.
 */
public final class CommandRunner {

    private static final String TAG = "CommandRunner";

    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";

    /** 결과를 서버로 보낼 곳 (ControlChannel). code는 없으면 null */
    public interface Sink {
        void onResult(Command command, String status, String message, String code);
    }

    public static final class Command {
        public final String id;
        public final String action;
        public final Map<String, String> args;
        /** 서버가 정한 제한 시간. 0 이하면 action별 기본값 */
        public final int timeoutS;

        public Command(String id, String action, Map<String, String> args, int timeoutS) {
            this.id = id;
            this.action = action == null ? "" : action;
            this.args = args == null ? Collections.<String, String>emptyMap() : new HashMap<>(args);
            this.timeoutS = timeoutS;
        }

        public String arg(String key) {
            String v = args.get(key);
            return v == null ? "" : v.trim();
        }
    }

    private final RobotController robot;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Command> queue = new ArrayDeque<>();
    private Command current;
    private boolean attached;

    private final Runnable timeout = this::onTimeout;

    private final RobotController.Listener robotListener = new RobotController.Listener() {
        @Override
        public void onGoToStatus(String location, RobotController.MoveStatus status, String detail) {
            Command c = current;
            if (c == null || !"go_to".equals(c.action) || !sameLocation(c.arg("location"), location)) return;
            if (status == RobotController.MoveStatus.COMPLETE) {
                finish(c, COMPLETED, c.arg("location") + " 도착", null);
            } else if (status == RobotController.MoveStatus.ABORT) {
                String why = detail == null || detail.isEmpty() ? "" : ": " + detail;
                finish(c, FAILED, "이동 중단" + why, "ABORT");
            }
        }

        @Override
        public void onSpeechStatus(String text, RobotController.SpeechStatus status) {
            Command c = current;
            if (c == null || !"speak".equals(c.action) || !c.arg("text").equals(text == null ? "" : text.trim())) return;
            if (status == RobotController.SpeechStatus.COMPLETED) finish(c, COMPLETED, "말하기 끝", null);
            else if (status == RobotController.SpeechStatus.ERROR) finish(c, FAILED, "TTS 오류", "TTS_ERROR");
            else if (status == RobotController.SpeechStatus.CANCELED) finish(c, FAILED, "TTS 취소됨", "TTS_CANCELED");
        }
    };

    public CommandRunner(RobotController robot, Sink sink) {
        this.robot = robot;
        this.sink = sink;
    }

    /** 앱 전체에서 한 번 (ControlChannel 생성 시). 앱이 살아 있는 동안 로봇 이벤트를 듣는다. */
    public void attach() {
        if (attached) return;
        attached = true;
        robot.addListener(robotListener);
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        robot.removeListener(robotListener);
    }

    /** 서버에서 받은 명령. ack는 ControlChannel이 이미 보낸 상태 */
    public void submit(Command c) {
        if ("stop".equals(c.action)) {
            stopNow(c);
            return;
        }
        queue.addLast(c);
        pump();
    }

    /** 연결이 끊겼을 때: 결과를 보내지 않고 이전 명령을 모두 잊는다 (로봇 동작은 건드리지 않음) */
    public void reset() {
        main.removeCallbacks(timeout);
        current = null;
        queue.clear();
    }

    public boolean isBusy() {
        return current != null || !queue.isEmpty();
    }

    // ------------------------------------------------------------ 내부

    private void stopNow(Command stop) {
        safe(robot::stopMovement);
        safe(robot::cancelSpeech);
        main.removeCallbacks(timeout);
        Command interrupted = current;
        current = null;
        sink.onResult(stop, COMPLETED, "정지함", null);
        if (interrupted != null) sink.onResult(interrupted, FAILED, "stop 명령으로 중단", "STOPPED");
        rejectQueue("stop으로 취소");
    }

    private void pump() {
        while (current == null && !queue.isEmpty()) {
            Command c = queue.pollFirst();
            current = c;
            begin(c);   // 즉시 끝나는 명령(rejected 등)은 begin 안에서 finish → current가 다시 null
        }
    }

    private void begin(Command c) {
        Log.i(TAG, "실행: " + c.action + " id=" + c.id);   // speak 문장은 로그에 남기지 않음
        switch (c.action) {
            case "go_to": {
                String location = c.arg("location");
                if (location.isEmpty()) { finish(c, REJECTED, "location이 없습니다", "BAD_ARGS"); return; }
                if (!robot.isReady()) { finish(c, REJECTED, "temi가 준비되지 않았습니다", "NOT_READY"); return; }
                if (findLocation(location) == null) {
                    finish(c, REJECTED, "temi에 없는 위치: " + location, "UNKNOWN_LOCATION");
                    return;
                }
                armTimeout(c);
                try {
                    robot.goTo(findLocation(location));
                } catch (RuntimeException e) {
                    finish(c, FAILED, "이동 명령 실패: " + e.getMessage(), "GOTO_ERROR");
                }
                return;
            }
            case "speak": {
                String text = c.arg("text");
                if (text.isEmpty()) { finish(c, REJECTED, "text가 없습니다", "BAD_ARGS"); return; }
                if (!robot.isReady()) { finish(c, REJECTED, "temi가 준비되지 않았습니다", "NOT_READY"); return; }
                armTimeout(c);
                try {
                    robot.speak(text);
                } catch (RuntimeException e) {
                    finish(c, FAILED, "말하기 실패: " + e.getMessage(), "TTS_ERROR");
                }
                return;
            }
            case "start_game":
                // 숨바꼭질은 화면이 있지만 교사가 화면에서 장소·인원·안전 확인을 해야 시작한다 → 원격 시작은 아직 안 함
                finish(c, REJECTED, "not_implemented", "NOT_IMPLEMENTED");
                return;
            case "start_delivery":
                // 식판·간식 배부는 담당 팀원 구현 전
                finish(c, REJECTED, "not_implemented", "NOT_IMPLEMENTED");
                return;
            default:
                finish(c, REJECTED, "모르는 action: " + c.action, "UNKNOWN_ACTION");
        }
    }

    private void finish(Command c, String status, String message, String code) {
        if (c != current) return;   // 이미 끝났거나 stop/reset으로 잊은 명령
        main.removeCallbacks(timeout);
        current = null;
        sink.onResult(c, status, message, code);
        if (!COMPLETED.equals(status)) rejectQueue("앞 명령 실패로 취소");
        pump();
    }

    private void onTimeout() {
        Command c = current;
        if (c == null) return;
        if ("go_to".equals(c.action)) safe(robot::stopMovement);
        if ("speak".equals(c.action)) safe(robot::cancelSpeech);
        finish(c, FAILED, "시간 초과 (" + effectiveTimeoutS(c) + "초)", "TIMEOUT");
    }

    private void rejectQueue(String message) {
        while (!queue.isEmpty()) {
            sink.onResult(queue.pollFirst(), REJECTED, message, "CANCELED");
        }
    }

    private void armTimeout(Command c) {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, effectiveTimeoutS(c) * 1000L);
    }

    static int effectiveTimeoutS(Command c) {
        if (c.timeoutS > 0) return c.timeoutS;
        switch (c.action) {
            case "go_to": return 120;
            case "speak": return 30;
            default: return 30;
        }
    }

    /** temi는 위치 이름을 소문자로 저장할 수 있어 대소문자·앞뒤 공백을 무시하고 찾는다. 실제 저장된 이름을 돌려준다 */
    private String findLocation(String wanted) {
        List<String> saved;
        try {
            saved = robot.getLocations();
        } catch (RuntimeException e) {
            return null;
        }
        if (saved == null) return null;
        for (String s : saved) if (sameLocation(s, wanted)) return s;
        return null;
    }

    private static boolean sameLocation(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "로봇 명령 실패", e);
        }
    }
}
