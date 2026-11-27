package app.dengar.songfinder;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * Home-screen widget: the orange button starts listening right away,
 * and the text shows the last song Dengar found (tap it to open the app).
 */
public class SongWidget extends AppWidgetProvider {

    static final String PREFS = "dengar";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context));
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_song);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String title = p.getString("title", "");
        String artist = p.getString("artist", "");
        if (title == null || title.isEmpty()) {
            v.setTextViewText(R.id.w_title, ctx.getString(R.string.widget_tap));
            v.setTextViewText(R.id.w_artist, ctx.getString(R.string.widget_hint));
        } else {
            v.setTextViewText(R.id.w_title, title);
            v.setTextViewText(R.id.w_artist, artist);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent listen = new Intent(ctx, MainActivity.class)
                .setAction(MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_button, PendingIntent.getActivity(ctx, 1, listen, flags));

        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_text, PendingIntent.getActivity(ctx, 2, open, flags));
        return v;
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, SongWidget.class));
        RemoteViews views = build(ctx);
        for (int id : ids) m.updateAppWidget(id, views);
    }
}
