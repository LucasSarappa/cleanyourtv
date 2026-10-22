package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Choices made in the Appearance panel on the TV, over the theme's values in Personal. They live with the other
 * on-TV choices (Config.SETTINGS_NAME), so pushing a theme or resetting it clears them too: config.json wins again.
 */
final class Appearance {
    static final String DOCK_STYLE = "ap_dock_style";
    static final String NOW_PLAYING = "ap_now_playing";
    static final String BLUR = "ap_blur";
    static final String REST = "ap_rest_seconds";
    static final String GREETING = "ap_greeting";
    /** The dock's apps as chosen on the TV (the "+" tile and the dock menu), comma separated; over dock_apps. */
    static final String DOCK_APPS = "ap_dock_apps";
    static final String[] KEYS = {DOCK_STYLE, NOW_PLAYING, BLUR, REST, GREETING, DOCK_APPS};

    static final String FLOATING = "floating";
    static final String GLASS = "glass";
    static final String BAND = "band";
    /** The rest delays the panel cycles through, in seconds; 0 is never. */
    static final int[] REST_STEPS = {30, 60, 120, 300, 0};

    /** floating (no band), glass (frosted band) or band (the dark tvOS band). */
    final String dockStyle;
    final boolean nowPlaying;
    final boolean wallpaperBlur;
    final int restSeconds;
    final boolean greeting;

    private Appearance(String dockStyle, boolean nowPlaying, boolean wallpaperBlur, int restSeconds, boolean greeting) {
        this.dockStyle = dockStyle;
        this.nowPlaying = nowPlaying;
        this.wallpaperBlur = wallpaperBlur;
        this.restSeconds = restSeconds;
        this.greeting = greeting;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static Appearance load(Context context, Personal theme) {
        SharedPreferences p = prefs(context);
        String themeDock = !theme.dockBand ? FLOATING : theme.dockGlass ? GLASS : BAND;
        String dock = p.getString(DOCK_STYLE, themeDock);
        if (!FLOATING.equals(dock) && !GLASS.equals(dock) && !BAND.equals(dock)) dock = themeDock;
        return new Appearance(dock, p.getBoolean(NOW_PLAYING, theme.nowPlaying), p.getBoolean(BLUR, theme.wallpaperBlur),
                Math.max(0, p.getInt(REST, theme.restSeconds)), p.getBoolean(GREETING, true));
    }

    static void clear(SharedPreferences.Editor editor) {
        for (String key : KEYS) editor.remove(key);
    }
}
