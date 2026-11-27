package app.dengar.songfinder;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/**
 * Home-screen widget with the live lyrics of the song playing (Spotify or
 * another music app). LiveLyrics keeps it on the current line.
 */
public class LyricsWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        LiveLyrics.refresh(context);
    }

    @Override
    public void onEnabled(Context context) {
        LiveLyrics.refresh(context);
    }
}
