package app.dengar.songfinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Follows what Spotify is playing through Spotify's own "Device Broadcast Status"
 * broadcasts (turned on in Spotify's settings). Needs no special permission.
 */
public final class SpotifyNow {

    public interface Listener {
        void onNowPlaying(String json);
    }

    static final String PKG = "com.spotify.music";
    private static final String META = PKG + ".metadatachanged";
    private static final String STATE = PKG + ".playbackstatechanged";

    static volatile Listener listener;

    private static String id = "", title = "", artist = "", album = "";
    private static long lenMs = 0, pos = 0, posAt = 0, stateAt = 0;
    private static boolean playing = false;
    private static boolean started = false;

    private SpotifyNow() {
    }

    /** Starts listening (once per app process). Spotify's last broadcasts are sticky, so we catch up at once. */
    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handle(app, intent);
            }
        };
        for (String action : new String[]{META, STATE}) {
            IntentFilter f = new IntentFilter(action);
            Intent sticky;
            if (Build.VERSION.SDK_INT >= 33) sticky = app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            else sticky = app.registerReceiver(r, f);
            if (sticky != null) handle(app, sticky);
        }
    }

    private static long num(Intent i, String key) {
        Bundle b = i.getExtras();
        if (b == null) return 0;
        @SuppressWarnings("deprecation")
        Object o = b.get(key);
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private static String str(Intent i, String key) {
        String s = i.getStringExtra(key);
        return s == null ? "" : s;
    }

    static void handle(Context app, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        long sent = i.getLongExtra("timeSent", System.currentTimeMillis());
        synchronized (SpotifyNow.class) {
            if (META.equals(a)) {
                String nid = str(i, "id");
                boolean changed = !nid.equals(id);
                id = nid;
                title = str(i, "track");
                artist = str(i, "artist");
                album = str(i, "album");
                long len = num(i, "length");
                lenMs = len > 20000 ? len : len * 1000; // documented as seconds; some versions send ms
                if (changed && sent > posAt) {
                    pos = 0;
                    posAt = sent;
                }
            } else if (STATE.equals(a)) {
                if (sent >= stateAt) {
                    stateAt = sent;
                    playing = i.getBooleanExtra("playing", false);
                }
                if (sent >= posAt) {
                    pos = num(i, "playbackPosition");
                    posAt = sent;
                }
            } else {
                return;
            }
        }
        if (!seen(app)) {
            app.getSharedPreferences(SongWidget.PREFS, Context.MODE_PRIVATE).edit().putBoolean("spotifySeen", true).apply();
        }
        Listener l = listener;
        if (l != null) l.onNowPlaying(json());
    }

    /** True once Spotify has sent anything, i.e. Device Broadcast Status is on. */
    static boolean seen(Context c) {
        return c.getSharedPreferences(SongWidget.PREFS, Context.MODE_PRIVATE).getBoolean("spotifySeen", false);
    }

    static synchronized boolean isPlaying() {
        if (!playing || title.isEmpty()) return false;
        long p = pos + (System.currentTimeMillis() - posAt);
        return lenMs <= 0 || p < lenMs + 10000;
    }

    static synchronized String json() {
        if (title.isEmpty()) return "{}";
        try {
            boolean on = isPlaying();
            long p = pos + (on ? System.currentTimeMillis() - posAt : 0);
            if (lenMs > 0 && p > lenMs) p = lenMs;
            JSONObject o = new JSONObject();
            o.put("pkg", PKG);
            o.put("app", "Spotify");
            o.put("id", id);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("duration", lenMs);
            o.put("art", "");
            o.put("playing", on);
            o.put("pos", Math.max(0, p));
            o.put("speed", 1);
            o.put("at", System.currentTimeMillis());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
