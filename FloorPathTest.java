import org.example.sparkfloor.FloorDistance;
import org.example.sparkfloor.FloorPath;
import org.example.sparkfloor.GroundProjection;
import java.util.function.DoubleUnaryOperator;

public class FloorPathTest {
    static final int W = 160, H = 200;

    interface Region {
        boolean contains(double x, double z);
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static GroundProjection camera(double roll, double scale) {
        double pitch = Math.toRadians(35), r = Math.toRadians(roll);
        double[] up = {
            -Math.cos(pitch) * Math.sin(r), Math.cos(pitch) * Math.cos(r), Math.sin(pitch)
        };
        return new GroundProjection(480, 600, 440, 440, 1.4, up, scale);
    }

    static boolean[] raster(GroundProjection camera, Region region) {
        boolean[] mask = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double[] p = camera.toGround((x + .5) * 480 / W, (y + .5) * 600 / H);
                mask[y * W + x] = p != null && region.contains(p[0], p[1]);
            }
        return mask;
    }

    static FloorPath.Result corridor(GroundProjection camera, DoubleUnaryOperator centre) {
        return FloorPath.analyze(
                raster(
                        camera,
                        (x, z) ->
                                z >= 1 && z <= 5.5 && Math.abs(x - centre.applyAsDouble(z)) < .65),
                W,
                H,
                camera);
    }

    static void verifyLine(FloorPath.Result path, DoubleUnaryOperator centre) {
        check(!path.points.isEmpty(), "No path: " + path.end);
        double expected = 0, error = 0;
        for (int i = 0; i < path.points.size(); i++) {
            FloorPath.Point p = path.points.get(i);
            error += Math.abs(p.x - centre.applyAsDouble(p.z));
            if (i > 0) {
                FloorPath.Point prev = path.points.get(i - 1);
                expected +=
                        Math.hypot(
                                centre.applyAsDouble(p.z) - centre.applyAsDouble(prev.z),
                                p.z - prev.z);
            }
        }
        check(error / path.points.size() < .12, "Centre error: " + error / path.points.size());
        check(
                Math.abs(path.length - expected) < .2,
                "Arc length error: " + path.length + " vs " + expected);
    }

    public static void main(String[] args) {
        GroundProjection camera = camera(0, 1);
        for (double roll : new double[] {0, 12, -18}) {
            GroundProjection rolled = camera(roll, 1.2);
            for (double x : new double[] {-.7, 0, .8})
                for (double z : new double[] {2, 3, 5}) {
                    double[] pixel = rolled.toImage(x, z);
                    check(pixel != null, "Expected visible point");
                    double[] floor = rolled.toGround(pixel[0], pixel[1]);
                    check(
                            floor != null && Math.hypot(floor[0] - x, floor[1] - z) < 1e-8,
                            "Projection roundtrip");
                }
            FloorPath.Result straight = corridor(rolled, z -> 0);
            verifyLine(straight, z -> 0);
            check(
                    straight.length > 4 && straight.length < 4.6,
                    "Incorrect visible straight length");
            check(straight.bends.isEmpty(), "False bend with roll: " + roll);
        }
        FloorPath.Result diagonal = corridor(camera, z -> .12 * z);
        verifyLine(diagonal, z -> .12 * z);
        check(diagonal.bends.isEmpty(), "Constant diagonal heading is not a bend");

        DoubleUnaryOperator curve = z -> .18 * Math.pow(Math.max(0, z - 2), 2);
        FloorPath.Result right = corridor(camera, curve);
        verifyLine(right, curve);
        check(!right.bends.isEmpty() && right.bends.get(0).degrees > 0, "Missing right bend");
        FloorPath.Point first = right.points.get(0),
                last = right.points.get(right.points.size() - 1);
        check(
                right.length > Math.hypot(last.x - first.x, last.z - first.z) + .03,
                "Used endpoint distance instead of curved arc length");
        FloorPath.Result left = corridor(camera, z -> -curve.applyAsDouble(z));
        verifyLine(left, z -> -curve.applyAsDouble(z));
        check(!left.bends.isEmpty() && left.bends.get(0).degrees < 0, "Missing left bend");

        DoubleUnaryOperator snake = z -> .3 * Math.sin((z - 1) * 1.8);
        FloorPath.Result s = corridor(camera, snake);
        verifyLine(s, snake);
        check(
                s.bends.size() >= 2 && s.bends.get(0).degrees * s.bends.get(1).degrees < 0,
                "Missing S bends");

        FloorPath.Result branch =
                FloorPath.analyze(
                        raster(
                                camera,
                                (x, z) ->
                                        z >= 1
                                                && z <= 6
                                                && Math.abs(x) < 1
                                                && (z < 3 || Math.abs(x) > .3)),
                        W,
                        H,
                        camera);
        check(branch.end == FloorPath.End.BRANCH, "Did not stop before fork: " + branch.end);
        check(branch.points.get(branch.points.size() - 1).z < 3.1, "Crossed fork");

        FloorPath.Result gap =
                FloorPath.analyze(
                        raster(
                                camera,
                                (x, z) ->
                                        Math.abs(x) < .65
                                                && ((z >= 1 && z < 3) || (z > 3.7 && z < 6))),
                        W,
                        H,
                        camera);
        check(
                !gap.points.isEmpty() && gap.points.get(gap.points.size() - 1).z <= 3,
                "Bridged unseen gap");
        check(
                FloorPath.analyze(new boolean[W * H], W, H, camera).points.isEmpty(),
                "Empty mask accepted");
        check(
                FloorPath.analyze(raster(camera, (x, z) -> true), W, H, camera).points.isEmpty(),
                "Invented centre from floor clipped at both image edges");
        check(
                FloorPath.analyze(
                                raster(camera, (x, z) -> z > 2 && z < 2.5 && Math.abs(x) < .6),
                                W,
                                H,
                                camera)
                        .points
                        .isEmpty(),
                "Very short fragment accepted");
        check(
                FloorPath.analyze(raster(camera, (x, z) -> z > 4 && Math.abs(x) < .6), W, H, camera)
                        .points
                        .isEmpty(),
                "Remote disconnected floor accepted");
        check(
                FloorPath.analyze(new boolean[W * H], W, H, null).end == FloorPath.End.NO_GEOMETRY,
                "Missing pose accepted");
        check(camera.toImage(0, 11) == null, "Beyond range accepted");
        check(camera.toGround(240, 0) == null, "Horizon accepted");
        check(
                Double.isNaN(
                        FloorDistance.measure(
                                0, 0, 480, 600, 440, 440, 1.4, new double[] {Double.NaN, 1, 0}, 1)),
                "Invalid normal accepted");
        System.out.println(
                "PASS projection/roll/scale, straight/diagonal/left/right/S paths, metric arc length, fork/gap/empty/remote/pose/range refusals");
    }
}
