package com.osuserverlist.koneko.routes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.api.ApiException;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * The front page's replay video of the best osu!standard score.
 *
 * <p>{@code GET /best-replay} asks bancho.jar which score is the best and whether its video has been rendered. The
 * video itself is copied to disk here once, so visitors never reach the game server for it and the clip keeps
 * playing from this site's own cache. {@code GET /best-replay/video/{id}.mp4} serves that copy, with byte ranges so
 * the browser can loop it without fetching it again.
 *
 * <p>The state reported to the page is one of {@code ready}, {@code rendering} (bancho.jar is still drawing it, or
 * the copy is on its way here) and {@code unavailable}.
 */
public final class BestReplayRoutes {

    private static final Logger logger = LoggerFactory.getLogger("BestReplayRoutes");

    private static final Path CACHE_DIR = Path.of("data", "video-cache");

    /** A 1080p60 clip of a long map is some tens of MB; anything far above that is not one. */
    private static final long MAX_BYTES = 400L * 1024 * 1024;

    private static final int KEEP_VIDEOS = 3;
    private static final long INFO_TTL_READY_MS = 30_000;
    private static final long INFO_TTL_PENDING_MS = 4_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService COPIER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "best-replay-copy");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Boolean> COPYING = new ConcurrentHashMap<>();

    private static volatile ObjectNode cachedInfo;
    private static volatile long cachedAt;

    private BestReplayRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/best-replay", BestReplayRoutes::info);
        config.routes.get("/best-replay/video/{file}", BestReplayRoutes::video);
    }

    // ------------------------------------------------------------------ info

    private static void info(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ctx.json(currentInfo());
    }

    private static synchronized ObjectNode currentInfo() {
        long now = System.currentTimeMillis();
        ObjectNode cached = cachedInfo;

        if (cached != null) {
            boolean ready = "ready".equals(cached.path("status").asText());

            if (now - cachedAt < (ready ? INFO_TTL_READY_MS : INFO_TTL_PENDING_MS)) {
                return cached;
            }
        }

        ObjectNode fresh = build();
        cachedInfo = fresh;
        cachedAt = now;
        return fresh;
    }

    private static ObjectNode build() {
        try {
            JsonNode upstream = App.api.get("/api/v1/get_best_replay", Map.of());
            String status = upstream.path("status").asText("unavailable");

            if (!(upstream instanceof ObjectNode answer) || "unavailable".equals(status)) {
                return state("unavailable");
            }

            ObjectNode out = answer.deepCopy();
            out.remove("video_url");

            long scoreId = upstream.path("score").path("id").asLong(0);

            if (scoreId <= 0) {
                return state("unavailable");
            }

            if ("ready".equals(status)) {
                // the version is part of the name: a re-rendered clip is a new file, not the copy held before
                int version = upstream.path("video_version").asInt(1);
                String name = scoreId + "_v" + version + ".mp4";

                if (Files.isRegularFile(CACHE_DIR.resolve(name))) {
                    out.put("status", "ready");
                    out.put("video_url", "/best-replay/video/" + name);
                } else {
                    // bancho.jar has it; bring a copy here, the page is told to ask again shortly
                    startCopy(scoreId, version);
                    out.put("status", "rendering");
                }
            } else {
                out.put("status", "rendering");
            }

            return out;
        } catch (ApiException e) {
            logger.warn("The best replay could not be fetched: {}", e.getMessage());

            // keep showing a clip we already hold rather than dropping the panel over a hiccup
            ObjectNode held = cachedInfo;
            return held != null && "ready".equals(held.path("status").asText()) ? held : state("unavailable");
        }
    }

    private static ObjectNode state(String status) {
        ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("status", status);
        return node;
    }

    // ------------------------------------------------------------------ copy from bancho.jar

    private static void startCopy(long scoreId, int version) {
        String key = scoreId + "_v" + version;

        if (COPYING.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }

        COPIER.submit(() -> {
            try {
                download(scoreId, version);
                prune(key + ".mp4");
                // let the next page load see the new state at once
                cachedAt = 0;
            } catch (Exception e) {
                logger.warn("Could not copy the replay video {}: {}", scoreId, e.getMessage());
            } finally {
                COPYING.remove(key);
            }
        });
    }

    private static void download(long scoreId, int version) throws IOException, InterruptedException {
        Files.createDirectories(CACHE_DIR);
        Path target = CACHE_DIR.resolve(scoreId + "_v" + version + ".mp4");
        Path partial = CACHE_DIR.resolve(scoreId + "_v" + version + ".mp4.part");

        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(App.api.getBaseUrl() + "/api/v1/replay_video?id=" + scoreId))
                        .timeout(Duration.ofSeconds(900)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("bancho.jar answered " + response.statusCode());
        }

        long total = 0;

        try (InputStream in = response.body(); var out = Files.newOutputStream(partial)) {
            byte[] buf = new byte[65536];
            int n;

            while ((n = in.read(buf)) > 0) {
                total += n;

                if (total > MAX_BYTES) {
                    throw new IOException("video is too large");
                }

                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }

        if (total == 0) {
            Files.deleteIfExists(partial);
            throw new IOException("empty video");
        }

        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void prune(String keepName) {
        try (Stream<Path> files = Files.list(CACHE_DIR)) {
            List<Path> videos = files.filter(p -> p.getFileName().toString().endsWith(".mp4"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified())).toList();

            for (int i = KEEP_VIDEOS; i < videos.size(); i++) {
                if (!videos.get(i).getFileName().toString().equals(keepName)) {
                    Files.deleteIfExists(videos.get(i));
                }
            }
        } catch (IOException ignored) {
            // housekeeping only
        }
    }

    // ------------------------------------------------------------------ serving

    /** The first {@code remaining} bytes of a stream; closing it closes the stream. */
    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedStream(InputStream in, long remaining) {
            this.in = in;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int b = in.read();

            if (b >= 0) {
                remaining--;
            }

            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int n = in.read(buffer, offset, (int) Math.min(length, remaining));

            if (n > 0) {
                remaining -= n;
            }

            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Serves a cached clip. The name is digits plus .mp4, so nothing outside the cache folder can be named. */
    private static void video(Context ctx) {
        String file = ctx.pathParam("file");

        if (!file.matches("\\d{1,12}(_v\\d{1,4})?\\.mp4")) {
            ctx.status(404);
            return;
        }

        Path path = CACHE_DIR.resolve(file);

        if (!Files.isRegularFile(path)) {
            ctx.status(404);
            return;
        }

        try {
            long size = Files.size(path);
            long start = 0;
            long end = size - 1;
            String range = ctx.header("Range");

            ctx.header("Accept-Ranges", "bytes");
            // a score id names one fixed clip, so the browser may keep it for good
            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            ctx.contentType("video/mp4");

            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);

                try {
                    if (!parts[0].isEmpty()) {
                        start = Long.parseLong(parts[0].strip());

                        if (parts.length > 1 && !parts[1].isBlank()) {
                            end = Math.min(end, Long.parseLong(parts[1].strip()));
                        }
                    } else if (parts.length > 1 && !parts[1].isBlank()) {
                        start = Math.max(0, size - Long.parseLong(parts[1].strip()));
                    }
                } catch (NumberFormatException e) {
                    ctx.status(416).header("Content-Range", "bytes */" + size);
                    return;
                }

                if (start > end || start >= size) {
                    ctx.status(416).header("Content-Range", "bytes */" + size);
                    return;
                }

                ctx.status(206);
                ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }

            long length = end - start + 1;
            ctx.header("Content-Length", String.valueOf(length));

            // streamed from disk: a whole clip must not be held in memory for every visitor
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            channel.position(start);
            ctx.result(new LimitedStream(Channels.newInputStream(channel), length));
        } catch (IOException e) {
            logger.warn("Could not read the cached video {}: {}", file, e.getMessage());
            ctx.status(500);
        }
    }
}
