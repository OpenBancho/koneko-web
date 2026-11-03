package com.osuserverlist.koneko.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.koneko.api.TokenPair;
import com.osuserverlist.koneko.config.Env;

/**
 * Store of logged in browsers, kept in memory and mirrored to a file.
 *
 * <p>Deliberately not backed by Redis: koneko-web has no database of its own, and a
 * handful of sessions does not justify a second service. The map is written to
 * {@code data/sessions.json} (override with the {@code SESSIONS_FILE} environment
 * variable) when a session is created or removed, every 30 seconds, and on shutdown,
 * then read back at boot, so a restart no longer logs everybody out. The file holds
 * API tokens: keep it out of version control and out of backups you share.
 * Idle sessions are swept once a minute so a long uptime does not leak memory.
 */
public final class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger("SessionStore");

    private static final int ID_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(Env.SESSION_TIMEOUT_MINUTES);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Object SAVE_LOCK = new Object();

    private static final Path FILE = Path.of(
            System.getenv("SESSIONS_FILE") == null || System.getenv("SESSIONS_FILE").isBlank()
                    ? "data/sessions.json"
                    : System.getenv("SESSIONS_FILE"));

    private static volatile ScheduledExecutorService sweeper;

    private SessionStore() {
    }

    /** How long a cookie stays valid, in seconds. */
    public static int idleTimeoutSeconds() {
        return (int) IDLE_TIMEOUT.toSeconds();
    }

    /** Called once at boot to start the sweeper. */
    public static synchronized void configure() {
        if (sweeper != null) {
            return;
        }

        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "koneko-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });

        load();

        sweeper.scheduleAtFixedRate(SessionStore::sweep, 1, 1, TimeUnit.MINUTES);
        // lastSeen and refreshed tokens change without any create/remove, so snapshot regularly too.
        sweeper.scheduleAtFixedRate(SessionStore::save, 30, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(SessionStore::save, "koneko-session-save"));
    }

    /** Stores a session and returns the id the browser gets as a cookie. */
    public static String create(UserSession session) {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);

        String id = ENCODER.encodeToString(bytes);
        SESSIONS.put(id, session);
        save();

        return id;
    }

    /** Returns the session, or null when it is unknown or has gone idle. */
    public static UserSession get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }

        UserSession session = SESSIONS.get(id);

        if (session == null) {
            return null;
        }

        if (isExpired(session)) {
            SESSIONS.remove(id);
            return null;
        }

        session.touch();
        return session;
    }

    public static UserSession remove(String id) {
        UserSession removed = id == null ? null : SESSIONS.remove(id);

        if (removed != null) {
            save();
        }

        return removed;
    }

    public static int size() {
        return SESSIONS.size();
    }

    private static boolean isExpired(UserSession session) {
        return Instant.now().isAfter(session.getLastSeen().plus(IDLE_TIMEOUT))
                || session.getTokens().isDead();
    }

    private static void sweep() {
        int before = SESSIONS.size();

        Iterator<Map.Entry<String, UserSession>> iterator = SESSIONS.entrySet().iterator();

        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getValue())) {
                iterator.remove();
            }
        }

        int removed = before - SESSIONS.size();

        if (removed > 0) {
            logger.debug("Swept {} idle session(s), {} left", removed, SESSIONS.size());
        }
    }

    // ------------------------------------------------------------ persistence

    private static void save() {
        synchronized (SAVE_LOCK) {
            try {
                List<Map<String, Object>> rows = new ArrayList<>();

                for (Map.Entry<String, UserSession> entry : SESSIONS.entrySet()) {
                    UserSession s = entry.getValue();
                    TokenPair t = s.getTokens();

                    if (isExpired(s)) {
                        continue;
                    }

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.getKey());
                    row.put("userId", s.getUserId());
                    row.put("username", s.getUsername());
                    row.put("privileges", s.getPrivileges());
                    row.put("lastSeen", s.getLastSeen().toEpochMilli());
                    row.put("staffCodeAt", s.getStaffCodeAt().toEpochMilli());
                    row.put("twoFactorEnabled", s.isTwoFactorEnabled());
                    row.put("lastTwoFactorCheck", s.getLastTwoFactorCheck().toEpochMilli());
                    row.put("accessToken", t.getAccessToken());
                    row.put("refreshToken", t.getRefreshToken());
                    row.put("scope", t.getScope());
                    row.put("accessExpiresAt", t.getAccessExpiresAt().toEpochMilli());
                    row.put("refreshExpiresAt", t.getRefreshExpiresAt().toEpochMilli());
                    rows.add(row);
                }

                Path parent = FILE.toAbsolutePath().getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                JSON.writeValue(temp.toFile(), rows);

                try {
                    // Tokens inside: readable by the owner only, where the filesystem knows about owners.
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Windows: the file inherits the folder's permissions.
                }

                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not save sessions to {}: {}", FILE, e.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }

        try {
            List<Map<String, Object>> rows = JSON.readValue(FILE.toFile(), List.class);

            for (Map<String, Object> row : rows) {
                TokenPair tokens = new TokenPair(
                        (String) row.get("accessToken"),
                        (String) row.get("refreshToken"),
                        (String) row.get("scope"),
                        Instant.ofEpochMilli(((Number) row.get("accessExpiresAt")).longValue()),
                        Instant.ofEpochMilli(((Number) row.get("refreshExpiresAt")).longValue()));

                UserSession session = new UserSession(
                        ((Number) row.get("userId")).intValue(),
                        (String) row.get("username"),
                        ((Number) row.get("privileges")).intValue(),
                        tokens);

                session.setLastSeen(Instant.ofEpochMilli(((Number) row.get("lastSeen")).longValue()));
                session.setStaffCodeAt(Instant.ofEpochMilli(((Number) row.get("staffCodeAt")).longValue()));
                session.setTwoFactorEnabled(Boolean.TRUE.equals(row.get("twoFactorEnabled")));
                session.setLastTwoFactorCheck(
                        Instant.ofEpochMilli(((Number) row.get("lastTwoFactorCheck")).longValue()));

                if (!isExpired(session)) {
                    SESSIONS.put((String) row.get("id"), session);
                }
            }

            logger.info("Restored {} session(s) from {}", SESSIONS.size(), FILE);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read sessions from {}, starting empty: {}", FILE, e.toString());
        }
    }
}
