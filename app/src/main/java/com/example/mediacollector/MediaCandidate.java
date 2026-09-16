package com.example.mediacollector;

public final class MediaCandidate {
    public final String url;
    public final String mimeType;
    public final String title;

    public MediaCandidate(String url, String mimeType, String title) {
        this.url = url;
        this.mimeType = mimeType;
        this.title = title;
    }
}
