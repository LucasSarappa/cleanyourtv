package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * The Now playing card (config.json now_playing): the media session that is playing, or the last paused one, with
 * its artwork, title, artist and progress. OK plays or pauses it; holding OK opens the app. Hidden when nothing is
 * playing, and when the notification listener (MediaListener) has not been allowed, which getActiveSessions needs.
 */
final class NowPlaying implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final String TAG = "TvHome.NowPlaying";

    interface Listener {
        /** The card was shown or hidden. */
        void onVisibility(boolean shown);

        /** Title, artwork or play state changed (the player panel follows it). */
        default void onChanged() { }

        /** OK on the card: open the player panel. */
        default void onOpenPlayer() { }
    }

    private final Context context;
    private final View card;
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final ProgressBar progress;
    private final TextView button;
    private final EqualizerView equalizer;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private final MediaSessionManager sessions;
    private MediaController controller;
    private boolean started;
    private Bitmap shownArt;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            render();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    /** Moves the progress bar once a second while something plays and Home is on screen. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (started && isPlaying()) handler.postDelayed(this, 1000);
        }
    };

    NowPlaying(Context context, View card, Listener listener) {
        this.context = context;
        this.card = card;
        this.listener = listener;
        art = card.findViewById(R.id.npArt);
        title = card.findViewById(R.id.npTitle);
        artist = card.findViewById(R.id.npArtist);
        progress = card.findViewById(R.id.npProgress);
        button = card.findViewById(R.id.npButton);
        art.setClipToOutline(true);
        equalizer = card.findViewById(R.id.npEqualizer);
        component = new ComponentName(context, MediaListener.class);
        sessions = context.getSystemService(MediaSessionManager.class);
        card.setOnClickListener(v -> listener.onOpenPlayer());
        card.setOnLongClickListener(v -> {
            openApp();
            return true;
        });
    }

    void start() {
        started = true;
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(this, component, handler);
            onActiveSessionsChanged(sessions.getActiveSessions(component));
        } catch (SecurityException e) {
            // The listener has not been allowed yet: no card, and nothing else changes.
            Log.i(TAG, "Notification listener not allowed; Now playing stays hidden");
            setShown(false);
        }
    }

    void stop() {
        started = false;
        handler.removeCallbacks(tick);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        MediaController playing = null, paused = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                PlaybackState s = c.getPlaybackState();
                if (s == null) continue;
                if (s.getState() == PlaybackState.STATE_PLAYING && playing == null) playing = c;
                else if (s.getState() == PlaybackState.STATE_PAUSED && paused == null) paused = c;
            }
        }
        pick(playing != null ? playing : paused);
    }

    private void pick(MediaController next) {
        if (controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken())) {
            render();
            return;
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = next;
        if (controller != null) controller.registerCallback(callback, handler);
        render();
    }

    private boolean isPlaying() {
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private void render() {
        MediaMetadata m = controller != null ? controller.getMetadata() : null;
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        boolean live = s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_PAUSED
                || s.getState() == PlaybackState.STATE_BUFFERING);
        CharSequence name = m == null ? null : first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (!live || TextUtils.isEmpty(name)) {
            setShown(false);
            return;
        }
        title.setText(name);
        CharSequence by = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        artist.setText(TextUtils.isEmpty(by) ? appLabel() : by);
        Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (cover != shownArt) {
            shownArt = cover;
            art.setImageBitmap(cover);
        }
        button.setText(isPlaying() ? "❚❚" : "▶");
        equalizer.setPlaying(isPlaying());
        updateProgress();
        setShown(true);
        listener.onChanged();
        handler.removeCallbacks(tick);
        if (started && isPlaying()) handler.postDelayed(tick, 1000);
    }

    private void updateProgress() {
        MediaMetadata m = controller != null ? controller.getMetadata() : null;
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (s == null || duration <= 0) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress((int) Math.max(0, Math.min(1000, position * 1000 / duration)));
    }

    // ---- for the player panel ----------------------------------------------------------------------

    boolean active() {
        return controller != null && card.getVisibility() == View.VISIBLE;
    }

    boolean playing() {
        return isPlaying();
    }

    CharSequence title() {
        return title.getText();
    }

    CharSequence artist() {
        return artist.getText();
    }

    Bitmap art() {
        return shownArt;
    }

    /** Where the track is now, in ms, counting the time since the app last reported it while playing. */
    long position() {
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        if (s == null) return 0;
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    long duration() {
        MediaMetadata m = controller != null ? controller.getMetadata() : null;
        return m == null ? 0 : Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    void toggle() {
        if (controller == null) return;
        if (isPlaying()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    void seekTo(long ms) {
        if (controller != null) controller.getTransportControls().seekTo(Math.max(0, ms));
    }

    void openApp() {
        if (controller == null) return;
        PendingIntent activity = controller.getSessionActivity();
        try {
            if (activity != null) {
                activity.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
            // fall back to the app's start screen
        }
        Intent launch = context.getPackageManager().getLeanbackLaunchIntentForPackage(controller.getPackageName());
        if (launch == null) launch = context.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private CharSequence appLabel() {
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(controller.getPackageName(), 0));
        } catch (Exception e) {
            return "";
        }
    }

    private void setShown(boolean shown) {
        if ((card.getVisibility() == View.VISIBLE) == shown) return;
        if (!shown) {
            handler.removeCallbacks(tick);
            shownArt = null;
            art.setImageBitmap(null);
        }
        card.setVisibility(shown ? View.VISIBLE : View.GONE);
        listener.onVisibility(shown);
    }

    private static CharSequence first(MediaMetadata m, String key, String fallback) {
        CharSequence text = m.getText(key);
        return TextUtils.isEmpty(text) ? m.getText(fallback) : text;
    }
}
