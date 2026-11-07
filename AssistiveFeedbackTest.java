import org.example.sparkfloor.*;
import java.util.Arrays;

public class AssistiveFeedbackTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int w = 80, h = 100;
        int[] labels = new int[w * h];
        Arrays.fill(labels, 3);
        for (int y = 40; y < 70; y++) for (int x = 35; x < 45; x++) labels[y * w + x] = 19;
        SceneDescription.Result scene =
                SceneDescription.describe(
                        labels, w, h, FloorPath.unavailable(), null, "Нужна высота.");
        String brief =
                AssistiveFeedback.brief(scene, FloorPath.unavailable(), null, "Нужна высота.");
        check(brief.startsWith("Возможные предметы"), "Objects should precede metric refusal");
        check(brief.contains("перед камерой"), "Direction reference missing");
        check(
                brief.contains("Нужна высота") && !brief.contains("около"),
                "Invented metres without geometry");
        check(
                brief.length() < scene.summary.length(),
                "Brief response did not shorten scene description");
        Arrays.fill(labels, 3);
        for (int y = 40; y < 70; y++) for (int x = 35; x < 45; x++) labels[y * w + x] = 0;
        for (int y = 45; y < 65; y++) for (int x = 5; x < 15; x++) labels[y * w + x] = 19;
        SceneDescription.Result mixed =
                SceneDescription.describe(
                        labels, w, h, FloorPath.unavailable(), null, "Нужна высота.");
        String gapFirst =
                AssistiveFeedback.brief(mixed, FloorPath.unavailable(), null, "Нужна высота.");
        check(
                gapFirst.startsWith("По центру виден разрыв") && gapFirst.contains("стул"),
                "Central unknown gap lost behind side object");
        ResultSnapshot snapshot = new ResultSnapshot(brief, scene.summary, 1000, false);
        check(!snapshot.age(2000).contains("могла измениться"), "Fresh result marked stale");
        check(
                snapshot.age(16000).contains("могла измениться"),
                "Delayed processing not marked stale");
        check(snapshot.message(false, 20000).contains(brief), "Brief result missing");
        check(snapshot.message(true, 20000).contains(scene.summary), "Details missing");
        check(snapshot.capturedAt == 1000, "Repeat reset capture time");
        check(snapshot.age(500).contains("неизвестен"), "Clock rollback treated as fresh");
        check(
                new ResultSnapshot("a", "b", -1, true).age(999999).contains("Встроенный пример"),
                "Demo treated as current scene");
        int[] black = new int[128 * 128];
        String rejected =
                AssistiveFeedback.brief(null, null, ImageQuality.evaluate(black, 128, 128), null);
        check(
                rejected.contains("Оценка сцены не выполнена"),
                "Unusable image produced scene estimate");
        System.out.println(
                "PASS short/detail feedback, direction reference, no-height refusal, capture age, stale processing, repeat and black-frame refusal");
    }
}
