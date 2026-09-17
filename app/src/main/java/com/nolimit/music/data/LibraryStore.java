package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LibraryStore {
    private static final String PREFS = "library";
    private static final String KEY_TRACKS = "tracks";
    private final Context context;
    private final SharedPreferences prefs;

    public LibraryStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Track> load() {
        List<Track> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_TRACKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String title = o.optString("title");
                String artist = o.optString("artist");
                result.add(new Track(o.optString("id"), title, artist, o.optString("path"), o.optLong("duration"),
                        o.optLong("addedAt"), o.optBoolean("liked", false), o.optInt("playCount", 0),
                        o.optLong("lastPlayedAt", 0L), o.optString("thumbnail", ""),
                        o.optString("album", AutoTagger.inferAlbum(title, artist)),
                        o.optString("tags", AutoTagger.infer(title, artist))));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized Track find(String id) { for (Track track : load()) if (track.id.equals(id)) return track; return null; }

    public synchronized void upsert(Track track) {
        List<Track> tracks = load();
        Track previous = null;
        for (Track t : tracks) if (t.id.equals(track.id)) previous = t;
        if (previous != null) {
            tracks.remove(previous);
            String thumbnail = empty(track.thumbnailUrl) ? previous.thumbnailUrl : track.thumbnailUrl;
            String album = empty(track.album) ? previous.album : track.album;
            String tags = empty(track.tags) ? previous.tags : track.tags;
            track = new Track(track.id, track.title, track.artist, track.path, track.durationSeconds,
                    previous.addedAt > 0 ? previous.addedAt : track.addedAt, previous.liked, previous.playCount,
                    previous.lastPlayedAt, thumbnail, album, tags);
        }
        if (empty(track.album) || empty(track.tags)) {
            track = track.withMetadata(empty(track.album) ? AutoTagger.inferAlbum(track.title, track.artist) : track.album,
                    empty(track.tags) ? AutoTagger.infer(track.title, track.artist) : track.tags);
        }
        tracks.add(0, track);
        save(tracks);
        TrackStorage.mirrorBestEffort(context, track);
    }

    public synchronized int importSharedMusic() {
        List<Track> existing = load(); Set<String> ids = new HashSet<>(); for (Track t : existing) ids.add(t.id); int added = 0;
        for (Track t : TrackStorage.scanShared(context)) {
            if (ids.add(t.id)) { existing.add(0, t); added++; }
            else {
                for (int i = 0; i < existing.size(); i++) {
                    Track old = existing.get(i);
                    if (old.id.equals(t.id) && !TrackStorage.exists(context, old.path)) {
                        existing.set(i, new Track(old.id, old.title, old.artist, t.path,
                                old.durationSeconds > 0 ? old.durationSeconds : t.durationSeconds,
                                old.addedAt > 0 ? old.addedAt : t.addedAt, old.liked, old.playCount, old.lastPlayedAt,
                                old.thumbnailUrl, empty(old.album) ? t.album : old.album, empty(old.tags) ? t.tags : old.tags));
                    }
                }
            }
        }
        save(existing); return added;
    }

    public synchronized void setLiked(String id, boolean liked) {
        List<Track> tracks = load();
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(id)) { tracks.set(i, tracks.get(i).withLiked(liked)); save(tracks); return; }
    }

    public synchronized void incrementPlayCount(String id) {
        List<Track> tracks = load(); long now = System.currentTimeMillis();
        for (int i = 0; i < tracks.size(); i++) { Track t = tracks.get(i); if (t.id.equals(id)) { tracks.set(i, t.withPlayCount(t.playCount + 1, now)); save(tracks); return; } }
    }

    public synchronized List<Track> recent(int limit) { List<Track> tracks = load(); tracks.sort((a,b)->Long.compare(b.addedAt,a.addedAt)); return take(tracks, limit); }
    public synchronized List<Track> liked() { List<Track> out = new ArrayList<>(); for (Track t:load()) if(t.liked) out.add(t); out.sort((a,b)->Long.compare(b.addedAt,a.addedAt)); return out; }
    public synchronized List<Track> mostPlayed(int limit) { List<Track> tracks=load(); tracks.removeIf(t->t.playCount<=0); tracks.sort(Comparator.comparingInt((Track t)->t.playCount).reversed().thenComparing(Comparator.comparingLong((Track t)->t.lastPlayedAt).reversed())); return take(tracks,limit); }
    public synchronized List<Track> byArtist(String artist) { List<Track> out=new ArrayList<>(); for(Track t:load()) if(artist!=null&&artist.equalsIgnoreCase(t.artist)) out.add(t); return out; }
    public synchronized List<Track> byAlbum(String album) { List<Track> out=new ArrayList<>(); for(Track t:load()) if(album!=null&&album.equalsIgnoreCase(t.album)) out.add(t); return out; }
    public synchronized List<Track> byTag(String tag) { List<Track> out=new ArrayList<>(); for(Track t:load()) if(t.tags!=null&&t.tags.toLowerCase().contains(tag.toLowerCase())) out.add(t); return out; }

    public synchronized void remove(String id) {
        List<Track> tracks=load(); Track target=null; for(Track track:tracks) if(track.id.equals(id)) target=track;
        if(target!=null){ TrackStorage.delete(context,target.path); TrackStorage.deleteSharedById(context,target.id); tracks.remove(target); save(tracks); }
    }

    private static List<Track> take(List<Track> tracks,int limit){ if(limit<=0||tracks.size()<=limit)return new ArrayList<>(tracks); return new ArrayList<>(tracks.subList(0,limit)); }
    private void save(List<Track> tracks){ JSONArray array=new JSONArray(); for(Track t:tracks){ JSONObject o=new JSONObject(); try{ o.put("id",t.id);o.put("title",t.title);o.put("artist",t.artist);o.put("path",t.path);o.put("duration",t.durationSeconds);o.put("addedAt",t.addedAt);o.put("liked",t.liked);o.put("playCount",t.playCount);o.put("lastPlayedAt",t.lastPlayedAt);o.put("thumbnail",t.thumbnailUrl==null?"":t.thumbnailUrl);o.put("album",t.album==null?"":t.album);o.put("tags",t.tags==null?"":t.tags);array.put(o);}catch(Exception ignored){} } prefs.edit().putString(KEY_TRACKS,array.toString()).apply(); }
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
}
