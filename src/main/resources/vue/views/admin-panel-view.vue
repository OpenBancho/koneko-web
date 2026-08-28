<template id="admin-panel-view">
    <!-- The shell is mounted once and stays mounted. Everything that used to be
         rebuilt on every tab change -- the navigation bar, the section column,
         the access request behind them -- now simply keeps existing. -->
    <admin-shell :section="section" v-slot="{ can }">
        <component :is="view" :can="can" :key="route"></component>
    </admin-shell>
</template>

<script>
    /**
     * The whole staff panel, as one page.
     *
     * Every /admin route is served by this component. It reads the address bar,
     * decides which section component belongs there, and swaps only that part
     * when a link inside the panel is clicked.
     *
     * Before this, each tab was its own server-rendered page: switching sections
     * threw away the navigation bar and the sidebar, downloaded them again, and
     * asked /admin/api/access again before anything could be drawn. That is what
     * made the frame flicker and shift on every click.
     *
     * Links are intercepted at the document rather than wired up template by
     * template, so the sidebar, the tabs and the player rows in the moderation
     * table all navigate this way without knowing about it. Anything pointing
     * outside /admin is left alone and still loads a real page.
     */
    app.component("admin-panel-view", {
        template: "#admin-panel-view",
        data: () => ({
            route: window.location.pathname + window.location.search
        }),
        computed: {
            path() {
                return this.route.split("?")[0];
            },
            parts() {
                return this.path.split("/").filter(part => part.length > 0);
            },
            /** Which sidebar entry to light up. */
            section() {
                const name = this.parts[1] || "overview";

                return ["themes", "requests", "moderation", "logs", "groups", "server"].indexOf(name) !== -1
                    ? name
                    : "overview";
            },
            view() {
                const name = this.parts[1];

                if (name === "themes") return "admin-themes-view";
                if (name === "requests") return "admin-requests-view";
                if (name === "logs") return "admin-logs-view";
                if (name === "groups") return "admin-groups-view";
                if (name === "server") return "admin-server-view";

                if (name === "moderation") {
                    // /admin/moderation/{userId} is one account, /admin/moderation
                    // is the list of them.
                    return this.parts.length > 2
                        ? "admin-player-view"
                        : "admin-moderation-view";
                }

                return "admin-overview-view";
            }
        },
        methods: {
            /**
             * Moves to a panel address without leaving the page.
             *
             * The route is read back from the address bar rather than from the
             * argument, so a relative href and the back button end up in the same
             * place.
             */
            go(href) {
                window.history.pushState({}, "", href);
                this.sync();
            },
            sync() {
                const route = window.location.pathname + window.location.search;

                if (route === this.route) return;

                this.route = route;

                // A new section starts at its own beginning, which is what a page
                // load used to do for free.
                window.scrollTo(0, 0);
            },
            onClick(event) {
                if (event.defaultPrevented || event.button !== 0) return;

                // Modified clicks belong to the browser: they open tabs and
                // windows, and taking them over would be rude.
                if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

                const link = event.target && event.target.closest
                    ? event.target.closest("a")
                    : null;

                if (!link || link.hasAttribute("download")) return;
                if (link.target && link.target !== "_self") return;
                if (link.host && link.host !== window.location.host) return;

                const href = link.getAttribute("href") || "";

                // Only the panel's own pages are handled here. A link out of it
                // has to leave the panel properly, shell and all.
                if (href.indexOf("/admin") !== 0) return;

                event.preventDefault();
                this.go(href);
            },
            onPop() {
                this.sync();
            }
        },
        mounted() {
            document.addEventListener("click", this.onClick);
            window.addEventListener("popstate", this.onPop);
        },
        unmounted() {
            document.removeEventListener("click", this.onClick);
            window.removeEventListener("popstate", this.onPop);
        }
    });
</script>
