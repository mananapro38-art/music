package com.nolimit.music;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nolimit.music.data.MusicRanker;
import com.nolimit.music.model.SearchResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MusicRankerTest {
    @Test
    public void topicAlbumTrackRanksAboveMusicVideoAndLive() {
        SearchResult album = new SearchResult("1", "밤편지", "아이유 - Topic", "u1", 253, "", 0, "");
        SearchResult mv = new SearchResult("2", "밤편지 Official Music Video", "이지금 [IU Official]", "u2", 280, "", 0, "");
        SearchResult live = new SearchResult("3", "밤편지 Live", "IU", "u3", 260, "", 0, "");
        List<SearchResult> ranked = MusicRanker.rank(Arrays.asList(mv, live, album), "아이유 밤편지");
        assertEquals("1", ranked.get(0).id);
        assertTrue(ranked.get(0).score > ranked.get(1).score);
    }
    @Test
    public void ytmAudioArtistMatchRanksAboveKaraokeMention() {
        SearchResult audio = new SearchResult(
                "audio123456", "봄봄봄", "로이킴", "u1", 210, "", 360, "YTM_AUDIO", "Love Love Love");
        SearchResult karaoke = new SearchResult(
                "karaoke1234", "[TJ노래방] 로이킴 - 봄봄봄", "TJ노래방", "u2", 220, "", 60, "YTM_OTHER", "");
        SearchResult episode = new SearchResult(
                "episode1234", "로이킴이 말하는 음악 이야기 Episode", "Podcast", "u3", 1800, "", 60, "YTM_OTHER", "");

        List<SearchResult> ranked = MusicRanker.rank(Arrays.asList(karaoke, episode, audio), "로이킴");

        assertEquals("audio123456", ranked.get(0).id);
        assertEquals("음원", ranked.get(0).badge);
        assertTrue(ranked.get(0).score > ranked.get(1).score);
    }

}
