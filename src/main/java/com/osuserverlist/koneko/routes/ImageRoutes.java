package com.osuserverlist.koneko.routes;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * Beatmap cover thumbnails as WebP.
 *
 * <p>{@code GET /img/cover/{setId}/{size}.webp} fetches the cover from the osu! CDN once, shrinks it to the width the
 * page actually draws, re-encodes it as lossy WebP and keeps the result on disk. Every later request is a file read
 * with a year-long {@code immutable} cache header, so a browser asks at most once per cover and the CDN is not hit by
 * every visitor.
 *
 * <p>Nothing here can be steered to another host: the set id is digits only and the size comes from a fixed list.
 * If a cover cannot be produced (no such set on the CDN, or the WebP encoder is unavailable) the answer is a 404 or a
 * redirect to the original, so a page never ends up worse than it was without this.
 */
public final class ImageRoutes {

    private static final Logger logger = LoggerFactory.getLogger("ImageRoutes");

    /** Size name -> the widest the page draws it, in px. The CDN's @2x file is shrunk down to this. */
    private static final Map<String, Integer> MAX_WIDTH = Map.of(
            "cover", 1100,
            "slimcover", 1100,
            "card", 800,
            "list", 400);

    private static final float QUALITY = 0.78f;

    private static final Path CACHE_DIR = Path.of("data", "img-cache");

    private static final long MISSING_TTL_MS = 10 * 60 * 1000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** The CDN is asked by a few covers at once, never by a whole page of them. */
    private static final Semaphore UPSTREAM = new Semaphore(4);

    /** Covers being produced right now, so two visitors of one page do not fetch and encode it twice. */
    private static final Map<String, CompletableFuture<Path>> INFLIGHT = new ConcurrentHashMap<>();

    /** Covers the CDN does not have (sets uploaded to this server), and when that was learned. */
    private static final Map<String, Long> MISSING = new ConcurrentHashMap<>();

    private ImageRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/img/cover/{setId}/{file}", ImageRoutes::cover);
    }

    private static void cover(Context ctx) {
        String setId = ctx.pathParam("setId");
        String file = ctx.pathParam("file");

        if (!setId.matches("\\d{1,9}") || !file.endsWith(".webp")) {
            ctx.status(404);
            return;
        }

        String size = file.substring(0, file.length() - ".webp".length());

        if (!MAX_WIDTH.containsKey(size)) {
            ctx.status(404);
            return;
        }

        String key = size + "/" + setId;
        Path target = CACHE_DIR.resolve(size).resolve(setId + ".webp");

        try {
            if (!Files.isRegularFile(target)) {
                Long missingSince = MISSING.get(key);

                if (missingSince != null && System.currentTimeMillis() - missingSince < MISSING_TTL_MS) {
                    ctx.status(404);
                    return;
                }

                Path made = INFLIGHT.computeIfAbsent(key, k ->
                        CompletableFuture.supplyAsync(() -> produce(setId, size, target))).join();

                INFLIGHT.remove(key);

                if (made == null) {
                    MISSING.put(key, System.currentTimeMillis());
                    ctx.status(404);
                    return;
                }
            }

            byte[] bytes = Files.readAllBytes(target);

            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            ctx.header("Content-Type", "image/webp");
            ctx.result(bytes);
        } catch (Exception e) {
            // The encoder or the disk failed: send the visitor to the original rather than to a broken picture.
            logger.warn("Could not serve cover {}: {}", key, e.getMessage());
            ctx.header("Cache-Control", "no-store");
            ctx.redirect("https://assets.ppy.sh/beatmaps/" + setId + "/covers/" + size + "@2x.jpg");
        }
    }

    /** Downloads, shrinks and encodes one cover. Null when the CDN has none. */
    private static Path produce(String setId, String size, Path target) {
        try {
            UPSTREAM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            byte[] jpeg = download("https://assets.ppy.sh/beatmaps/" + setId + "/covers/" + size + "@2x.jpg");

            if (jpeg == null) {
                return null;
            }

            BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));

            if (source == null) {
                return null;
            }

            byte[] webp = encode(shrink(source, MAX_WIDTH.get(size)));

            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.write(partial, webp);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);

            return target;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            UPSTREAM.release();
        }
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        return response.statusCode() == 200 ? response.body() : null;
    }

    private static BufferedImage shrink(BufferedImage source, int maxWidth) {
        // WebP has no use for an alpha channel on a photo, and a JPEG source has none anyway.
        int width = Math.min(source.getWidth(), maxWidth);
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();

        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");

        if (!writers.hasNext()) {
            throw new IOException("no WebP writer is registered");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            // "Lossy" is the first compression type of the WebP writer
            param.setCompressionType(param.getCompressionTypes()[0]);
            param.setCompressionQuality(QUALITY);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        return bytes.toByteArray();
    }
}
