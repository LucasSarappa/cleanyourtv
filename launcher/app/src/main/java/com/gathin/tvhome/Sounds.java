package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * Home's own navigation sounds (config.json sounds): a soft glass tick when focus moves and a light two-note pop on
 * OK, made for this launcher (res/raw). They play on the same stream as the system's clicks; switch "Sonidos del
 * sistema" off in the TV's sound settings so the two do not play at once.
 */
final class Sounds {
    private final SoundPool pool;
    private final int tick;
    private final int select;
    private long lastTick;

    Sounds(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tick = pool.load(context, R.raw.nav_tick, 1);
        select = pool.load(context, R.raw.nav_select, 1);
    }

    /** Focus moved; a held key repeats fast, so ticks closer than 45 ms apart are skipped. */
    void tick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < 45) return;
        lastTick = now;
        pool.play(tick, 0.55f, 0.55f, 1, 0, 1f);
    }

    void select() {
        pool.play(select, 0.6f, 0.6f, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
