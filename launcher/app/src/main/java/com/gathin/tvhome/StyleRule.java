package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One entry of config.json "styles": when it applies (days of the week, dates, a time span, the weather) and
 * what it changes (the wallpaper from the theme's wallpapers/ folder, the greeting). Conditions left out
 * match always; for each change the first rule that matches wins, so specific rules go above general ones:
 *
 * <pre>
 * {"nombre": "lluvia", "clima": ["lluvia", "tormenta"], "fondo": "12-monsoon.jpg"}
 * {"nombre": "navidad", "fechas": ["24/12", "25/12"], "saludo": "¡Feliz Navidad!"}
 * {"nombre": "noche finde", "dias": ["finde"], "desde": "20:00", "hasta": "06:00", "fondo": "10-city-lights.jpg"}
 * </pre>
 *
 * Keys are Spanish so the file stays easy to edit by hand; the English ones (days, dates, from, to,
 * weather, wallpaper, greeting) are accepted too.
 */
final class StyleRule {
    /** Only for whoever edits the file: which rule is which. */
    final String name;
    private final Set<DayOfWeek> days;
    private final Set<MonthDay> dates;
    /** Minutes since midnight, or -1 for any time. A span with from after to runs past midnight. */
    private final int from;
    private final int to;
    private final Set<String> weather;
    /** "fondo", or several in "fondos": they take turns, a different one each hour. Empty: no wallpaper change. */
    final List<String> wallpapers;
    final String greeting;

    private StyleRule(String name, Set<DayOfWeek> days, Set<MonthDay> dates, int from, int to, Set<String> weather,
                      List<String> wallpapers, String greeting) {
        this.name = name;
        this.days = days;
        this.dates = dates;
        this.from = from;
        this.to = to;
        this.weather = weather;
        this.wallpapers = wallpapers;
        this.greeting = greeting;
    }

    /** This hour's wallpaper of the rule: the list read round, one step per hour, so each day starts elsewhere. */
    String wallpaperFor(LocalDateTime now) {
        if (wallpapers.isEmpty()) return "";
        int step = now.getDayOfYear() * 24 + now.getHour();
        return wallpapers.get(step % wallpapers.size());
    }

    boolean matches(LocalDateTime now, String condition) {
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) return false;
        if (!dates.isEmpty() && !dates.contains(MonthDay.from(now))) return false;
        if (!weather.isEmpty() && (condition == null || !weather.contains(condition))) return false;
        if (from < 0 || to < 0) return true;
        int minute = now.getHour() * 60 + now.getMinute();
        return from <= to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    /**
     * The wallpaper of the first rule that applies now and sets one, or null. Wallpaper and greeting are looked up
     * on their own, so a date rule that only changes the greeting does not stop the time rules below it.
     */
    static String wallpaper(List<StyleRule> rules, LocalDateTime now, String condition) {
        for (StyleRule rule : rules) {
            if (!rule.wallpapers.isEmpty() && rule.matches(now, condition)) return rule.wallpaperFor(now);
        }
        return null;
    }

    /** The greeting of the first rule that applies now and sets one, or "" for the time-of-day greeting. */
    static String greeting(List<StyleRule> rules, LocalDateTime now, String condition) {
        for (StyleRule rule : rules) if (!rule.greeting.isEmpty() && rule.matches(now, condition)) return rule.greeting;
        return "";
    }

    static List<StyleRule> parseAll(JSONObject json) {
        JSONArray list = json == null ? null : json.optJSONArray("styles");
        if (list == null && json != null) list = json.optJSONArray("estilos");
        if (list == null) return Collections.emptyList();
        List<StyleRule> rules = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            StyleRule rule = parse(list.optJSONObject(i));
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    /** A rule that changes nothing, or whose times or days do not parse, is left out on its own. */
    private static StyleRule parse(JSONObject o) {
        if (o == null) return null;
        List<String> wallpapers = strings(o, "fondos", "wallpapers");
        String single = text(o, "fondo", "wallpaper");
        if (wallpapers.isEmpty() && !single.isEmpty()) wallpapers.add(single);
        String greeting = text(o, "saludo", "greeting");
        if (wallpapers.isEmpty() && greeting.isEmpty()) return null;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : strings(o, "dias", "days")) {
            Set<DayOfWeek> parsed = days(day);
            if (parsed == null) return null;
            days.addAll(parsed);
        }
        Set<MonthDay> dates = new HashSet<>();
        for (String date : strings(o, "fechas", "dates")) {
            String[] parts = date.trim().split("/");
            try {
                dates.add(MonthDay.of(Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[0].trim())));
            } catch (RuntimeException e) {
                return null;   // "25/12" is day/month; anything else would silently match the wrong day
            }
        }
        String fromText = text(o, "desde", "from"), toText = text(o, "hasta", "to");
        int from = fromText.isEmpty() ? -1 : minutes(fromText), to = toText.isEmpty() ? -1 : minutes(toText);
        if ((!fromText.isEmpty() && from < 0) || (!toText.isEmpty() && to < 0) || (from < 0) != (to < 0)) return null;
        Set<String> weather = new HashSet<>();
        for (String w : strings(o, "clima", "weather")) weather.add(plain(w));
        return new StyleRule(text(o, "nombre", "name"), days, dates, from, to, weather, wallpapers, greeting);
    }

    private static Set<DayOfWeek> days(String day) {
        switch (plain(day)) {
            case "lun": case "lunes": case "mon": return EnumSet.of(DayOfWeek.MONDAY);
            case "mar": case "martes": case "tue": return EnumSet.of(DayOfWeek.TUESDAY);
            case "mie": case "miercoles": case "wed": return EnumSet.of(DayOfWeek.WEDNESDAY);
            case "jue": case "jueves": case "thu": return EnumSet.of(DayOfWeek.THURSDAY);
            case "vie": case "viernes": case "fri": return EnumSet.of(DayOfWeek.FRIDAY);
            case "sab": case "sabado": case "sat": return EnumSet.of(DayOfWeek.SATURDAY);
            case "dom": case "domingo": case "sun": return EnumSet.of(DayOfWeek.SUNDAY);
            case "finde": case "weekend": return EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            case "semana": case "weekdays": return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            default: return null;
        }
    }

    /** "06:30" as 390; -1 when it does not parse. */
    private static int minutes(String time) {
        String[] parts = time.trim().split(":");
        try {
            int h = Integer.parseInt(parts[0]), m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return h >= 0 && h <= 24 && m >= 0 && m < 60 ? Math.min(h * 60 + m, 24 * 60) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Lower case without accents, so "Sábado" and "sabado" are the same day. */
    private static String plain(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String text(JSONObject o, String key, String alias) {
        return o.optString(o.has(key) ? key : alias, "").trim();
    }

    private static List<String> strings(JSONObject o, String key, String alias) {
        JSONArray a = o.optJSONArray(o.has(key) ? key : alias);
        List<String> out = new ArrayList<>();
        if (a == null) {
            String single = text(o, key, alias);
            if (!single.isEmpty()) out.add(single);
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
