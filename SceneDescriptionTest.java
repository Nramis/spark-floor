import org.example.sparkfloor.*;
import java.util.Arrays;

public class SceneDescriptionTest {
    static final int W = 120, H = 160;

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static GroundProjection camera() {
        double pitch = Math.toRadians(35);
        return new GroundProjection(
                480, 640, 440, 440, 1.4, new double[] {0, Math.cos(pitch), Math.sin(pitch)}, 1);
    }

    static int[] floor() {
        int[] labels = new int[W * H];
        Arrays.fill(labels, 3);
        return labels;
    }

    static void rectangle(int[] labels, int label, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) labels[y * W + x] = label;
    }

    static SceneDescription.Result describe(int[] labels, GroundProjection camera) {
        FloorPath.Result path = FloorPath.analyze(Segmentation.floorMask(labels), W, H, camera);
        return SceneDescription.describe(labels, W, H, path, camera, "Высота камеры не задана.");
    }

    public static void main(String[] args) {
        GroundProjection camera = camera();
        SceneDescription.Result open = describe(floor(), camera);
        check(
                open.summary.contains("распознан предполагаемый пол"),
                "No floor commentary without trapezoid");
        check(
                Double.isFinite(open.centralLength) && open.centralLength > 3,
                "Open floor has no fallback length");
        check(
                open.summary.contains("по центру")
                        && open.summary.contains("не означает отсутствие препятствий"),
                "Misleading open-floor commentary");

        SceneDescription.Result noGeometry = describe(floor(), null);
        check(
                noGeometry.summary.contains("Высота камеры не задана"),
                "Missing actionable metric refusal");
        check(!Double.isFinite(noGeometry.centralLength), "Invented metres without geometry");

        int[] labels = floor();
        rectangle(labels, 19, 10, 65, 30, 110);
        SceneDescription.Result chair = describe(labels, camera);
        check(
                chair.objects.size() == 1 && chair.objects.get(0).position.equals("слева"),
                "Missing left chair");
        check(chair.summary.contains("стул или кресло"), "Missing semantic object name");
        check(
                Double.isFinite(chair.objects.get(0).distance),
                "Visible grounded contact not measured");

        rectangle(labels, 19, 12, 115, 32, H);
        SceneDescription.Result clipped = describe(labels, camera);
        check(
                clipped.objects.stream().filter(o -> o.label == 19).count() == 1,
                "Duplicate semantic position");
        int[] clippedOnly = floor();
        rectangle(clippedOnly, 19, 10, 65, 30, H);
        check(
                !Double.isFinite(describe(clippedOnly, camera).objects.get(0).distance),
                "Invented base for clipped object");

        int[] elevated = floor();
        rectangle(elevated, 19, 10, 5, 30, 20);
        check(
                describe(elevated, camera).objects.isEmpty(),
                "Upper image decoration called floor obstacle");

        int[] isolated = new int[W * H];
        rectangle(isolated, 3, 0, 120, W, H);
        rectangle(isolated, 19, 10, 65, 30, 100);
        check(
                describe(isolated, camera).objects.isEmpty(),
                "Object unrelated to observed floor reported");

        int[] gap = floor();
        rectangle(gap, 0, 45, 85, 75, 110);
        SceneDescription.Result interrupted = describe(gap, camera);
        check(interrupted.summary.contains("есть разрыв"), "Unknown gap omitted");
        check(
                interrupted.centralLength < open.centralLength,
                "Fallback bridged floor interruption");
        check(interrupted.objects.isEmpty(), "Mask gap hallucinated as named object");

        int[] empty = new int[W * H];
        SceneDescription.Result noFloor = describe(empty, camera);
        check(noFloor.summary.contains("Пол в кадре не определён"), "No-floor explanation absent");
        check(!Double.isFinite(noFloor.centralLength), "Length invented without floor");

        float[][][] logits = new float[150][2][2];
        logits[3][0][0] = 4;
        logits[19][0][1] = 5;
        int[] semantic = Segmentation.labels(logits, 2, 2);
        check(Arrays.equals(semantic, new int[] {3, 19, 0, 0}), "Semantic classes discarded");
        check(
                Arrays.equals(
                        Segmentation.floorMask(semantic),
                        new boolean[] {true, false, false, false}),
                "Incorrect floor labels");
        check(
                Arrays.equals(Segmentation.floor(logits, 2, 2), Segmentation.floorMask(semantic)),
                "Floor compatibility broken");
        System.out.println(
                "PASS commentary without trapezoid, open-floor length, height refusal, semantic objects/contact/clipping, gap stop, no-floor and class preservation");
    }
}
