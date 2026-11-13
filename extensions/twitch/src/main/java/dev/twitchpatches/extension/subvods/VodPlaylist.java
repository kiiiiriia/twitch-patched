package dev.twitchpatches.extension.subvods;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds a VOD master playlist from public CDN renditions when usher refuses a restricted VOD.
 * Adapted from TwitchNoSub (https://github.com/besuper/TwitchNoSub), patch_amazonworker.js.
 */
public final class VodPlaylist {
    private static final Pattern USHER = Pattern.compile("^/vod/(v2/)?(\\d{1,20})\\.m3u8$");
    // Uploads created before this date still use the legacy CDN layout (TwitchNoSub: 7 days before 2023-02-10).
    private static final Instant LEGACY_UPLOAD_CUTOFF = Instant.parse("2023-02-03T00:00:00Z");
    static final String AVC = "avc1.4D001E";
    static final String HEVC = "hev1.1.6.L93.B0";

    /** Rendition key, display name, resolution, frame rate; highest first. */
    static final String[][] QUALITIES = {
            {"chunked", "chunked", "chunked", "60"},
            {"1440p60", "1440p60", "2560x1440", "60"},
            {"1080p60", "1080p60", "1920x1080", "60"},
            {"720p60", "720p60", "1280x720", "60"},
            {"480p30", "480p", "854x480", "30"},
            {"360p30", "360p", "640x360", "30"},
            {"160p30", "160p", "284x160", "30"},
    };

    private VodPlaylist() {}

    public static final class Usher {
        public final String vodId;
        public final boolean v2;
        Usher(String vodId, boolean v2) { this.vodId = vodId; this.v2 = v2; }
    }

    public static Usher usher(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"usher.ttvnw.net".equalsIgnoreCase(uri.getHost())) return null;
            Matcher matcher = USHER.matcher(uri.getPath() == null ? "" : uri.getPath());
            return matcher.matches() ? new Usher(matcher.group(2), matcher.group(1) != null) : null;
        } catch (IllegalArgumentException exception) { return null; }
    }

    public static final class Metadata {
        final String broadcastType, login, domain, specialId;
        final Instant created;
        Metadata(String broadcastType, String login, String domain, String specialId, Instant created) {
            this.broadcastType = broadcastType; this.login = login; this.domain = domain;
            this.specialId = specialId; this.created = created;
        }
    }

    /** Parses the GraphQL video response. Returns null when the CDN location cannot be derived. */
    public static Metadata metadata(String json) {
        JSONObject video;
        try { video = new JSONObject(json).optJSONObject("data"); }
        catch (JSONException exception) { return null; }
        video = video == null ? null : video.optJSONObject("video");
        if (video == null) return null;
        String previews = video.optString("seekPreviewsURL", "");
        if (previews.isEmpty() || "null".equals(previews)) return null;
        URI uri;
        try { uri = URI.create(previews); } catch (IllegalArgumentException exception) { return null; }
        if (uri.getHost() == null || uri.getPath() == null) return null;
        String[] parts = uri.getPath().split("/");
        String special = null;
        for (int i = 1; i < parts.length; i++) if (parts[i].contains("storyboards")) { special = parts[i - 1]; break; }
        if (special == null || special.isEmpty()) return null;
        JSONObject owner = video.optJSONObject("owner");
        Instant created;
        try { created = Instant.parse(video.optString("createdAt")); } catch (RuntimeException exception) { created = Instant.now(); }
        return new Metadata(video.optString("broadcastType", "archive").toLowerCase(Locale.ROOT),
                owner == null ? "" : owner.optString("login", ""), uri.getHost(), special, created);
    }

    public static String variantUrl(Metadata data, String vodId, String quality) {
        String base = "https://" + data.domain + "/";
        if ("highlight".equals(data.broadcastType))
            return base + data.specialId + "/" + quality + "/highlight-" + vodId + ".m3u8";
        if ("upload".equals(data.broadcastType) && data.created.isBefore(LEGACY_UPLOAD_CUTOFF))
            return base + data.login + "/" + vodId + "/" + data.specialId + "/" + quality + "/index-dvr.m3u8";
        return base + data.specialId + "/" + quality + "/index-dvr.m3u8";
    }

    /** Prefix shared by every rendition of this VOD, used to recognise its media playlists. */
    public static String cdnPrefix(Metadata data) { return "https://" + data.domain + "/"; }

    /** Returns AVC/HEVC hint from a rendition playlist, or null when it is not a usable VOD playlist. */
    static String containerCodec(String playlist) {
        if (playlist == null || !playlist.startsWith("#EXTM3U")) return null;
        if (playlist.contains(".ts")) return AVC;
        if (playlist.contains(".mp4")) return "mp4";
        return null;
    }

    static String codecFromInit(byte[] init) {
        if (init == null) return HEVC;
        String text = new String(init, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.contains("hev1") || text.contains("hvc1") ? HEVC : AVC;
    }

    public static final class Rendition {
        final String[] quality;
        final String url, codec;
        public Rendition(String[] quality, String url, String codec) { this.quality = quality; this.url = url; this.codec = codec; }
    }

    public static String master(List<Rendition> renditions, boolean v2, String servingId) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-TWITCH-INFO:ORIGIN=\"s3\",B=\"false\",REGION=\"EU\",")
                .append("USER-IP=\"127.0.0.1\",SERVING-ID=\"").append(servingId)
                .append("\",CLUSTER=\"cloudfront_vod\",USER-COUNTRY=\"BE\",MANIFEST-CLUSTER=\"cloudfront_vod\"\n");
        int bandwidth = 8534030;
        for (Rendition rendition : renditions) {
            String key = rendition.quality[0], name = rendition.quality[1];
            String resolution = rendition.quality[2], rate = rendition.quality[3];
            boolean source = "chunked".equals(key);
            if (v2) {
                out.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append(",CODECS=\"").append(rendition.codec)
                        .append(",mp4a.40.2\",RESOLUTION=").append(resolution).append(",FRAME-RATE=").append(rate)
                        .append(",STABLE-VARIANT-ID=\"").append(key).append("\",IVS-NAME=\"").append(name)
                        .append("\",IVS-VARIANT-SOURCE=\"").append(source ? "source" : "transcode").append("\"\n");
            } else {
                String flag = source ? "YES" : "NO";
                out.append("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"").append(key).append("\",NAME=\"").append(key)
                        .append("\",AUTOSELECT=").append(flag).append(",DEFAULT=").append(flag).append('\n')
                        .append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append(",CODECS=\"").append(rendition.codec)
                        .append(",mp4a.40.2\",RESOLUTION=").append(resolution).append(",VIDEO=\"").append(key)
                        .append("\",FRAME-RATE=").append(rate).append('\n');
            }
            out.append(rendition.url).append('\n');
            bandwidth -= 100;
        }
        return out.toString();
    }

    /** Muted segments stay public after DMCA muting; unmuted originals return 403. */
    public static String unmute(String playlist) { return playlist.replace("-unmuted", "-muted"); }

    static String servingId(java.util.Random random) {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        StringBuilder id = new StringBuilder(32);
        for (int i = 0; i < 32; i++) id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return id.toString();
    }

    static String query(String vodId) {
        // vodId is digits only (see USHER), so it needs no JSON escaping.
        return "{\"query\":\"query { video(id: \\\"" + vodId +
                "\\\") { broadcastType, createdAt, seekPreviewsURL, owner { login } }}\"}";
    }
}
