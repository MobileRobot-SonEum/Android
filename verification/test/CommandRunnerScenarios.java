import android.os.Handler;
import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.server.CommandRunner;
import java.util.*;

/** 명령 큐 규칙 시험 (Server/docs/control_protocol.md v1). */
public final class CommandRunnerScenarios {
    static class Bot implements RobotController {
        final List<Listener> ls = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        List<String> locations = Arrays.asList("home base", "식당", "놀이방");
        boolean ready = true;
        public void start() {}
        public boolean isReady() { return ready; }
        public void speak(String t) { calls.add("speak:" + t); }
        public void cancelSpeech() { calls.add("cancelSpeech"); }
        public void goTo(String l) { calls.add("goTo:" + l); }
        public void setCautiousNavigation(boolean on) {}
        public void turnBy(int d) {}
        public void tiltHead(int d) {}
        public void stopMovement() { calls.add("stop"); }
        public List<String> getLocations() { return locations; }
        public void requestDetectionPermission() {}
        public boolean isDetectionConfigured() { return true; }
        public String detectionStatus() { return ""; }
        public void addListener(Listener l) { ls.add(l); }
        public void removeListener(Listener l) { ls.remove(l); }
        void arrive(String l) { for (Listener x : new ArrayList<>(ls)) x.onGoToStatus(l, MoveStatus.COMPLETE, ""); }
        void abort(String l) { for (Listener x : new ArrayList<>(ls)) x.onGoToStatus(l, MoveStatus.ABORT, "막힘"); }
        void spoke(String t) { for (Listener x : new ArrayList<>(ls)) x.onSpeechStatus(t, SpeechStatus.COMPLETED); }
    }

    static final List<String> results = new ArrayList<>();
    static CommandRunner runner(Bot bot) {
        results.clear();
        CommandRunner r = new CommandRunner(bot, (c, status, msg, code) -> results.add(c.id + ":" + status + ":" + msg));
        r.attach();
        return r;
    }
    static CommandRunner.Command cmd(String id, String action, String... kv) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) a.put(kv[i], kv[i + 1]);
        return new CommandRunner.Command(id, action, a, 0);
    }
    static void eq(Object a, Object e, String why) { if (!Objects.equals(a, e)) throw new AssertionError(why + ": " + a + " != " + e); }

    static void queueRunsInOrder() {
        Handler.reset(); Bot bot = new Bot(); CommandRunner r = runner(bot);
        r.submit(cmd("1", "go_to", "location", "식당"));
        r.submit(cmd("2", "speak", "text", "손 씻고 오세요!"));
        eq(bot.calls, Arrays.asList("goTo:식당"), "second waits for first");
        bot.arrive("식당");
        eq(results, Arrays.asList("1:completed:식당 도착"), "go_to completed on arrival");
        eq(bot.calls.get(1), "speak:손 씻고 오세요!", "speak starts after go_to result");
        bot.spoke("손 씻고 오세요!");
        eq(results.get(1), "2:completed:말하기 끝", "speak completed when TTS done");
        eq(r.isBusy(), false, "idle");
        System.out.println("PASS: queue order / go_to arrive / speak done");
    }

    static void failureRejectsRest() {
        Handler.reset(); Bot bot = new Bot(); CommandRunner r = runner(bot);
        r.submit(cmd("1", "go_to", "location", "식당"));
        r.submit(cmd("2", "start_delivery", "kind", "snack"));
        r.submit(cmd("3", "speak", "text", "안녕"));
        bot.abort("식당");
        eq(results, Arrays.asList("1:failed:이동 중단: 막힘", "2:rejected:앞 명령 실패로 취소", "3:rejected:앞 명령 실패로 취소"),
                "abort → failed, rest rejected");
        eq(bot.calls, Arrays.asList("goTo:식당"), "nothing else executed");
        System.out.println("PASS: failure rejects remaining queue");
    }

    static void stopImmediate() {
        Handler.reset(); Bot bot = new Bot(); CommandRunner r = runner(bot);
        r.submit(cmd("1", "go_to", "location", "놀이방"));
        r.submit(cmd("2", "speak", "text", "도착"));
        r.submit(cmd("s", "stop"));
        eq(results, Arrays.asList("s:completed:정지함", "1:failed:stop 명령으로 중단", "2:rejected:stop으로 취소"), "stop order");
        eq(bot.calls, Arrays.asList("goTo:놀이방", "stop", "cancelSpeech"), "robot stopped");
        bot.abort("놀이방");   // 정지로 온 늦은 ABORT는 무시
        eq(results.size(), 3, "late abort ignored");
        System.out.println("PASS: stop runs immediately and clears queue");
    }

    static void notImplementedAndUnknown() {
        Handler.reset(); Bot bot = new Bot(); CommandRunner r = runner(bot);
        r.submit(cmd("1", "start_game", "name", "숨바꼭질"));
        eq(results.get(0), "1:rejected:not_implemented", "start_game not_implemented");
        r.submit(cmd("2", "start_delivery", "kind", "meal"));
        eq(results.get(1), "2:rejected:not_implemented", "start_delivery not_implemented");
        r.submit(cmd("3", "go_to", "location", "옥상"));
        eq(results.get(2), "3:rejected:temi에 없는 위치: 옥상", "unknown location rejected");
        r.submit(cmd("4", "go_to", "location", "HOME BASE"));
        eq(bot.calls, Arrays.asList("goTo:home base"), "location match ignores case, uses saved name");
        System.out.println("PASS: not_implemented / unknown location / case-insensitive");
    }

    static void timeoutAndReset() {
        Handler.reset(); Bot bot = new Bot(); CommandRunner r = runner(bot);
        r.submit(new CommandRunner.Command("1", "go_to", Collections.singletonMap("location", "식당"), 5));
        r.submit(cmd("2", "speak", "text", "x"));
        Handler.advance(5000);
        eq(results.get(0), "1:failed:시간 초과 (5초)", "go_to timeout");
        eq(bot.calls.contains("stop"), true, "timeout stops movement");
        eq(results.get(1), "2:rejected:앞 명령 실패로 취소", "rest rejected after timeout");

        results.clear();
        r.submit(cmd("3", "go_to", "location", "식당"));
        r.submit(cmd("4", "speak", "text", "y"));
        r.reset();
        bot.arrive("식당");
        Handler.advance(200000);
        eq(results.isEmpty(), true, "reset forgets without results");
        eq(r.isBusy(), false, "idle after reset");
        System.out.println("PASS: timeout / reset on disconnect");
    }

    public static void main(String[] args) {
        queueRunsInOrder();
        failureRejectsRest();
        stopImmediate();
        notImplementedAndUnknown();
        timeoutAndReset();
    }
}
