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
    public final String thumbnailUrl;
    public final String album;
    public final String tags;
    public final String sourceUrl;
    public final String sourceName;

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt) {
        this(id, title, artist, path, durationSeconds, addedAt, false, 0, 0L, "", "", "", "", "");
    }

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt,
                 boolean liked, int playCount, long lastPlayedAt) {
        this(id, title, artist, path, durationSeconds, addedAt, liked, playCount, lastPlayedAt,
                "", "", "", "", "");
    }

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt,
                 boolean liked, int playCount, long lastPlayedAt, String thumbnailUrl) {
        this(id, title, artist, path, durationSeconds, addedAt, liked, playCount, lastPlayedAt,
                thumbnailUrl, "", "", "", "");
    }

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt,
                 boolean liked, int playCount, long lastPlayedAt, String thumbnailUrl,
                 String album, String tags) {
        this(id, title, artist, path, durationSeconds, addedAt, liked, playCount, lastPlayedAt,
                thumbnailUrl, album, tags, "", "");
    }

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt,
                 boolean liked, int playCount, long lastPlayedAt, String thumbnailUrl,
                 String album, String tags, String sourceUrl, String sourceName) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.path = path == null ? "" : path;
        this.durationSeconds = durationSeconds;
        this.addedAt = addedAt;
        this.liked = liked;
        this.playCount = playCount;
        this.lastPlayedAt = lastPlayedAt;
        this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        this.album = album == null ? "" : album;
        this.tags = tags == null ? "" : tags;
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.sourceName = sourceName == null ? "" : sourceName;
    }

    public Track withLiked(boolean value) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, value, playCount,
                lastPlayedAt, thumbnailUrl, album, tags, sourceUrl, sourceName);
    }

    public Track withPlayCount(int count, long playedAt) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, liked, count,
                playedAt, thumbnailUrl, album, tags, sourceUrl, sourceName);
    }

    public Track withPath(String newPath) {
        return new Track(id, title, artist, newPath, durationSeconds, addedAt, liked, playCount,
                lastPlayedAt, thumbnailUrl, album, tags, sourceUrl, sourceName);
    }

    public Track withMetadata(String newAlbum, String newTags) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, liked, playCount,
                lastPlayedAt, thumbnailUrl, newAlbum, newTags, sourceUrl, sourceName);
    }

    public Track withSource(String newSourceUrl, String newSourceName) {
        return new Track(id, title, artist, path, durationSeconds, addedAt, liked, playCount,
                lastPlayedAt, thumbnailUrl, album, tags, newSourceUrl, newSourceName);
    }
}
