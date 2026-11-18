package com.damum.daycaretemi.play;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.damum.daycaretemi.DaycareApp;
import com.damum.daycaretemi.R;
import com.damum.daycaretemi.robot.RobotController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline picture-and-story game. No network, AI, personal data or teacher report.
 * The robot is used only for optional speech; gameplay also works without it.
 */
public final class MemoryGameActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> selectedStoryCards = new ArrayList<>();
    private MemoryGame game;
    private RobotController robot;
    private TextView title, connection, progress, instructions, countdown, feedback;
    private LinearLayout cards, options;
    private ScrollView scroll;
    private Button start, hint, example, next, quit;
    private long previewEnd;
    private boolean started;
    private boolean voicePending;
    private String voiceText;
    private Runnable voiceDone;
    private Runnable voiceTimeout;

    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            if (!started || game.phase() != MemoryGame.Phase.MEMORIZE) return;
            long remaining = Math.max(0L, previewEnd - SystemClock.elapsedRealtime());
            countdown.setText("기억할 시간 " + ((remaining + 999L) / 1000L) + "초");
            if (remaining == 0L) {
                game.revealAnswerStage(SystemClock.elapsedRealtime());
                showQuestion();
            } else handler.postDelayed(this, Math.min(200L, remaining));
        }
    };
    private final Runnable gentleReminder = () -> {
        if (!started || game.phase() != MemoryGame.Phase.ANSWER || voicePending) return;
        if (game.hintsInRound() > 0 || game.examplesInRound() > 0) return;
        if (!game.markWaitingSupport()) return;
        String message = PlaySupportCoach.waitingMessage();
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
        setAnswerEnabled(false);
        playSpeech(message, () -> setAnswerEnabled(true));
    };
    private final RobotController.Listener robotListener = new RobotController.Listener() {
        @Override public void onReadyChanged(boolean ready) {
            handler.post(() -> {
                if (connection != null) connection.setText(ready
                        ? "temi 음성 안내 사용 중" : "화면만으로도 게임할 수 있어요");
                // If the robot disconnects mid-speech, don't stall the game.
                if (!ready) completeSpeech();
            });
        }
        @Override public void onSpeechStatus(String spoken, RobotController.SpeechStatus status) {
            handler.post(() -> {
                if (voicePending && voiceText != null && voiceText.equals(spoken)
                        && (status == RobotController.SpeechStatus.COMPLETED
                        || status == RobotController.SpeechStatus.ERROR
                        || status == RobotController.SpeechStatus.CANCELED)) completeSpeech();
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory_game);
        robot = DaycareApp.robot(this);
        game = new MemoryGame(new OfflineMemoryPlanner());
        title = findViewById(R.id.memory_title);
        connection = findViewById(R.id.memory_connection);
        progress = findViewById(R.id.memory_progress);
        instructions = findViewById(R.id.memory_instructions);
        countdown = findViewById(R.id.memory_countdown);
        feedback = findViewById(R.id.memory_feedback);
        cards = findViewById(R.id.memory_cards);
        options = findViewById(R.id.memory_options);
        scroll = findViewById(R.id.memory_scroll);
        start = findViewById(R.id.memory_start);
        hint = findViewById(R.id.memory_hint);
        example = findViewById(R.id.memory_example);
        next = findViewById(R.id.memory_next);
        quit = findViewById(R.id.memory_quit);
        start.setOnClickListener(v -> startSession());
        hint.setOnClickListener(v -> showHint());
        example.setOnClickListener(v -> showExample());
        next.setOnClickListener(v -> {
            if (game.phase() == MemoryGame.Phase.ANSWER
                    && game.question().mission == MemoryGame.Mission.STORY) resetStory();
            else advance();
        });
        quit.setOnClickListener(v -> finish());
        showReady(false);
    }

    @Override protected void onStart() {
        super.onStart();
        robot.addListener(robotListener);
        connection.setText(robot.isReady() ? "temi 음성 안내 사용 중" : "화면만으로도 게임할 수 있어요");
    }

    @Override protected void onStop() {
        // The app can be backgrounded while a preview is running. Drop stale timers.
        clearAllTimers();
        stopSpeechIfSupported();
        robot.removeListener(robotListener);
        if (started && game.phase() != MemoryGame.Phase.FINISHED) {
            started = false;
            game = new MemoryGame(new OfflineMemoryPlanner());
            showReady(true);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    private void clearAllTimers() {
        handler.removeCallbacks(previewTick);
        handler.removeCallbacks(gentleReminder);
        if (voiceTimeout != null) handler.removeCallbacks(voiceTimeout);
        voicePending = false;
        voiceDone = null;
        voiceText = null;
        voiceTimeout = null;
    }

    private void startSession() {
        clearAllTimers();
        game = new MemoryGame(new OfflineMemoryPlanner());
        started = true;
        game.start(SystemClock.elapsedRealtime());
        // This game never drives or rotates temi. Stop motion from other modules.
        if (robot.isReady()) {
            try { robot.stopMovement(); } catch (RuntimeException ignored) { }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPreview();
    }

    private void showReady(boolean interrupted) {
        title.setText("기억·이야기 놀이");
        progress.setText("5문제 · 4가지 재미있는 미션");
        instructions.setText(interrupted
                ? "게임을 잠시 벗어나 처음으로 돌아왔어요. 다시 시작해요!"
                : "그림과 이야기를 살펴보고 맞혀 볼까요?");
        countdown.setText("준비됐나요?");
        feedback.setVisibility(View.GONE);
        cards.removeAllViews();
        options.removeAllViews();
        start.setText("시작하기");
        start.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        example.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void showPreview() {
        final MemoryGame.Question question = game.question();
        selectedStoryCards.clear();
        handler.removeCallbacks(gentleReminder);
        title.setText(question.mission.title);
        progress.setText(String.format(Locale.KOREA, "%d / %d 문제", game.currentRound(), MemoryGame.TOTAL_ROUNDS));
        String intro;
        switch (question.mission) {
            case POSITION: intro = "물건이 어느 칸에 있는지 기억해 봐요."; break;
            case MISSING: intro = "그림들을 잘 보고, 사라질 그림을 찾아봐요."; break;
            case STORY: intro = "그림 이야기의 순서를 잘 살펴봐요."; break;
            default: intro = "그림을 왼쪽부터 차례로 기억해 봐요."; break;
        }
        instructions.setText(intro);
        countdown.setText("그림을 살펴봐요");
        feedback.setVisibility(View.GONE);
        start.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        example.setVisibility(View.GONE);
        options.removeAllViews();
        if (question.mission == MemoryGame.Mission.POSITION) drawPositionGrid(question, false);
        else drawCards(question.sequence, -1);
        scroll.post(() -> scroll.scrollTo(0, 0));
        String speech = PlaySupportCoach.introduction(
                PlaySupportCoach.localStyle(game.results()), game.currentRound()) + " " + intro;
        playSpeech(speech, () -> {
            if (game.phase() != MemoryGame.Phase.MEMORIZE) return;
            long seconds = question.mission == MemoryGame.Mission.STORY
                    ? 12L : 9L + (question.sequence.size() - 2L) * 2L;
            previewEnd = SystemClock.elapsedRealtime() + seconds * 1000L;
            handler.post(previewTick);
        });
    }

    private void showQuestion() {
        final MemoryGame.Question question = game.question();
        instructions.setText(question.prompt());
        countdown.setText(question.mission == MemoryGame.Mission.STORY
                ? "카드를 첫 번째부터 차례로 눌러요" : "정답을 하나 눌러요");
        cards.removeAllViews();
        options.removeAllViews();
        selectedStoryCards.clear();
        if (question.mission == MemoryGame.Mission.POSITION) drawPositionGrid(question, true);
        else if (question.mission == MemoryGame.Mission.MISSING) drawCards(question.sequence, question.askedIndex);
        else if (question.mission == MemoryGame.Mission.STORY) drawStoryProgress();
        for (MemoryGame.Item option : question.options) {
            Button button = new Button(this);
            button.setText(option.label);
            applyPicture(button, option, 48);
            button.setTextSize(17);
            button.setAllCaps(false);
            button.setContentDescription(option.label);
            button.setTag(option.id);
            button.setEnabled(false); // No accidental answer while prompt is being spoken.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            options.addView(button, lp);
            button.setOnClickListener(v -> {
                if (question.mission == MemoryGame.Mission.STORY) chooseStoryCard(option);
                else choose(option.id);
            });
        }
        hint.setVisibility(View.VISIBLE);
        example.setVisibility(View.VISIBLE);
        hint.setEnabled(false);
        example.setEnabled(false);
        next.setVisibility(question.mission == MemoryGame.Mission.STORY ? View.VISIBLE : View.GONE);
        next.setText("다시 고르기");
        next.setEnabled(false);
        feedback.setVisibility(View.GONE);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, options.getTop() - dp(180))));
        playSpeech(question.prompt(), () -> {
            if (game.phase() == MemoryGame.Phase.ANSWER) {
                setAnswerEnabled(true);
                scheduleReminder();
            }
        });
    }

    private void setAnswerEnabled(boolean enabled) {
        for (int i = 0; i < options.getChildCount(); i++) {
            View view = options.getChildAt(i);
            view.setEnabled(enabled && !selectedStoryCards.contains(view.getTag()));
        }
        hint.setEnabled(enabled && game.phase() == MemoryGame.Phase.ANSWER && game.hintsInRound() == 0);
        example.setEnabled(enabled && game.phase() == MemoryGame.Phase.ANSWER && game.examplesInRound() == 0);
        if (game.phase() == MemoryGame.Phase.ANSWER && game.question().mission == MemoryGame.Mission.STORY)
            next.setEnabled(enabled);
    }

    private void scheduleReminder() {
        handler.removeCallbacks(gentleReminder);
        handler.postDelayed(gentleReminder, 30_000L);
    }

    private void showHint() {
        if (!started || game.phase() != MemoryGame.Phase.ANSWER || voicePending) return;
        String message = game.requestHint();
        if (message == null) return;
        handler.removeCallbacks(gentleReminder);
        if (game.question().mission == MemoryGame.Mission.STORY) {
            resetStory();
            chooseStoryCard(game.question().sequence.get(0));
        } else {
            // Visually eliminate one wrong answer. If only two remain, this reveals the answer.
            for (int i = 0; i < options.getChildCount(); i++) {
                View view = options.getChildAt(i);
                if (!game.question().correctId.equals(view.getTag())) {
                    view.setVisibility(View.GONE);
                    break;
                }
            }
        }
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
        setAnswerEnabled(false);
        playSpeech(PlaySupportCoach.hintLead(game.question().mission) + " " + message,
                () -> setAnswerEnabled(true));
    }

    private void showExample() {
        if (!started || game.phase() != MemoryGame.Phase.ANSWER || voicePending) return;
        String message = game.requestExample();
        if (message == null) return;
        handler.removeCallbacks(gentleReminder);
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
        setAnswerEnabled(false);
        playSpeech(message, () -> setAnswerEnabled(true));
    }

    private void chooseStoryCard(MemoryGame.Item item) {
        if (!started || game.phase() != MemoryGame.Phase.ANSWER || selectedStoryCards.contains(item.id)) return;
        selectedStoryCards.add(item.id);
        for (int i = 0; i < options.getChildCount(); i++) {
            View view = options.getChildAt(i);
            if (item.id.equals(view.getTag())) {
                view.setEnabled(false);
                view.setAlpha(0.35f);
                break;
            }
        }
        drawStoryProgress();
        if (selectedStoryCards.size() == game.question().sequence.size()) {
            StringBuilder answer = new StringBuilder();
            for (String id : selectedStoryCards) {
                if (answer.length() > 0) answer.append('|');
                answer.append(id);
            }
            choose(answer.toString());
        }
    }

    private void resetStory() {
        if (game.phase() != MemoryGame.Phase.ANSWER || voicePending) return;
        selectedStoryCards.clear();
        for (int i = 0; i < options.getChildCount(); i++) {
            View view = options.getChildAt(i);
            view.setAlpha(1f);
            view.setEnabled(true);
        }
        drawStoryProgress();
    }

    private void drawStoryProgress() {
        cards.removeAllViews();
        cards.setOrientation(LinearLayout.HORIZONTAL);
        TextView selected = new TextView(this);
        StringBuilder text = new StringBuilder("선택한 순서: ");
        if (selectedStoryCards.isEmpty()) text.append("아직 없어요");
        for (int i = 0; i < selectedStoryCards.size(); i++) {
            if (i > 0) text.append(" → ");
            for (MemoryGame.Item item : game.question().sequence)
                if (item.id.equals(selectedStoryCards.get(i))) text.append(item.label);
        }
        selected.setText(text.toString());
        selected.setTextColor(0xFF153C54);
        selected.setTextSize(18);
        selected.setGravity(Gravity.CENTER);
        cards.addView(selected, new LinearLayout.LayoutParams(-1, dp(62)));
    }

    private void choose(String answerId) {
        if (!started || game.phase() != MemoryGame.Phase.ANSWER || voicePending) return;
        handler.removeCallbacks(gentleReminder);
        MemoryGame.RoundResult result = game.choose(answerId, SystemClock.elapsedRealtime());
        setAnswerEnabled(false);
        options.removeAllViews();
        hint.setVisibility(View.GONE);
        example.setVisibility(View.GONE);
        next.setVisibility(View.VISIBLE);
        next.setText(game.results().size() == MemoryGame.TOTAL_ROUNDS ? "결과 보기" : "다음 문제");
        next.setEnabled(false);
        countdown.setText("이번 문제는 끝났어요");
        instructions.setText("정답 " + game.correctCount() + " / " + game.results().size() + "개");
        String speech = PlaySupportCoach.feedback(result,
                game.results().subList(0, game.results().size() - 1));
        feedback.setText(speech);
        feedback.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, feedback.getTop() - dp(185))));
        playSpeech(speech, () -> {
            if (game.phase() == MemoryGame.Phase.FEEDBACK) next.setEnabled(true);
        });
    }

    private void advance() {
        if (!started || voicePending || game.phase() != MemoryGame.Phase.FEEDBACK || !next.isEnabled()) return;
        game.advance(SystemClock.elapsedRealtime());
        if (game.phase() == MemoryGame.Phase.FINISHED) showFinished();
        else showPreview();
    }

    private void showFinished() {
        started = false;
        handler.removeCallbacks(gentleReminder);
        title.setText("게임 끝! 수고했어!");
        progress.setText("5문제 모두 끝났어요");
        instructions.setText("정답 " + game.correctCount() + " / " + MemoryGame.TOTAL_ROUNDS
                + " · 힌트 " + game.totalHints() + "회");
        countdown.setText("다시 하고 싶다면 아래 버튼을 눌러요");
        cards.removeAllViews();
        options.removeAllViews();
        feedback.setText("끝까지 참여해 줘서 고마워! 또 놀자!");
        feedback.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        example.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        start.setVisibility(View.VISIBLE);
        start.setText("다시 하기");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scroll.post(() -> scroll.scrollTo(0, 0));
        playSpeech("끝까지 참여해 줘서 고마워! 다음에도 함께 놀자!", null);
    }

    /** Only one short phrase at a time. A lost SDK callback is bounded by a timeout. */
    private void playSpeech(String message, Runnable whenDone) {
        if (voiceTimeout != null) handler.removeCallbacks(voiceTimeout);
        voiceTimeout = null;
        voiceText = null;
        voiceDone = null;
        voicePending = false;
        if (robot == null || !robot.isReady()) {
            if (whenDone != null) whenDone.run();
            return;
        }
        voicePending = true;
        voiceText = message;
        voiceDone = whenDone;
        // Most phrases are short; don't hang forever if SDK doesn't emit completed.
        final long timeout = Math.min(19_000L, 5_000L + (long) message.length() * 230L);
        Runnable fallback = () -> {
            stopSpeechIfSupported();
            completeSpeech();
        };
        voiceTimeout = fallback;
        handler.postDelayed(fallback, timeout);
        try { robot.speak(message); }
        catch (RuntimeException ignored) { completeSpeech(); }
    }

    private void completeSpeech() {
        if (!voicePending) return;
        if (voiceTimeout != null) handler.removeCallbacks(voiceTimeout);
        voiceTimeout = null;
        voicePending = false;
        voiceText = null;
        Runnable after = voiceDone;
        voiceDone = null;
        if (after != null) after.run();
    }

    /** 말하는 중·대기 중인 TTS 취소 (RobotController.cancelSpeech) */
    private void stopSpeechIfSupported() {
        if (robot == null) return;
        try { robot.cancelSpeech(); }
        catch (RuntimeException ignored) { }
    }

    private void drawCards(List<MemoryGame.Item> items, int missingIndex) {
        cards.removeAllViews();
        cards.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < items.size(); i++) {
            TextView card = new TextView(this);
            card.setGravity(Gravity.CENTER);
            card.setTextColor(0xFF193B52);
            card.setTextSize(18);
            if (i == missingIndex) card.setText("?\n사라짐");
            else {
                card.setText(items.get(i).label);
                applyPicture(card, items.get(i), 66);
            }
            card.setBackgroundColor(i % 2 == 0 ? 0xFFE1F4FD : 0xFFFFF1D8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            cards.addView(card, lp);
        }
    }

    private void drawPositionGrid(MemoryGame.Question question, boolean hidden) {
        cards.removeAllViews();
        cards.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                int cell = rowIndex * 2 + col;
                MemoryGame.Item item = null;
                if (!hidden) for (int i = 0; i < question.positionCells.size(); i++) {
                    if (question.positionCells.get(i) == cell) { item = question.sequence.get(i); break; }
                }
                TextView block = new TextView(this);
                block.setGravity(Gravity.CENTER);
                block.setTextColor(0xFF193B52);
                block.setTextSize(18);
                block.setText(item == null ? (hidden ? "?" : "빈칸") : item.label);
                if (item != null) applyPicture(block, item, 42);
                block.setBackgroundColor((rowIndex + col) % 2 == 0 ? 0xFFE1F4FD : 0xFFFFF1D8);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(85), 1);
                lp.setMargins(dp(4), dp(3), dp(4), dp(3));
                row.addView(block, lp);
            }
            cards.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void applyPicture(TextView view, MemoryGame.Item item, int size) {
        int id;
        switch (item.id) {
            case "dog": id = R.drawable.pic_dog; break;
            case "cat": id = R.drawable.pic_cat; break;
            case "rabbit": id = R.drawable.pic_rabbit; break;
            case "bear": id = R.drawable.pic_bear; break;
            case "apple": id = R.drawable.pic_apple; break;
            case "banana": id = R.drawable.pic_banana; break;
            case "grape": id = R.drawable.pic_grape; break;
            case "strawberry": id = R.drawable.pic_strawberry; break;
            case "seed": id = R.drawable.pic_seed; break;
            case "water": id = R.drawable.pic_water; break;
            case "bloom": id = R.drawable.pic_bloom; break;
            case "washwater": id = R.drawable.pic_washwater; break;
            case "soap": id = R.drawable.pic_soap; break;
            case "rinse": id = R.drawable.pic_rinse; break;
            case "ingredients": id = R.drawable.pic_ingredients; break;
            case "mix": id = R.drawable.pic_mix; break;
            case "salad": id = R.drawable.pic_salad; break;
            default: return;
        }
        Drawable drawable = ContextCompat.getDrawable(this, id);
        if (drawable == null) return;
        drawable.setBounds(0, 0, dp(size), dp(size));
        view.setCompoundDrawables(null, drawable, null, null);
        view.setCompoundDrawablePadding(dp(3));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
