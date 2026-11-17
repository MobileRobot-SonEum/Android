package com.damum.daycaretemi.server;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.DaycareApp;
import com.damum.daycaretemi.R;
import com.damum.daycaretemi.robot.RobotController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;

/**
 * 에이전트 서버 연결 테스트 화면 (MainActivity 메뉴에서 진입).
 * ① GET /health ② 아이 모드 대화 POST /agent/child → 화면 표시 + speak ③ 명령 채널 상태·최근 명령 20개.
 * 아이 발화는 화면에만 보이고 로그에는 남기지 않는다.
 */
public class ConnectionTestActivity extends AppCompatActivity {

    private static final int COLOR_OK = Color.parseColor("#1B5E20");
    private static final int COLOR_ERROR = Color.parseColor("#C62828");
    private static final int COLOR_WARN = Color.parseColor("#E65100");
    private static final int COLOR_NORMAL = Color.parseColor("#18384A");

    private RobotController robot;
    private ControlChannel channel;
    private AgentApi api;
    private Call pendingChild;
    private Call pendingHealth;

    private TextView healthResult, childReply, childMeta, channelState, commandList;
    private EditText childInput;
    private Button reconnectButton;

    private final ControlChannel.Listener channelListener = new ControlChannel.Listener() {
        @Override
        public void onStateChanged(ControlChannel.State state, String detail) {
            channelState.setText("● " + state.label + (TextUtils.isEmpty(detail) ? "" : "\n" + detail));
            switch (state) {
                case CONNECTED: channelState.setTextColor(COLOR_OK); break;
                case CONNECTING: case RECONNECTING: channelState.setTextColor(COLOR_WARN); break;
                default: channelState.setTextColor(COLOR_ERROR);
            }
            reconnectButton.setVisibility(state == ControlChannel.State.DISCONNECTED ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onCommandsChanged(List<ControlChannel.CommandLog> recent) {
            if (recent.isEmpty()) {
                commandList.setText("받은 명령이 없습니다.");
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
            StringBuilder sb = new StringBuilder();
            for (ControlChannel.CommandLog e : recent) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(fmt.format(new Date(e.receivedAt))).append("  ")
                        .append(statusMark(e.status)).append(' ').append(e.action);
                if (!e.summary.isEmpty()) sb.append(' ').append(e.summary);
                if (!e.message.isEmpty()) sb.append("  — ").append(e.message);
            }
            commandList.setText(sb);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection_test);
        robot = DaycareApp.robot(this);
        channel = DaycareApp.channel(this);
        api = new AgentApi(this);

        TextView config = findViewById(R.id.config_text);
        String problem = ServerConfig.problem();
        config.setText(ServerConfig.describe() + (problem == null ? "" : "\n⚠ " + problem));
        if (problem != null) config.setTextColor(COLOR_ERROR);

        healthResult = findViewById(R.id.health_result);
        childInput = findViewById(R.id.child_input);
        childReply = findViewById(R.id.child_reply);
        childMeta = findViewById(R.id.child_meta);
        channelState = findViewById(R.id.channel_state);
        commandList = findViewById(R.id.command_list);
        reconnectButton = findViewById(R.id.btn_reconnect);

        findViewById(R.id.btn_health).setOnClickListener(v -> checkHealth());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendChild());
        childInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChild();
                return true;
            }
            return false;
        });
        reconnectButton.setOnClickListener(v -> channel.reconnectNow());
    }

    @Override
    protected void onStart() {
        super.onStart();
        channel.addListener(channelListener);
    }

    @Override
    protected void onStop() {
        channel.removeListener(channelListener);
        if (pendingChild != null) pendingChild.cancel();
        if (pendingHealth != null) pendingHealth.cancel();
        super.onStop();
    }

    private void checkHealth() {
        if (pendingHealth != null) pendingHealth.cancel();
        healthResult.setTextColor(COLOR_NORMAL);
        healthResult.setText("확인 중…");
        pendingHealth = api.health(new AgentApi.ResultCallback<AgentApi.Health>() {
            @Override
            public void onSuccess(AgentApi.Health h) {
                healthResult.setTextColor(h.isOk() ? COLOR_OK : COLOR_WARN);
                healthResult.setText("status=" + h.status + "  executor=" + h.executor
                        + "\n명령 채널에 연결된 temi: " + (h.temiConnected.isEmpty() ? "없음" : h.temiConnected));
            }

            @Override
            public void onError(AgentError e) {
                showError(healthResult, e);
            }
        });
    }

    private void sendChild() {
        String text = childInput.getText().toString().trim();
        if (text.isEmpty()) return;
        // 이전 요청이 아직이면 그 응답은 버리고 새 발화만 처리 (문서 권장)
        if (pendingChild != null) pendingChild.cancel();
        childInput.setText("");
        childReply.setTextColor(COLOR_NORMAL);
        childReply.setText("생각하는 중…");
        childMeta.setText("");
        pendingChild = api.childTalk(ServerConfig.DEVICE_ID_VALUE, text, new AgentApi.ResultCallback<AgentApi.ChildReply>() {
            @Override
            public void onSuccess(AgentApi.ChildReply r) {
                childReply.setText(r.reply);
                childReply.setTextColor(r.hasAlert() ? COLOR_ERROR : COLOR_NORMAL);
                StringBuilder meta = new StringBuilder();
                if (r.hasAlert()) {
                    meta.append("⚠ 교사 알림 ").append(r.alertLevel == null ? "" : r.alertLevel)
                            .append(r.alertId == null ? "" : " #" + r.alertId).append(" (전송은 서버가 함)  ");
                }
                if (r.gameStarted != null) meta.append("게임 명령: ").append(r.gameStarted).append("  ");
                if (r.fallbackReason != null) meta.append("fallback: ").append(r.fallbackReason).append("  ");
                meta.append(r.model == null ? "" : r.model + "  ").append(r.latencyMs).append("ms");
                childMeta.setText(meta);
                childMeta.setTextColor(r.hasAlert() ? COLOR_ERROR : COLOR_NORMAL);
                robot.speak(r.reply);
            }

            @Override
            public void onError(AgentError e) {
                showError(childMeta, e);
                if (e.shouldSpeakFallback()) {
                    childReply.setText(AgentApi.FALLBACK_REPLY + "  (대체 답변)");
                    robot.speak(AgentApi.FALLBACK_REPLY);
                } else {
                    childReply.setText("(말하지 않음 — 설정을 확인하세요)");
                }
            }
        });
    }

    private void showError(TextView view, AgentError e) {
        view.setTextColor(COLOR_ERROR);
        view.setText("✕ " + e.userMessage() + (e.detail.isEmpty() ? "" : "\n" + e.detail));
    }

    private static String statusMark(String status) {
        switch (status) {
            case CommandRunner.COMPLETED: return "✓";
            case CommandRunner.FAILED: return "✕";
            case CommandRunner.REJECTED: return "⊘";
            case "forgotten": return "–";
            default: return "…";
        }
    }
}
