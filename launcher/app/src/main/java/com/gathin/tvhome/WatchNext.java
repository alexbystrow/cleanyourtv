package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Continue watching: the rows other apps publish to the system's Watch Next table (docs/watch-next.md).
 * Needs READ_TV_LISTINGS (granted from the shell); without it the query fails and the list is empty.
 * The whole table is read and filtered here, since a selection without ACCESS_ALL_EPG_DATA throws.
 */
final class WatchNext {

    static final class Item {
        String pkg, title, episodeTitle, poster, intentUri;
        int season, episode, type;
        long engaged, position, duration;
    }

    private WatchNext() {}

    static List<Item> read(Context ctx) {
        List<Item> items = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                null, null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                Item it = new Item();
                it.pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                it.title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                it.episodeTitle = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                it.poster = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                it.intentUri = str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                it.season = num(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                it.episode = num(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                it.type = (int) lng(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
                it.engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                it.position = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                it.duration = lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                if (it.pkg != null && it.intentUri != null) items.add(it);
            }
        } catch (RuntimeException e) {
            Log.w("WatchNext", "query failed: " + e);
        }
        return items;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static int num(Cursor c, String col) {
        String s = str(c, col);
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
