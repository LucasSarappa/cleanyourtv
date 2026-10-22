package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Today's matches of the leagues in config.json "futbol", from ESPN's public scoreboard (no key, not an
 * official API: if ESPN changes it the pill simply stays hidden). ESPN only lists US broadcasters, so the channel
 * and the app to open come from the theme, one per league. Fetched in the background: every minute while a
 * match is live, every ten minutes otherwise.
 */
final class Football {
    private static final String TAG = "TvHome.Football";
    private static final long LIVE_MS = 60_000L;
    private static final long IDLE_MS = 10 * 60_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** An app a competition can be watched in, with the deep link it opens with (empty: its start screen). */
    static final class Option {
        final String app;
        final String link;

        Option(String app, String link) {
            this.app = app;
            this.link = link;
        }
    }

    /** One competition: ESPN's id (arg.1, uefa.champions…) plus where it is shown in Argentina. */
    static final class League {
        final String id;
        final String name;
        final String channel;
        /** The apps it can be watched in ("opciones", or the older single "app"/"link"); the list asks which. */
        final List<Option> options;
        /** Only the favourite team's matches (cups such as the Copa Argentina). */
        final boolean teamOnly;
        /** The competition's accent in the list (theme "color"), a calm default per known league otherwise. */
        final int color;

        League(String id, String name, String channel, List<Option> options, boolean teamOnly, int color) {
            this.id = id;
            this.name = name;
            this.channel = channel;
            this.options = options;
            this.teamOnly = teamOnly;
            this.color = color;
        }

        /** Muted accents taken from each competition's own colours, light enough to read on dark glass. */
        static int defaultColor(String id) {
            switch (id) {
                case "arg.1": return 0xFF74B9F0;                  // celeste
                case "arg.copa": return 0xFF5BC0EB;
                case "conmebol.libertadores": return 0xFFE2B84E;  // gold
                case "conmebol.sudamericana": return 0xFF4FA3F7;
                case "uefa.champions": return 0xFF7C8CFF;         // night blue
                case "eng.1": return 0xFFC08BFF;                  // Premier purple
                case "esp.1": return 0xFFFF6B5E;                  // LaLiga coral
                case "ita.1": return 0xFF3CC8E8;
                case "ger.1": return 0xFFFF5A5A;
                case "fra.1": return 0xFFE4E15A;
                default: return 0xFFBFC7D5;
            }
        }

        static List<League> parseAll(JSONArray list) {
            List<League> leagues = new ArrayList<>();
            if (list == null) return leagues;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                if (!id.matches("[a-z0-9_.]+")) continue;   // pasted into the URL below
                int color = League.defaultColor(id);
                String hex = o.optString("color", "").trim();
                if (hex.matches("#[0-9A-Fa-f]{6}")) color = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
                List<Option> options = new ArrayList<>();
                JSONArray choices = o.optJSONArray("opciones");
                for (int j = 0; choices != null && j < choices.length(); j++) {
                    JSONObject c = choices.optJSONObject(j);
                    String app = c == null ? "" : c.optString("app", "").trim();
                    if (!app.isEmpty()) options.add(new Option(app, c.optString("link", "").trim()));
                }
                String single = o.optString("app", "").trim();
                if (options.isEmpty() && !single.isEmpty()) options.add(new Option(single, o.optString("link", "").trim()));
                leagues.add(new League(id, o.optString("nombre", id).trim(), o.optString("canal", "").trim(),
                        options, o.optBoolean("solo_equipo", false), color));
            }
            return leagues;
        }
    }

    /** A goal: who scored it, the minute ("61'"), for which side, and whether it was a penalty or an own goal. */
    static final class Goal {
        final String player;
        final String minute;
        final boolean home;
        final boolean penalty;
        final boolean ownGoal;

        Goal(String player, String minute, boolean home, boolean penalty, boolean ownGoal) {
            this.player = player;
            this.minute = minute;
            this.home = home;
            this.penalty = penalty;
            this.ownGoal = ownGoal;
        }

        /** "Kane 61' (p)": the surname keeps the line short. */
        String text() {
            String[] parts = player.trim().split("\\s+");
            String name = parts.length > 1 ? parts[parts.length - 1] : player;
            return name + " " + minute + (penalty ? " (p)" : ownGoal ? " (ec)" : "");
        }
    }

    static final class Match {
        final League league;
        final String home;
        final String away;
        final String homeScore;
        final String awayScore;
        /** ESPN's state: pre, in or post. */
        final String state;
        /** "63'", "90'+3'" or "HT" while live. */
        final String clock;
        final Instant start;
        final boolean favorite;
        final String homeLogo;
        final String awayLogo;
        /** Full names ("Boca Juniors"), for finding the match in another app's guide. */
        final String homeFull;
        final String awayFull;
        final List<Goal> goals;
        /** ESPN's short detail after the end: "FT", "AET" (extra time) or "FT-Pens". */
        final String detail;

        Match(League league, String home, String away, String homeScore, String awayScore, String state,
              String clock, Instant start, boolean favorite, String homeLogo, String awayLogo, String homeFull,
              String awayFull, List<Goal> goals, String detail) {
            this.homeFull = homeFull;
            this.awayFull = awayFull;
            this.league = league;
            this.home = home;
            this.away = away;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.state = state;
            this.clock = clock;
            this.start = start;
            this.favorite = favorite;
            this.homeLogo = homeLogo;
            this.awayLogo = awayLogo;
            this.goals = goals;
            this.detail = detail;
        }

        boolean upcoming() {
            return "pre".equals(state);
        }

        /** "Davies 35', Kane 61' (p)" for one side; empty without goals. */
        String scorers(boolean homeSide) {
            StringBuilder out = new StringBuilder();
            for (Goal g : goals) {
                if (g.home != homeSide) continue;
                if (out.length() > 0) out.append(", ");
                out.append(g.text());
            }
            return out.toString();
        }

        boolean live() {
            return "in".equals(state);
        }

        boolean finished() {
            return "post".equals(state);
        }

        /** "Boca 1 – 0 Racing" once it started, "Boca vs Racing" before. */
        String teams() {
            return "pre".equals(state) ? home + "  vs  " + away : home + "  " + homeScore + " – " + awayScore + "  " + away;
        }

        /** "63'" or "90'+3'" live, "Entretiempo" at half time, "Final" after, "21:30" before. */
        String status() {
            if (live()) return "HT".equals(clock) ? "Entretiempo" : clock.isEmpty() ? "En vivo" : clock;
            if (finished()) {
                if (detail.contains("Pens")) return "Final · penales";
                if (detail.contains("AET")) return "Final · alargue";
                return "Final";
            }
            return TIME.format(start.atZone(ZoneId.systemDefault()));
        }
    }

    interface Listener {
        void onMatches(List<Match> matches);
    }

    private final List<League> leagues;
    private final String team;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Match> matches = Collections.emptyList();
    private long lastFetch;
    /** The day the list on screen is for; null before the first fetch. */
    private LocalDate fetchedDay;
    private boolean loading;
    private boolean closed;

    Football(Personal personal, Listener listener) {
        leagues = personal.footballLeagues;
        team = plain(personal.footballTeam);
        this.listener = listener;
    }

    /** Live, upcoming, finished (the favourite's first in each), by kick-off; empty before the first fetch. */
    List<Match> matches() {
        return matches;
    }

    /** The favourite team's match today, or null. */
    Match favorite() {
        for (Match m : matches) if (m.favorite) return m;
        return null;
    }

    int liveCount() {
        int n = 0;
        for (Match m : matches) if (m.live()) n++;
        return n;
    }

    /**
     * Fetches again when due; force (the list was opened) refreshes whatever the interval. A new day always fetches
     * at once: yesterday's list must not wait out the ten minutes, nor stay up on a day the fetch fails.
     */
    void update(boolean force) {
        long now = System.currentTimeMillis();
        if (closed || loading) return;
        LocalDate today = LocalDate.now();
        boolean newDay = !today.equals(fetchedDay);
        long interval = liveCount() > 0 ? LIVE_MS : IDLE_MS;
        if (!newDay && !force && lastFetch != 0 && now - lastFetch < interval) return;
        if (!newDay && force && now - lastFetch < 20_000L) return;   // opened twice in a row: fresh enough
        if (newDay && fetchedDay != null && !matches.isEmpty()) {
            // Drop yesterday's list right away; today's arrives with the fetch below.
            matches = Collections.emptyList();
            listener.onMatches(matches);
        }
        lastFetch = now;
        loading = true;
        worker.execute(() -> {
            List<Match> fetched = null;
            try {
                fetched = fetchAll();
            } catch (RuntimeException e) {
                Log.w(TAG, "Fetch failed: " + e);
            }
            List<Match> result = fetched;
            // Always posted, whatever happened above: a lost post would leave loading set and stop every update.
            main.post(() -> {
                loading = false;
                if (closed || result == null) return;
                fetchedDay = today;
                matches = result;
                listener.onMatches(result);
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    /** Null when every league failed (no network), so the last list stays up instead of emptying. */
    private List<Match> fetchAll() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        // ESPN files matches under their US Eastern date and takes one date per request (a range is a 400). Our
        // date covers every kick-off from about 2 am on; until then it is still yesterday in New York, so yesterday's
        // list is read too, for the late matches still on. Asking for New York's date (as before) asked for
        // yesterday all through the first hours of the day, and the list stayed on the day before.
        List<String> days = new ArrayList<>();
        days.add(DAY.format(today));
        if (LocalTime.now(zone).getHour() < 4) days.add(DAY.format(today.minusDays(1)));
        List<Match> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean any = false;
        for (League league : leagues) {
            for (String day : days) {
                String body = get("https://site.api.espn.com/apis/site/v2/sports/soccer/" + league.id
                        + "/scoreboard?dates=" + day);
                if (body == null) continue;
                any = true;
                try {
                    JSONArray events = new JSONObject(body).optJSONArray("events");
                    for (int i = 0; events != null && i < events.length(); i++) {
                        JSONObject event = events.getJSONObject(i);
                        if (!seen.add(league.id + ":" + event.optString("id"))) continue;
                        Match m = parse(league, event);
                        if (m == null) continue;
                        if (!m.live() && !m.start.atZone(zone).toLocalDate().equals(today)) continue;
                        if (league.teamOnly && !m.favorite) continue;
                        all.add(m);
                    }
                } catch (Exception e) {
                    Log.w(TAG, league.id + ": " + e);
                }
            }
        }
        if (!any) return null;
        Collections.sort(all, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.start.compareTo(b.start);
        });
        return all;
    }

    /** Live, then upcoming, then finished, the favourite first within each, so the list reads as three groups. */
    private static int rank(Match m) {
        int group = m.live() ? 0 : m.upcoming() ? 2 : 4;
        return m.favorite ? group : group + 1;
    }

    private Match parse(League league, JSONObject event) throws Exception {
        JSONObject competition = event.getJSONArray("competitions").getJSONObject(0);
        JSONArray competitors = competition.getJSONArray("competitors");
        String home = "", away = "", homeScore = "", awayScore = "", homeLogo = "", awayLogo = "", homeId = "";
        String homeFull = "", awayFull = "";
        boolean favorite = false;
        for (int i = 0; i < competitors.length(); i++) {
            JSONObject c = competitors.getJSONObject(i);
            JSONObject t = c.getJSONObject("team");
            String name = t.optString("shortDisplayName", t.optString("displayName", "?"));
            if (!team.isEmpty() && (plain(t.optString("displayName", "")).contains(team) || plain(name).contains(team))) {
                favorite = true;
            }
            if ("home".equals(c.optString("homeAway"))) {
                home = name;
                homeScore = c.optString("score", "0");
                homeLogo = t.optString("logo", "");
                homeId = t.optString("id", "");
                homeFull = t.optString("displayName", name);
            } else {
                away = name;
                awayScore = c.optString("score", "0");
                awayLogo = t.optString("logo", "");
                awayFull = t.optString("displayName", name);
            }
        }
        // Goals from the match's events: ESPN lists cards and subs there too, only scoring plays count.
        List<Goal> goals = new ArrayList<>();
        JSONArray details = competition.optJSONArray("details");
        for (int i = 0; details != null && i < details.length(); i++) {
            JSONObject d = details.optJSONObject(i);
            if (d == null || !d.optBoolean("scoringPlay", false)) continue;
            JSONArray who = d.optJSONArray("athletesInvolved");
            String player = who != null && who.length() > 0 ? who.getJSONObject(0).optString("displayName", "") : "";
            if (player.isEmpty()) continue;
            JSONObject side = d.optJSONObject("team"), clockOf = d.optJSONObject("clock");
            goals.add(new Goal(player, clockOf == null ? "" : clockOf.optString("displayValue", ""),
                    side != null && homeId.equals(side.optString("id")), d.optBoolean("penaltyKick", false),
                    d.optBoolean("ownGoal", false)));
        }
        JSONObject status = event.getJSONObject("status");
        JSONObject type = status.getJSONObject("type");
        String state = type.optString("state", "pre");
        String clock = status.optString("displayClock", "");
        if ("HT".equals(type.optString("shortDetail")) || "STATUS_HALFTIME".equals(type.optString("name"))) clock = "HT";
        return new Match(league, home, away, homeScore, awayScore, state, clock, instant(event.getString("date")), favorite,
                homeLogo, awayLogo, homeFull, awayFull, goals, type.optString("shortDetail", ""));
    }

    /** ESPN writes "2026-10-23T21:00Z", without seconds. */
    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return OffsetDateTime.parse(text.replace("Z", ":00Z")).toInstant();
        }
    }

    private static String get(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            Log.w(TAG, "Fetch failed: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String plain(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
