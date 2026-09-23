<template id="site-footer">
    <footer class="footer">
        <div class="footer-top">
            <div class="footer-brand">
                <span class="footer-logo">
                    <img v-if="logo" :src="logo" :alt="site.name">
                    <span v-else>{{ initials }}</span>
                </span>

                <span class="footer-name">{{ site.name }}</span>
                <p class="footer-blurb" v-if="blurb">{{ blurb }}</p>
            </div>

            <nav class="footer-columns">
                <div class="footer-column" v-for="column in columns" :key="column.id">
                    <h4 class="footer-column-title">{{ column.label }}</h4>

                    <a v-for="item in column.items" :key="item.id" :href="item.href"
                        :target="item.external ? '_blank' : null"
                        :rel="item.external ? 'noopener' : null">{{ item.label }}</a>
                </div>

                <div class="footer-column footer-column-plugins">
                    <koneko-slot name="footer.links"></koneko-slot>
                </div>
            </nav>
        </div>

        <div class="footer-bottom">
            <span class="footer-copy">&copy; {{ year }} {{ site.name }}.</span>
            <span class="footer-note">Not affiliated with ppy.sh or osu!.</span>
            <span class="footer-built">powered by bancho.jar and koneko-web</span>
        </div>

        <koneko-slot name="footer.bottom"></koneko-slot>
    </footer>
</template>

<script>
    app.component("site-footer", {
        template: "#site-footer",
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
            year() {
                return new Date().getFullYear();
            },
            initials() {
                const name = String(this.site.name || "").trim();

                if (!name) return "?";

                return name.split(/\s+/).slice(0, 2)
                    .map(word => word.charAt(0).toUpperCase()).join("");
            },
            blurb() {
                const description = String(this.site.description || "").trim();

                if (!description) return this.site.tagline || "";

                const first = description.split(/\n\s*\n/)[0].replace(/\s+/g, " ").trim();

                return first.length > 220 ? first.slice(0, 217).trimEnd() + "\u2026" : first;
            },
            coreLinks() {
                return [
                    { id: "footer.leaderboard", label: "Leaderboard", href: "/leaderboard", group: "site", order: 10 },
                    { id: "footer.beatmaps", label: "Beatmaps", href: "/beatmaps", group: "site", order: 20 },
                    { id: "footer.connect", label: "How to connect", href: "/connect", group: "site", order: 25 },
                    { id: "footer.discord", label: "Discord", href: this.links.discord, group: "community", order: 10, external: true },
                    { id: "footer.github", label: "GitHub", href: this.links.github, group: "community", order: 20, external: true },
                    { id: "footer.api", label: "API", href: this.links.apiDocs, group: "developers", order: 10, external: true }
                ];
            },
            allLinks() {
                const hidden = this.pluginNavHidden();

                return this.coreLinks.concat(this.pluginNav("footer"))
                    .filter(item => item.href && hidden.indexOf(item.id) === -1)
                    .sort((left, right) => (left.order || 0) - (right.order || 0));
            },
            columns() {
                const groups = [
                    { id: "site", label: "Server" },
                    { id: "community", label: "Community" },
                    { id: "developers", label: "Developers" }
                ];

                return groups.map(group => ({
                    id: group.id,
                    label: group.label,
                    items: this.allLinks.filter(item => (item.group || "community") === group.id)
                })).filter(group => group.items.length > 0);
            }
        }
    });
</script>
