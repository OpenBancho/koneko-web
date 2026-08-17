package com.osuserverlist.koneko.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.osuserverlist.koneko.plugin.api.KonekoContext;
import com.osuserverlist.koneko.plugin.api.PluginAction;
import com.osuserverlist.koneko.plugin.api.PluginAsset;
import com.osuserverlist.koneko.plugin.api.PluginFilter;
import com.osuserverlist.koneko.plugin.api.PluginJob;
import com.osuserverlist.koneko.plugin.api.PluginNavItem;
import com.osuserverlist.koneko.plugin.api.PluginPage;
import com.osuserverlist.koneko.plugin.api.PluginSlot;
import com.osuserverlist.koneko.plugin.api.StateContributor;

/**
 * Everything the loaded plugins asked for, in one place.
 *
 * <p>Filled while the plugins register, then only read: the lists are built
 * before the HTTP server exists and never change afterwards, which is why plain
 * ArrayLists are enough here.
 */
final class Contributions {

    /** One page, remembering which plugin asked for it. */
    record Page(String pluginId, PluginPage page) {
    }

    /** One endpoint, with the path it is actually mounted at. */
    record Action(String pluginId, String mountedPath, PluginAction action) {
    }

    /** One vue component, already read into a string. */
    record Component(String pluginId, String name, String source) {
    }

    /** One slot entry. */
    record Slot(String pluginId, PluginSlot slot) {
    }

    /** One navigation entry. */
    record Nav(String pluginId, PluginNavItem item) {
    }

    /** One single asset. */
    record Asset(String pluginId, String urlPath, PluginAsset asset, ClassLoader loader) {
    }

    /** A whole asset directory of a plugin jar. */
    record AssetRoot(String pluginId, String resourceRoot, ClassLoader loader) {
    }

    /** One state contributor. */
    record State(String pluginId, String key, StateContributor contributor) {
    }

    /** One scheduled job. */
    record Job(String pluginId, PluginJob job) {
    }

    /** One request filter. */
    record Filter(String pluginId, PluginFilter filter) {
    }

    /** Raw html injected into the shell. */
    record Html(String pluginId, String html) {
    }

    final List<Page> pages = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final List<Component> components = new ArrayList<>();
    final List<Slot> slots = new ArrayList<>();
    final List<Nav> navItems = new ArrayList<>();

    /** Ids of navigation entries plugins asked to hide, core ones included. */
    final Set<String> hiddenNav = new LinkedHashSet<>();

    final List<Asset> assets = new ArrayList<>();
    final List<AssetRoot> assetRoots = new ArrayList<>();
    final List<State> state = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    final List<Filter> filters = new ArrayList<>();
    final List<Html> head = new ArrayList<>();
    final List<Html> bodyEnd = new ArrayList<>();

    /** The context of every started plugin, by id. */
    final Map<String, KonekoContext> contexts = new LinkedHashMap<>();

    /** Paths the core site owns, so a plugin page cannot shadow one. */
    static final List<String> RESERVED_PREFIXES = List.of(
            "/data/", "/auth/", "/account/", "/css/", "/js/", "/img/",
            "/plugin-assets/", "/plugins/");

    /** Exact paths of the core pages. */
    static final List<String> RESERVED_PATHS = List.of(
            "/", "/login", "/me", "/leaderboard", "/beatmaps", "/settings");

    boolean isReserved(String path) {
        if (path == null || !path.startsWith("/")) {
            return true;
        }

        if (RESERVED_PATHS.contains(path)) {
            return true;
        }

        for (String prefix : RESERVED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        // The two parameterised core pages.
        return path.startsWith("/u/") || path.startsWith("/beatmapsets/");
    }

    /** True when a page of another plugin already took this exact path. */
    boolean pageTaken(String path) {
        return pages.stream().anyMatch(page -> page.page().path().equals(path));
    }

    /** True when a component of this name was already registered. */
    boolean componentTaken(String name) {
        return components.stream().anyMatch(component -> component.name().equals(name));
    }

    /** Ids of every plugin that contributed anything at all. */
    List<String> pluginIds() {
        return List.copyOf(contexts.keySet());
    }
}
