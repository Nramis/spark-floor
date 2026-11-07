package org.example.sparkfloor;

/** Upright portrait image, back camera: image ray -> device axes [x,-y,-1]. */
public final class FloorDistance {
    public static double measure(
            double u,
            double v,
            double width,
            double height,
            double fx,
            double fy,
            double cameraHeight,
            double[] up,
            double focalScale) {
        double[] point = intersect(u, v, width, height, fx, fy, cameraHeight, up, focalScale);
        return point == null
                ? Double.NaN
                : Math.sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2]);
    }

    /** Floor point relative to the camera's vertical projection, in device axes. */
    public static double[] intersect(
            double u,
            double v,
            double width,
            double height,
            double fx,
            double fy,
            double cameraHeight,
            double[] up,
            double focalScale) {
        if (up == null
                || up.length != 3
                || !Double.isFinite(u)
                || !Double.isFinite(v)
                || !Double.isFinite(width)
                || !Double.isFinite(height)
                || width <= 0
                || height <= 0
                || !Double.isFinite(fx)
                || !Double.isFinite(fy)
                || !Double.isFinite(cameraHeight)
                || !Double.isFinite(focalScale)
                || fx <= 0
                || fy <= 0
                || cameraHeight < .4
                || cameraHeight > 2.5
                || focalScale <= 0) return null;
        double norm = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        if (!Double.isFinite(norm) || norm < 1e-6) return null;
        double x = (u - width / 2) / (fx * focalScale),
                y = -(v - height / 2) / (fy * focalScale),
                z = -1;
        double dot = (x * up[0] + y * up[1] + z * up[2]) / norm,
                rayNorm = Math.sqrt(x * x + y * y + 1);
        if (!Double.isFinite(dot) || -dot / rayNorm < .10) return null; // Reject near-horizon rays.
        double t = -cameraHeight / dot;
        double range = t * rayNorm;
        double ground = Math.sqrt(Math.max(0, range * range - cameraHeight * cameraHeight));
        return Double.isFinite(ground) && ground <= 10
                ? new double[] {
                    t * x + cameraHeight * up[0] / norm,
                    t * y + cameraHeight * up[1] / norm,
                    -t + cameraHeight * up[2] / norm
                }
                : null;
    }

    public static double calibrate(
            double u,
            double v,
            double w,
            double h,
            double fx,
            double fy,
            double cameraHeight,
            double[] up,
            double known) {
        if (!Double.isFinite(known) || known < .5 || known > 6) return Double.NaN;
        double candidate = Double.NaN;
        int roots = 0;
        double prevS = .5, prev = measure(u, v, w, h, fx, fy, cameraHeight, up, prevS) - known;
        for (int i = 1; i <= 500; i++) {
            double s = .5 + 1.5 * i / 500;
            double val = measure(u, v, w, h, fx, fy, cameraHeight, up, s) - known;
            if (Double.isFinite(prev)
                    && Double.isFinite(val)
                    && ((prev <= 0 && val > 0) || (prev >= 0 && val < 0))) {
                double l = prevS, r = s, lv = prev;
                for (int k = 0; k < 45; k++) {
                    double mid = (l + r) / 2,
                            mv = measure(u, v, w, h, fx, fy, cameraHeight, up, mid) - known;
                    if (!Double.isFinite(mv)) break;
                    if ((lv <= 0 && mv <= 0) || (lv >= 0 && mv >= 0)) {
                        l = mid;
                        lv = mv;
                    } else r = mid;
                }
                double root = (l + r) / 2;
                double a = measure(u, v, w, h, fx, fy, cameraHeight, up, root * .99),
                        b = measure(u, v, w, h, fx, fy, cameraHeight, up, root * 1.01);
                if (Double.isFinite(a) && Double.isFinite(b) && Math.abs(b - a) > .005) {
                    candidate = root;
                    roots++;
                }
            }
            prevS = s;
            prev = val;
        }
        return roots == 1 ? candidate : Double.NaN;
    }
}
