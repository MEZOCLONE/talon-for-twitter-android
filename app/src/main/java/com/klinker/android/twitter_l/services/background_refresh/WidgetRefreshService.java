package com.klinker.android.twitter_l.services.background_refresh;
/*
 * Copyright 2014 Luke Klinker
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
 */

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.klinker.android.twitter_l.R;
import com.klinker.android.twitter_l.data.sq_lite.HomeContentProvider;
import com.klinker.android.twitter_l.data.sq_lite.HomeDataSource;
import com.klinker.android.twitter_l.services.PreCacheService;
import com.klinker.android.twitter_l.services.abstract_services.KillerIntentService;
import com.klinker.android.twitter_l.settings.AppSettings;
import com.klinker.android.twitter_l.activities.MainActivity;
import com.klinker.android.twitter_l.utils.NotificationChannelUtil;
import com.klinker.android.twitter_l.utils.Utils;
import com.klinker.android.twitter_l.widget.WidgetProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import twitter4j.Paging;
import twitter4j.Status;
import twitter4j.Twitter;

public class WidgetRefreshService  extends KillerIntentService {

    SharedPreferences sharedPrefs;
    public static boolean isRunning = false;

    public WidgetRefreshService() {
        super("WidgetRefreshService");
    }

    @Override
    public void handleIntent(Intent intent) {
        // it is refreshing elsewhere, so don't start
        if (WidgetRefreshService.isRunning || TimelineRefreshService.isRunning || !MainActivity.canSwitch) {
            return;
        }

        WidgetRefreshService.isRunning = true;
        sharedPrefs = AppSettings.getSharedPreferences(this);


        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, NotificationChannelUtil.WIDGET_REFRESH_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_icon)
                        .setTicker(getResources().getString(R.string.refreshing) + "...")
                        .setContentTitle(getResources().getString(R.string.app_name))
                        .setContentText(getResources().getString(R.string.refreshing_widget) + "...")
                        .setProgress(100, 100, true)
                        .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.drawable.drawer_sync_dark));

        NotificationManager mNotificationManager =
                (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(6, mBuilder.build());

        final Context context = getApplicationContext();
        AppSettings settings = AppSettings.getInstance(context);
        Twitter twitter = Utils.getTwitter(context, settings);
        HomeDataSource dataSource = HomeDataSource.getInstance(context);

        int currentAccount = sharedPrefs.getInt("current_account", 1);

        List<twitter4j.Status> statuses = new ArrayList<twitter4j.Status>();

        boolean foundStatus = false;

        Paging paging = new Paging(1, 200);

        long[] lastId;
        long id;
        try {
            lastId = dataSource.getLastIds(currentAccount);
            id = lastId[0];
        } catch (Exception e) {
            WidgetRefreshService.isRunning = false;
            mNotificationManager.cancel(6);
            return;
        }

        if (id != 0) {
            paging.setSinceId(id);
        }

        for (int i = 0; i < settings.maxTweetsRefresh; i++) {
            try {
                if (!foundStatus) {
                    paging.setPage(i + 1);
                    List<Status> list = twitter.getHomeTimeline(paging);
                    statuses.addAll(list);

                    if (statuses.size() <= 1 || statuses.get(statuses.size() - 1).getId() == lastId[0]) {
                        Log.v("talon_inserting", "found status");
                        foundStatus = true;
                    } else {
                        Log.v("talon_inserting", "haven't found status");
                        foundStatus = false;
                    }
                }
            } catch (Exception e) {
                // the page doesn't exist
                foundStatus = true;
            } catch (OutOfMemoryError o) {
                // don't know why...
            }
        }

        Log.v("talon_pull", "got statuses, new = " + statuses.size());

        // hash set to remove duplicates I guess
        HashSet hs = new HashSet();
        hs.addAll(statuses);
        statuses.clear();
        statuses.addAll(hs);

        Log.v("talon_inserting", "tweets after hashset: " + statuses.size());

        lastId = dataSource.getLastIds(currentAccount);

        int inserted = HomeDataSource.getInstance(context).insertTweets(statuses, currentAccount, lastId);

        if (inserted > 0 && statuses.size() > 0) {
            sharedPrefs.edit().putLong("account_" + currentAccount + "_lastid", statuses.get(0).getId()).apply();
        }

        if (settings.preCacheImages) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    PreCacheService.cache(context);
                }
            }).start();
        }

        WidgetProvider.updateWidget(this);
        getContentResolver().notifyChange(HomeContentProvider.CONTENT_URI, null);
        sharedPrefs.edit().putBoolean("refresh_me", true).apply();

        mNotificationManager.cancel(6);

        WidgetRefreshService.isRunning = false;
    }
}