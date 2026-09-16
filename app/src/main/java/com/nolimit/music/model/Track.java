package com.nolimit.music.model;

public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String path;
    public final long durationSeconds;
    public final long addedAt;

    public Track(String id, String title, String artist, String path, long durationSeconds, long addedAt) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.path = path;
        this.durationSeconds = durationSeconds;
        this.addedAt = addedAt;
    }
}
