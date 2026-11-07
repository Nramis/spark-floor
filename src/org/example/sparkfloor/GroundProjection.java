package org.example.sparkfloor;

/** Metric floor axes: x right, z forward; roll removed using the captured gravity. */
public final class GroundProjection {
    public final double width, height;
    private final double fx, fy, cameraHeight, scale;
    private final double[] up, right, forward;

    public GroundProjection(
            double width,
            double height,
            double fx,
            double fy,
            double cameraHeight,
            double[] normal,
            double scale) {
        if (normal == null || normal.length != 3)
            throw new IllegalArgumentException("Missing pose");
        double norm = Math.sqrt(dot(normal, normal));
        if (!Double.isFinite(norm)
                || norm < 1e-6
                || !Double.isFinite(width)
                || width <= 0
                || !Double.isFinite(height)
                || height <= 0
                || !Double.isFinite(fx)
                || fx <= 0
                || !Double.isFinite(fy)
                || fy <= 0
                || !Double.isFinite(scale)
                || scale <= 0
                || !Double.isFinite(cameraHeight)
                || cameraHeight < .4
                || cameraHeight > 2.5)
            throw new IllegalArgumentException("Invalid camera geometry");
        up = new double[] {normal[0] / norm, normal[1] / norm, normal[2] / norm};
        double horizontal = Math.hypot(up[0], up[1]);
        if (horizontal < .15) throw new IllegalArgumentException("Forward direction undefined");
        right = new double[] {up[1] / horizontal, -up[0] / horizontal, 0};
        forward =
                new double[] {up[2] * up[0] / horizontal, up[2] * up[1] / horizontal, -horizontal};
        this.width = width;
        this.height = height;
        this.fx = fx;
        this.fy = fy;
        this.cameraHeight = cameraHeight;
        this.scale = scale;
    }

    public double[] toGround(double u, double v) {
        double[] p = FloorDistance.intersect(u, v, width, height, fx, fy, cameraHeight, up, scale);
        return p == null ? null : new double[] {dot(p, right), dot(p, forward)};
    }

    /** Null outside the image or the same valid range/incidence limits as point measurement. */
    public double[] toImage(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.hypot(x, z) > 10) return null;
        double px = x * right[0] + z * forward[0] - cameraHeight * up[0];
        double py = x * right[1] + z * forward[1] - cameraHeight * up[1];
        double pz = z * forward[2] - cameraHeight * up[2];
        double range = Math.sqrt(px * px + py * py + pz * pz);
        if (pz >= -1e-6 || cameraHeight / range < .10) return null;
        double u = width / 2 + fx * scale * px / -pz;
        double v = height / 2 - fy * scale * py / -pz;
        return u >= 0 && v >= 0 && u < width && v < height ? new double[] {u, v} : null;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
