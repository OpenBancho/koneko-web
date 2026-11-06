package com.osuserverlist.koneko.routes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osuserverlist.koneko.App;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

/**
 * Live spectating: watching a player who is in the game right now, drawn in the browser from their replay frames.
 *
 * <p>Nothing is rendered or encoded here. bancho.jar (stable) and lazer.jar (lazer) each publish the frames of a
 * player as server-sent events in one shared JSON shape; this service holds <em>one</em> upstream connection to each of
 * them per watched player, however many people watch, and fans the events out to the browsers. The page draws the
 * beatmap and the cursor itself, from the .osu.
 *
 * <ul>
 * <li>{@code GET /spectate/status/{id}} - is the player in gameplay on either server; drives the profile button.</li>
 * <li>{@code GET /spectate/stream/{id}} - the events, as {@code text/event-stream}. Both servers are followed at once,
 * so the stream keeps working when the player switches clients; every event names its {@code source}.</li>
 * <li>{@code GET /spectate/asset/{beatmapId}/{osu|audio|background}} - the beatmap files, copied from bancho.jar once
 * and served from disk, the song with byte ranges so the page can seek it.</li>
 * </ul>
 *
 * <p>The last seconds of frames are held per player, so a browser that opens the page in the middle of a map starts
 * drawing at once instead of after a few seconds of buffering.
 */
public final class SpectateRoutes {

    private static final Logger logger = LoggerFactory.getLogger("SpectateRoutes");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path CACHE_DIR = Path.of("data", "spectate-cache");

    private static final int MAX_VIEWERS_PER_PLAYER = 100;
    private static final int MAX_VIEWERS = 1000;
    private static final long STATUS_TTL_MS = 3_000;
    private static final long MISSING_TTL_MS = 10 * 60 * 1000L;
    /** An upstream is kept this long after its last viewer left, so a page reload does not reconnect. */
    private static final long LINGER_MS = 8_000;
    private static final double BACKLOG_MS = 12_000;
    private static final int BACKLOG_EVENTS = 800;
    private static final long MAX_OSU = 4L * 1024 * 1024;
    private static final long MAX_AUDIO = 40L * 1024 * 1024;
    private static final long MAX_IMAGE = 20L * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spectate-timer");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Integer, Relay> RELAYS = new ConcurrentHashMap<>();
    private static final AtomicInteger VIEWERS = new AtomicInteger();
    private static final Map<Integer, CachedStatus> STATUS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Path>> INFLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, Long> MISSING = new ConcurrentHashMap<>();

    private record CachedStatus(long at, ObjectNode body) {
    }

    private SpectateRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/spectate/status/{id}", SpectateRoutes::status);
        config.routes.sse("/spectate/stream/{id}", SpectateRoutes::stream);
        config.routes.get("/spectate/asset/{id}/{kind}", SpectateRoutes::asset);

        TIMER.scheduleAtFixedRate(SpectateRoutes::housekeeping, 15, 15, TimeUnit.SECONDS);
    }

    private static int userId(String raw) {
        try {
            int id = Integer.parseInt(raw);
            return id > 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ status

    private static void status(Context ctx) {
        int id = userId(ctx.pathParam("id"));

        if (id < 0) {
            ctx.status(404);
            return;
        }

        ctx.header("Cache-Control", "no-store");
        ctx.json(currentStatus(id));
    }

    /** The server the player is in gameplay on, else the one they are online on, else offline. */
    static ObjectNode currentStatus(int id) {
        CachedStatus cached = STATUS.get(id);
        long now = System.currentTimeMillis();

        if (cached != null && now - cached.at() < STATUS_TTL_MS) {
            return cached.body();
        }

        CompletableFuture<JsonNode> stable = CompletableFuture.supplyAsync(() -> upstreamStatus(App.api.getBaseUrl(), id));
        CompletableFuture<JsonNode> lazer = App.lazer == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> upstreamStatus(App.lazer.getBaseUrl(), id));

        JsonNode s = stable.join();
        JsonNode l = lazer.join();

        ObjectNode out;

        if (s != null && s.path("playing").asBoolean()) {
            out = (ObjectNode) s.deepCopy();
        } else if (l != null && l.path("playing").asBoolean()) {
            out = (ObjectNode) l.deepCopy();
        } else if (s != null && s.path("online").asBoolean()) {
            out = (ObjectNode) s.deepCopy();
        } else if (l != null && l.path("online").asBoolean()) {
            out = (ObjectNode) l.deepCopy();
        } else {
            out = MAPPER.createObjectNode();
            out.put("online", false);
            out.put("playing", false);
        }

        out.put("user_id", id);
        STATUS.put(id, new CachedStatus(now, out));
        return out;
    }

    private static JsonNode upstreamStatus(String base, int id) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/spectate/status?id=" + id))
                    .timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            return r.statusCode() == 200 ? MAPPER.readTree(r.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the relay

    /** One browser. Sends are serialised: the two upstreams deliver on threads of their own. */
    private static final class Viewer {
        final SseClient client;

        Viewer(SseClient client) {
            this.client = client;
        }

        synchronized boolean send(String event, String data) {
            if (client.terminated()) {
                return false;
            }

            try {
                if (event == null) {
                    client.sendComment(data);
                } else {
                    client.sendEvent(event, data);
                }

                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private record Held(double time, String data) {
    }

    /** Everything about one watched player: the browsers, the two upstream connections, and the recent frames. */
    private static final class Relay {
        final int userId;
        final Set<Viewer> viewers = ConcurrentHashMap.newKeySet();
        final List<Upstream> upstreams = new ArrayList<>();
        final Map<String, String> lastState = new ConcurrentHashMap<>();
        final Map<String, Deque<Held>> backlog = new ConcurrentHashMap<>();
        volatile long emptySince = 0;
        volatile boolean closed = false;

        Relay(int userId) {
            this.userId = userId;
        }

        void start() {
            upstreams.add(new Upstream(this, "stable", App.api.getBaseUrl()));

            if (App.lazer != null) {
                upstreams.add(new Upstream(this, "lazer", App.lazer.getBaseUrl()));
            }

            for (Upstream u : upstreams) {
                Thread.ofVirtual().name("spectate-" + u.source + "-" + userId).start(u);
            }
        }

        void stop() {
            closed = true;

            for (Upstream u : upstreams) {
                u.stop();
            }
        }

        /** A new browser gets the latest state of both servers and the frames held, then the live events. */
        void welcome(Viewer v) {
            for (String state : lastState.values()) {
                v.send("state", state);
            }

            for (Deque<Held> held : backlog.values()) {
                List<Held> copy;

                synchronized (held) {
                    copy = new ArrayList<>(held);
                }

                for (Held h : copy) {
                    v.send("frames", h.data());
                }
            }
        }

        void dispatch(String source, String event, String data) {
            switch (event) {
                case "state" -> {
                    lastState.put(source, data);

                    if (!readPlaying(data)) {
                        backlog.remove(source);
                    }
                }
                case "end" -> backlog.remove(source);
                case "frames" -> hold(source, data);
                default -> {
                }
            }

            for (Viewer v : viewers) {
                if (!v.send(event, data)) {
                    leave(this, v);
                }
            }
        }

        private void hold(String source, String data) {
            double time;

            try {
                JsonNode frames = MAPPER.readTree(data).path("frames");
                time = frames.size() == 0 ? -1 : frames.get(frames.size() - 1).path(0).asDouble();
            } catch (IOException e) {
                return;
            }

            if (time < 0) {
                return;
            }

            Deque<Held> held = backlog.computeIfAbsent(source, k -> new ArrayDeque<>());

            synchronized (held) {
                // time going back means a retry or a new map: what was held belongs to another play
                if (!held.isEmpty() && time + 1000 < held.peekLast().time()) {
                    held.clear();
                }

                held.addLast(new Held(time, data));

                while (held.size() > BACKLOG_EVENTS || (held.size() > 1 && time - held.peekFirst().time() > BACKLOG_MS)) {
                    held.pollFirst();
                }
            }
        }

        private static boolean readPlaying(String data) {
            try {
                return MAPPER.readTree(data).path("playing").asBoolean();
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** One server-sent events connection to bancho.jar or lazer.jar, reconnected until the relay stops. */
    private static final class Upstream implements Runnable {
        final Relay relay;
        final String source;
        final String url;
        volatile InputStream body;
        volatile boolean stopped;

        Upstream(Relay relay, String source, String base) {
            this.relay = relay;
            this.source = source;
            this.url = base + "/api/v1/spectate/stream?id=" + relay.userId;
        }

        void stop() {
            stopped = true;
            InputStream in = body;

            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // unblocks the reading thread
                }
            }
        }

        @Override
        public void run() {
            long backoff = 1000;

            while (!stopped) {
                long started = System.currentTimeMillis();

                try {
                    HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .header("Accept", "text/event-stream").GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());

                    body = r.body();

                    if (r.statusCode() != 200) {
                        r.body().close();
                        throw new IOException("answered " + r.statusCode());
                    }

                    read(r.body());
                } catch (Exception e) {
                    if (!stopped) {
                        logger.debug("Spectate upstream {} for {} dropped: {}", source, relay.userId, e.getMessage());
                    }
                } finally {
                    body = null;
                }

                if (stopped) {
                    break;
                }

                // a connection that lived a while was fine; start the backoff over
                backoff = System.currentTimeMillis() - started > 30_000 ? 1000 : Math.min(backoff * 2, 15_000);

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private void read(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;

            while (!stopped && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        relay.dispatch(source, event, data.toString());
                    }

                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }

                    data.append(line.substring(5).stripLeading());
                }
                // comments (":ping") and ids are not passed on; the relay pings the browsers itself
            }
        }
    }

    private static void stream(SseClient client) {
        int id = userId(client.ctx().pathParam("id"));

        if (id < 0 || VIEWERS.get() >= MAX_VIEWERS) {
            client.sendEvent("error", "{\"error\":\"unavailable\"}");
            client.close();
            return;
        }

        Viewer viewer = new Viewer(client);
        Relay[] joined = { null };

        RELAYS.compute(id, (k, relay) -> {
            if (relay == null || relay.closed) {
                relay = new Relay(id);
                relay.start();
            }

            if (relay.viewers.size() < MAX_VIEWERS_PER_PLAYER) {
                relay.viewers.add(viewer);
                relay.emptySince = 0;
                VIEWERS.incrementAndGet();
                joined[0] = relay;
            }

            return relay;
        });

        if (joined[0] == null) {
            client.sendEvent("error", "{\"error\":\"too many viewers\"}");
            client.close();
            return;
        }

        Relay relay = joined[0];
        client.keepAlive();
        client.onClose(() -> leave(relay, viewer));
        relay.welcome(viewer);
    }

    private static void leave(Relay relay, Viewer viewer) {
        if (relay.viewers.remove(viewer)) {
            VIEWERS.decrementAndGet();

            if (relay.viewers.isEmpty()) {
                relay.emptySince = System.currentTimeMillis();
            }
        }
    }

    private static void housekeeping() {
        long now = System.currentTimeMillis();

        for (Relay relay : RELAYS.values()) {
            for (Viewer v : relay.viewers) {
                if (!v.send(null, "ping")) {
                    leave(relay, v);
                }
            }

            RELAYS.computeIfPresent(relay.userId, (k, r) -> {
                if (r.viewers.isEmpty() && r.emptySince > 0 && now - r.emptySince > LINGER_MS) {
                    r.stop();
                    return null;
                }

                return r;
            });
        }

        STATUS.entrySet().removeIf(e -> now - e.getValue().at() > 60_000);
        MISSING.entrySet().removeIf(e -> now - e.getValue() > MISSING_TTL_MS);
    }

    // ------------------------------------------------------------------ beatmap files

    private static void asset(Context ctx) {
        String id = ctx.pathParam("id");
        String kind = ctx.pathParam("kind");

        if (!id.matches("\\d{1,10}") || !(kind.equals("osu") || kind.equals("audio") || kind.equals("background"))) {
            ctx.status(404);
            return;
        }

        String key = id + "." + kind;
        Long missingAt = MISSING.get(key);

        if (missingAt != null && System.currentTimeMillis() - missingAt < MISSING_TTL_MS) {
            ctx.status(404);
            return;
        }

        Path target = CACHE_DIR.resolve(key);

        try {
            if (!Files.isRegularFile(target)) {
                Path fetched = INFLIGHT.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(id, kind, target)))
                        .whenComplete((p, e) -> INFLIGHT.remove(key)).join();

                if (fetched == null) {
                    MISSING.put(key, System.currentTimeMillis());
                    ctx.status(404);
                    return;
                }
            }

            String type = kind.equals("osu") ? "text/plain; charset=utf-8" : readType(target);
            serve(ctx, target, type);
        } catch (Exception e) {
            logger.warn("Could not serve {} of beatmap {}: {}", kind, id, e.getMessage());
            ctx.status(502);
        }
    }

    private static Path fetch(String id, String kind, Path target) {
        try {
            Files.createDirectories(CACHE_DIR);

            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(
                    URI.create(App.api.getBaseUrl() + "/api/v1/beatmap_asset?id=" + id + "&kind=" + kind))
                    .timeout(Duration.ofSeconds(180)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());

            if (r.statusCode() != 200) {
                r.body().close();
                return null;
            }

            long max = switch (kind) {
                case "osu" -> MAX_OSU;
                case "audio" -> MAX_AUDIO;
                default -> MAX_IMAGE;
            };

            Path partial = target.resolveSibling(target.getFileName() + ".part");

            try (InputStream in = r.body()) {
                byte[] data = in.readNBytes((int) max + 1);

                if (data.length == 0 || data.length > max) {
                    return null;
                }

                Files.write(partial, data);
            }

            String type = r.headers().firstValue("Content-Type").orElse("application/octet-stream");

            if (!kind.equals("osu")) {
                Files.writeString(target.resolveSibling(target.getFileName() + ".type"), type);
            }

            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            logger.warn("Could not copy {} of beatmap {}: {}", kind, id, e.getMessage());
            return null;
        }
    }

    private static String readType(Path target) {
        try {
            String type = Files.readString(target.resolveSibling(target.getFileName() + ".type")).strip();

            // only the types bancho.jar sends; nothing else is passed to the browser
            if (type.matches("(audio|image)/[a-z0-9.+-]+")) {
                return type;
            }
        } catch (IOException ignored) {
            // fall through
        }

        return "application/octet-stream";
    }

    /** A file from the cache, with byte ranges: the page seeks the song to the player's position. */
    private static void serve(Context ctx, Path path, String type) throws IOException {
        long size = Files.size(path);
        long start = 0;
        long end = size - 1;
        String range = ctx.header("Range");

        ctx.header("Accept-Ranges", "bytes");
        ctx.header("Cache-Control", "public, max-age=86400");
        ctx.contentType(type);

        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring(6).split(",")[0].split("-", 2);

            try {
                if (!parts[0].isBlank()) {
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

        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        channel.position(start);
        InputStream in = Channels.newInputStream(channel);
        ctx.result(length == size ? in : new InputStream() {
            long left = length;

            @Override
            public int read() throws IOException {
                if (left <= 0) {
                    return -1;
                }

                int b = in.read();

                if (b >= 0) {
                    left--;
                }

                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (left <= 0) {
                    return -1;
                }

                int n = in.read(b, off, (int) Math.min(len, left));

                if (n > 0) {
                    left -= n;
                }

                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        });
    }
}
