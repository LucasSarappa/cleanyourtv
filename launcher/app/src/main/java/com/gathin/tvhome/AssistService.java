package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Match autopilot: after a match is picked in the football list, for 25 seconds only, it does the steps one would do
 * by hand in that app: Flow, scroll its guide to the programme with both teams and open it; Disney+, pick the
 * profile, open "En vivo" and the match. The service can only read Flow's and Disney+'s screens
 * (res/xml/assist_service.xml), and does nothing in them outside that window.
 *
 * (It also tried to replace the volume bar. On this Google TV the system handles the volume keys before any
 * accessibility service sees them, so that cannot work without system rights and was taken out.)
 *
 * Enabled over ADB: {@code settings put secure enabled_accessibility_services com.gathin.tvhome/.AssistService}.
 */
public class AssistService extends AccessibilityService {
    private static final String TAG = "TvHome.Assist";
    private static final String FLOW = "ar.com.flow.androidtv";
    private static final String DISNEY = "com.disney.disneyplus";
    private static final long TASK_MS = 25_000L;
    private static final int MAX_SCROLLS = 45;

    /** What to do in the app just opened: find the match whose two teams are given. */
    private static final class Task {
        final String pkg;
        final String profile;
        final List<String> home;
        final List<String> away;
        final long until;
        int stage;
        int scrolls;

        Task(String pkg, String profile, List<String> home, List<String> away) {
            this.pkg = pkg;
            this.profile = profile;
            this.home = home;
            this.away = away;
            until = SystemClock.uptimeMillis() + TASK_MS;
        }
    }

    private static volatile Task task;
    private static AssistService running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::step;

    static boolean isRunning() {
        return running != null;
    }

    /** Starts the autopilot for a match just opened in {@code pkg} (Flow or Disney+; other apps are left alone). */
    static void begin(String pkg, String profile, Football.Match match) {
        if (!FLOW.equals(pkg) && !DISNEY.equals(pkg)) return;
        task = new Task(pkg, profile, words(match.home, match.homeFull), words(match.away, match.awayFull));
        AssistService service = running;
        if (service != null) {
            service.handler.removeCallbacks(service.step);
            service.handler.postDelayed(service.step, 2500);
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Task t = task;
        if (t == null || event.getPackageName() == null || !t.pkg.contentEquals(event.getPackageName())) return;
        // Content changes come in bursts while a screen draws: act once it settles.
        handler.removeCallbacks(step);
        handler.postDelayed(step, 450);
    }

    @Override
    public void onInterrupt() {
    }

    // ---- autopilot ---------------------------------------------------------------------------------

    private void step() {
        Task t = task;
        if (t == null) return;
        if (SystemClock.uptimeMillis() > t.until) {
            Log.i(TAG, "Gave up finding the match in " + t.pkg);
            task = null;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !t.pkg.contentEquals(root.getPackageName())) {
            retry();
            return;
        }
        boolean done = FLOW.equals(t.pkg) ? stepFlow(t, root) : stepDisney(t, root);
        if (done) task = null;
        else retry();
    }

    private void retry() {
        handler.removeCallbacks(step);
        handler.postDelayed(step, 800);
    }

    /** Flow: its guide lists channels by number, each with its programmes; the match is a programme title. */
    private boolean stepFlow(Task t, AccessibilityNodeInfo root) {
        if (t.stage == 1) {
            // Some programmes open a detail first: its "Ver" button starts the channel.
            AccessibilityNodeInfo watch = find(root, n -> "Ver".contentEquals(text(n)));
            if (watch != null) click(watch);
            return true;
        }
        if (find(root, n -> id(n).endsWith("channelNumberEpg")) == null) return false;   // guide not up yet
        AccessibilityNodeInfo programme = find(root, n -> matches(t, text(n)));
        if (programme != null) {
            Log.i(TAG, "Found the match in Flow's guide: " + text(programme));
            click(programme);
            t.stage = 1;
            return false;
        }
        if (t.scrolls++ >= MAX_SCROLLS) return true;
        AccessibilityNodeInfo list = find(root, AccessibilityNodeInfo::isScrollable);
        if (list != null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return false;
    }

    /** Disney+: "¿Quién anda ahí?" → the profile, then "En vivo" in the menu, then the match's tile. */
    private boolean stepDisney(Task t, AccessibilityNodeInfo root) {
        String profile = "perfil de " + plain(t.profile);
        AccessibilityNodeInfo who = find(root, n -> plain(desc(n)).contains(profile) && plain(desc(n)).startsWith("acceder"));
        if (who != null) {
            click(who);
            return false;
        }
        AccessibilityNodeInfo ok = find(root, n -> "ACEPTAR".contentEquals(text(n)));
        if (ok != null) {
            click(ok);
            return false;
        }
        if (t.stage == 0) {
            AccessibilityNodeInfo live = find(root, n -> desc(n).startsWith("En vivo"));
            if (live == null) return false;
            // Disney+'s side menu ignores accessibility clicks; a tap on the item works. A tap while it is still
            // loading its home is lost too, so only go on once "En vivo" really is the selected section.
            if (live.isSelected()) t.stage = 1;
            else tap(live);
            return false;
        }
        AccessibilityNodeInfo tile = find(root, n -> matches(t, desc(n)) || matches(t, text(n)));
        if (tile == null) return false;
        Log.i(TAG, "Found the match in Disney+");
        tap(tile);   // like its menu, Disney+'s tiles answer taps, not accessibility clicks
        return true;
    }

    // ---- node helpers ------------------------------------------------------------------------------

    private interface Test {
        boolean test(AccessibilityNodeInfo node);
    }

    /** Breadth first, so the outer (and usually clickable) node of a tile is found before its text. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Test test) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 4000) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            if (test.test(n)) return n;
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return null;
    }

    /** Clicks the node, or the nearest ancestor that can be clicked; a tap when nothing takes the click. */
    private void click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        tap(node);
    }

    /** A short tap on the middle of the node, for apps whose views ignore ACTION_CLICK. */
    private void tap(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;
        Path at = new Path();
        at.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(at, 0, 60)).build(), null, null);
    }

    private static String text(AccessibilityNodeInfo n) {
        return n.getText() == null ? "" : n.getText().toString();
    }

    private static String desc(AccessibilityNodeInfo n) {
        return n.getContentDescription() == null ? "" : n.getContentDescription().toString();
    }

    private static String id(AccessibilityNodeInfo n) {
        return n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
    }

    /** A title names the match when it has a word of each team ("Boca Jrs. vs. River" for Boca Juniors – River Plate). */
    private static boolean matches(Task t, String s) {
        if (s.isEmpty()) return false;
        String p = plain(s);
        return hasAny(p, t.home) && hasAny(p, t.away);
    }

    private static boolean hasAny(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /** The words of a team's names worth matching: four letters or more, so "FC" or "Man" never match alone. */
    private static List<String> words(String... names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (name == null) continue;
            for (String w : plain(name).split("[^a-z0-9]+")) {
                if (w.length() >= 4 && !out.contains(w) && !w.equals("club") && !w.equals("atletico")
                        && !w.equals("deportivo") && !w.equals("sporting")) out.add(w);
            }
        }
        return out;
    }

    private static String plain(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
