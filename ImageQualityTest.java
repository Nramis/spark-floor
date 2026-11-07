import org.example.sparkfloor.ImageQuality;
import java.util.Arrays;

public class ImageQualityTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static int gray(int v) {
        return v << 16 | v << 8 | v;
    }

    public static void main(String[] args) {
        int w = 128, h = 128;
        int[] rgb = new int[w * h];
        Arrays.fill(rgb, gray(4));
        check(ImageQuality.evaluate(rgb, w, h).unusable, "Black frame accepted");
        Arrays.fill(rgb, gray(128));
        ImageQuality.Result plain = ImageQuality.evaluate(rgb, w, h);
        check(
                plain.lowDetail && !plain.blurSuspected && !plain.unusable,
                "Plain floor called blurred");
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) rgb[y * w + x] = gray(x * 255 / (w - 1));
        check(
                ImageQuality.evaluate(rgb, w, h).blurSuspected,
                "Smooth high-contrast image not flagged");
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) rgb[y * w + x] = gray(((x / 8 + y / 8) % 2) * 255);
        ImageQuality.Result sharp = ImageQuality.evaluate(rgb, w, h);
        check(!sharp.blurSuspected && !sharp.dark, "Sharp edges rejected");
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) rgb[y * w + x] = gray(8 + ((x / 8 + y / 8) % 2) * 16);
        check(ImageQuality.evaluate(rgb, w, h).dark, "Dark scene warning absent");
        try {
            ImageQuality.evaluate(new int[3], 128, 128);
            throw new AssertionError("Invalid image accepted");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println(
                "PASS black/dark/sharp/smooth/untextured quality heuristics and invalid input refusal");
    }
}
