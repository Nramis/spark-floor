import org.example.sparkfloor.FloorDistance;

public class DistanceTest {
    static void near(double a, double b) {
        if (!Double.isFinite(a) || Math.abs(a - b) > 1e-6) throw new AssertionError(a + " != " + b);
    }

    public static void main(String[] args) {
        double h = 1.4, theta = Math.toRadians(25), f = 480;
        double[] up = {0, Math.cos(theta), Math.sin(theta)};
        for (double d : new double[] {1.5, 2, 3, 5, 8}) {
            double y = h * Math.cos(theta) - d * Math.sin(theta),
                    z = d * Math.cos(theta) + h * Math.sin(theta),
                    v = 240 + f * y / z;
            near(FloorDistance.measure(320, v, 640, 480, f, f, h, up, 1), d);
        }
        double d = 3,
                x = 1,
                z = d * Math.cos(theta) + h * Math.sin(theta),
                cy = h * Math.cos(theta) - d * Math.sin(theta);
        near(
                FloorDistance.measure(320 + f * x / z, 240 + f * cy / z, 640, 480, f, f, h, up, 1),
                Math.sqrt(d * d + x * x));
        double angle = Math.toRadians(12), rx = x / z, ry = -cy / z;
        double[] rolled = {-up[1] * Math.sin(angle), up[1] * Math.cos(angle), up[2]};
        double rrx = rx * Math.cos(angle) - ry * Math.sin(angle),
                rry = rx * Math.sin(angle) + ry * Math.cos(angle);
        near(
                FloorDistance.measure(320 + f * rrx, 240 - f * rry, 640, 480, f, f, h, rolled, 1),
                Math.sqrt(10));
        d = 2;
        double v =
                240
                        + 480
                                * (h * Math.cos(theta) - d * Math.sin(theta))
                                / (d * Math.cos(theta) + h * Math.sin(theta));
        near(FloorDistance.calibrate(320, v, 640, 480, 400, 400, h, up, 2), 1.2);
        if (!Double.isNaN(FloorDistance.measure(320, 0, 640, 480, f, f, h, up, 1)))
            throw new AssertionError("horizon accepted");
        if (!Double.isNaN(FloorDistance.measure(320, 240, 640, 480, f, f, 0, up, 1)))
            throw new AssertionError("zero height accepted");
        if (!Double.isNaN(
                FloorDistance.calibrate(320, 240, 640, 480, f, f, h, up, h / Math.tan(theta))))
            throw new AssertionError("ambiguous calibration accepted");
        System.out.println(
                "PASS known distances, off-axis, roll, focal calibration, horizon/height refusal, ill-conditioned calibration refusal");
    }
}
