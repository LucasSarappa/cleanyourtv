package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** A style rule's wallpaper (config.json styles): shown instead of the rotation while the rule applies. */
    private String pinned;
    /**
     * wallpaper_blur: a small blurred copy of the picture (SoftBlur), stretched over the sharp one and faded in while
     * the dock is up, out while Home is locked. Null when off.
     */
    private final ImageView soft;

    WallpaperPlayer(Context context, FrameLayout root, Config config, String pinned, boolean blur) {
        this.root = root;
        this.pinned = pinned;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        boolean shown = config.wallpaperEnabled && !files.isEmpty();
        if (shown && blur) {
            soft = new ImageView(context);
            soft.setScaleType(ImageView.ScaleType.CENTER_CROP);
            soft.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            soft.setAlpha(0f);
        } else {
            soft = null;
        }
        if (shown) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int next = 1;
            if (soft != null) root.addView(soft, next++, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, next, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        }
    }

    /** Fades the blurred copy in (dock up) or out (Home resting); does nothing without wallpaper_blur. */
    void setBlurred(boolean blurred) {
        if (soft == null) return;
        soft.animate().cancel();
        soft.animate().alpha(blurred ? 1f : 0f).setDuration(blurred ? 520 : 700).start();
    }

    private void show(Bitmap bitmap) {
        image.setImageBitmap(bitmap);
        if (soft == null || closed) return;
        // The blurred copy is worked out once, off the main thread; until it is ready the old one stays.
        decoder.execute(() -> {
            Bitmap blurred = SoftBlur.of(bitmap);
            if (blurred != null) root.post(() -> {
                if (!closed) soft.setImageBitmap(blurred);
            });
        });
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File styled = pinned != null ? WallpaperRotation.find(files, pinned) : null;
        if (styled != null) {
            Bitmap bitmap = decode(styled);
            if (bitmap != null) {
                // The rotation's own place is left as it was, so it carries on from there once the rule ends.
                show(bitmap);
                displayed = styled.getName();
                return;
            }
        }
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    /** Shows a style rule's wallpaper, or goes back to the rotation with null; a name not in the collection is ignored. */
    void pin(String name) {
        if (name != null && WallpaperRotation.find(files, name) == null) name = null;
        if (name == null ? pinned == null : name.equals(pinned)) return;
        pinned = name;
        update();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File styled = pinned != null ? WallpaperRotation.find(files, pinned) : null;
        File selected = styled != null ? styled : WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (styled == null && (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval))) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        boolean rotation = styled == null;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                if (rotation && (!displayed.equals(previous) || changed == 0)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
