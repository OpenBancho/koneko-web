<template id="admin-shell">
    <div class="page">
        <site-nav></site-nav>

        <div class="admin-layout">
            <!-- The narrow column. Which entries exist is the API's decision, not
                 this component's: a nominator has one section, a developer has a
                 different one, and neither is told about the other. -->
            <aside class="admin-side">
                <a class="admin-side-link" v-for="item in items" :key="item.id"
                    :href="item.href" :class="{ 'is-active': item.id === section }"
                    :title="item.label">
                    <span class="admin-side-label">{{ item.label }}</span>
                </a>
            </aside>

            <main class="admin-main">
                <div class="skeleton-rows" v-if="loading">
                    <div class="skeleton skeleton-row" v-for="n in 4" :key="n"></div>
                </div>

                <section class="card" v-else-if="error">
                    <p class="muted">{{ error }}</p>
                </section>

                <!-- The page itself, handed what the caller may do so it can hide
                     the buttons it must not offer. -->
                <slot v-else :access="access" :can="can"></slot>
            </main>
        </div>

        <site-footer></site-footer>
    </div>
</template>

<script>
    /**
     * The frame every staff page sits in: the navigation bar, the narrow section
     * column, and the page.
     *
     * The sidebar is built from /admin/api/access rather than from the privilege
     * bits in the session, because the bitmask is the API's to interpret. Two
     * copies of an access rule drift apart the first time a role changes, and the
     * copy living in the browser is the one that would be wrong.
     *
     * Hiding a section is a courtesy, not a defence. Every endpoint behind these
     * pages authorises its caller again.
     */
    app.component("admin-shell", {
        template: "#admin-shell",
        props: {
            // Which sidebar entry to mark as current.
            section: { type: String, default: "" }
        },
        data: () => ({
            loading: true,
            error: "",
            access: { roles: [], sections: [], actions: [] }
        }),
        computed: {
            items() {
                const sections = this.access.sections || [];

                const all = [
                    { id: "overview", label: "Overview", href: "/admin" },
                    { id: "themes", label: "Event Themes", href: "/admin/themes" },
                    { id: "requests", label: "Requests", href: "/admin/requests" },
                    { id: "moderation", label: "Moderation", href: "/admin/moderation" },
                    { id: "logs", label: "Logs", href: "/admin/logs" },
                    { id: "groups", label: "Groups", href: "/admin/groups" },
                    { id: "server", label: "Server", href: "/admin/server" }
                ];

                // Overview & Themes are always accessible for staff
                return all.filter(item => item.id === "overview" || item.id === "themes"
                    || sections.indexOf(item.id) !== -1);
            }
        },
        methods: {
            can(action) {
                return (this.access.actions || []).indexOf(action) !== -1;
            },
            async load() {
                try {
                    const answer = await this.session("GET", "/admin/api/access");

                    this.access = answer || { roles: [], sections: [], actions: [] };
                } catch (e) {
                    this.error = e.message || "The panel could not be loaded.";
                } finally {
                    this.loading = false;
                }
            }
        },
        created() {
            this.load();
        }
    });
</script>
