package com.nolimit.music.model;

import com.nolimit.music.util.DisplayText;

public final class SearchResult {
    public final String id;
    public final String title;
    public final String channel;
    public final String url;
    public final long durationSeconds;
    public final String thumbnail;
    public final int score;
    public final String badge;
    public final String album;

    public SearchResult(String id, String title, String channel, String url,
                        long durationSeconds, String thumbnail, int score, String badge) {
        this(id, title, channel, url, durationSeconds, thumbnail, score, badge, "");
    }

    public SearchResult(String id, String title, String channel, String url,
                        long durationSeconds, String thumbnail, int score, String badge, String album) {
        this.id = id;
        this.title = DisplayText.cleanTitle(title);
        this.channel = DisplayText.cleanArtist(channel);
        this.url = url;
        this.durationSeconds = durationSeconds;
        this.thumbnail = thumbnail;
        this.score = score;
        this.badge = badge;
        this.album = album == null ? "" : album;
    }

    public SearchResult withRanking(int newScore, String newBadge) {
        return new SearchResult(id, title, channel, url, durationSeconds, thumbnail, newScore, newBadge, album);
    }
}
