package dev.twitchpatches.extension.subvods;

import android.util.Log;
import com.amazonaws.ivs.net.HttpClient;
import com.amazonaws.ivs.net.Method;
import com.amazonaws.ivs.net.ReadCallback;
import com.amazonaws.ivs.net.Request;
import com.amazonaws.ivs.net.Response;
import com.amazonaws.ivs.net.ResponseCallback;
import com.amazonaws.ivs.net.StreamConsumer;
import dev.twitchpatches.extension.ads.PlaylistBody;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Replaces refused usher VOD manifests and unmutes the media playlists that the replacement points to. */
final class SubVodHttpClient implements HttpClient {
    private static final String TAG = "TwitchPatchesSubVods";
    private final HttpClient original;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> new Thread(task, "TwitchPatchSubVod"));
    private final ExecutorService probes = Executors.newFixedThreadPool(4, task -> new Thread(task, "TwitchPatchSubVodProbe"));
    private final VodResolver resolver = new VodResolver(probes);
    private final Set<String> cdnPrefixes = ConcurrentHashMap.newKeySet();
    private volatile boolean released;

    SubVodHttpClient(HttpClient original) {
        this.original = original;
        worker.allowCoreThreadTimeOut(true);
    }

    @Override public String description() { return original.description(); }

    @Override public void execute(Request request, ResponseCallback callback) {
        if (released || request.getMethod() != Method.GET || !SubVodRuntime.enabled()) {
            original.execute(request, callback);
            return;
        }
        String url = request.getUrl();
        VodPlaylist.Usher usher = VodPlaylist.usher(url);
        if (usher != null) { executeUsher(request, callback, usher); return; }
        if (generatedMedia(url)) { executeMedia(request, callback); return; }
        original.execute(request, callback);
    }

    private boolean generatedMedia(String url) {
        if (!url.contains(".m3u8")) return false;
        for (String prefix : cdnPrefixes) if (url.startsWith(prefix)) return true;
        return false;
    }

    private void executeUsher(Request request, ResponseCallback callback, VodPlaylist.Usher usher) {
        original.execute(request, new ResponseCallback() {
            @Override public void onError(Exception error) { deliverError(request, callback, error); }
            @Override public void onResponse(Response response) {
                if (response.getStatus() == 200) { deliver(request, callback, response); return; }
                Log.i(TAG, "Usher refused VOD (HTTP " + response.getStatus() + "); building public playlist");
                Runnable fallback = () -> {
                    if (released || request.isCancelled()) return;
                    VodResolver.Result result = null;
                    try { result = resolver.resolve(usher); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    if (result == null) { deliver(request, callback, response); return; }
                    cdnPrefixes.add(result.cdnPrefix);
                    deliver(request, callback, ok(request, response.getUrl(), result.master.getBytes(StandardCharsets.UTF_8)));
                };
                try { worker.execute(fallback); }
                catch (RejectedExecutionException exception) { deliver(request, callback, response); }
            }
        });
    }

    private void executeMedia(Request request, ResponseCallback callback) {
        original.execute(request, new ResponseCallback() {
            @Override public void onError(Exception error) { deliverError(request, callback, error); }
            @Override public void onResponse(Response response) {
                if (response.getStatus() != 200) { deliver(request, callback, response); return; }
                response.readContent(new PlaylistBody(new PlaylistBody.Completion() {
                    @Override public void error(Exception error) { deliverError(request, callback, error); }
                    @Override public void complete(byte[] body) {
                        String text = VodPlaylist.unmute(PlaylistBody.text(body));
                        deliver(request, callback, PlaylistBody.response(request, response, text.getBytes(StandardCharsets.UTF_8)));
                    }
                }));
            }
        });
    }

    private static Response ok(Request request, String url, byte[] body) {
        Response response = new Response(200, url);
        response.setHeader("Content-Type", "application/vnd.apple.mpegurl");
        response.setHeader("Content-Length", Integer.toString(body.length));
        response.setHeader("Cache-Control", "no-store");
        response.setConsumer(null, new StreamConsumer() {
            @Override public void consume(ReadCallback reader) {
                synchronized (request.lock()) {
                    if (request.isCancelled()) return;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(body.length);
                    buffer.put(body);
                    ((java.nio.Buffer) buffer).flip();
                    reader.onData(buffer, true);
                }
            }
        });
        return response;
    }

    private static void deliver(Request request, ResponseCallback callback, Response response) {
        synchronized (request.lock()) { if (!request.isCancelled()) callback.onResponse(response); }
    }

    private static void deliverError(Request request, ResponseCallback callback, Exception error) {
        synchronized (request.lock()) { if (!request.isCancelled()) callback.onError(error); }
    }

    @Override public void release() {
        released = true;
        cdnPrefixes.clear();
        worker.shutdownNow();
        probes.shutdownNow();
        original.release();
    }
}
