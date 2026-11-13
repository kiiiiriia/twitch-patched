package dev.twitchpatches.extension.subvods;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Network side of the restricted-VOD fallback. Runs on worker threads only. */
final class VodResolver {
    private static final String TAG = "TwitchPatchesSubVods";
    // Public web client ID used by TwitchNoSub for the anonymous metadata query.
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final int LIMIT = 512 * 1024;
    private final ExecutorService probes;

    VodResolver(ExecutorService probes) { this.probes = probes; }

    static final class Result {
        final String master, cdnPrefix;
        Result(String master, String cdnPrefix) { this.master = master; this.cdnPrefix = cdnPrefix; }
    }

    Result resolve(VodPlaylist.Usher usher) throws InterruptedException {
        byte[] json = request("https://gql.twitch.tv/gql", VodPlaylist.query(usher.vodId));
        VodPlaylist.Metadata data;
        try { data = json == null ? null : VodPlaylist.metadata(new String(json, StandardCharsets.UTF_8)); }
        catch (RuntimeException exception) { data = null; }
        if (data == null) { Log.i(TAG, "VOD metadata unavailable"); return null; }

        List<Future<VodPlaylist.Rendition>> pending = new ArrayList<>();
        for (String[] quality : VodPlaylist.QUALITIES) {
            String url = VodPlaylist.variantUrl(data, usher.vodId, quality[0]);
            pending.add(probes.submit((Callable<VodPlaylist.Rendition>) () -> probe(quality, url)));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        List<VodPlaylist.Rendition> found = new ArrayList<>();
        for (Future<VodPlaylist.Rendition> future : pending) {
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                VodPlaylist.Rendition rendition = future.get(remaining, TimeUnit.NANOSECONDS);
                if (rendition != null) found.add(rendition);
            } catch (InterruptedException exception) {
                for (Future<?> other : pending) other.cancel(true);
                throw exception;
            } catch (Exception ignored) { future.cancel(true); }
        }
        Log.i(TAG, "Restricted VOD renditions found: " + found.size());
        if (found.isEmpty()) return null;
        String master = VodPlaylist.master(found, usher.v2, VodPlaylist.servingId(new SecureRandom()));
        return new Result(master, VodPlaylist.cdnPrefix(data));
    }

    private static VodPlaylist.Rendition probe(String[] quality, String url) {
        byte[] body = request(url, null);
        String codec = VodPlaylist.containerCodec(body == null ? null : new String(body, StandardCharsets.UTF_8));
        if (codec == null) return null;
        if ("mp4".equals(codec)) codec = VodPlaylist.codecFromInit(request(url.replace("index-dvr.m3u8", "init-0.mp4"), null));
        return new VodPlaylist.Rendition(quality, url, codec);
    }

    /** GET when {@code post} is null, otherwise a JSON POST. Returns null on any failure. */
    private static byte[] request(String url, String post) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            if (post != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Client-Id", CLIENT_ID);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(post.getBytes(StandardCharsets.UTF_8)); }
            }
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    if (bytes.size() + read > LIMIT) return null;
                    bytes.write(buffer, 0, read);
                    // Codec sniffing only needs the start of init segments.
                    if (url.endsWith("init-0.mp4") && bytes.size() >= 4096) break;
                }
                return bytes.toByteArray();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
