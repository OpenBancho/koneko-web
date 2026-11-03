package com.osuserverlist.koneko.routes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * Beatmap preview clips, cached on this server.
 *
 * <p>{@code GET /audio/preview/{setId}.mp3} fetches the clip from the osu! CDN once and keeps it on disk. Every later
 * request is a file read with a year-long {@code immutable} cache header, so the player no longer waits on a slow
 * connection to {@code b.ppy.sh} for each press of the play button, and the CDN is not hit by every visitor.
 *
 * <p>Nothing here can be steered to another host: the set id is digits only. A set the CDN does not have (one uploaded
 * to this server) answers 404, remembered for a while so a page of them does not ask the CDN again and again.
 */
public final class AudioRoutes {

    private static final Logger logger = LoggerFactory.getLogger("AudioRoutes");

    private static final Path CACHE_DIR = Path.of("data", "audio-cache");

    private static final long MISSING_TTL_MS = 10 * 60 * 1000L;

    /** A preview is about 100-300 KB; anything far above that is not one. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Semaphore UPSTREAM = new Semaphore(4);

    private static final Map<String, CompletableFuture<Path>> INFLIGHT = new ConcurrentHashMap<>();

    private static final Map<String, Long> MISSING = new ConcurrentHashMap<>();

    private AudioRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/audio/preview/{file}", AudioRoutes::preview);
    }

    private static void preview(Context ctx) {
        String file = ctx.pathParam("file");

        if (!file.endsWith(".mp3")) {
            ctx.status(404);
            return;
        }

        String setId = file.substring(0, file.length() - ".mp3".length());

        if (!setId.matches("\\d{1,9}")) {
            ctx.status(404);
            return;
        }

        Path target = CACHE_DIR.resolve(setId + ".mp3");

        try {
            if (!Files.isRegularFile(target)) {
                Long missingSince = MISSING.get(setId);

                if (missingSince != null && System.currentTimeMillis() - missingSince < MISSING_TTL_MS) {
                    ctx.status(404);
                    return;
                }

                Path made = INFLIGHT.computeIfAbsent(setId, k ->
                        CompletableFuture.supplyAsync(() -> produce(setId, target))).join();

                INFLIGHT.remove(setId);

                if (made == null) {
                    MISSING.put(setId, System.currentTimeMillis());
                    ctx.status(404);
                    return;
                }
            }

            byte[] bytes = Files.readAllBytes(target);

            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            ctx.header("Content-Type", "audio/mpeg");
            ctx.result(bytes);
        } catch (Exception e) {
            // Could not cache it: let the browser go to the CDN itself, as it did before this route existed.
            logger.warn("Could not serve preview {}: {}", setId, e.getMessage());
            ctx.header("Cache-Control", "no-store");
            ctx.redirect("https://b.ppy.sh/preview/" + setId + ".mp3");
        }
    }

    /** Downloads one clip. Null when the CDN has none. */
    private static Path produce(String setId, Path target) {
        try {
            UPSTREAM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            HttpResponse<byte[]> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("https://b.ppy.sh/preview/" + setId + ".mp3"))
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            byte[] body = response.body();

            if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_BYTES) {
                return null;
            }

            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.write(partial, body);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);

            return target;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            UPSTREAM.release();
        }
    }
}
