package com.nolimit.music.model;

import java.util.ArrayList;
import java.util.List;

public final class Playlist {
    public final String id;
    public final String name;
    public final List<String> trackIds;

    public Playlist(String id, String name, List<String> trackIds) {
        this.id = id;
        this.name = name;
        this.trackIds = new ArrayList<>(trackIds);
    }
}
