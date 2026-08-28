package com.osuserverlist.koneko.vue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * A tiny Vue layer in the spirit of the JavalinVue plugin.
 *
 * <p>The plugin itself only exists up to Javalin 6 - Javalin 7 dropped it, so
 * this class does the same three things it did, and nothing more:
 *
 * <ul>
 *   <li>{@code layout.html} is the single HTML shell of the site;</li>
 *   <li>{@code @componentRegistration} is replaced by every {@code .vue} file
 *       found under {@code /vue}, so a new component is a new file and nothing
 *       else;</li>
 *   <li>{@code @routeComponent} is replaced by the component of the route being
 *       served, which is what makes one route one line.</li>
 * </ul>
 *
 * <p>In production everything is read from the classpath once and cached, so a
 * page render is two string replacements. In development ({@code LEVEL=DEV})
 * the files are re-read from {@code src/main/resources/vue} on every request,
 * so editing a component only needs a refresh.
 */
public final class KonekoVue {

    private static final Logger logger = LoggerFactory.getLogger("KonekoVue");

    private static final String CLASSPATH_ROOT = "/vue";
    private static final Path DEV_ROOT = Paths.get("src", "main", "resources", "vue");

    private static final String LAYOUT = "layout.html";
    // The markers are matched as whole lines. A marker name that appears
    // inside a comment must never be substituted: the injected components
    // carry their own </script>, so a replacement inside the layout script
    // block would close it early and dump the rest of the file as text.
    private static final Pattern COMPONENT_MARKER = marker("@componentRegistration");
    private static final Pattern ROUTE_MARKER = marker("@routeComponent");
    // The two plugin markers. They are optional: a layout without them still
    // renders, plugins simply cannot inject html into the shell.
    private static final Pattern PLUGIN_HEAD_MARKER = marker("@pluginHead");
    private static final Pattern PLUGIN_BODY_MARKER = marker("@pluginBody");

    private static Pattern marker(String name) {
        return Pattern.compile("^[ \\t]*" + Pattern.quote(name) + "[ \\t]*$", Pattern.MULTILINE);
    }

    private static boolean devMode;

    private static volatile String cachedLayout;
    private static volatile String cachedComponents;

    // Filled by the plugin host at boot. They stay null when no plugin is
    // loaded, which keeps a plugin-free site byte for byte what it was.
    private static volatile Supplier<String> pluginComponents;
    private static volatile Supplier<String> pluginHead;
    private static volatile Supplier<String> pluginBodyEnd;
    private static volatile BiConsumer<Context, String> renderHook;

    private KonekoVue() {
    }

    /**
     * @param dev when true, the .vue files are re-read per request from the
     *            source tree instead of the classpath.
     */
    public static void configure(boolean dev) {
        devMode = dev;
        cachedLayout = null;
        cachedComponents = null;
    }

    /**
     * Lets the plugin host contribute to the shell: the components of every
     * plugin, plus whatever they asked to put in the head and at the end of the
     * body.
     */
    public static void setPluginSources(Supplier<String> components, Supplier<String> head,
            Supplier<String> bodyEnd) {

        pluginComponents = components;
        pluginHead = head;
        pluginBodyEnd = bodyEnd;
    }

    /** Called on every page render, so plugins can see the traffic. */
    public static void setRenderHook(BiConsumer<Context, String> hook) {
        renderHook = hook;
    }

    /** A handler rendering one component as a full page. */
    public static Handler component(String name) {
        return component(name, 200);
    }

    /** A handler rendering one component as a full page with a given status. */
    public static Handler component(String name, int status) {
        return ctx -> render(ctx, name, status);
    }

    private static void render(Context ctx, String component, int status) throws IOException {
        // Plugin components go after the core ones, so a plugin component may
        // use any core component and can never replace one.
        String html = replace(layout(), COMPONENT_MARKER, components() + fromPlugin(pluginComponents));
        html = replace(html, ROUTE_MARKER, "<" + component + "></" + component + ">");
        html = replace(html, PLUGIN_HEAD_MARKER, fromPlugin(pluginHead));
        html = replace(html, PLUGIN_BODY_MARKER, fromPlugin(pluginBodyEnd));

        BiConsumer<Context, String> hook = renderHook;

        if (hook != null) {
            hook.accept(ctx, component);
        }

        ctx.status(status);
        ctx.contentType("text/html; charset=utf-8");
        ctx.result(html);
    }

    /** A plugin contribution, or an empty string when there is none. */
    private static String fromPlugin(Supplier<String> source) {
        if (source == null) {
            return "";
        }

        String value = source.get();

        return value == null ? "" : value;
    }

    private static String layout() throws IOException {
        String cached = cachedLayout;

        if (cached != null && !devMode) {
            return cached;
        }

        String layout = readFile(LAYOUT);

        if (!ROUTE_MARKER.matcher(layout).find()) {
            logger.warn("{} has no route marker on its own line, pages will render empty", LAYOUT);
        }

        cachedLayout = layout;
        return layout;
    }

    /** Substitutes a whole marker line, treating the replacement literally. */
    private static String replace(String source, Pattern marker, String replacement) {
        return marker.matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Warns when a component ends its script block before the end of the file.
     *
     * <p>The HTML parser closes a script element at the first {@code </script} it sees and
     * does not care that it sits inside a string or a comment. A component that spells the
     * sequence out mid-file therefore stops being JavaScript at that point: the rest lands on
     * the page as text, the browser reports a syntax error, and any markup further down - a
     * stylesheet especially - is applied for real. That failure is silent on the server and
     * looks nothing like its cause in the browser, which is exactly why it is checked here.
     *
     * <p>The fix in a component is to write the tag with an escape, {@code \u005Cu003c/script>},
     * which the parser does not recognise while JavaScript still reads it as the same string.
     */
    private static void warnAboutEarlyScriptEnd(String file, String source) {
        int last = source.lastIndexOf("</script");

        if (last < 0) {
            return;
        }

        int first = source.indexOf("</script");

        if (first == last) {
            return;
        }

        long line = source.chars().limit(first).filter(c -> c == '\n').count() + 1;

        logger.error("{} spells out a closing script tag on line {}, before the end of the file."
                + " Everything after it is served as text instead of JavaScript."
                + " Write it as \\u003c/script> instead.", file, line);
    }

    private static String components() throws IOException {
        String cached = cachedComponents;

        if (cached != null && !devMode) {
            return cached;
        }

        StringBuilder builder = new StringBuilder();

        for (String file : componentFiles()) {
            String source = readFile(file);

            warnAboutEarlyScriptEnd(file, source);

            builder.append("<!-- ").append(file).append(" -->\n");
            builder.append(source).append('\n');
        }

        String components = builder.toString();

        cachedComponents = components;
        return components;
    }

    /** Every .vue file under the vue root, as paths relative to that root. */
    private static List<String> componentFiles() throws IOException {
        if (devMode && Files.isDirectory(DEV_ROOT)) {
            return collect(DEV_ROOT);
        }

        URL url = KonekoVue.class.getResource(CLASSPATH_ROOT);

        if (url == null) {
            throw new IOException("The " + CLASSPATH_ROOT + " directory is missing from the classpath");
        }

        URI uri;

        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Could not read " + CLASSPATH_ROOT, e);
        }

        if (!"jar".equals(uri.getScheme())) {
            return collect(Paths.get(uri));
        }

        // Running from the shaded jar: mount it as a filesystem to list it. The
        // filesystem is left open on purpose, the result is cached anyway.
        FileSystem fs;

        try {
            fs = FileSystems.newFileSystem(uri, Map.of());
        } catch (FileSystemAlreadyExistsException e) {
            fs = FileSystems.getFileSystem(uri);
        }

        return collect(fs.getPath(CLASSPATH_ROOT));
    }

    private static List<String> collect(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<String> names = new ArrayList<>();

            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".vue"))
                    .forEach(path -> names.add(root.relativize(path).toString().replace('\\', '/')));

            // Components before views, so the shared pieces are defined first.
            names.sort(String::compareTo);
            return names;
        } catch (UncheckedIOException e) {
            throw new IOException("Could not list the vue files", e);
        }
    }

    private static String readFile(String relative) throws IOException {
        if (devMode) {
            Path path = DEV_ROOT.resolve(relative);

            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }

        try (InputStream in = KonekoVue.class.getResourceAsStream(CLASSPATH_ROOT + "/" + relative)) {
            if (in == null) {
                throw new IOException("Missing vue resource: " + relative);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
