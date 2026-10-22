package com.gathin.tvhome;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * This TV's additions to the theme, all from config.json: the weather pill (weather_latitude,
 * weather_longitude, weather_place, weather_refresh_minutes), the style rules ("styles", see StyleRule),
 * the glass dock band (dock_glass), the large centred clock (clock_style "center") and the football pill
 * ("futbol": "equipo" and "ligas", see Football). The look of the reference video comes from menu_style "side"
 * (the top-bar buttons as a column at the right that shows only while focused), dock_band false (floating tiles),
 * dock_reflection, wallpaper_blur (the wallpaper softens while the dock is up), rest_seconds (after that long
 * without a key only the clock and the sharp wallpaper stay) and now_playing. The Appearance panel can change
 * several of these on the TV (see Appearance). Without these keys the launcher looks exactly as before.
 */
final class Personal {
    static final Personal NONE = new Personal(Double.NaN, Double.NaN, "", 30, Collections.emptyList(), false, false,
            "", Collections.emptyList(), false, true, false, false, 0, false, false, AppAdapter.BOTTOM_ROOM_DP, "",
            false);

    final double weatherLatitude;
    final double weatherLongitude;
    /** Shown after the reading in the pill ("Palermo"); empty for none. */
    final String weatherPlace;
    final int weatherRefreshMinutes;
    final List<StyleRule> styles;
    final boolean dockGlass;
    final boolean centeredClock;
    /** The team whose matches go first and fill the pill ("Boca"); empty for none. */
    final String footballTeam;
    /** Empty hides the football pill. */
    final List<Football.League> footballLeagues;
    final boolean sideMenu;
    /** false: no band behind the dock, the tiles float over the wallpaper. */
    final boolean dockBand;
    final boolean dockReflection;
    final boolean wallpaperBlur;
    /** Seconds without a key before Home rests (only clock and wallpaper); 0 never. */
    final int restSeconds;
    final boolean nowPlaying;
    /** dock_plus: a "+" tile in the first free dock slot; OK held on a dock app opens its menu. */
    final boolean dockPlus;
    /** dock_bottom_dp: room under the dock row, in dp; smaller parks the dock lower. */
    final float dockBottomDp;
    /** futbol.perfil: the streaming profile AssistService picks (Disney+'s "¿Quién anda ahí?"). */
    final String footballProfile;
    /** sounds: Home's own navigation sounds (Sounds). */
    final boolean sounds;

    private Personal(double weatherLatitude, double weatherLongitude, String weatherPlace, int weatherRefreshMinutes,
                     List<StyleRule> styles, boolean dockGlass, boolean centeredClock, String footballTeam,
                     List<Football.League> footballLeagues, boolean sideMenu, boolean dockBand, boolean dockReflection,
                     boolean wallpaperBlur, int restSeconds, boolean nowPlaying, boolean dockPlus, float dockBottomDp,
                     String footballProfile, boolean sounds) {
        this.sounds = sounds;
        this.footballProfile = footballProfile;
        this.dockPlus = dockPlus;
        this.dockBottomDp = dockBottomDp;
        this.sideMenu = sideMenu;
        this.dockBand = dockBand;
        this.dockReflection = dockReflection;
        this.wallpaperBlur = wallpaperBlur;
        this.restSeconds = restSeconds;
        this.nowPlaying = nowPlaying;
        this.weatherLatitude = weatherLatitude;
        this.weatherLongitude = weatherLongitude;
        this.weatherPlace = weatherPlace;
        this.weatherRefreshMinutes = weatherRefreshMinutes;
        this.styles = Collections.unmodifiableList(styles);
        this.dockGlass = dockGlass;
        this.centeredClock = centeredClock;
        this.footballTeam = footballTeam;
        this.footballLeagues = Collections.unmodifiableList(footballLeagues);
    }

    boolean weatherEnabled() {
        return !Double.isNaN(weatherLatitude) && !Double.isNaN(weatherLongitude);
    }

    boolean footballEnabled() {
        return !footballLeagues.isEmpty();
    }

    static Personal parse(JSONObject json) {
        if (json == null) return NONE;
        double lat = json.optDouble("weather_latitude", Double.NaN);
        double lon = json.optDouble("weather_longitude", Double.NaN);
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) lat = lon = Double.NaN;
        int refresh = Math.max(10, Math.min(360, json.optInt("weather_refresh_minutes", 30)));
        JSONObject football = json.optJSONObject("futbol");
        return new Personal(lat, lon, json.optString("weather_place", "").trim(), refresh,
                StyleRule.parseAll(json), json.optBoolean("dock_glass", false),
                "center".equals(json.optString("clock_style", "")),
                football == null ? "" : football.optString("equipo", "").trim(),
                football == null ? Collections.emptyList() : Football.League.parseAll(football.optJSONArray("ligas")),
                "side".equals(json.optString("menu_style", "")), json.optBoolean("dock_band", true),
                json.optBoolean("dock_reflection", false), json.optBoolean("wallpaper_blur", false),
                Math.max(0, Math.min(3600, json.optInt("rest_seconds", 0))), json.optBoolean("now_playing", false),
                json.optBoolean("dock_plus", false),
                (float) Math.max(-60, Math.min(120, json.optDouble("dock_bottom_dp", AppAdapter.BOTTOM_ROOM_DP))),
                football == null ? "" : football.optString("perfil", "").trim(), json.optBoolean("sounds", false));
    }
}
