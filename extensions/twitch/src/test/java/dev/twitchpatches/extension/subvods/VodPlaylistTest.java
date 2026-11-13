package dev.twitchpatches.extension.subvods;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VodPlaylistTest {
    private static String video(String type, String created) {
        return "{\"data\":{\"video\":{\"broadcastType\":\"" + type + "\",\"createdAt\":\"" + created + "\"," +
                "\"seekPreviewsURL\":\"https://d1m7jfoe9zdc1j.cloudfront.net/abc123_streamer_4242_1700000000/storyboards/987-info.json\"," +
                "\"owner\":{\"login\":\"streamer\"}}}}";
    }

    @Test public void recognisesUsherVodManifestsOnly() {
        VodPlaylist.Usher v1 = VodPlaylist.usher("https://usher.ttvnw.net/vod/987.m3u8?sig=x&token=y");
        assertEquals("987", v1.vodId);
        assertFalse(v1.v2);
        assertTrue(VodPlaylist.usher("https://usher.ttvnw.net/vod/v2/987.m3u8").v2);
        assertNull(VodPlaylist.usher("https://usher.ttvnw.net/api/channel/hls/streamer.m3u8"));
        assertNull(VodPlaylist.usher("https://evil.example/vod/987.m3u8"));
        assertNull(VodPlaylist.usher("http://usher.ttvnw.net/vod/987.m3u8"));
    }

    @Test public void derivesCdnLocationForEachBroadcastType() {
        VodPlaylist.Metadata archive = VodPlaylist.metadata(video("ARCHIVE", "2026-10-01T10:00:00Z"));
        assertEquals("https://d1m7jfoe9zdc1j.cloudfront.net/abc123_streamer_4242_1700000000/chunked/index-dvr.m3u8",
                VodPlaylist.variantUrl(archive, "987", "chunked"));
        VodPlaylist.Metadata highlight = VodPlaylist.metadata(video("HIGHLIGHT", "2026-10-01T10:00:00Z"));
        assertEquals("https://d1m7jfoe9zdc1j.cloudfront.net/abc123_streamer_4242_1700000000/720p60/highlight-987.m3u8",
                VodPlaylist.variantUrl(highlight, "987", "720p60"));
        VodPlaylist.Metadata oldUpload = VodPlaylist.metadata(video("UPLOAD", "2022-01-01T10:00:00Z"));
        assertEquals("https://d1m7jfoe9zdc1j.cloudfront.net/streamer/987/abc123_streamer_4242_1700000000/480p30/index-dvr.m3u8",
                VodPlaylist.variantUrl(oldUpload, "987", "480p30"));
    }

    @Test public void missingStoryboardsAreRejected() {
        assertNull(VodPlaylist.metadata("{\"data\":{\"video\":{\"broadcastType\":\"ARCHIVE\",\"seekPreviewsURL\":null}}}"));
        assertNull(VodPlaylist.metadata("{\"data\":{\"video\":null}}"));
    }

    @Test public void buildsBothUsherMasterFormats() {
        VodPlaylist.Rendition source = new VodPlaylist.Rendition(VodPlaylist.QUALITIES[0], "https://cdn/x/chunked/index-dvr.m3u8", VodPlaylist.AVC);
        VodPlaylist.Rendition low = new VodPlaylist.Rendition(VodPlaylist.QUALITIES[6], "https://cdn/x/160p30/index-dvr.m3u8", VodPlaylist.HEVC);
        String v2 = VodPlaylist.master(Arrays.asList(source, low), true, "id");
        assertTrue(v2.startsWith("#EXTM3U\n"));
        assertTrue(v2.contains("IVS-VARIANT-SOURCE=\"source\"\nhttps://cdn/x/chunked/index-dvr.m3u8\n"));
        assertTrue(v2.contains("BANDWIDTH=8533930,CODECS=\"hev1.1.6.L93.B0,mp4a.40.2\",RESOLUTION=284x160"));
        String v1 = VodPlaylist.master(Arrays.asList(source), false, "id");
        assertTrue(v1.contains("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"chunked\",NAME=\"chunked\",AUTOSELECT=YES,DEFAULT=YES\n"));
    }

    @Test public void codecAndUnmuteHelpers() {
        assertEquals(VodPlaylist.AVC, VodPlaylist.containerCodec("#EXTM3U\n#EXTINF:10,\n0.ts\n"));
        assertEquals("mp4", VodPlaylist.containerCodec("#EXTM3U\n#EXT-X-MAP:URI=\"init-0.mp4\"\n0.mp4\n"));
        assertNull(VodPlaylist.containerCodec("<Error>AccessDenied</Error>"));
        assertEquals(VodPlaylist.HEVC, VodPlaylist.codecFromInit("....ftyp....hev1....".getBytes()));
        assertEquals(VodPlaylist.AVC, VodPlaylist.codecFromInit("....ftyp....avc1....".getBytes()));
        assertEquals("#EXTM3U\n12-muted.ts\n13.ts\n", VodPlaylist.unmute("#EXTM3U\n12-unmuted.ts\n13.ts\n"));
        assertTrue(VodPlaylist.servingId(new Random(1)).matches("[0-9a-z]{32}"));
        assertTrue(VodPlaylist.query("987").contains("video(id: \\\"987\\\")"));
    }
}
