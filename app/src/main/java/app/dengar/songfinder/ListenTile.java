package app.dengar.songfinder;

import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: swipe down, tap "Dengar", and it starts listening. */
public class ListenTile extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        // On the lock screen while a song plays: open the lyrics instead of listening.
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean lyricsOnly = km != null && km.isKeyguardLocked()
                && MainActivity.lockLyricsOn(this) && NowPlayingService.isPlaying();
        Intent intent = new Intent(this, MainActivity.class)
                .setAction(lyricsOnly ? Intent.ACTION_MAIN : MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 3, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
