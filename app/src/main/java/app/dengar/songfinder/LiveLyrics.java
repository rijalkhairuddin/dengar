package app.dengar.songfinder;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds the live lyrics widget: follows the song NowPlayingService reports,
 * fetches its synced lyrics from LRCLIB and moves the widget to the current line.
 * Everything runs on the main thread except the network call.
 */
final class LiveLyrics {

    static final class Line {
        final long t;
        final String text;

        Line(long t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    private static final int LOADING = 0, SYNCED = 1, PLAIN = 2, NONE = 3, ERROR = 4;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    @SuppressWarnings("serial")
    private static final Map<String, List<Line>> cache = new LinkedHashMap<String, List<Line>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Line>> e) {
            return size() > 30;
        }
    };
    private static final Map<String, Integer> kinds = new LinkedHashMap<>();

    private static Context app;
    private static String key = "", title = "", artist = "", album = "";
    private static long durMs = 0, pos = 0, at = 0;
    private static double speed = 1;
    private static boolean playing = false;
    private static int kind = NONE;
    private static List<Line> lines = Collections.emptyList();
    private static int shown = Integer.MIN_VALUE;
    private static String shownKey = "";

    private static final Runnable tick = LiveLyrics::tick;

    private LiveLyrics() {
    }

    /** Latest now-playing state from NowPlayingService (main thread). */
    static void onState(Context c, String json) {
        app = c.getApplicationContext();
        try {
            JSONObject o = new JSONObject(json);
            title = o.optString("title", "");
            artist = o.optString("artist", "");
            album = o.optString("album", "");
            durMs = o.optLong("duration", 0);
            playing = o.optBoolean("playing", false);
            pos = o.optLong("pos", 0);
            at = o.optLong("at", System.currentTimeMillis());
            speed = o.optDouble("speed", 1);
            if (speed <= 0) speed = 1;
        } catch (Exception e) {
            title = "";
        }
        if (title.isEmpty()) {
            key = "";
        } else {
            String k = title + "\n" + artist;
            if (!k.equals(key)) {
                key = k;
                load(k, title, artist, durMs);
            }
        }
        shown = Integer.MIN_VALUE; // redraw: playing/paused may have changed
        tick();
    }

    /** A widget was added or asked to refresh. */
    static void refresh(Context c) {
        app = c.getApplicationContext();
        if (key.isEmpty() && !"{}".equals(NowPlayingService.last)) {
            onState(app, NowPlayingService.last);
            return;
        }
        shown = Integer.MIN_VALUE;
        tick();
    }

    private static boolean hasWidgets() {
        if (app == null) return false;
        int[] ids = AppWidgetManager.getInstance(app).getAppWidgetIds(new ComponentName(app, LyricsWidget.class));
        return ids != null && ids.length > 0;
    }

    private static long nowMs() {
        return pos + (playing ? (long) ((System.currentTimeMillis() - at) * speed) : 0);
    }

    private static void tick() {
        main.removeCallbacks(tick);
        if (!hasWidgets()) return;
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();

        int idx = -1;
        long next = -1;
        if (!key.isEmpty() && kind == SYNCED) {
            long p = nowMs();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).t <= p) idx = i;
                else {
                    next = lines.get(i).t - p;
                    break;
                }
            }
        }
        if (screenOn && (idx != shown || !key.equals(shownKey))) {
            render(idx);
            shown = idx;
            shownKey = key;
        }
        if (playing && kind == SYNCED) {
            long wait = !screenOn ? 5000 : next < 0 ? 5000 : Math.max(150, Math.min(next + 30, 5000));
            main.postDelayed(tick, wait);
        }
    }

    private static String lineText(int i) {
        if (i < 0 || i >= lines.size()) return "";
        String t = lines.get(i).text;
        return t.isEmpty() ? "♪" : t;
    }

    private static void render(int idx) {
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_lyrics);
        String song = app.getString(R.string.lw_name), prev = "", cur, nxt = "";
        if (!NowPlayingService.hasAccess(app)) {
            cur = app.getString(R.string.lw_access);
        } else if (key.isEmpty()) {
            cur = app.getString(R.string.lw_idle);
        } else {
            song = (playing ? "" : app.getString(R.string.lw_paused) + " · ") + title + (artist.isEmpty() ? "" : " – " + artist);
            switch (kind) {
                case LOADING:
                    cur = app.getString(R.string.lw_loading);
                    break;
                case SYNCED:
                    if (idx < 0) {
                        cur = "♪";
                        nxt = lineText(0);
                    } else {
                        prev = lineText(idx - 1);
                        cur = lineText(idx);
                        nxt = lineText(idx + 1);
                    }
                    break;
                case PLAIN:
                    cur = app.getString(R.string.lw_plain);
                    break;
                case ERROR:
                    cur = app.getString(R.string.lw_error);
                    break;
                default:
                    cur = app.getString(R.string.lw_none);
            }
        }
        v.setTextViewText(R.id.lw_song, song);
        v.setTextViewText(R.id.lw_prev, prev);
        v.setTextViewText(R.id.lw_cur, cur);
        v.setTextViewText(R.id.lw_next, nxt);

        Intent open = new Intent(app, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.lw_root, PendingIntent.getActivity(app, 4, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        AppWidgetManager m = AppWidgetManager.getInstance(app);
        for (int id : m.getAppWidgetIds(new ComponentName(app, LyricsWidget.class))) m.updateAppWidget(id, v);
    }

    /* ---------- lyrics from LRCLIB ---------- */

    private static void load(final String k, final String t, final String a, final long dur) {
        List<Line> hit = cache.get(k);
        if (hit != null) {
            lines = hit;
            kind = kinds.containsKey(k) ? kinds.get(k) : SYNCED;
            return;
        }
        lines = Collections.emptyList();
        kind = LOADING;
        io.execute(() -> {
            int resultKind;
            List<Line> result = Collections.emptyList();
            try {
                JSONObject best = find(t, a, dur / 1000);
                if (best == null) resultKind = NONE;
                else if (!best.isNull("syncedLyrics") && !best.optString("syncedLyrics", "").isEmpty()) {
                    result = parse(best.optString("syncedLyrics"));
                    resultKind = result.isEmpty() ? PLAIN : SYNCED;
                } else resultKind = best.optBoolean("instrumental") ? NONE : PLAIN;
            } catch (Exception e) {
                resultKind = ERROR;
            }
            final int rk = resultKind;
            final List<Line> rl = result;
            main.post(() -> {
                if (rk != ERROR) {
                    cache.put(k, rl);
                    kinds.put(k, rk);
                    if (kinds.size() > 60) kinds.remove(kinds.keySet().iterator().next());
                }
                if (!k.equals(key)) return;
                lines = rl;
                kind = rk;
                shown = Integer.MIN_VALUE;
                tick();
            });
        });
    }

    private static JSONObject find(String title, String artist, long durSec) throws Exception {
        String base = title.replaceAll("(?i)\\s*[(\\[][^)\\]]*(feat|ft\\.|with|remaster|version|edit|mix|live)[^)\\]]*[)\\]]", "")
                .replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|mix|live).*$", "").trim();
        if (base.isEmpty()) base = title;
        String mainArtist = artist.split("(?i)\\s*(,|&| feat\\.? | ft\\.? | x )\\s*")[0];

        JSONObject best = pick(get("search?track_name=" + enc(base) + "&artist_name=" + enc(mainArtist)), durSec);
        if (best == null) best = pick(get("search?q=" + enc(base + " " + mainArtist)), durSec);
        return best;
    }

    private static JSONObject pick(String body, long durSec) throws Exception {
        if (body == null) return null;
        JSONArray list = new JSONArray(body);
        JSONObject synced = null, plain = null, anySynced = null, anyPlain = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            boolean fits = durSec <= 0 || o.optDouble("duration", 0) <= 0 || Math.abs(o.optDouble("duration") - durSec) <= 4;
            boolean hasSynced = !o.optString("syncedLyrics", "").isEmpty() && !o.isNull("syncedLyrics");
            boolean hasPlain = !o.optString("plainLyrics", "").isEmpty() && !o.isNull("plainLyrics");
            if (hasSynced && fits && synced == null) synced = o;
            if (hasPlain && fits && plain == null) plain = o;
            if (hasSynced && anySynced == null) anySynced = o;
            if (hasPlain && anyPlain == null) anyPlain = o;
        }
        if (synced != null) return synced;
        if (plain != null) return plain;
        if (anySynced != null) return anySynced;
        return anyPlain;
    }

    private static String enc(String s) {
        return Uri.encode(s);
    }

    private static String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://lrclib.net/api/" + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Dengar (https://github.com/rijalkhairuddin/dengar)");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new Exception("lrclib " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            while (m.find()) {
                long ms = Long.parseLong(m.group(1)) * 60000L + Math.round(Double.parseDouble(m.group(2)) * 1000);
                times.add(ms);
            }
            if (times.isEmpty()) continue;
            String text = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.t, y.t));
        return out;
    }
}
