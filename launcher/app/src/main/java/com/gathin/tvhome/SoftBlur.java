package com.gathin.tvhome;

import android.graphics.Bitmap;

/**
 * The blurred copy of a wallpaper (wallpaper_blur), worked out once per picture: shrunk to an eighth, box-blurred
 * three times (close to a Gaussian) and left small, for an ImageView to stretch back with filtering. Fading it in or
 * out then costs one bitmap blend, where a live blur effect had the GPU re-blur the whole screen on every frame.
 */
final class SoftBlur {
    private SoftBlur() { }

    /** A small blurred copy of {@code source}, about 240 px wide; null if it cannot be made. */
    static Bitmap of(Bitmap source) {
        if (source == null || source.getWidth() <= 0) return null;
        int w = Math.max(16, source.getWidth() / 8), h = Math.max(9, source.getHeight() / 8);
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
        int[] pixels = new int[w * h];
        int[] scratch = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(pixels, scratch, w, h, 5, true);
            boxBlur(scratch, pixels, w, h, 5, false);
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h);
        return small;
    }

    /** One box blur pass along rows (horizontal) or columns, from {@code in} into {@code out}. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h;
        int span = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = in[index(line, clamp(i, length), w, horizontal)];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | (r / span) << 16 | (g / span) << 8 | (b / span);
                int add = in[index(line, clamp(i + radius + 1, length), w, horizontal)];
                int drop = in[index(line, clamp(i - radius, length), w, horizontal)];
                r += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                b += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int length) {
        return i < 0 ? 0 : i >= length ? length - 1 : i;
    }
}
