package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Team crests for the football list: fetched once at 48 px through ESPN's image resizer, kept in memory and in the
 * app's cache folder, so a list opens with its crests after the first time. Only ESPN's image host is fetched; a
 * crest from anywhere else is left out.
 */
final class Crests {
    private static final String HOST = "https://a.espncdn.com/";
    private static final int SIZE = 48;

    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<>(48);
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    Crests(Context context) {
        dir = new File(context.getCacheDir(), "crests");
    }

    /** Puts the crest for {@code logo} into {@code view}, now from memory or once it has loaded. */
    void load(String logo, ImageView view) {
        view.setTag(logo);
        view.setImageDrawable(null);
        if (logo == null || !logo.startsWith(HOST)) return;
        Bitmap cached = memory.get(logo);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        worker.execute(() -> {
            Bitmap bitmap = fetch(logo);
            if (bitmap == null) return;
            main.post(() -> {
                memory.put(logo, bitmap);
                if (logo.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    void close() {
        worker.shutdownNow();
    }

    private Bitmap fetch(String logo) {
        File file = new File(dir, Integer.toHexString(logo.hashCode()) + ".png");
        if (file.isFile()) {
            Bitmap disk = BitmapFactory.decodeFile(file.getPath());
            if (disk != null) return disk;
        }
        // "https://a.espncdn.com/i/teamlogos/soccer/500/5.png" through the resizer at 48 px instead of 500.
        String path = logo.substring(HOST.length() - 1);
        String address = HOST + "combiner/i?img=" + path + "&w=" + SIZE + "&h=" + SIZE;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < 512 * 1024) out.write(buffer, 0, n);
                bytes = out.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            // The resizer can be bypassed by a cache in between: never decode a big image whole.
            options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / (SIZE * 2));
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null && (dir.isDirectory() || dir.mkdirs())) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
