package com.nolimit.music.model;

public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String path;
    public final long durationSeconds;
    public final long addedAt;
    public final boolean liked;
    public final int playCount;
    public final long lastPlayedAt;

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt) {
        this(id, title, artist, path, durationSeconds, addedAt, false, 0, 0L);
    }

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt,
                 boolean liked, int playCount, long lastPlayedAt) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.path = path;
        this.durationSeconds = durationSeconds;
        this.addedAt = addedAt;
        this.liked = liked;
        this.playCount = playCount;
        this.lastPlayedAt = lastPlayedAt;
    }

    public Track withLiked(boolean value) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, value, playCount, lastPlayedAt);
    }

    public Track withPlayCount(int count, long playedAt) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, liked, count, playedAt);
    }
}
