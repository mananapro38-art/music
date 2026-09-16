package com.example.mediacollector;

import android.app.Application;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.scheduler.Requirements;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@UnstableApi
public final class MediaCollectorApp extends Application {
    private DatabaseProvider databaseProvider;
    private SimpleCache downloadCache;
    private DownloadManager downloadManager;
    private DataSource.Factory upstreamFactory;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private LibraryRepository libraryRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        databaseProvider = new StandaloneDatabaseProvider(this);
        File cacheDir = new File(getFilesDir(), "offline_media");
        downloadCache = new SimpleCache(cacheDir, new NoOpCacheEvictor(), databaseProvider);
        upstreamFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("MediaCollector/0.3")
                .setAllowCrossProtocolRedirects(false);
        cacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        Executor executor = Executors.newFixedThreadPool(3);
        downloadManager = new DownloadManager(this, databaseProvider, downloadCache, upstreamFactory, executor);
        downloadManager.setMaxParallelDownloads(2);
        downloadManager.setRequirements(new Requirements(Requirements.NETWORK_UNMETERED));

        libraryRepository = new LibraryRepository(this);
        downloadManager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(
                    DownloadManager manager,
                    Download download,
                    @Nullable Exception finalException) {
                if (download.state != Download.STATE_COMPLETED) return;
                MediaCandidate meta = DownloadMetadata.decode(download.request);
                LibraryItem item = new LibraryItem();
                item.id = download.request.id;
                item.url = download.request.uri.toString();
                item.title = meta.title;
                item.mimeType = meta.mimeType;
                item.bytes = download.getBytesDownloaded();
                item.savedAt = System.currentTimeMillis();
                libraryRepository.upsert(item);
            }

            @Override
            public void onDownloadRemoved(DownloadManager manager, Download download) {
                libraryRepository.remove(download.request.id);
            }
        });
        downloadManager.resumeDownloads();
    }

    public DownloadManager downloads() { return downloadManager; }
    public Cache cache() { return downloadCache; }
    public DataSource.Factory upstreamFactory() { return upstreamFactory; }
    public CacheDataSource.Factory cacheDataSourceFactory() { return cacheDataSourceFactory; }
}
