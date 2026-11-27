package app.dengar.songfinder;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches the phone's "now playing" media sessions (Spotify first, any other
 * music app otherwise) so the app can show lyrics in time with the song.
 * Android only shares media sessions with an enabled notification listener;
 * Dengar never reads the notifications themselves.
 */
public class NowPlayingService extends NotificationListenerService {

    public interface Listener {
        void onNowPlaying(String json);
    }

    public static final String SPOTIFY = "com.spotify.music";

    static volatile Listener listener;
    static volatile String last = "{}";
    static volatile NowPlayingService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private ComponentName me;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController current;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            controllers -> pick(controllers);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            pick(null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pick(null);
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    public static boolean isPlaying() {
        return last.contains("\"playing\":true");
    }

    public static boolean hasAccess(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(c.getPackageName() + "/");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        me = new ComponentName(this, NowPlayingService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main);
        } catch (SecurityException ignored) {
        }
        pick(null);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (Exception ignored) {
        }
        unwatchAll();
        current = null;
        if (instance == this) instance = null;
        publish();
    }

    /** Re-reads the active sessions and picks the one to follow. */
    void pick(List<MediaController> controllers) {
        if (controllers == null) {
            try {
                controllers = msm == null ? null : msm.getActiveSessions(me);
            } catch (SecurityException e) {
                controllers = null;
            }
        }
        if (controllers == null) controllers = new ArrayList<>();

        // Watch every session so we notice when another app starts playing.
        boolean same = controllers.size() == watched.size();
        if (same) {
            for (int i = 0; i < controllers.size(); i++) {
                if (!controllers.get(i).getSessionToken().equals(watched.get(i).getSessionToken())) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            unwatchAll();
            for (MediaController c : controllers) {
                try {
                    c.registerCallback(callback, main);
                    watched.add(c);
                } catch (Exception ignored) {
                }
            }
        }

        // Playing beats paused; Spotify beats other apps.
        MediaController best = null;
        int bestScore = -1;
        for (MediaController c : watched) {
            MediaMetadata m = c.getMetadata();
            if (m == null || m.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
            PlaybackState st = c.getPlaybackState();
            int score = 0;
            if (st != null && (st.getState() == PlaybackState.STATE_PLAYING
                    || st.getState() == PlaybackState.STATE_BUFFERING)) score += 2;
            if (SPOTIFY.equals(c.getPackageName())) score += 1;
            if (score > bestScore) {
                best = c;
                bestScore = score;
            }
        }
        current = best;
        publish();
    }

    private void unwatchAll() {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }
        watched.clear();
    }

    private void publish() {
        String json = "{}";
        MediaController c = current;
        if (c != null) {
            try {
                MediaMetadata m = c.getMetadata();
                PlaybackState st = c.getPlaybackState();
                JSONObject o = new JSONObject();
                o.put("pkg", c.getPackageName());
                o.put("app", appName(c.getPackageName()));
                o.put("title", text(m, MediaMetadata.METADATA_KEY_TITLE));
                String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
                if (artist.isEmpty()) artist = text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                o.put("artist", artist);
                o.put("album", text(m, MediaMetadata.METADATA_KEY_ALBUM));
                o.put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION));
                String art = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (art.isEmpty()) art = text(m, MediaMetadata.METADATA_KEY_ART_URI);
                o.put("art", art.startsWith("http") ? art : "");
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                long pos = st == null ? 0 : st.getPosition();
                float speed = st == null ? 1f : st.getPlaybackSpeed();
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
                }
                o.put("playing", playing);
                o.put("pos", Math.max(0, pos));
                o.put("speed", speed == 0f ? 1.0 : (double) speed);
                o.put("at", System.currentTimeMillis());
                json = o.toString();
            } catch (Exception ignored) {
                json = "{}";
            }
        }
        last = json;
        LiveLyrics.onState(this, json);
        Listener l = listener;
        if (l != null) l.onNowPlaying(json);
    }

    private static String text(MediaMetadata m, String key) {
        if (m == null) return "";
        CharSequence s = m.getText(key);
        return s == null ? "" : s.toString();
    }

    private String appName(String pkg) {
        if (SPOTIFY.equals(pkg)) return "Spotify";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
