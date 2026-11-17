package com.damum.daycaretemi.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.damum.daycaretemi.BuildConfig;
import com.damum.daycaretemi.robot.RobotController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 서버 → temi 명령 채널 (Server/docs/control_protocol.md v1, WebSocket /ws/temi). 앱 전체에 하나({@code DaycareApp}이 만들고 start).
 * <ul>
 *   <li>앱이 켜지면 접속. 끊기면 3초 → 6 → 12 → 24 → 30초(최대)로 늘려 가며 재접속. welcome을 받으면 3초로 초기화</li>
 *   <li>close 4000(같은 device_id의 새 연결로 교체됨)·HTTP 401/403(키·장치 ID 문제)이면 재접속하지 않음</li>
 *   <li>command를 받으면 그 자리(OkHttp 스레드)에서 바로 ack → 실행은 메인 스레드의 {@link CommandRunner} 큐</li>
 *   <li>서버 ping에는 같은 id로 pong, 앱도 20초마다 JSON ping (NAT가 조용한 연결을 끊지 않게)</li>
 * </ul>
 * TLS는 {@link TrustedHttp}의 OkHttpClient를 그대로 쓴다(ISRG Root X1 추가 신뢰, 호스트 이름 검증 유지).
 */
public final class ControlChannel {

    private static final String TAG = "ControlChannel";
    private static final int PROTOCOL = 1;
    private static final long RECONNECT_MIN_MS = 3_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long APP_PING_MS = 20_000;
    private static final int LOG_SIZE = 20;
    private static final int CLOSE_REPLACED = 4000;

    public enum State {
        /** local.properties 설정이 없어 시작하지 않음 */
        NOT_CONFIGURED("설정 필요"),
        CONNECTING("연결 중"),
        CONNECTED("연결됨"),
        RECONNECTING("재접속 중"),
        /** 재접속하지 않는 끊김 (4000 교체, 401/403 거부) */
        DISCONNECTED("끊김");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    public interface Listener {
        default void onStateChanged(State state, String detail) {}

        /** 최근 명령 목록이 바뀜 (최신이 앞) */
        default void onCommandsChanged(List<CommandLog> recent) {}
    }

    /** 화면 표시용 명령 기록 */
    public static final class CommandLog {
        public final long receivedAt;
        public final String id;
        public final String action;
        public final String summary;
        /** "received"(큐 대기·실행 중) / completed / failed / rejected */
        public final String status;
        public final String message;

        CommandLog(long receivedAt, String id, String action, String summary, String status, String message) {
            this.receivedAt = receivedAt;
            this.id = id;
            this.action = action;
            this.summary = summary;
            this.status = status;
            this.message = message;
        }

        CommandLog withResult(String status, String message) {
            return new CommandLog(receivedAt, id, action, summary, status, message);
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RobotController robot;
    private final CommandRunner runner;
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final LinkedList<CommandLog> recent = new LinkedList<>();   // 메인 스레드에서만

    // 아래는 메인 스레드에서만 접근
    private OkHttpClient wsClient;
    private WebSocket socket;
    private int generation;          // 연결 시도마다 증가. 이전 소켓의 늦은 콜백을 무시하는 데 사용
    private boolean started;
    private long reconnectDelayMs = RECONNECT_MIN_MS;
    private State state = State.NOT_CONFIGURED;
    private String stateDetail = "";
    private String executor = "";

    private final Runnable reconnect = this::connect;
    private final Runnable appPing = new Runnable() {
        @Override
        public void run() {
            if (state != State.CONNECTED || socket == null) return;
            sendJson(socket, msg("ping"));
            main.postDelayed(this, APP_PING_MS);
        }
    };

    public ControlChannel(Context context, RobotController robot) {
        this.appContext = context.getApplicationContext();
        this.robot = robot;
        this.runner = new CommandRunner(robot, this::onRunnerResult);
    }

    /** DaycareApp.onCreate에서 한 번. 설정이 없으면 NOT_CONFIGURED로 남는다. */
    public void start() {
        if (started) return;
        started = true;
        runner.attach();
        String problem = ServerConfig.problem();
        if (problem != null) {
            setState(State.NOT_CONFIGURED, problem);
            return;
        }
        connect();
    }

    /** 끊김(DISCONNECTED) 상태에서 화면 버튼으로 다시 시도할 때 */
    public void reconnectNow() {
        if (!started || ServerConfig.problem() != null) return;
        main.removeCallbacks(reconnect);
        reconnectDelayMs = RECONNECT_MIN_MS;
        connect();
    }

    public State state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    /** 등록 직후 현재 상태와 명령 목록이 한 번 전달된다 (메인 스레드) */
    public void addListener(Listener l) {
        if (!listeners.add(l)) return;
        main.post(() -> {
            if (!listeners.contains(l)) return;
            l.onStateChanged(state, stateDetail);
            l.onCommandsChanged(snapshot());
        });
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------ 연결

    private void connect() {
        main.removeCallbacks(reconnect);
        main.removeCallbacks(appPing);
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
        OkHttpClient client;
        try {
            if (wsClient == null) {
                // 같은 OkHttpClient에서 파생: TLS 설정·연결 풀·스레드 공유. WebSocket은 읽기 타임아웃 없음 + 프로토콜 ping 20초
                wsClient = TrustedHttp.get(appContext).newBuilder()
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .pingInterval(20, TimeUnit.SECONDS)
                        .build();
            }
            client = wsClient;
        } catch (IllegalStateException e) {
            setState(State.DISCONNECTED, "TLS 설정 실패: " + e.getMessage());
            return;
        }
        int gen = ++generation;
        setState(state == State.RECONNECTING ? State.RECONNECTING : State.CONNECTING,
                state == State.RECONNECTING ? stateDetail : "");
        Request req = new Request.Builder()
                .url(ServerConfig.controlUrl())
                .header("X-API-Key", ServerConfig.API_KEY)
                .header("X-Device-Id", ServerConfig.DEVICE_ID_VALUE)
                .build();
        Log.i(TAG, "접속 시도 #" + gen);
        socket = client.newWebSocket(req, new Socket(gen));
    }

    private final class Socket extends WebSocketListener {
        private final int gen;

        Socket(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            main.post(() -> {
                if (gen != generation) return;
                Log.i(TAG, "연결됨");
                setState(State.CONNECTED, "welcome 대기");
                sendHello(ws);
                main.removeCallbacks(appPing);
                main.postDelayed(appPing, APP_PING_MS);
            });
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            JSONObject m;
            try {
                m = new JSONObject(text);
            } catch (JSONException e) {
                Log.w(TAG, "JSON 아닌 메시지 무시 (" + text.length() + "자)");
                return;
            }
            String type = m.optString("type");
            String id = m.optString("id");
            switch (type) {
                case "command":
                    // 받자마자 ack (서버는 5초 기다림) → 실행은 메인 스레드 큐에서
                    if (!id.isEmpty()) sendJson(ws, ack(id));
                    CommandRunner.Command c = parseCommand(m);
                    main.post(() -> {
                        if (gen != generation) return;
                        onCommand(c);
                    });
                    break;
                case "ping":
                    sendJson(ws, msgWithId("pong", id));
                    break;
                case "welcome":
                    main.post(() -> {
                        if (gen != generation) return;
                        executor = m.optString("executor");
                        reconnectDelayMs = RECONNECT_MIN_MS;
                        Log.i(TAG, "welcome: executor=" + executor + " protocol=" + m.optInt("protocol"));
                        setState(State.CONNECTED, "executor=" + executor
                                + ("mock".equals(executor) ? " (서버가 mock 모드: stop 외 명령은 오지 않음)" : ""));
                    });
                    break;
                case "error":
                    Log.w(TAG, "서버 error: " + m.optString("message"));
                    break;
                case "pong":
                    break;
                default:
                    Log.w(TAG, "모르는 type 무시: " + type);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            main.post(() -> {
                if (gen != generation) return;
                onDisconnected(code, "close " + code + (reason == null || reason.isEmpty() ? "" : " " + reason), false);
            });
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            int http = response == null ? 0 : response.code();
            if (response != null) response.close();
            AgentError err = http > 0 ? AgentError.fromHttp(http, "") : AgentError.fromThrowable(t);
            main.post(() -> {
                if (gen != generation) return;
                Log.w(TAG, "연결 실패: " + err);
                boolean refused = http == 401 || http == 403;
                onDisconnected(0, refused
                        ? "접속 거부 HTTP " + http + " — temi 키·장치 ID를 확인하세요(재접속 안 함)"
                        : err.userMessage(), refused);
            });
        }
    }

    private void onDisconnected(int closeCode, String why, boolean refused) {
        socket = null;
        main.removeCallbacks(appPing);
        runner.reset();   // 서버가 진행 중 작업을 disconnected로 닫음 → 결과를 보내지 않고 잊는다
        markPendingForgotten();
        if (refused) {
            setState(State.DISCONNECTED, why);
            return;
        }
        if (closeCode == CLOSE_REPLACED) {
            setState(State.DISCONNECTED, "다른 연결로 교체됨(4000) — 같은 장치 ID의 앱이 또 켜져 있는지 확인하세요");
            return;
        }
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
        setState(State.RECONNECTING, why + " → " + (delay / 1000) + "초 뒤 재접속");
        main.postDelayed(reconnect, delay);
    }

    // ------------------------------------------------------------ 명령

    private void onCommand(CommandRunner.Command c) {
        Log.i(TAG, "command: " + c.action + " id=" + c.id);   // 인자(말할 문장 등)는 로그에 남기지 않음
        addLog(new CommandLog(System.currentTimeMillis(), c.id, c.action, summarize(c), "received", ""));
        runner.submit(c);
    }

    private void onRunnerResult(CommandRunner.Command c, String status, String message, String code) {
        Log.i(TAG, "result: " + c.action + " id=" + c.id + " " + status + (code == null ? "" : " " + code));
        updateLog(c.id, status, message);
        WebSocket ws = socket;
        if (ws == null || state != State.CONNECTED) return;   // 끊긴 뒤의 결과는 서버가 받지 않음
        try {
            JSONObject detail = new JSONObject().put("message", message);
            if (code != null) detail.put("code", code);
            sendJson(ws, msgWithId("result", c.id).put("status", status).put("detail", detail));
        } catch (JSONException ignored) {
        }
    }

    private static CommandRunner.Command parseCommand(JSONObject m) {
        Map<String, String> args = new HashMap<>();
        JSONObject a = m.optJSONObject("args");
        if (a != null) {
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = a.opt(k);
                if (v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    List<String> items = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) items.add(arr.optString(i));
                    args.put(k, android.text.TextUtils.join(", ", items));
                } else if (v != null && v != JSONObject.NULL) {
                    args.put(k, String.valueOf(v));
                }
            }
        }
        return new CommandRunner.Command(m.optString("id"), m.optString("action"), args, m.optInt("timeout_s", 0));
    }

    private static String summarize(CommandRunner.Command c) {
        switch (c.action) {
            case "go_to": return "→ " + c.arg("location");
            case "speak": return "\"" + c.arg("text") + "\"";
            case "start_game": return c.arg("name");
            case "start_delivery": return c.arg("kind") + " " + c.arg("targets");
            default: return "";
        }
    }

    // ------------------------------------------------------------ 기록·상태

    private void addLog(CommandLog entry) {
        recent.addFirst(entry);
        while (recent.size() > LOG_SIZE) recent.removeLast();
        notifyCommands();
    }

    private void updateLog(String id, String status, String message) {
        for (int i = 0; i < recent.size(); i++) {
            if (recent.get(i).id.equals(id)) {
                recent.set(i, recent.get(i).withResult(status, message));
                notifyCommands();
                return;
            }
        }
    }

    /** 끊기면서 잊은 명령을 목록에 표시 */
    private void markPendingForgotten() {
        boolean changed = false;
        for (int i = 0; i < recent.size(); i++) {
            if ("received".equals(recent.get(i).status)) {
                recent.set(i, recent.get(i).withResult("forgotten", "연결이 끊겨 취소(서버가 disconnected로 기록)"));
                changed = true;
            }
        }
        if (changed) notifyCommands();
    }

    private List<CommandLog> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(recent));
    }

    private void notifyCommands() {
        List<CommandLog> copy = snapshot();
        for (Listener l : listeners) l.onCommandsChanged(copy);
    }

    private void setState(State s, String detail) {
        state = s;
        stateDetail = detail == null ? "" : detail;
        for (Listener l : listeners) l.onStateChanged(state, stateDetail);
    }

    // ------------------------------------------------------------ 메시지

    private void sendHello(WebSocket ws) {
        try {
            List<String> locations;
            try {
                locations = robot.getLocations();
            } catch (RuntimeException e) {
                locations = Collections.emptyList();
            }
            JSONObject hello = msg("hello")
                    .put("device_id", ServerConfig.DEVICE_ID_VALUE)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("protocol", PROTOCOL)
                    .put("locations", new JSONArray(locations == null ? Collections.<String>emptyList() : locations));
            sendJson(ws, hello);
        } catch (JSONException ignored) {
        }
    }

    private static JSONObject ack(String id) {
        return msgWithId("ack", id);
    }

    private static JSONObject msg(String type) {
        return msgWithId(type, UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    private static JSONObject msgWithId(String type, String id) {
        try {
            return new JSONObject().put("type", type).put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sendJson(WebSocket ws, JSONObject json) {
        if (!ws.send(json.toString())) Log.w(TAG, "보내기 실패(소켓 닫힘): " + json.optString("type"));
    }
}
