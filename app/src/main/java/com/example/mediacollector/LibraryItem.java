package com.example.mediacollector;

import org.json.JSONException;
import org.json.JSONObject;

public final class LibraryItem {
    public String id;
    public String title;
    public String url;
    public String mimeType;
    public long bytes;
    public long savedAt;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("url", url);
        o.put("mimeType", mimeType == null ? JSONObject.NULL : mimeType);
        o.put("bytes", bytes);
        o.put("savedAt", savedAt);
        return o;
    }

    public static LibraryItem fromJson(JSONObject o) {
        LibraryItem i = new LibraryItem();
        i.id = o.optString("id");
        i.title = o.optString("title", "미디어");
        i.url = o.optString("url");
        i.mimeType = o.isNull("mimeType") ? null : o.optString("mimeType", null);
        i.bytes = o.optLong("bytes", 0L);
        i.savedAt = o.optLong("savedAt", 0L);
        return i;
    }
}
