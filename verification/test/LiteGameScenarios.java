import com.damum.daycaretemi.play.MemoryGame;
import com.damum.daycaretemi.play.OfflineMemoryPlanner;
import com.damum.daycaretemi.play.PlaySupportCoach;
import java.util.*;

public class LiteGameScenarios {
    private static void ok(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        for (int seed = 0; seed < 100; seed++) {
            MemoryGame game = new MemoryGame(new OfflineMemoryPlanner(new Random(seed)));
            game.start(100);
            EnumSet<MemoryGame.Mission> missions = EnumSet.noneOf(MemoryGame.Mission.class);
            for (int n = 0; n < 5; n++) {
                MemoryGame.Question q = game.question();
                ok(game.phase() == MemoryGame.Phase.MEMORIZE, "memorize phase");
                missions.add(q.mission);
                ok(q.sequence.size() >= 2 && q.sequence.size() <= 4, "picture bound");
                if (q.mission == MemoryGame.Mission.STORY) {
                    ok(q.sequence.size() <= 3, "story card bound");
                    ok(!MemoryGame.Question.joinIds(q.options).equals(q.correctId),
                            "scrambled story must not begin solved");
                }
                game.revealAnswerStage(1000 + 100*n);
                ok(game.phase() == MemoryGame.Phase.ANSWER, "answer phase");
                ok(game.requestHint() != null, "first hint");
                ok(game.requestHint() == null, "only one hint");
                ok(game.requestExample() != null, "worked example");
                ok(game.requestExample() == null, "only one example");
                String selected = q.correctId;
                if (n == 2) {
                    for (MemoryGame.Item option : q.options)
                        if (!option.id.equals(q.correctId)) { selected = option.id; break; }
                }
                MemoryGame.RoundResult result = game.choose(selected, 1400 + 100*n);
                ok(result.correct == (n != 2), "scored answer");
                ok(PlaySupportCoach.feedback(result, game.results().subList(0, n)).length() > 2,
                        "feedback text");
                game.advance(1600 + 100*n);
            }
            ok(missions.size() == 4, "four unique tasks in a five-round session");
            ok(game.phase() == MemoryGame.Phase.FINISHED, "five rounds finish");
            ok(game.totalHints() == 5 && game.totalExamples() == 5, "help totals");
            ok(game.correctCount() == 4, "score four correct");
            game.start(10000);
            ok(game.results().isEmpty() && game.currentRound() == 1, "restart clean");
        }
        System.out.println("PASS: 100 seeds × 5 rounds, four missions, story shuffle, hints, examples, scoring, restart");
    }
}
