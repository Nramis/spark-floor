package org.example.sparkfloor;

import java.nio.*;

public final class Segmentation {
    public static FloatBuffer preprocess(int[] rgb) {
        if (rgb.length != 512 * 512)
            throw new IllegalArgumentException("Expected 512x512 RGB image");
        FloatBuffer input =
                ByteBuffer.allocateDirect(rgb.length * 3 * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
        float[] means = {.485f, .456f, .406f}, stds = {.229f, .224f, .225f};
        for (int c = 0; c < 3; c++)
            for (int color : rgb)
                input.put((((color >> (16 - 8 * c)) & 255) / 255f - means[c]) / stds[c]);
        input.rewind();
        return input;
    }

    public static boolean[] floor(float[][][] logits, int mw, int mh) {
        int oh = logits[0].length, ow = logits[0][0].length;
        boolean[] floor = new boolean[mw * mh];
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                float sx = Math.max(0, Math.min(ow - 1, (x + .5f) * ow / mw - .5f)),
                        sy = Math.max(0, Math.min(oh - 1, (y + .5f) * oh / mh - .5f));
                int x0 = (int) sx,
                        y0 = (int) sy,
                        x1 = Math.min(ow - 1, x0 + 1),
                        y1 = Math.min(oh - 1, y0 + 1);
                float dx = sx - x0, dy = sy - y0;
                int label = -1;
                float best = -Float.MAX_VALUE;
                for (int c = 0; c < logits.length; c++) {
                    float v =
                            (1 - dy) * ((1 - dx) * logits[c][y0][x0] + dx * logits[c][y0][x1])
                                    + dy * ((1 - dx) * logits[c][y1][x0] + dx * logits[c][y1][x1]);
                    if (v > best) {
                        best = v;
                        label = c;
                    }
                }
                floor[y * mw + x] = label == 3;
            }
        return floor;
    }
}
