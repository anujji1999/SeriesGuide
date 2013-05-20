/*
 * Copyright 2011 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.battlelancer.seriesguide.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.widget.Toast;

import com.battlelancer.seriesguide.SeriesGuideApplication;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.ui.SeriesGuidePreferences;
import com.battlelancer.seriesguide.ui.ShowsActivity;
import com.battlelancer.thetvdbapi.TheTVDB;
import com.google.analytics.tracking.android.EasyTracker;
import com.jakewharton.apibuilder.ApiException;
import com.jakewharton.trakt.ServiceManager;
import com.jakewharton.trakt.TraktException;
import com.jakewharton.trakt.entities.Activity;
import com.jakewharton.trakt.entities.ActivityItem;
import com.jakewharton.trakt.entities.TvShowEpisode;
import com.jakewharton.trakt.enumerations.ActivityAction;
import com.jakewharton.trakt.enumerations.ActivityType;
import com.uwetrottmann.seriesguide.R;
import com.uwetrottmann.tmdb.TmdbException;
import com.uwetrottmann.tmdb.entities.Configuration;

import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

enum UpdateResult {
    SILENT_SUCCESS, ERROR, OFFLINE, CANCELLED;
}

enum UpdateType {
    AUTO_SINGLE, DELTA, FULL
}

public class UpdateTask extends AsyncTask<Void, Integer, UpdateResult> {

    private static final String TAG = "UpdateTask";

    private Context mAppContext;

    private String[] mShows = null;

    private final AtomicInteger mUpdateCount = new AtomicInteger();

    private String mFailedShows = "";

    private UpdateType mUpdateType;

    private String mCurrentShowName;

    private NotificationManager mNotificationManager;

    private ArrayList<SearchResult> mNewShows;

    private Builder mBuilder;

    private static final int UPDATE_NOTIFICATION_ID = 1;

    public UpdateTask(boolean isFullUpdate, Context context) {
        mAppContext = context.getApplicationContext();
        if (isFullUpdate) {
            mUpdateType = UpdateType.FULL;
        } else {
            mUpdateType = UpdateType.DELTA;
        }
    }

    public UpdateTask(String showId, Context context) {
        mAppContext = context.getApplicationContext();
        mShows = new String[] {
                showId
        };
        mUpdateType = UpdateType.AUTO_SINGLE;
    }

    public UpdateTask(String[] showIds, int index, String failedShows, Context context) {
        mAppContext = context.getApplicationContext();
        mShows = showIds;
        mUpdateCount.set(index);
        mFailedShows = failedShows;
        mUpdateType = UpdateType.DELTA;
    }

    @Override
    protected void onPreExecute() {
        // create a notification (holy crap is that a lot of code)
        mBuilder = new NotificationCompat.Builder(mAppContext)
                .setContentTitle(mAppContext.getString(R.string.update_notification))
                .setSmallIcon(R.drawable.stat_sys_download)
                .setContentText(mAppContext.getString(R.string.update_notification));

        mBuilder.setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true);
        // no disturbing ticker text when auto-updating in overview
        if (mUpdateType != UpdateType.AUTO_SINGLE) {
            mBuilder.setTicker(mAppContext.getString(R.string.update_notification));
        }

        // content intent
        Intent notificationIntent = new Intent(mAppContext, ShowsActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(mAppContext, 0, notificationIntent,
                0);
        mBuilder.setContentIntent(contentIntent);

        mNotificationManager = (NotificationManager) mAppContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(UPDATE_NOTIFICATION_ID, mBuilder.build());
    }

    @Override
    protected UpdateResult doInBackground(Void... params) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        final ContentResolver resolver = mAppContext.getContentResolver();
        final AtomicInteger updateCount = mUpdateCount;
        long currentTime = System.currentTimeMillis();

        // build a list of shows to update
        if (mShows == null) {
            switch (mUpdateType) {
                case FULL:
                    // get all show IDs for a full update
                    final Cursor shows = resolver.query(Shows.CONTENT_URI, new String[] {
                            Shows._ID
                    }, null, null, null);

                    mShows = new String[shows.getCount()];
                    int i = 0;
                    while (shows.moveToNext()) {
                        mShows[i] = shows.getString(0);
                        i++;
                    }

                    shows.close();
                    break;
                case DELTA:
                default:
                    // get only shows which have not been updated for a certain
                    // time
                    mShows = TheTVDB.deltaUpdateShows(currentTime, mAppContext);
                    break;
            }
        }

        final int maxProgress = mShows.length + 2;
        UpdateResult resultCode = UpdateResult.SILENT_SUCCESS;
        String id;

        // actually update the shows
        for (int i = updateCount.get(); i < mShows.length; i++) {
            // skip ahead if we get cancelled or connectivity is
            // lost/forbidden
            if (isCancelled()) {
                resultCode = UpdateResult.CANCELLED;
                break;
            }
            if (!Utils.isAllowedConnection(mAppContext)) {
                resultCode = UpdateResult.OFFLINE;
                break;
            }

            id = mShows[i];
            setCurrentShowName(resolver, id);

            publishProgress(i, maxProgress);

            for (int itry = 0; itry < 2; itry++) {
                // skip ahead if we get cancelled or connectivity is
                // lost/forbidden
                if (isCancelled()) {
                    resultCode = UpdateResult.CANCELLED;
                    break;
                }
                if (!Utils.isAllowedConnection(mAppContext)) {
                    resultCode = UpdateResult.OFFLINE;
                    break;
                }

                try {
                    TheTVDB.updateShow(id, mAppContext);

                    // make sure overview and details loaders are notified
                    resolver.notifyChange(Episodes.CONTENT_URI_WITHSHOW, null);

                    break;
                } catch (SAXException e) {
                    if (itry == 1) {
                        // failed twice, give up
                        resultCode = UpdateResult.ERROR;
                        addFailedShow(mCurrentShowName);
                        Utils.trackExceptionAndLog(mAppContext, TAG, e);
                    }
                }
            }

            updateCount.incrementAndGet();
        }

        // do not refresh search table and load trakt activity on each single
        // auto update will run anyhow
        if (mUpdateType != UpdateType.AUTO_SINGLE) {
            if (updateCount.get() > 0 && mShows.length > 0) {
                // try to avoid renewing the search table as it is time
                // consuming
                publishProgress(mShows.length, maxProgress);
                TheTVDB.onRenewFTSTable(mAppContext);

                // get latest TMDb configuration
                try {
                    Configuration config = ServiceUtils.getTmdbServiceManager(mAppContext)
                            .configurationService()
                            .configuration().fire();
                    if (config != null && config.images != null
                            && !TextUtils.isEmpty(config.images.base_url)) {
                        prefs.edit()
                                .putString(SeriesGuidePreferences.KEY_TMDB_BASE_URL,
                                        config.images.base_url).commit();
                    }
                } catch (TmdbException e) {
                    Utils.trackExceptionAndLog(mAppContext, TAG, e);
                } catch (ApiException e) {
                    Utils.trackExceptionAndLog(mAppContext, TAG, e);
                }
            }

            // mark episodes based on trakt activity
            final UpdateResult traktResult = getTraktActivity(prefs, maxProgress, currentTime,
                    resolver);
            // do not overwrite earlier failure codes
            if (resultCode == UpdateResult.SILENT_SUCCESS) {
                resultCode = traktResult;
            }

            publishProgress(maxProgress, maxProgress);

            // update the latest episodes
            Utils.updateLatestEpisodes(mAppContext);

            // store time for triggering next update 15min after
            if (resultCode == UpdateResult.SILENT_SUCCESS) {
                // now, if we were successful, reset failed counter
                prefs.edit().putLong(SeriesGuidePreferences.KEY_LASTUPDATE, currentTime)
                        .putInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, 0).commit();
            } else {
                int failed = prefs.getInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, 0);

                // back off by the power of 2 times minutes
                long time;
                if (failed < 4) {
                    time = currentTime
                            - ((15 - (int) Math.pow(2, failed)) * DateUtils.MINUTE_IN_MILLIS);
                } else {
                    time = currentTime;
                }

                failed += 1;
                prefs.edit()
                        .putLong(SeriesGuidePreferences.KEY_LASTUPDATE, time)
                        .putInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, failed).commit();
            }
        } else {
            publishProgress(maxProgress, maxProgress);
        }

        return resultCode;
    }

    private UpdateResult getTraktActivity(SharedPreferences prefs, int maxProgress,
            long currentTime, ContentResolver resolver) {
        if (ServiceUtils.isTraktCredentialsValid(mAppContext)) {
            // return if we get cancelled or connectivity is lost/forbidden
            if (isCancelled()) {
                return UpdateResult.CANCELLED;
            }
            if (!Utils.isAllowedConnection(mAppContext)) {
                return UpdateResult.OFFLINE;
            }

            publishProgress(maxProgress - 1, maxProgress);

            // get last trakt update timestamp
            final long startTimeTrakt = prefs.getLong(SeriesGuidePreferences.KEY_LASTTRAKTUPDATE,
                    currentTime) / 1000;

            ServiceManager manager = ServiceUtils
                    .getTraktServiceManagerWithAuth(mAppContext, false);
            if (manager == null) {
                return UpdateResult.ERROR;
            }

            // get watched episodes from trakt
            Activity activity;
            try {
                activity = manager
                        .activityService()
                        .user(ServiceUtils.getTraktUsername(mAppContext))
                        .types(ActivityType.Episode)
                        .actions(ActivityAction.Checkin, ActivityAction.Seen,
                                ActivityAction.Scrobble, ActivityAction.Collection)
                        .timestamp(startTimeTrakt).fire();
            } catch (TraktException e) {
                Utils.trackExceptionAndLog(mAppContext, TAG, e);
                return UpdateResult.ERROR;
            } catch (ApiException e) {
                Utils.trackExceptionAndLog(mAppContext, TAG, e);
                return UpdateResult.ERROR;
            }

            if (activity == null || activity.activity == null) {
                return UpdateResult.ERROR;
            }

            // get a list of existing shows
            boolean isAutoAddingShows = prefs.getBoolean(
                    SeriesGuidePreferences.KEY_AUTO_ADD_TRAKT_SHOWS, true);
            final HashSet<String> existingShows = new HashSet<String>();
            if (isAutoAddingShows) {
                final Cursor shows = resolver.query(Shows.CONTENT_URI, new String[] {
                        Shows._ID
                }, null, null, null);
                if (shows != null) {
                    while (shows.moveToNext()) {
                        existingShows.add(shows.getString(0));
                    }
                    shows.close();
                }
            }

            // build an update batch
            mNewShows = Lists.newArrayList();
            final HashSet<String> newShowIds = new HashSet<String>();
            final ArrayList<ContentProviderOperation> batch = Lists.newArrayList();
            for (ActivityItem item : activity.activity) {
                // check for null (potential fix for reported crash)
                if (item.action != null && item.show != null) {
                    if (isAutoAddingShows && !existingShows.contains(item.show.tvdbId)
                            && !newShowIds.contains(item.show.tvdbId)) {
                        SearchResult show = new SearchResult();
                        show.title = item.show.title;
                        show.tvdbid = item.show.tvdbId;
                        mNewShows.add(show);
                        newShowIds.add(item.show.tvdbId); // prevent duplicates
                    } else {
                        switch (item.action) {
                            case Seen: {
                                // seen uses an array of episodes
                                List<TvShowEpisode> episodes = item.episodes;
                                int season = -1;
                                int number = -1;
                                for (TvShowEpisode episode : episodes) {
                                    if (episode.season > season || episode.number > number) {
                                        season = episode.season;
                                        number = episode.number;
                                    }
                                    addEpisodeSeenUpdateOp(batch, episode, item.show.tvdbId);
                                }
                                // set highest season + number combo as last
                                // watched
                                if (season != -1 && number != -1) {
                                    addLastWatchedUpdateOp(resolver, batch, season, number,
                                            item.show.tvdbId);
                                }
                                break;
                            }
                            case Checkin:
                            case Scrobble: {
                                // checkin and scrobble use a single episode
                                TvShowEpisode episode = item.episode;
                                addEpisodeSeenUpdateOp(batch, episode, item.show.tvdbId);
                                addLastWatchedUpdateOp(resolver, batch, episode.season,
                                        episode.number, item.show.tvdbId);
                                break;
                            }
                            case Collection: {
                                // collection uses an array of episodes
                                List<TvShowEpisode> episodes = item.episodes;
                                for (TvShowEpisode episode : episodes) {
                                    addEpisodeCollectedUpdateOp(batch, episode, item.show.tvdbId);
                                }
                                break;
                            }
                            default:
                                break;
                        }
                    }
                }
            }

            // execute the batch
            try {
                mAppContext.getContentResolver()
                        .applyBatch(SeriesGuideApplication.CONTENT_AUTHORITY, batch);
            } catch (RemoteException e) {
                // Failed binder transactions aren't recoverable
                Utils.trackExceptionAndLog(mAppContext, TAG, e);
                throw new RuntimeException("Problem applying batch operation", e);
            } catch (OperationApplicationException e) {
                // Failures like constraint violation aren't
                // recoverable
                Utils.trackExceptionAndLog(mAppContext, TAG, e);
                throw new RuntimeException("Problem applying batch operation", e);
            }

            // store time of this update as seen by the trakt server
            prefs.edit()
                    .putLong(SeriesGuidePreferences.KEY_LASTTRAKTUPDATE,
                            activity.timestamps.current.getTime()).commit();

        }

        return UpdateResult.SILENT_SUCCESS;
    }

    private void setCurrentShowName(final ContentResolver resolver, String id) {
        Cursor show = resolver.query(Shows.buildShowUri(id), new String[] {
                Shows.TITLE
        }, null, null, null);
        if (show.moveToFirst()) {
            mCurrentShowName = show.getString(0);
        }
        show.close();
    }

    @Override
    protected void onPostExecute(UpdateResult result) {
        String message = null;
        int length = 0;
        switch (result) {
            case SILENT_SUCCESS:
                fireTrackerEvent("Success");
                break;
            case ERROR:
                message = mAppContext.getString(R.string.update_saxerror);
                length = Toast.LENGTH_LONG;

                fireTrackerEvent("Error");
                break;
            case OFFLINE:
                message = mAppContext.getString(R.string.update_offline);
                length = Toast.LENGTH_LONG;

                fireTrackerEvent("Offline");
                break;
            default:
                break;
        }

        if (message != null) {
            // add a list of failed shows
            if (mFailedShows.length() != 0) {
                message += "(" + mFailedShows + ")";
            }
            Toast.makeText(mAppContext, message, length).show();
        }
        mNotificationManager.cancel(UPDATE_NOTIFICATION_ID);

        // add newly discovered shows to database
        if (mNewShows != null && mNewShows.size() > 0) {
            TaskManager.getInstance(mAppContext).performAddTask(mNewShows);
        }

        // There could have been new episodes added after an update
        Utils.runNotificationService(mAppContext);

        TaskManager.getInstance(mAppContext).onTaskCompleted();
    }

    @Override
    protected void onCancelled() {
        mNotificationManager.cancel(UPDATE_NOTIFICATION_ID);
        TaskManager.getInstance(mAppContext).onTaskCompleted();
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        String text;
        if (values[0] == values[1]) {
            // clear the text field if we are finishing up
            text = "";
        } else if (values[0] + 1 == values[1]) {
            // if we're one before completion, we're looking on trakt for user
            // activity
            text = mAppContext.getString(R.string.update_traktactivity);
        } else if (values[0] + 2 == values[1]) {
            // if we're two before completion, we're rebuilding the search index
            text = mAppContext.getString(R.string.update_rebuildsearch);
        } else {
            text = mCurrentShowName + "...";
        }

        mBuilder.setContentText(text).setProgress(values[1], values[0], false);
        mNotificationManager.notify(UPDATE_NOTIFICATION_ID, mBuilder.build());
    }

    private void addFailedShow(String seriesName) {
        if (mFailedShows.length() != 0) {
            mFailedShows += ", ";
        }
        mFailedShows += seriesName;
    }

    private static void addEpisodeSeenUpdateOp(final ArrayList<ContentProviderOperation> batch,
            TvShowEpisode episode, String showTvdbId) {
        batch.add(ContentProviderOperation.newUpdate(Episodes.buildEpisodesOfShowUri(showTvdbId))
                .withSelection(Episodes.NUMBER + "=? AND " + Episodes.SEASON + "=?", new String[] {
                        String.valueOf(episode.number), String.valueOf(episode.season)
                }).withValue(Episodes.WATCHED, true).build());
    }

    /**
     * Queries for an episode id and adds a content provider op to set it as
     * last watched for the given show.
     */
    private void addLastWatchedUpdateOp(ContentResolver resolver,
            ArrayList<ContentProviderOperation> batch, int season, int number, String showTvdbId) {
        // query for the episode id
        final Cursor episode = resolver.query(
                Episodes.buildEpisodesOfShowUri(showTvdbId),
                new String[] {
                    Episodes._ID
                }, Episodes.SEASON + "=" + season + " AND "
                        + Episodes.NUMBER + "=" + number, null, null);

        // store the episode id as last watched for the given show
        if (episode != null) {
            if (episode.moveToFirst()) {
                batch.add(ContentProviderOperation.newUpdate(Shows.buildShowUri(showTvdbId))
                        .withValue(Shows.LASTWATCHEDID, episode.getInt(0)).build());
            }

            episode.close();
        }

    }

    private static void addEpisodeCollectedUpdateOp(ArrayList<ContentProviderOperation> batch,
            TvShowEpisode episode, String showTvdbId) {
        batch.add(ContentProviderOperation.newUpdate(Episodes.buildEpisodesOfShowUri(showTvdbId))
                .withSelection(Episodes.NUMBER + "=? AND " + Episodes.SEASON + "=?", new String[] {
                        String.valueOf(episode.number), String.valueOf(episode.season)
                }).withValue(Episodes.COLLECTED, true).build());
    }

    private void fireTrackerEvent(String message) {
        EasyTracker.getTracker().sendEvent(TAG, "Update result", message, (long) 0);
    }

}
