package com.damum.daycaretemi.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 에이전트 서버 HTTP API (Server/docs/temi_api.md v0.1). 결과 콜백은 메인 스레드에서 온다.
 *
 * <p><b>개인정보:</b> 아이 발화와 답변 원문은 로그에 남기지 않는다(디버그 빌드 포함). 글자 수·alert_level 같은 메타데이터만.
 */
public final class AgentApi {

    private static final String TAG = "AgentApi";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_TEXT = 300;

    /** 서버에 닿지 못했을 때 테미가 말할 문장 (문서의 '대체 답변'). 지킬 수 없는 약속을 넣지 말 것 */
    public static final String FALLBACK_REPLY = "지금은 대답하기 어려워. 선생님께 같이 물어보자.";

    public interface ResultCallback<T> {
        void onSuccess(T result);

        void onError(AgentError error);
    }

    public static final class Health {
        public final String status;
        public final String executor;
        public final List<String> temiConnected;

        Health(String status, String executor, List<String> temiConnected) {
            this.status = status;
            this.executor = executor;
            this.temiConnected = temiConnected;
        }

        public boolean isOk() {
            return "ok".equals(status);
        }
    }

    public static final class ChildReply {
        /** 그대로 TTS로 읽을 문장 */
        public final String reply;
        public final boolean alertCreated;
        /** "urgent" / "normal" / null */
        public final String alertLevel;
        public final Long alertId;
        public final String gameStarted;
        public final String fallbackReason;
        public final String model;
        public final long latencyMs;

        ChildReply(JSONObject j) throws JSONException {
            reply = j.getString("reply");
            alertCreated = j.optBoolean("alert_created", false);
            alertLevel = optStringOrNull(j, "alert_level");
            alertId = j.isNull("alert_id") || !j.has("alert_id") ? null : j.getLong("alert_id");
            gameStarted = optStringOrNull(j, "game_started");
            fallbackReason = optStringOrNull(j, "fallback_reason");
            model = optStringOrNull(j, "model");
            latencyMs = j.optLong("latency_ms", -1);
        }

        public boolean hasAlert() {
            return alertLevel != null || alertCreated;
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AgentApi(Context context) {
        appContext = context.getApplicationContext();
    }

    /** GET /health (인증 없음, 타임아웃 5초). 반환한 Call로 취소할 수 있다. 설정 문제면 null */
    public Call health(ResultCallback<Health> cb) {
        String problem = ServerConfig.urlProblem();
        if (problem != null) {
            deliverError(null, cb, AgentError.config(problem));
            return null;
        }
        OkHttpClient client = clientOrNull(cb);
        if (client == null) return null;
        client = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build();   // 같은 연결 풀·TLS 설정 공유

        Request req = new Request.Builder().url(ServerConfig.BASE_URL + "/health").get().build();
        Call call = client.newCall(req);
        Log.i(TAG, "health 요청");
        call.enqueue(new JsonCallback<>(cb, json -> {
            List<String> connected = new ArrayList<>();
            JSONArray arr = json.optJSONArray("temi_connected");
            if (arr != null) for (int i = 0; i < arr.length(); i++) connected.add(arr.optString(i));
            Health h = new Health(json.optString("status", ""), json.optString("executor", ""),
                    Collections.unmodifiableList(connected));
            Log.i(TAG, "health: status=" + h.status + " executor=" + h.executor + " connected=" + connected.size());
            return h;
        }));
        return call;
    }

    /**
     * POST /agent/child (연결 5초, 읽기 15초). 300자 넘으면 앞부분만 보낸다.
     * 새 발화가 오면 이전 Call을 {@link Call#cancel()}하고 새로 보내는 것을 권장(취소된 Call은 콜백을 부르지 않음).
     * @return 설정 문제·빈 문장이면 null (콜백으로 오류 전달)
     */
    public Call childTalk(String deviceId, String text, ResultCallback<ChildReply> cb) {
        String problem = ServerConfig.problem();
        if (problem != null) {
            deliverError(null, cb, AgentError.config(problem));
            return null;
        }
        String utterance = text == null ? "" : text.trim();
        if (utterance.isEmpty()) {
            deliverError(null, cb, new AgentError(AgentError.Kind.CLIENT, 0, "빈 문장은 보내지 않습니다."));
            return null;
        }
        utterance = truncate(utterance);
        OkHttpClient client = clientOrNull(cb);
        if (client == null) return null;

        String body;
        try {
            body = new JSONObject().put("text", utterance).put("device_id", deviceId).toString();
        } catch (JSONException e) {
            deliverError(null, cb, AgentError.badResponse("요청 JSON 생성 실패"));
            return null;
        }
        Request req = new Request.Builder()
                .url(ServerConfig.BASE_URL + "/agent/child")
                .header("X-API-Key", ServerConfig.API_KEY)
                .post(RequestBody.create(body, JSON))
                .build();
        Call call = client.newCall(req);
        Log.i(TAG, "childTalk 요청: 글자 수 " + utterance.length());   // 원문 금지
        call.enqueue(new JsonCallback<>(cb, json -> {
            ChildReply r = new ChildReply(json);
            Log.i(TAG, "childTalk 응답: 답변 글자 수 " + r.reply.length() + " alert_level=" + r.alertLevel
                    + " alert_id=" + r.alertId + " fallback=" + r.fallbackReason + " latency_ms=" + r.latencyMs);
            return r;
        }));
        return call;
    }

    // ------------------------------------------------------------ 내부

    private interface Parser<T> {
        T parse(JSONObject json) throws JSONException;
    }

    private final class JsonCallback<T> implements Callback {
        private final ResultCallback<T> cb;
        private final Parser<T> parser;

        JsonCallback(ResultCallback<T> cb, Parser<T> parser) {
            this.cb = cb;
            this.parser = parser;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            if (call.isCanceled()) return;
            AgentError err = AgentError.fromThrowable(e);
            Log.w(TAG, "요청 실패: " + err);
            deliverError(call, cb, err);
        }

        @Override
        public void onResponse(Call call, Response response) {
            try (ResponseBody rb = response.body()) {
                String text = rb == null ? "" : rb.string();
                if (!response.isSuccessful()) {
                    // 오류 본문(detail)에는 아이 발화가 없음. 그래도 길이를 제한한다
                    AgentError err = AgentError.fromHttp(response.code(), head(text));
                    Log.w(TAG, "HTTP 오류: " + err);
                    deliverError(call, cb, err);
                    return;
                }
                T result = parser.parse(new JSONObject(text));
                main.post(() -> {
                    if (!call.isCanceled()) cb.onSuccess(result);
                });
            } catch (JSONException e) {
                Log.w(TAG, "응답 JSON 오류: " + e.getMessage());
                deliverError(call, cb, AgentError.badResponse(e.getMessage()));
            } catch (IOException e) {
                if (call.isCanceled()) return;
                deliverError(call, cb, AgentError.fromThrowable(e));
            }
        }
    }

    private <T> OkHttpClient clientOrNull(ResultCallback<T> cb) {
        try {
            return TrustedHttp.get(appContext);
        } catch (IllegalStateException e) {
            deliverError(null, cb, AgentError.fromThrowable(e));
            return null;
        }
    }

    private <T> void deliverError(Call call, ResultCallback<T> cb, AgentError err) {
        main.post(() -> {
            if (call == null || !call.isCanceled()) cb.onError(err);
        });
    }

    private static String truncate(String s) {
        if (s.codePointCount(0, s.length()) <= MAX_TEXT) return s;
        return s.substring(0, s.offsetByCodePoints(0, MAX_TEXT));
    }

    private static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    static String optStringOrNull(JSONObject j, String key) {
        return j.has(key) && !j.isNull(key) ? j.optString(key) : null;
    }
}
