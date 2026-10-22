package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher's own settings, as a glass sheet at the right of Home (menu_style "side", the Apariencia pill):
 * greeting, dock style, dock apps, wallpaper and blur, Now playing, motion, rest delay and a way to the TV's own
 * Settings. Choices are saved as they are made (Appearance, Config.SETTINGS_NAME) and Home rebuilds with them when
 * the panel closes. Left or Back goes up a page, then closes.
 */
final class AppearancePanel {

    interface Host {
        /** The panel is gone; changed means Home has to rebuild with the new choices. */
        void onAppearanceClosed(boolean changed);

        void onEditDock();

        /** One of the TV's own settings screens, from the System section. */
        void onOpen(Intent intent);

        /** The network the TV Box is on, for the Wi-Fi row ("" when unknown). */
        String networkName();
    }

    private static final String[] REST_LABELS = {"30 s", "1 min", "2 min", "5 min", "Nunca"};

    private final Activity activity;
    private final FrameLayout root;
    private final Personal theme;
    private final String profileName;
    private final int dockCount;
    private final Host host;
    private final float density;
    private final SharedPreferences prefs;
    private View panel;
    private LinearLayout rows;
    private TextView kicker;
    private TextView title;
    private boolean changed;
    private boolean wallpaperPage;

    AppearancePanel(Activity activity, FrameLayout root, Personal theme, String profileName, int dockCount, Host host) {
        this.activity = activity;
        this.root = root;
        this.theme = theme;
        this.profileName = profileName;
        this.dockCount = dockCount;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        prefs = Appearance.prefs(activity);
    }

    boolean isOpen() {
        return panel != null;
    }

    void open() {
        if (panel != null) return;
        panel = activity.getLayoutInflater().inflate(R.layout.panel_appearance, root, false);
        rows = panel.findViewById(R.id.apRows);
        kicker = panel.findViewById(R.id.apKicker);
        title = panel.findViewById(R.id.apTitle);
        ((TextView) panel.findViewById(R.id.apProfile)).setText(profileName.isEmpty() ? "" : "Perfil " + profileName);
        // Full height between margins: the rows scroll inside it on a 540dp tall TV screen.
        // Explicit width: the layout's own 400dp is dropped once these params replace its root's.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (400 * density),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMarginEnd((int) (32 * density));
        lp.topMargin = (int) (24 * density);
        lp.bottomMargin = (int) (24 * density);
        root.addView(panel, lp);
        // Slide in from the right edge, like a sheet.
        panel.setAlpha(0f);
        panel.setTranslationX(48 * density);
        panel.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        showMain(0);
    }

    /** Back or Left: from a sub-page to the main page, from the main page out. */
    boolean back() {
        if (panel == null) return false;
        if (wallpaperPage) showMain(3);
        else close();
        return true;
    }

    void close() {
        if (panel == null) return;
        View closing = panel;
        panel = null;
        closing.animate().alpha(0f).translationX(36 * density).setDuration(180)
                .withEndAction(() -> root.removeView(closing)).start();
        host.onAppearanceClosed(changed);
    }

    // ---- pages -------------------------------------------------------------------------------------

    private void showMain(int focus) {
        wallpaperPage = false;
        Appearance look = Appearance.load(activity, theme);
        swap("TV BOX", "Ajustes", () -> {
            header("Apariencia");
            String initial = profileName.isEmpty() ? "★" : profileName.substring(0, 1).toUpperCase(Locale.ROOT);
            row(initial, profileName.isEmpty() ? "Perfil" : profileName,
                    "Perfil activo · saludo " + (look.greeting ? "visible" : "oculto"), "", () -> {
                        prefs.edit().putBoolean(Appearance.GREETING, !look.greeting).apply();
                        changed = true;
                        showMain(0);
                    });
            row("▦", "Pantalla de inicio", "Dock, reloj, fecha y saludo", dockLabel(look.dockStyle), () -> {
                String next = Appearance.FLOATING.equals(look.dockStyle) ? Appearance.GLASS
                        : Appearance.GLASS.equals(look.dockStyle) ? Appearance.BAND : Appearance.FLOATING;
                prefs.edit().putString(Appearance.DOCK_STYLE, next).apply();
                changed = true;
                showMain(1);
            });
            row("▭", "Apps del dock", "Elegí cuáles van en el dock", dockCount + " en el dock", () -> {
                close();
                host.onEditDock();
            });
            row("◩", "Fondo", "Wallpaper y desenfoque", wallpaperLabel(), () -> showWallpapers(0));
            row("♫", "Ahora suena", "Música en reproducción", look.nowPlaying ? "Sí" : "No", () -> {
                prefs.edit().putBoolean(Appearance.NOW_PLAYING, !look.nowPlaying).apply();
                changed = true;
                showMain(4);
            });
            boolean full = prefs.getBoolean("advanced_effects", true);
            row("✦", "Movimiento", "Efectos y animaciones", full ? "Completo" : "Sutil", () -> {
                prefs.edit().putBoolean("advanced_effects", !full).apply();
                changed = true;
                showMain(5);
            });
            row("☾", "Pantalla bloqueada", "Se bloquea sola tras un rato sin usar", restLabel(look.restSeconds), () -> {
                prefs.edit().putInt(Appearance.REST, nextRest(look.restSeconds)).apply();
                changed = true;
                showMain(6);
            });
            header("Sistema");
            String network = host.networkName();
            system("", "Wi‑Fi", "Redes e internet", network, new Intent(Settings.ACTION_WIFI_SETTINGS));
            system("▣", "Imagen", "Resolución y pantalla", "", new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            system("♪", "Sonido", "Salida de audio y sonidos del sistema", "", new Intent(Settings.ACTION_SOUND_SETTINGS));
            system("▤", "Apps", "Instaladas, permisos y espacio", "", new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            system("⚙", "Todos los ajustes", "Los ajustes completos de Android", "", new Intent(Settings.ACTION_SETTINGS));
        }, focus);
    }

    /** A row of the System section: opens that screen of the TV's own Settings. */
    private void system(String icon, String titleText, String hint, String value, Intent target) {
        row(icon.isEmpty() ? "◉" : icon, titleText, hint, value, () -> {
            close();
            host.onOpen(target);
        });
    }

    /** A section title between rows; never takes focus. */
    private void header(String text) {
        TextView view = new TextView(activity);
        view.setText(text.toUpperCase(Locale.ROOT));
        view.setTextColor(0x8CFFFFFF);
        view.setTextSize(10f);
        view.setLetterSpacing(0.22f);
        view.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.outfit_medium));
        view.setPadding((int) (12 * density), (int) (14 * density), 0, (int) (6 * density));
        view.setFocusable(false);
        rows.addView(view);
    }

    private void showWallpapers(int focus) {
        wallpaperPage = true;
        Appearance look = Appearance.load(activity, theme);
        String choice = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        File dir = Config.dir(activity);
        List<File> images = WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"));
        swap("AJUSTES", "Fondo", () -> {
            row("◐", "Desenfoque al navegar", "El fondo se suaviza con el dock arriba", look.wallpaperBlur ? "Sí" : "No", () -> {
                prefs.edit().putBoolean(Appearance.BLUR, !look.wallpaperBlur).apply();
                changed = true;
                showWallpapers(0);
            });
            row(choice == null ? "✓" : "◎", "Automático", "Según la hora, el día y el clima",
                    mark(choice == null), () -> {
                        prefs.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                        changed = true;
                        showWallpapers(1);
                    });
            for (int i = 0; i < images.size(); i++) {
                File image = images.get(i);
                int index = i + 2;
                boolean picked = image.getName().equals(choice);
                row(picked ? "✓" : "◩", pretty(image.getName()), "Siempre este fondo", mark(picked), () -> {
                    prefs.edit().putString(Config.WALLPAPER_CHOICE_KEY, image.getName()).apply();
                    changed = true;
                    showWallpapers(index);
                });
            }
        }, focus);
    }

    /** Replaces the rows with a quick cross-fade, then focuses row {@code focus}. */
    private void swap(String kickerText, String titleText, Runnable build, int focus) {
        kicker.setText(kickerText);
        title.setText(titleText);
        rows.removeAllViews();
        build.run();
        rows.setAlpha(0.4f);
        rows.animate().alpha(1f).setDuration(140).start();
        List<View> focusable = focusableRows();
        if (!focusable.isEmpty()) focusable.get(Math.max(0, Math.min(focus, focusable.size() - 1))).requestFocus();
    }

    /** The rows that take focus, in order (section titles left out). */
    private List<View> focusableRows() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).isFocusable()) out.add(rows.getChildAt(i));
        return out;
    }

    private void row(String icon, String titleText, String hint, String value, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_appearance, rows, false);
        ((TextView) row.findViewById(R.id.apIcon)).setText(icon);
        ((TextView) row.findViewById(R.id.apRowTitle)).setText(titleText);
        ((TextView) row.findViewById(R.id.apRowHint)).setText(hint);
        ((TextView) row.findViewById(R.id.apRowValue)).setText(value);
        row.setOnClickListener(v -> action.run());
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            List<View> focusable = focusableRows();
            int index = focusable.indexOf(v);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return back();
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // Row to row ourselves: section titles sit between them, and focus must never leave the panel.
                    if (index > 0) focusable.get(index - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (index >= 0 && index < focusable.size() - 1) focusable.get(index + 1).requestFocus();
                    return true;
                default:
                    return false;
            }
        });
        rows.addView(row);
    }

    // ---- labels ------------------------------------------------------------------------------------

    private static String dockLabel(String style) {
        if (Appearance.FLOATING.equals(style)) return "Íconos flotantes";
        if (Appearance.GLASS.equals(style)) return "Barra de vidrio";
        return "Barra oscura";
    }

    private String wallpaperLabel() {
        String choice = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (choice == null) return "Automático";
        return "none".equals(choice) ? "Ninguno" : pretty(choice);
    }

    private static String restLabel(int seconds) {
        for (int i = 0; i < Appearance.REST_STEPS.length; i++) if (Appearance.REST_STEPS[i] == seconds) return REST_LABELS[i];
        return seconds <= 0 ? "Nunca" : seconds + " s";
    }

    private static int nextRest(int seconds) {
        for (int i = 0; i < Appearance.REST_STEPS.length; i++) {
            if (Appearance.REST_STEPS[i] == seconds) return Appearance.REST_STEPS[(i + 1) % Appearance.REST_STEPS.length];
        }
        return Appearance.REST_STEPS[0];
    }

    private static String mark(boolean on) {
        return on ? "✓" : "";
    }

    /** "07-mirror-lake.jpg" as "Mirror lake". */
    static String pretty(String file) {
        String name = file.replaceFirst("\\.[A-Za-z0-9]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ').trim();
        if (name.isEmpty()) return file;
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
