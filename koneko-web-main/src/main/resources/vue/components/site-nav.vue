<template id="site-nav">
    <!-- The restriction strip belongs above everything, so it is part of the
         bar every page already renders rather than something each page has to
         remember to include. It renders nothing at all unless the account
         looking at the page is restricted. -->
    <header class="nav">
        <div class="nav-inner">
            <a class="nav-brand" href="/">
                <span class="nav-logo">
                    <img v-if="logo" :src="logo" :alt="site.name">
                    <span v-else>{{ initials }}</span>
                </span>

                <span class="nav-brand-text">
                    <span class="nav-name">{{ site.name }}</span>
                    <span class="nav-tagline" v-if="site.tagline">{{ site.tagline }}</span>
                </span>
            </a>

            <!-- One list, built from the core entries and the ones plugins
                 added, sorted by order. A plugin can hide any of them by id,
                 so a core link can be replaced rather than duplicated. -->
            <nav class="nav-links">
                <a v-for="item in navLinks" :key="item.id" :href="item.href"
                    :target="item.external ? '_blank' : null"
                    :rel="item.external ? 'noopener' : null">{{ item.label }}</a>

                <koneko-slot name="nav.links"></koneko-slot>
            </nav>

            <!-- A player search in the bar itself: looking someone up is the
                 thing people come here to do most often, and it should not need
                 a page of its own to start. -->
            <form class="nav-search" @submit.prevent="searchPlayers">
                <input class="nav-search-input" type="search" v-model="query"
                    placeholder="Find a player" aria-label="Find a player">
            </form>

            <div class="nav-actions">
                <!-- Everything that belongs to the person rather than to the site
                     sits behind one control: the profile, the settings, the staff
                     panel and the way out. They were four separate items competing
                     for room with the section links, which is what pushed the bar
                     into two lines. -->
                <div class="nav-menu" v-if="user">
                    <!-- @click.stop, or the document listener below would close the
                         menu in the same click that opened it. -->
                    <button class="nav-menu-trigger" :class="menuOpen ? 'is-open' : ''"
                        type="button" aria-haspopup="true"
                        :aria-expanded="menuOpen ? 'true' : 'false'"
                        @click.stop="menuOpen = !menuOpen">
                        <img class="nav-user-avatar" :src="avatar" :alt="user.name">
                        <span class="nav-menu-name">{{ user.name }}</span>
                        <span class="nav-menu-caret" aria-hidden="true">&#9662;</span>
                    </button>

                    <div class="nav-menu-panel" v-if="menuOpen" @click.stop>
                        <a class="nav-menu-item" :href="'/u/' + user.id">Profile</a>
                        <a class="nav-menu-item" href="/friends">Friends</a>
                        <a class="nav-menu-item" href="/settings">Settings</a>

                        <!-- Staff only, and set apart: it leaves the site proper for
                             the panel, which is a different kind of place. -->
                        <template v-if="isStaff">
                            <span class="nav-menu-line"></span>
                            <a class="nav-menu-item" href="/admin">Staff panel</a>
                        </template>

                        <span class="nav-menu-line"></span>

                        <button class="nav-menu-item is-danger" type="button"
                            @click="logout">Log out</button>
                    </div>
                </div>

                <template v-else>
                    <!-- The way in for somebody who has no account yet sits next
                         to the way in for somebody who has one, and disappears
                         with REGISTRATION_ENABLED. -->
                    <!-- button-ghost, not button-quiet: the latter only drops the
                         background, and .button carries no border, so the label
                         was left standing on nothing. -->
                    <a class="button button-small button-ghost" v-if="registration.enabled"
                        href="/register">Sign up</a>

                    <a class="button button-small" href="/login">Log in</a>
                </template>

                <koneko-slot name="nav.actions"></koneko-slot>
            </div>
        </div>

        <restriction-banner></restriction-banner>
    </header>
</template>

<script>
    app.component("site-nav", {
        template: "#site-nav",
        data: () => ({
            query: "",
            menuOpen: false
        }),
        computed: {
            site() {
                return this.$koneko.site || {};
            },
            links() {
                return this.site.links || {};
            },
            home() {
                return this.site.home || {};
            },
            logo() {
                return this.home.logoImage || "";
            },
            // Without a logo the mark falls back to the initials of the
            // server name, so the bar never has an empty hole in it.
            initials() {
                const name = String(this.site.name || "").trim();

                if (!name) return "?";

                return name.split(/\s+/).slice(0, 2)
                    .map(word => word.charAt(0).toUpperCase()).join("");
            },
            user() {
                return this.$koneko.user;
            },
            registration() {
                return this.$koneko.registration || { enabled: false };
            },
            // The links the site itself owns. Every one has an id, which is
            // what registry.hideNavItem(id) refers to.
            coreLinks() {
                return [
                    { id: "leaderboard", label: "Leaderboard", href: "/leaderboard", order: 10 },
                    { id: "beatmaps", label: "Beatmaps", href: "/beatmaps", order: 20 },
                    { id: "community", label: "Community", href: this.links.discord, order: 30, external: true },
                    { id: "api", label: "API", href: this.links.apiDocs, order: 40, external: true }
                ];
            },
            // The same four bits the server checks. This decides whether the
            // staff entry appears in the account menu, never whether a request
            // is allowed: every panel endpoint checks the privileges again.
            isStaff() {
                const user = this.$koneko.user;

                if (!user) return false;

                const staffMask = (1 << 11) | (1 << 12) | (1 << 13) | (1 << 14);

                return ((user.priv || 0) & staffMask) !== 0;
            },
            // Plugin entries are already filtered by the server: one that needs
            // privileges is never sent to a browser that may not see it.
            navLinks() {
                return this.mergeNav(this.coreLinks, this.pluginNav("nav"));
            },
            avatar() {
                return "https://a." + this.$koneko.domain + "/" + this.user.id;
            }
        },
        methods: {
            // Core entries and plugin entries, minus everything hidden, in
            // order. Entries without a href (an unset Discord link, say) drop
            // out on their own.
            mergeNav(core, contributed) {
                const hidden = this.pluginNavHidden();

                return core.concat(contributed)
                    .filter(item => item.href && hidden.indexOf(item.id) === -1)
                    .sort((left, right) => (left.order || 0) - (right.order || 0));
            },
            // An empty box still goes to the directory: that page lists
            // everyone, so it is a useful place to end up either way.
            searchPlayers() {
                const query = this.query.trim();

                window.location.href = query
                    ? "/players?q=" + encodeURIComponent(query)
                    : "/players";
            },
            async logout() {
                await fetch("/auth/logout", { method: "POST" });
                window.location.href = "/";
            },
            closeMenu() {
                this.menuOpen = false;
            },
            onKey(event) {
                if (event.key === "Escape") {
                    this.menuOpen = false;
                }
            }
        },
        /*
         * A click anywhere else, or Escape, closes the menu. Listening on the
         * document rather than on a blur handler is what makes a click on the
         * page behind it dismiss the menu instead of being swallowed.
         */
        mounted() {
            document.addEventListener("click", this.closeMenu);
            document.addEventListener("keydown", this.onKey);
        },
        unmounted() {
            document.removeEventListener("click", this.closeMenu);
            document.removeEventListener("keydown", this.onKey);
        }
    });
</script>
