package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for one place (config.json weather_latitude / weather_longitude) from Open-Meteo, which is
 * free and needs no key. The only thing sent is the coordinates. A reading is cached, so Home opens with the
 * last one instead of an empty pill, and a new one is fetched in the background once it is older than
 * weather_refresh_minutes. A failed fetch keeps the old reading and is retried after five minutes.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long RETRY_MS = 5 * 60_000L;

    /** One reading: temperatures in °C and the WMO weather code Open-Meteo reports. */
    static final class Reading {
        final double temperature;
        final double feelsLike;
        final int code;
        final boolean day;
        final long fetched;

        Reading(double temperature, double feelsLike, int code, boolean day, long fetched) {
            this.temperature = temperature;
            this.feelsLike = feelsLike;
            this.code = code;
            this.day = day;
            this.fetched = fetched;
        }

        /** The category style rules match on: despejado, nublado, niebla, lluvia, nieve or tormenta. */
        String condition() {
            if (code <= 1) return "despejado";
            if (code <= 3) return "nublado";
            if (code == 45 || code == 48) return "niebla";
            if (code >= 95) return "tormenta";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "nieve";
            return "lluvia";
        }

        String label() {
            if (code == 0) return "Despejado";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 61 || code == 80) return "Lluvia leve";
            if (code == 63 || code == 81) return "Lluvia";
            if (code == 65 || code == 82) return "Lluvia fuerte";
            if (code == 66 || code == 67) return "Lluvia helada";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Nieve";
            if (code == 95) return "Tormenta";
            if (code >= 96) return "Tormenta con granizo";
            return "—";
        }

        String icon() {
            switch (condition()) {
                case "despejado": return day ? "☀️" : "🌙";
                case "nublado": return code == 3 ? "☁️" : (day ? "⛅" : "☁️");
                case "niebla": return "🌫️";
                case "nieve": return "❄️";
                case "tormenta": return "⛈️";
                default: return code <= 57 ? "🌦️" : "🌧️";
            }
        }

        /** The weather chip: "☁️  15°  Nublado" over "ST 14°  ·  Palermo". */
        String chipFirstLine() {
            return icon() + "  " + Math.round(temperature) + "°  " + label();
        }

        String chipSecondLine(String place) {
            String text = "ST " + Math.round(feelsLike) + "°";
            return place.isEmpty() ? text : text + "  ·  " + place;
        }

        /** "☁️ 15° Nublado · ST 14° · Palermo" */
        String pillText(String place) {
            String text = icon() + "  " + Math.round(temperature) + "°  " + label()
                    + "  ·  ST " + Math.round(feelsLike) + "°";
            return place.isEmpty() ? text : text + "  ·  " + place;
        }
    }

    interface Listener {
        void onWeather(Reading reading);
    }

    private final double latitude;
    private final double longitude;
    private final long refreshMs;
    private final SharedPreferences cache;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Reading last;
    private long lastAttempt;
    private boolean loading;
    private boolean closed;

    Weather(Context context, Personal personal, Listener listener) {
        latitude = personal.weatherLatitude;
        longitude = personal.weatherLongitude;
        refreshMs = personal.weatherRefreshMinutes * 60_000L;
        this.listener = listener;
        cache = context.getSharedPreferences("weather", Context.MODE_PRIVATE);
        // A cached reading for other coordinates belongs to another place: start without one.
        String place = latitude + "," + longitude;
        if (place.equals(cache.getString("place", "")) && cache.contains("fetched")) {
            last = new Reading(cache.getFloat("temperature", 0f), cache.getFloat("feels_like", 0f),
                    cache.getInt("code", 0), cache.getBoolean("day", true), cache.getLong("fetched", 0));
        }
    }

    /** The last reading, cached or fresh; null until the first fetch for this place succeeds. */
    Reading last() {
        return last;
    }

    /** Fetches a new reading when the last one is due; called from Home's minute tick and on resume. */
    void update() {
        long now = System.currentTimeMillis();
        if (closed || loading) return;
        if (last != null && now - last.fetched < refreshMs) return;
        if (now - lastAttempt < RETRY_MS) return;
        lastAttempt = now;
        loading = true;
        worker.execute(() -> {
            Reading reading = fetch();
            main.post(() -> {
                loading = false;
                if (closed || reading == null) return;
                last = reading;
                cache.edit().putString("place", latitude + "," + longitude)
                        .putFloat("temperature", (float) reading.temperature)
                        .putFloat("feels_like", (float) reading.feelsLike)
                        .putInt("code", reading.code).putBoolean("day", reading.day)
                        .putLong("fetched", reading.fetched).apply();
                listener.onWeather(reading);
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    private Reading fetch() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=auto",
                    latitude, longitude));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            if (connection.getResponseCode() != 200) return null;
            JSONObject current = new JSONObject(read(connection.getInputStream())).getJSONObject("current");
            return new Reading(current.getDouble("temperature_2m"), current.getDouble("apparent_temperature"),
                    current.getInt("weather_code"), current.optInt("is_day", 1) == 1, System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "Weather fetch failed: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
