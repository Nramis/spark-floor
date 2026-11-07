import org.example.sparkfloor.CameraGuidance;
import static org.example.sparkfloor.CameraGuidance.State.*;

public class CameraGuidanceTest {
    static void expect(double[] up, CameraGuidance.State expected) {
        if (CameraGuidance.state(up) != expected) throw new AssertionError("Expected " + expected);
    }

    public static void main(String[] args) {
        double p = Math.toRadians(25);
        expect(new double[] {0, Math.cos(p), Math.sin(p)}, READY);
        expect(new double[] {0, 1, 0}, TILT_DOWN);
        expect(new double[] {0, Math.cos(p), -Math.sin(p)}, TILT_DOWN);
        expect(new double[] {0, Math.cos(Math.toRadians(80)), Math.sin(Math.toRadians(80))}, RAISE);
        expect(new double[] {Math.cos(p), 0, Math.sin(p)}, STRAIGHTEN);
        expect(new double[] {0, 0, 0}, UNSTABLE);
        expect(new double[] {Double.NaN, 1, 0}, UNSTABLE);
        expect(null, UNSTABLE);
        System.out.println("PASS camera pitch/roll framing guidance and unavailable pose");
    }
}
