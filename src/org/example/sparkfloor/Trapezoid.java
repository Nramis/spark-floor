package org.example.sparkfloor;

import java.util.*;

public final class Trapezoid {
    public final double lt, rt, lb, rb, area;
    public final int top, bottom;

    Trapezoid(double a, double b, double c, double d, int t, int z) {
        lt = a;
        rt = b;
        lb = c;
        rb = d;
        top = t;
        bottom = z;
        area = (z - t) * ((b - a) + (d - c)) / 2;
    }

    static ArrayList<double[]> square(int w) {
        ArrayList<double[]> p = new ArrayList<>();
        p.add(new double[] {0, 0});
        p.add(new double[] {w - 1, 0});
        p.add(new double[] {w - 1, w - 1});
        p.add(new double[] {0, w - 1});
        return p;
    }

    static ArrayList<double[]> clip(ArrayList<double[]> p, double a, double b, double c) {
        ArrayList<double[]> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        double[] previous = p.get(p.size() - 1);
        double pv = a * previous[0] + b * previous[1] - c;
        for (double[] current : p) {
            double cv = a * current[0] + b * current[1] - c;
            boolean pi = pv <= 1e-8, ci = cv <= 1e-8;
            if (pi != ci) {
                double t = pv / (pv - cv);
                out.add(
                        new double[] {
                            previous[0] + t * (current[0] - previous[0]),
                            previous[1] + t * (current[1] - previous[1])
                        });
            }
            if (ci) out.add(current);
            previous = current;
            pv = cv;
        }
        return out;
    }

    public static Trapezoid fit(boolean[] mask, int w, int h) {
        if (mask.length != w * h || w < 8 || h < 20)
            throw new IllegalArgumentException("Bad mask dimensions");
        boolean[] clean = new boolean[mask.length];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                clean[i] = mask[i] && mask[i - 1] && mask[i + 1] && mask[i - w] && mask[i + w];
            }
        int[] labels = new int[mask.length], queue = new int[mask.length];
        int label = 0, bestLabel = 0, bestSupport = 0;
        for (int i = 0; i < clean.length; i++) {
            if (!clean[i] || labels[i] != 0) continue;
            label++;
            int head = 0, tail = 1, support = 0;
            queue[0] = i;
            labels[i] = label;
            while (head < tail) {
                int index = queue[head++], x = index % w, y = index / w;
                if (y >= h * 4 / 5) support++;
                int[] neighbours = {
                    x > 0 ? index - 1 : -1,
                    x < w - 1 ? index + 1 : -1,
                    y > 0 ? index - w : -1,
                    y < h - 1 ? index + w : -1
                };
                for (int n : neighbours)
                    if (n >= 0 && clean[n] && labels[n] == 0) {
                        labels[n] = label;
                        queue[tail++] = n;
                    }
            }
            if (support > bestSupport) {
                bestSupport = support;
                bestLabel = label;
            }
        }
        if (bestLabel == 0) return null;
        int[] left = new int[h], right = new int[h];
        Arrays.fill(left, -1);
        int first = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            int best = 0, start = -1;
            for (int x = 0; x <= w; x++) {
                boolean inside = x < w && labels[y * w + x] == bestLabel;
                if (inside && start < 0) start = x;
                if (!inside && start >= 0) {
                    int len = x - start;
                    if (len > best) {
                        best = len;
                        left[y] = start;
                        right[y] = x - 1;
                    }
                    start = -1;
                }
            }
            if (left[y] >= 0) {
                first = Math.min(first, y);
                bottom = y;
            }
        }
        Trapezoid best = null;
        int minHeight = Math.max(20, h / 5), minWidth = Math.max(5, w / 30);
        for (int top = first; top <= bottom - minHeight; top++) {
            ArrayList<double[]> lp = clip(square(w), -1, 1, 0), rp = clip(square(w), 1, -1, 0);
            boolean valid = true;
            for (int y = top; y <= bottom; y++) {
                if (left[y] < 0) {
                    valid = false;
                    break;
                }
                double t = (double) (y - top) / (bottom - top);
                lp = clip(lp, -(1 - t), -t, -left[y]);
                rp = clip(rp, 1 - t, t, right[y]);
                if (lp.isEmpty() || rp.isEmpty()) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;
            double[] l = null, r = null;
            double ls = Double.POSITIVE_INFINITY, rs = -Double.MAX_VALUE;
            for (double[] v : lp)
                if (v[0] + v[1] < ls) {
                    l = v;
                    ls = v[0] + v[1];
                }
            for (double[] v : rp)
                if (v[0] + v[1] > rs) {
                    r = v;
                    rs = v[0] + v[1];
                }
            if (r[0] - l[0] < minWidth || r[1] - l[1] < minWidth) continue;
            Trapezoid candidate = new Trapezoid(l[0], r[0], l[1], r[1], top, bottom);
            if (best == null || candidate.area > best.area) best = candidate;
        }
        if (best == null) return null;
        for (int y = best.top; y <= best.bottom; y++) {
            double t = (double) (y - best.top) / (best.bottom - best.top);
            double l = best.lt * (1 - t) + best.lb * t, r = best.rt * (1 - t) + best.rb * t;
            for (int x = (int) Math.ceil(l + 1e-6); x <= Math.floor(r - 1e-6); x++)
                if (x < 0 || x >= w || !mask[y * w + x]) return null;
        }
        return best;
    }
}
