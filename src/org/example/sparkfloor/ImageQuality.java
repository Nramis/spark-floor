package org.example.sparkfloor;

/** Conservative image heuristics. Blur is a suspicion, not a calibrated camera measurement. */
public final class ImageQuality {
    public static final class Result {
        public final double mean, contrast, laplacianVariance;
        public final boolean unusable, dark, blurSuspected, lowDetail;

        Result(double mean, double contrast, double variance) {
            this.mean = mean;
            this.contrast = contrast;
            laplacianVariance = variance;
            unusable = mean < 12 && contrast < 18;
            dark = mean < 40;
            lowDetail = contrast < 18;
            blurSuspected = !dark && contrast > 40 && variance < 35;
        }

        public String hint() {
            if (unusable)
                return "На снимке почти ничего не видно. Проверьте, не закрыт ли объектив; нужно больше света.";
            if (dark) return "На снимке темно. Для оценки нужно больше света.";
            if (blurSuspected)
                return "Возможно, кадр смазан. Держите телефон неподвижно и переснимите.";
            return "";
        }

        public String details() {
            String hint = hint();
            return hint
                    + (lowDetail && !unusable
                            ? " На снимке мало деталей: резкость по нему оценить надёжно нельзя."
                            : "");
        }
    }

    public static Result evaluate(int[] rgb, int width, int height) {
        if (rgb == null || width < 3 || height < 3 || rgb.length != width * height)
            throw new IllegalArgumentException("Invalid image dimensions");
        int[] luma = new int[rgb.length], histogram = new int[256];
        long sum = 0;
        for (int i = 0; i < rgb.length; i++) {
            int color = rgb[i];
            int y =
                    (77 * ((color >> 16) & 255) + 150 * ((color >> 8) & 255) + 29 * (color & 255))
                            >> 8;
            luma[i] = y;
            histogram[y]++;
            sum += y;
        }
        int low = percentile(histogram, rgb.length, .1),
                high = percentile(histogram, rgb.length, .9);
        double lapSum = 0, lapSquare = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++)
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                int lap =
                        4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width];
                lapSum += lap;
                lapSquare += lap * lap;
                count++;
            }
        double variance = Math.max(0, lapSquare / count - Math.pow(lapSum / count, 2));
        return new Result(sum / (double) rgb.length, high - low, variance);
    }

    private static int percentile(int[] histogram, int count, double fraction) {
        int sum = 0, threshold = Math.max(1, (int) Math.ceil(count * fraction));
        for (int i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= threshold) return i;
        }
        return 255;
    }
}
