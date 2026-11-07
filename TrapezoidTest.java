import org.example.sparkfloor.Trapezoid;

public class TrapezoidTest {
    static void check(boolean v) {
        if (!v) throw new AssertionError();
    }

    public static void main(String[] args) {
        int w = 160, h = 120;
        boolean[] m = new boolean[w * h];
        check(Trapezoid.fit(m, w, h) == null);
        for (int y = 20; y < h; y++) for (int x = 20; x < 140; x++) m[y * w + x] = true;
        Trapezoid r = Trapezoid.fit(m, w, h);
        check(r != null);
        check(r.area > 10000);
        for (int y = 50; y < 85; y++) for (int x = 100; x < 140; x++) m[y * w + x] = false;
        r = Trapezoid.fit(m, w, h);
        check(r != null);
        for (int y = Math.max(50, r.top); y <= Math.min(84, r.bottom); y++) {
            double t = (double) (y - r.top) / (r.bottom - r.top);
            check(r.rt * (1 - t) + r.rb * t <= 99.00001);
        }
        m = new boolean[w * h];
        for (int y = 5; y < 45; y++) for (int x = 20; x < 140; x++) m[y * w + x] = true;
        check(Trapezoid.fit(m, w, h) == null);
        m = new boolean[w * h];
        for (int y = 10; y < 90; y++) for (int x = 20; x < 140; x++) m[y * w + x] = true;
        for (int y = 108; y < 120; y++) for (int x = 20; x < 140; x++) m[y * w + x] = true;
        check(Trapezoid.fit(m, w, h) == null);
        System.out.println(
                "PASS: empty, rectangle, obstacle notch, no near floor, disconnected near strip");
    }
}
