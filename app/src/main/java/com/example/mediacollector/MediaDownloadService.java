package com.example.mediacollector;

import android.app.Notification;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.scheduler.Scheduler;

import java.util.List;

@UnstableApi
public final class MediaDownloadService extends DownloadService {
    public static final String CHANNEL_ID = "media_collector_downloads";
    private static final int NOTIFICATION_ID = 1001;

    private DownloadNotificationHelper notificationHelper;

    public MediaDownloadService() {
        super(
                NOTIFICATION_ID,
                DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
                CHANNEL_ID,
                R.string.download_channel_name,
                R.string.download_channel_description
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationHelper = new DownloadNotificationHelper(this, CHANNEL_ID);
    }

    @Override
    protected DownloadManager getDownloadManager() {
        return ((MediaCollectorApp) getApplication()).downloads();
    }

    @Nullable
    @Override
    protected Scheduler getScheduler() {
        return null;
    }

    @Override
    protected Notification getForegroundNotification(
            List<Download> downloads,
            @Requirements.RequirementFlags int notMetRequirements) {
        return notificationHelper.buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                null,
                "미디어 저장 중",
                downloads,
                notMetRequirements
        );
    }
}
