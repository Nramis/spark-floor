package org.example.sparkfloor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Experimental centre of the visible floor mask, not a navigation or obstacle planner. */
public final class FloorPath {
    private static final double CELL = .10;
    private static final int COLS = 101, ROWS = 100, HALF = 50;

    public enum End {
        MASK_OR_VIEW,
        BRANCH,
        DISCONTINUITY,
        LIMIT,
        TOO_SHORT,
        NO_GEOMETRY
    }

    public static final class Point {
        public final double x, z, u, v;

        Point(double x, double z, double u, double v) {
            this.x = x;
            this.z = z;
            this.u = u;
            this.v = v;
        }
    }

    public static final class Bend {
        /** Positive angle means right. Distance is along the line from its visible start. */
        public final double atMeters, degrees;

        Bend(double atMeters, double degrees) {
            this.atMeters = atMeters;
            this.degrees = degrees;
        }
    }

    public static final class Result {
        public final List<Point> points;
        public final List<Bend> bends;
        public final double length;
        public final End end;

        private Result(List<Point> points, End end) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.end = end;
            double[] distance = new double[points.size()];
            for (int i = 1; i < points.size(); i++)
                distance[i] =
                        distance[i - 1]
                                + Math.hypot(
                                        points.get(i).x - points.get(i - 1).x,
                                        points.get(i).z - points.get(i - 1).z);
            length = points.isEmpty() ? 0 : distance[distance.length - 1];
            bends = Collections.unmodifiableList(findBends(points, distance));
        }

        public String summary() {
            return summary(true);
        }

        public String summary(boolean includeDisclaimer) {
            if (end == End.NO_GEOMETRY)
                return "Длина и изгибы дорожки недоступны: нужны высота, устойчивый наклон и углы обзора при съёмке.";
            if (points.isEmpty())
                return "Недостаточно непрерывного видимого пола для оценки дорожки.";
            StringBuilder text =
                    new StringBuilder(
                            String.format(
                                    Locale.getDefault(),
                                    "Видимый участок предполагаемой дорожки: ≈ %.1f м между концами розовой линии. ",
                                    length));
            if (bends.isEmpty()) text.append("Выраженные изгибы на этом участке не определены. ");
            else {
                text.append("Предполагаемые изгибы: ");
                for (int i = 0; i < bends.size(); i++) {
                    if (i > 0) text.append("; затем ");
                    Bend bend = bends.get(i);
                    text.append(bend.degrees > 0 ? "вправо" : "влево");
                    text.append(
                            String.format(
                                    Locale.getDefault(),
                                    " около %.1f м от начала линии",
                                    bend.atMeters));
                }
                text.append(". ");
            }
            if (end == End.BRANCH)
                text.append("Дальше неоднозначное разделение маски; линия остановлена. ");
            else if (end == End.DISCONTINUITY)
                text.append("Дальше резкое изменение или разрыв; линия остановлена. ");
            else if (end == End.LIMIT) text.append("Достигнут предел области расчёта. ");
            else text.append("Линия заканчивается у границы видимого или распознанного участка. ");
            if (includeDisclaimer)
                text.append(
                        "Это оценка по маске пола, не полная длина дорожки и не подтверждение проходимости.");
            return text.toString();
        }
    }

    private static final class Run {
        final int left, right;

        Run(int left, int right) {
            this.left = left;
            this.right = right;
        }

        double centre() {
            return (left + right) / 2.0;
        }

        boolean overlaps(Run other) {
            return Math.max(left, other.left) <= Math.min(right, other.right);
        }
    }

    public static Result unavailable() {
        return new Result(Collections.emptyList(), End.NO_GEOMETRY);
    }

    public static Result analyze(boolean[] mask, int width, int height, GroundProjection camera) {
        if (width < 3 || height < 3 || mask == null || mask.length != width * height)
            throw new IllegalArgumentException("Invalid floor mask");
        if (camera == null) return unavailable();
        List<Run> selected = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        Run previous = null;
        End end = End.LIMIT;
        for (int row = 0; row < ROWS; row++) {
            double z = (row + 1) * CELL;
            List<Run> runs = new ArrayList<>();
            int start = -1;
            for (int col = 0; col <= COLS; col++) {
                boolean inside =
                        col < COLS && onFloor(mask, width, height, camera, (col - HALF) * CELL, z);
                if (inside && start < 0) start = col;
                if (!inside && start >= 0) {
                    if (col - start >= 3) runs.add(new Run(start, col - 1));
                    start = -1;
                }
            }
            List<Run> candidates = new ArrayList<>();
            for (Run run : runs) {
                if (previous == null) {
                    // Do not invent a connection from the camera through unseen floor.
                    if (z <= 3
                            && run.left <= HALF + 7
                            && run.right >= HALF - 7
                            && !clipped(run, z, camera, width, height)) candidates.add(run);
                } else if (run.overlaps(previous)) candidates.add(run);
            }
            if (candidates.isEmpty()) {
                if (previous == null) continue;
                end = End.MASK_OR_VIEW;
                break;
            }
            if (candidates.size() > 1) {
                end = End.BRANCH;
                break;
            }
            Run next = candidates.get(0);
            if (clipped(next, z, camera, width, height)) {
                end = End.MASK_OR_VIEW;
                break;
            }
            if (previous != null && Math.abs(next.centre() - previous.centre()) * CELL > .35) {
                end = End.DISCONTINUITY;
                break;
            }
            selected.add(next);
            rows.add(row);
            previous = next;
        }

        List<Point> points = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            double centre = 0;
            int count = 0;
            // Smooth quantisation without crossing the selected floor interval.
            for (int j = Math.max(0, i - 3); j <= Math.min(selected.size() - 1, i + 3); j++) {
                centre += selected.get(j).centre();
                count++;
            }
            Run run = selected.get(i);
            centre = Math.max(run.left, Math.min(run.right, centre / count));
            double x = (centre - HALF) * CELL, z = (rows.get(i) + 1) * CELL;
            if (!points.isEmpty()) {
                Point p = points.get(points.size() - 1);
                int steps = Math.max(4, (int) Math.ceil(Math.hypot(x - p.x, z - p.z) / .025));
                boolean valid = true;
                for (int step = 1; step <= steps; step++) {
                    double t = step / (double) steps;
                    if (!onFloor(
                            mask,
                            width,
                            height,
                            camera,
                            p.x + t * (x - p.x),
                            p.z + t * (z - p.z))) {
                        valid = false;
                        break;
                    }
                }
                if (!valid) {
                    end = End.DISCONTINUITY;
                    break;
                }
            }
            double[] pixel = camera.toImage(x, z);
            if (pixel == null) {
                end = End.MASK_OR_VIEW;
                break;
            }
            points.add(new Point(x, z, pixel[0], pixel[1]));
        }
        Result result = new Result(points, end);
        return points.size() < 10 || result.length < 1
                ? new Result(Collections.emptyList(), End.TOO_SHORT)
                : result;
    }

    private static boolean onFloor(
            boolean[] mask, int width, int height, GroundProjection camera, double x, double z) {
        double[] pixel = camera.toImage(x, z);
        if (pixel == null) return false;
        int mx = (int) (pixel[0] * width / camera.width),
                my = (int) (pixel[1] * height / camera.height);
        // One mask cell of erosion: avoid isolated speckles and uncertain floor edges.
        if (mx < 1 || my < 1 || mx >= width - 1 || my >= height - 1) return false;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) if (!mask[(my + dy) * width + mx + dx]) return false;
        return true;
    }

    private static boolean clipped(
            Run run, double z, GroundProjection camera, int width, int height) {
        if (run.left == 0 || run.right == COLS - 1) return true;
        for (int col : new int[] {run.left - 1, run.right + 1}) {
            double[] pixel = camera.toImage((col - HALF) * CELL, z);
            if (pixel == null) return true;
            double mx = pixel[0] * width / camera.width, my = pixel[1] * height / camera.height;
            if (mx < 2 || mx >= width - 2 || my < 2 || my >= height - 2) return true;
        }
        return false;
    }

    private static List<Bend> findBends(List<Point> points, double[] distance) {
        List<Bend> result = new ArrayList<>();
        double previous = Double.NaN, accumulated = 0, at = 0;
        int sign = 0;
        // Headings measured over 0.6 m forward, rather than noisy adjacent pixels.
        for (int i = 0; i + 6 < points.size(); i += 3) {
            Point a = points.get(i), b = points.get(i + 6);
            double heading = Math.toDegrees(Math.atan2(b.x - a.x, b.z - a.z));
            if (Double.isFinite(previous)) {
                double delta = heading - previous;
                if (Math.abs(delta) >= 1) {
                    int nextSign = delta > 0 ? 1 : -1;
                    if (nextSign != sign) {
                        if (Math.abs(accumulated) >= 18 && result.size() < 4)
                            result.add(new Bend(at, accumulated));
                        sign = nextSign;
                        accumulated = 0;
                        at = distance[i + 3];
                    }
                    accumulated += delta;
                }
            }
            previous = heading;
        }
        if (Math.abs(accumulated) >= 18 && result.size() < 4) result.add(new Bend(at, accumulated));
        return result;
    }
}
