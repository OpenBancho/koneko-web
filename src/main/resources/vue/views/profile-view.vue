<template id="profile-view">
<div class="page" :style="schemeStyle">
    <!-- The supporter's own picture behind the whole page. Fixed to the
         window, so it stays put while the cards scroll over it. -->
    <div class="profile-backdrop" v-if="backgroundUrl || schemeStyle" :style="backdropStyle" aria-hidden="true"></div>

    <site-nav></site-nav>

    <koneko-slot name="profile.top"></koneko-slot>
    <koneko-slot name="page.top"></koneko-slot>

    <section class="card" v-if="error">
        <h2>Nothing here</h2>
        <p class="muted">{{ error }}</p>
    </section>

    <!-- The shell is painted at once and every card below fills itself in
         when its own answer lands, so nothing waits for the slowest one. -->
    <template v-else>
        <section class="mode-bar">
            <div class="mode-tabs">
                <button class="mode-tab" v-for="tab in modes" :key="tab.id"
                    :class="{ active: base === tab.id }" @click="pickBase(tab.id)">
                    {{ tab.label }}
                </button>
            </div>

            <div class="mode-variants">
                <button class="mode-variant" :class="{ active: !variant }"
                    @click="pickVariant('')">Vanilla</button>
                <button class="mode-variant" :class="{ active: variant === 'relax' }"
                    :disabled="!relaxAllowed" @click="pickVariant('relax')">Relax</button>
                <button class="mode-variant" :class="{ active: variant === 'autopilot' }"
                    :disabled="!autopilotAllowed" @click="pickVariant('autopilot')">Autopilot</button>
            </div>
        </section>

        <section class="card profile-card">
            <div class="skeleton-profile" v-if="pending.header">
                <div class="skeleton skeleton-avatar"></div>

                <div class="skeleton-lines">
                    <div class="skeleton skeleton-line skeleton-title"></div>
                    <div class="skeleton skeleton-line skeleton-line-wide"></div>
                    <div class="skeleton skeleton-line"></div>
                    <div class="skeleton skeleton-line skeleton-line-short"></div>
                </div>
            </div>

            <div class="profile-top" v-else>
                <!-- The cover picture. It is a supporter perk, so most
                     profiles show the plain strip underneath instead. -->
                <div class="profile-cover" :style="coverStyle"></div>

                <div class="profile-head">
                    <img class="profile-avatar" :src="avatarUrl" :alt="info.name">

                    <div class="profile-identity">
                        <h1 class="profile-name">{{ info.name }}</h1>

                        <!-- Country and badge sit under the name, the way the
                             game client lays a profile out. -->
                        <div class="profile-meta-row">
                            <span class="profile-country" v-if="info.country">
                                <span class="country-tag" v-if="flagClass(info.country)"
                                    :class="flagClass(info.country)"></span>
                                <span>{{ countryName }}</span>
                            </span>

                            <span class="user-badge profile-badge" v-if="badge" :title="badge.name">
                                <img class="user-badge-icon" :src="badge.icon" alt=""
                                    loading="lazy" referrerpolicy="no-referrer">
                                <span class="user-badge-name">{{ badge.name }}</span>
                            </span>

                            <!-- The groups the account carries, in their own colours. -->
                            <span class="group-badge" v-for="group in groups" :key="group.id"
                                :style="groupStyle(group)" :title="group.name">
                                <span v-if="group.icon">{{ group.icon }}</span>
                                <span>{{ group.name }}</span>
                            </span>
                        </div>
                    </div>

                    <!-- Friending lives on the profile, but only for a visitor
                         who is logged in, looking at somebody else, and not
                         separated from them by a block. -->
                    <div class="profile-actions" v-if="showFriendButton">
                        <button class="button button-small" type="button"
                            :class="friendshipButtonClass"
                            :title="friendshipHint"
                            :disabled="friendship.busy || !friendship.loaded"
                            @mouseenter="friendHover = true" @mouseleave="friendHover = false"
                            @click="actOnFriend">{{ friendshipLabel }}</button>
                        <span class="profile-actions-error" v-if="friendship.error">{{ friendship.error }}</span>
                    </div>
                </div>

                <div class="profile-body">
                    <div class="profile-left">

                        <div class="profile-ranks">
                            <div class="profile-rank">
                                <span class="profile-rank-label">Rank</span>
                                <span class="profile-rank-value">{{ fmtRank(stats.rank) }}</span>
                            </div>

                            <div class="profile-rank">
                                <span class="profile-rank-label">Country Rank</span>
                                <span class="profile-rank-value">{{ fmtRank(stats.country_rank) }}</span>
                            </div>

                            <div class="profile-rank profile-rank-followers" v-if="!pending.header">
                                <span class="profile-rank-label">Followers</span>
                                <span class="profile-rank-value">{{ fmtNumber(info.followers || 0) }}</span>
                            </div>

                        </div>

                        <!-- How the player drifted through the world ranking.
                             It sits right under the positions it belongs to. -->
                        <div class="profile-rank-graph">
                            <span class="profile-graph-title">World rank</span>

                            <div class="skeleton skeleton-graph" v-if="pending.rankHistory"></div>
                            <rank-graph v-else :days="rankHistory"></rank-graph>
                        </div>

                        <div class="profile-quick">
                            <div class="profile-quick-item">
                                <span class="profile-quick-label">Medals</span>
                                <span class="profile-quick-value">{{ fmtNumber(medals.length) }}</span>
                            </div>

                            <div class="profile-quick-item">
                                <span class="profile-quick-label">pp</span>
                                <span class="profile-quick-value">{{ fmtNumber(stats.pp) }}</span>
                            </div>

                            <div class="profile-quick-item">
                                <span class="profile-quick-label">Played for</span>
                                <span class="profile-quick-value">{{ fmtPlaytime(stats.playtime) }}</span>
                            </div>
                        </div>

                        <div class="grade-counts">
                            <div class="grade-count" v-for="grade in grades" :key="grade.cls">
                                <span class="grade-mark" :class="'grade-' + grade.cls">{{ grade.mark }}</span>
                                <span class="grade-value">{{ fmtNumber(grade.value) }}</span>
                            </div>
                        </div>
                    </div>

                    <div class="profile-right">
                        <!-- The level bar heads the numbers column: it is a
                             summary of the same statistics listed below it. -->
                        <div class="level-row">
                            <span class="level-badge">{{ stats.level || 1 }}</span>
                            <span class="level-bar">
                                <span class="level-fill" :style="{ width: levelProgress + '%' }"></span>
                            </span>
                        </div>

                        <dl class="profile-facts">
                            <div><dt>Joined:</dt><dd>{{ fmtRelative(info.creation_time) }}</dd></div>
                            <div><dt>Last Activity:</dt><dd>{{ fmtRelative(info.latest_activity) }}</dd></div>
                            <div><dt>PP:</dt><dd>{{ fmtNumber(stats.pp) }}</dd></div>
                            <div><dt>Playcount:</dt><dd>{{ fmtNumber(stats.plays) }}</dd></div>
                            <div><dt>Replay views:</dt><dd>{{ fmtNumber(stats.replay_views) }}</dd></div>
                            <div><dt>Total Score:</dt><dd>{{ fmtNumber(stats.tscore) }}</dd></div>
                            <div><dt>Ranked Score:</dt><dd>{{ fmtNumber(stats.rscore) }}</dd></div>
                            <div><dt>Max Combo:</dt><dd>{{ fmtNumber(stats.max_combo) }}</dd></div>
                            <div><dt>Total Hits:</dt><dd>{{ fmtNumber(stats.total_hits) }}</dd></div>
                            <div><dt>Accuracy:</dt><dd>{{ fmtAccuracy(stats.acc) }}</dd></div>
                            <div class="played-for">
                                <dt>Played for:</dt>
                                <dd>{{ fmtPlaytime(stats.playtime) }}</dd>
                            </div>
                            <!-- From lazer.jar, a server of its own: shown only when it is linked and knows the player. -->
                            <template v-if="lazer.counts">
                                <div><dt>Favourite maps:</dt><dd>{{ fmtNumber(lazer.counts.favourites) }}</dd></div>
                                <div><dt>Ranked maps:</dt><dd>{{ fmtNumber(lazer.counts.ranked) }}</dd></div>
                                <div><dt>Loved maps:</dt><dd>{{ fmtNumber(lazer.counts.loved) }}</dd></div>
                            </template>
                        </dl>
                    </div>
                </div>
            </div>
        </section>

        <section class="card" v-if="userpage">
            <h2>About me</h2>
            <!-- Plain text on purpose: the text is written by the player, so it is never injected as HTML. -->
            <p class="lazer-page">{{ userpage }}</p>
        </section>

        <section class="card" v-if="lazer.pinned.length">
            <h2>Pinned scores <span class="muted">({{ lazer.pinned.length }})</span></h2>

            <div class="score-row score-clickable" v-for="score in lazer.pinned" :key="score.id"
                @click="openLazerScore(score)">
                <div class="score-grade" :class="'grade-' + lazerGrade(score.rank)">{{ score.rank }}</div>

                <div class="score-map">
                    <div class="score-title">
                        <a :href="'/beatmapsets/' + score.set_id" @click.stop>
                            {{ score.artist }} - {{ score.title }}
                            <span class="muted" v-if="score.version">[{{ score.version }}]</span>
                        </a>
                        <span class="mods" v-if="score.mods.length">{{ score.mods.join(", ") }}</span>
                    </div>

                    <div class="score-meta">
                        <span>{{ fmtAccuracy(score.accuracy) }}</span>
                        <span>{{ fmtNumber(score.max_combo) }}x</span>
                        <span>{{ fmtNumber(score.total_score) }}</span>
                        <span>{{ fmtRelative(score.ended_at) }}</span>
                    </div>
                </div>

                <div class="score-numbers">
                    <div class="score-pp">{{ fmtDecimal(score.pp, 0) }}<span class="unit">pp</span></div>
                </div>
            </div>
        </section>

        <section class="card" v-if="lazer.activity.length">
            <h2>Recent activity</h2>

            <div class="played" v-for="event in lazer.activity" :key="event.id">
                <span>
                    <span class="grade-mark" :class="'grade-' + lazerGrade(event.grade)">{{ event.grade }}</span>
                    achieved rank #{{ fmtNumber(event.position) }} on
                    <strong>{{ event.title }}</strong>
                    <span class="muted">({{ event.mode }})</span>
                </span>

                <span class="played-count">{{ fmtRelative(event.created_at) }}</span>
            </div>
        </section>

        <section class="card">
            <h2>Best scores</h2>

            <div class="skeleton-rows" v-if="pending.best">
                <div class="skeleton skeleton-row" v-for="n in 5" :key="n"></div>
            </div>

            <template v-else>
                <p class="muted" v-if="!best.length">No scores yet.</p>

                <score-row v-for="(score, i) in best" :key="score.id"
                    :score="score" :index="i"></score-row>

                <button class="load-more" v-if="canLoad('best')" :disabled="busy.best"
                    @click="loadMore('best')">Load more</button>
            </template>
        </section>

        <section class="card">
            <h2>Last scores</h2>

            <div class="skeleton-rows" v-if="pending.recent">
                <div class="skeleton skeleton-row" v-for="n in 5" :key="n"></div>
            </div>

            <template v-else>
                <p class="muted" v-if="!recent.length">No scores yet.</p>

                <score-row v-for="score in recent" :key="score.id" :score="score"></score-row>

                <button class="load-more" v-if="canLoad('recent')" :disabled="busy.recent"
                    @click="loadMore('recent')">Load more</button>
            </template>
        </section>

        <section class="card">
            <h2>First places <span class="muted" v-if="counts.first">({{ fmtNumber(counts.first) }})</span></h2>

            <div class="skeleton-rows" v-if="pending.first">
                <div class="skeleton skeleton-row" v-for="n in 3" :key="n"></div>
            </div>

            <template v-else>
                <p class="muted" v-if="!firstPlaces.length">No number one scores yet.</p>

                <score-row v-for="score in firstPlaces" :key="score.id" :score="score"></score-row>

                <button class="load-more" v-if="canLoad('first')" :disabled="busy.first"
                    @click="loadMore('first')">Load more</button>
            </template>
        </section>

        <section class="card">
            <h2>Playcount graph</h2>

            <div class="skeleton skeleton-graph" v-if="pending.playcounts"></div>
            <playcount-graph v-else :months="playcounts"></playcount-graph>
        </section>

        <section class="card" v-if="pending.medals || achievements.length">
            <h2>
                Medals
                <span class="muted" v-if="!pending.medals">({{ medals.length }} / {{ medalTotal }})</span>
            </h2>

            <div class="skeleton-medals" v-if="pending.medals">
                <div class="skeleton skeleton-medal" v-for="n in 12" :key="n"></div>
            </div>

            <p class="muted" v-else-if="!medals.length">No medals yet.</p>

            <div class="medals" v-else>
                <div class="medal" v-for="medal in medals" :key="medal.id">
                    <img class="medal-icon" :src="medalIcon(medal.file)"
                        :alt="medal.name" loading="lazy">

                    <div class="medal-tip">
                        <span class="medal-name">{{ medal.name }}</span>
                        <span class="medal-desc">{{ medal.description }}</span>
                    </div>
                </div>
            </div>
        </section>

        <section class="card">
            <h2>Most played</h2>

            <div class="skeleton-rows" v-if="pending.mostPlayed">
                <div class="skeleton skeleton-row" v-for="n in 3" :key="n"></div>
            </div>

            <p class="muted" v-else-if="!mostPlayed.length">
                No plays in this mode yet.
            </p>

            <div class="played" v-for="map in mostPlayed" :key="map.map_id" v-else>
                <a class="played-title" :href="'/beatmapsets/' + map.set_id">
                    {{ map.artist }} - {{ map.title }}
                    <span class="muted">[{{ map.version }}]</span>
                </a>

                <span class="played-count">{{ fmtNumber(map.playcount) }}x</span>
            </div>
        </section>

        <section class="card">
            <h2>Submitted beatmaps</h2>

            <div class="skeleton-rows" v-if="pending.beatmapsets">
                <div class="skeleton skeleton-row" v-for="n in 2" :key="n"></div>
            </div>

            <p class="muted" v-else-if="!beatmapsets.length">
                This player has not submitted any beatmaps yet.
            </p>

            <div class="mapset" v-for="set in beatmapsets" :key="set.set_id">
                <div class="mapset-head">
                    <a class="mapset-title" :href="'/beatmapsets/' + set.set_id">
                        {{ set.artist }} - {{ set.title }}
                    </a>
                    <span class="badge">{{ statusName(set.status) }}</span>
                </div>

                <div class="mapset-meta">
                    <span>set #{{ set.set_id }}</span>
                    <span>{{ (set.difficulties || []).length }} difficulties</span>
                    <span>updated {{ fmtDate(set.last_update) }}</span>
                </div>
            </div>
        </section>
    </template>

    <koneko-slot name="page.bottom"></koneko-slot>
    <koneko-slot name="profile.bottom"></koneko-slot>

    <site-footer></site-footer>
</div>
</template>

<script>
app.component("profile-view", {
    template: "#profile-view",
    data: () => ({
        error: "",
        identifier: "",
        player: null,
        // One flag per card. Every section fetches itself, shows its own
        // loader and clears its own flag, so the page is usable as soon as
        // the first answer is in instead of when the last one is.
        pending: {
            header: true,
            best: true,
            recent: true,
            first: true,
            playcounts: true,
            rankHistory: true,
            mostPlayed: true,
            medals: true,
            beatmapsets: true
        },
        // Raised on every mode switch: answers of the previous run are
        // dropped instead of painted over the new mode.
        generation: 0,
        // The sections as they arrive, kept so the FastLoad copy of the
        // page can be written while the rest is still on its way.
        snapshot: {},
        // How much of a list the first page holds, and how far the
        // playcount graph looks back.
        pageSize: 10,
        playcountMonths: 12,
        // How far back the world rank graph on the player card looks.
        rankDays: 90,
        // 0-3 is the plain mode, the variant shifts it the way the
        // server numbers them: +4 for relax, 8 for autopilot.
        base: 0,
        variant: "",
        best: [],
        recent: [],
        firstPlaces: [],
        playcounts: [],
        rankHistory: [],
        mostPlayed: [],
        beatmapsets: [],
        achievements: [],
        // Medals that exist on the server, reported by the API so the
        // ratio does not have to be counted from the list.
        medalTotal: 0,
        counts: { best: 0, recent: 0, first: 0 },
        busy: { best: false, recent: false, first: false },
        // The visitor's relation to this profile: none, outgoing, incoming or
        // friends. Fetched on its own and never from the FastLoad copy of the
        // page, so the button never shows a stale state.
        friendship: { loaded: false, status: "none", blocked: false, blockedBy: false, busy: false, error: "" },
        // Hovering a "Following"/"Mutual" button swaps it to a red "Unfollow".
        friendHover: false,
        // What lazer.jar adds to a profile. lazer.jar is a server of its own and may not be linked at all, in which
        // case every field stays empty and none of its cards render.
        lazer: { page: null, counts: null, pinned: [], activity: [] },
        modes: [
            { id: 0, label: "osu!" },
            { id: 1, label: "osu!taiko" },
            { id: 2, label: "osu!catch" },
            { id: 3, label: "osu!mania" }
        ]
    }),
    computed: {
        medals() {
            return this.achievements.filter(medal => medal.unlocked);
        },
        mode() {
            if (this.variant === "autopilot") return 8;
            if (this.variant === "relax") return this.base + 4;

            return this.base;
        },
        // Relax has no mania, autopilot is standard only.
        relaxAllowed() {
            return this.base !== 3;
        },
        autopilotAllowed() {
            return this.base === 0;
        },
        info() {
            return (this.player && this.player.info) || {};
        },
        /**
         * What the player wrote about themselves in the site settings. It comes with the player card, so it does not
         * depend on lazer.jar being linked; lazer.jar's copy of the same text is only a fallback.
         */
        userpage() {
            const own = this.info.userpage_content;

            return (own && String(own).trim()) ? own : (this.lazer.page || "");
        },
        /**
         * The custom badge, or nothing. Both halves are required, and the
         * picture has to be one this server stores: badge images are uploaded
         * to /api/v1/me/badge, never linked, so any other value is ignored
         * instead of being handed to an <img> as-is.
         */
        badge() {
            const name = (this.info.custom_badge_name || "").trim();
            const icon = (this.info.custom_badge_icon || "").trim();

            if (!name || !/^\/api\/v1\/badge\/[0-9]{1,10}\.png$/.test(icon)) return null;

            return { name: name, icon: icon };
        },
        /**
         * The cover picture, or nothing.
         *
         * Any path on this origin, and any https address, is drawn. This used to
         * insist on one exact spelling of the upload path, which meant a banner
         * the API had happily returned was dropped without a word and the page
         * looked as if none existed. The value is still not pasted into a style
         * blindly: whitespace, quotes, brackets and backslashes are refused, so
         * nothing can break out of the url() around it.
         *
         * The decision is written to the console on purpose: a cover that never
         * even gets requested is otherwise impossible to tell apart from a
         * player who simply does not have one.
         */
        bannerUrl() {
            const raw = this.info.custom_banner;
            const banner = (raw || "").trim();
            let ignored = "";

            if (!banner) {
                ignored = "custom_banner is empty, so the API sent no banner for this player";
            } else if (/[\s"'()\\]/.test(banner)) {
                ignored = "the value holds a character that cannot sit inside url()";
            } else if (!/^\//.test(banner) && !/^https:\/\//i.test(banner)) {
                ignored = "the value is neither a path on this site nor an https address";
            }

            console.info("[profile] banner", {
                raw: raw,
                used: ignored ? null : banner,
                ignored: ignored || null
            });

            return ignored ? "" : banner;
        },
        /**
         * The page background, or nothing. Backgrounds are uploaded to
         * /api/v1/me/background and served from /api/v1/background/, so any other
         * value is ignored instead of being pasted into a style.
         */
        backgroundUrl() {
            const background = (this.info.custom_background || "").trim();

            return /^\/api\/v1\/background\/[0-9]{1,10}\.jpg$/.test(background) ? background : "";
        },
        /**
         * The colour scheme of the profile: the accent tokens, derived from the one
         * colour the supporter picked. Only a strict #rrggbb value is used, since it
         * ends up in a style attribute. Without one the site colours stay.
         */
        schemeStyle() {
            const color = (this.info.profile_color || "").trim();

            if (!/^#[0-9a-fA-F]{6}$/.test(color)) return null;

            const r = parseInt(color.slice(1, 3), 16);
            const g = parseInt(color.slice(3, 5), 16);
            const b = parseInt(color.slice(5, 7), 16);
            const shade = amount => "rgb(" + [r, g, b].map(v =>
                Math.max(0, Math.min(255, Math.round(amount > 0 ? v + (255 - v) * amount : v * (1 + amount))))
            ).join(",") + ")";
            // Text on the accent: dark on light colours, white on dark ones.
            const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;

            // The surfaces take the hue of the colour, kept dark and quiet so the
            // content stays readable. With a background picture the panels let a
            // little of it through.
            // The panels take their hue from the secondary colour, or from the
            // primary one when no secondary was picked.
            const other = (this.info.profile_color_secondary || "").trim();
            const tint = /^#[0-9a-fA-F]{6}$/.test(other) ? other : color;
            const tr = parseInt(tint.slice(1, 3), 16) / 255;
            const tg = parseInt(tint.slice(3, 5), 16) / 255;
            const tb = parseInt(tint.slice(5, 7), 16) / 255;
            const max = Math.max(tr, tg, tb), min = Math.min(tr, tg, tb);
            const d = max - min;
            let hue = 0;
            if (d > 0) {
                if (max === tr) hue = ((tg - tb) / d + 6) % 6;
                else if (max === tg) hue = (tb - tr) / d + 2;
                else hue = (tr - tg) / d + 4;
                hue *= 60;
            }
            const chroma = d === 0 ? 0 : d / (1 - Math.abs(max + min - 1) || 1);
            const sat = Math.round(6 + 16 * Math.min(1, chroma));
            const alpha = this.backgroundUrl ? 0.86 : 1;
            const surface = (l, a) => "hsla(" + Math.round(hue) + "," + sat + "%," + l + "%," + a + ")";
            const panel = surface(20, alpha);
            const inset = surface(15, alpha);

            return {
                "--page-bg": surface(12, 1),
                "--bg": surface(12, 1),
                "--bg-alt": surface(12, 1),
                "--panel": panel,
                "--bg-card": panel,
                "--bg-panel": panel,
                "--inset": inset,
                "--bg-soft": inset,
                "--inset-deep": surface(11, alpha),
                "--accent": color,
                "--accent-soft": shade(0.25),
                "--accent-dark": shade(-0.18),
                "--accent-tint": "rgba(" + r + "," + g + "," + b + ",0.16)",
                "--accent-ink": luma > 0.6 ? "#14090e" : "#ffffff"
            };
        },
        backdropStyle() {
            return this.backgroundUrl
                ? { backgroundImage: "url(" + this.backgroundUrl + ")" }
                : null;
        },
        /** Without a banner the strip keeps the flat colour from the stylesheet. */
        coverStyle() {
            return this.bannerUrl
                ? { backgroundImage: "url(" + this.bannerUrl + ")" }
                : null;
        },
        countryName() {
            return (this.info.country || "").toUpperCase();
        },
        // The groups the API sent with the profile, shown as badges.
        groups() {
            return this.info.groups || [];
        },
        stats() {
            const stats = this.player && this.player.stats;

            if (!stats) return {};

            return stats[String(this.mode)] || {};
        },
        levelProgress() {
            const progress = Number(this.stats.level_progress || 0);

            return Math.max(0, Math.min(100, progress));
        },
        isSelf() {
            return this.$koneko.user && this.$koneko.user.id === this.info.id;
        },
        avatarUrl() {
            return "https://a." + this.$koneko.domain + "/" + (this.info.id || 0);
        },
        showFriendButton() {
            return !!this.$koneko.user && !this.isSelf
                && !this.friendship.blocked && !this.friendship.blockedBy;
        },
        friendshipLabel() {
            if (this.friendship.busy) return "...";

            switch (this.friendship.status) {
                case "mutual":
                case "following":
                    return this.friendHover ? "Unfollow"
                        : (this.friendship.status === "mutual" ? "Mutual" : "Following");
                case "followed_by": return "Follow back";
                default: return "Follow";
            }
        },
        // The button keeps a visible border whenever it sits on a state, so it
        // reads against the profile background; hovering an unfollow turns it red.
        friendshipButtonClass() {
            const active = this.friendship.status === "following"
                || this.friendship.status === "mutual";

            if (active && this.friendHover) return "button-danger";
            if (active) return "button-ghost";

            return "";
        },
        friendshipHint() {
            switch (this.friendship.status) {
                case "mutual": return "You follow each other. Click to unfollow.";
                case "following": return "You follow this player. Click to unfollow.";
                case "followed_by": return "This player follows you. Click to follow back.";
                default: return "Follow this player.";
            }
        }
    },
    watch: {
        // The button state is personal: it is fetched once the profile's id
        // is known, and it never comes out of the FastLoad copy of the page.
        "info.id": function (id) {
            if (id) this.loadFriendship();
        }
    },
    methods: {
        // The icons are downloaded by the game server on its first start
        // and served from the assets host, the same place the client
        // takes them from.
        medalIcon(file) {
            return "https://assets." + this.$koneko.domain
                + "/medals/client/" + file + "@2x.png";
        },
        pickBase(id) {
            if (this.base === id) return;

            this.base = id;

            // Keep the variant only where it exists for the new mode.
            if (this.variant === "relax" && !this.relaxAllowed) this.variant = "";
            if (this.variant === "autopilot" && !this.autopilotAllowed) this.variant = "";

            this.reload();
        },
        pickVariant(name) {
            this.variant = this.variant === name ? "" : name;
            this.reload();
        },
        listOf(kind) {
            if (kind === "best") return this.best;
            if (kind === "first") return this.firstPlaces;

            return this.recent;
        },
        canLoad(kind) {
            return this.listOf(kind).length < Number(this.counts[kind] || 0);
        },
        async loadMore(kind) {
            if (this.busy[kind]) return;

            this.busy[kind] = true;

            try {
                // First places have their own route, the other two are
                // scopes of the score route.
                const endpoint = kind === "first"
                    ? "get_player_first_places"
                    : "get_player_scores";

                const params = Object.assign({}, this.apiWho(this.identifier), {
                    mode: this.mode,
                    limit: this.pageSize,
                    offset: this.listOf(kind).length
                });

                if (kind !== "first") params.scope = kind;

                const answer = await this.playerApiQuietly(endpoint, params);
                const page = (answer && answer.results) || [];

                this.listOf(kind).push(...page);
            } catch (e) {
                // A failed page just leaves the button in place.
            } finally {
                this.busy[kind] = false;
            }
        },
        // The whole page of the last visit at once: a revisit paints filled
        // cards immediately and the fresh answers replace them silently.
        apply(body) {
            this.player = body.player || null;

            this.best = (body.best && body.best.results) || [];
            this.recent = (body.recent && body.recent.results) || [];
            this.firstPlaces = (body.firstPlaces && body.firstPlaces.results) || [];
            this.beatmapsets = (body.beatmapsets && body.beatmapsets.results) || [];
            this.playcounts = (body.playcounts && body.playcounts.months) || [];
            this.rankHistory = (body.rankHistory && body.rankHistory.days) || [];

            this.achievements = (body.achievements && body.achievements.results) || [];
            this.medalTotal = (body.achievements && body.achievements.total)
                || this.achievements.length;

            this.counts = {
                best: (body.best && body.best.count) || this.best.length,
                recent: (body.recent && body.recent.count) || this.recent.length,
                first: (body.firstPlaces && body.firstPlaces.count) || this.firstPlaces.length
            };

            this.mostPlayed = (body.mostPlayed && body.mostPlayed.results) || [];

            this.settled();

            // A stored copy can be missing sections - after a reload the scores are dropped on purpose, and an old
            // copy may predate a section. Those cards keep their loader until the fresh answer lands, instead of
            // claiming "no scores yet".
            const parts = { best: "best", recent: "recent", first: "firstPlaces", mostPlayed: "mostPlayed",
                playcounts: "playcounts", rankHistory: "rankHistory" };

            Object.keys(parts).forEach(name => {
                if (body[parts[name]] === undefined) this.pending[name] = true;
            });

            this.setTitle(this.info.name);
        },
        /** Nothing is being waited for any more: drop every loader. */
        settled() {
            Object.keys(this.pending).forEach(name => {
                this.pending[name] = false;
            });
        },
        cacheKey() {
            return "profile:" + this.identifier.toLowerCase() + ":" + this.mode;
        },
        /**
         * Takes the loader off one card and keeps the FastLoad copy of the
         * page in step: the sections land one by one, so the stored page
         * grows with them instead of being written once at the end.
         */
        settle(name, part, value) {
            this.pending[name] = false;

            if (part) {
                this.snapshot[part] = value;
                this.fastSave(this.cacheKey(), this.snapshot);
            }
        },
        notFound() {
            this.error = "This player does not exist.";
            this.setTitle("Not found");
            this.settled();
        },
        /**
         * The player card, and the only section that decides whether the
         * profile exists at all. It is also the only one that is not per
         * mode: the details carry the stats of every mode at once, so a
         * mode switch does not refetch it.
         */
        async loadHeader(run, who) {
            try {
                // Through this service rather than straight from the API: the
                // session's token goes with the call, which is what lets staff
                // open a profile the public is answered 404 for.
                const details = await this.playerApi("get_player_details",
                    Object.assign({}, who, { scope: "all" }));

                if (run !== this.generation) return;

                const player = (details && details.player) || null;

                if (!player) {
                    this.notFound();
                    return;
                }

                // Left in with the banner log above: this shows what the API
                // really answered, cache or no cache.
                console.info("[profile] info from the API", player.info);

                this.player = player;
                this.setTitle(this.info.name);
                this.settle("header", "player", player);
                this.loadLazer(run);
            } catch (e) {
                if (run !== this.generation) return;

                this.pending.header = false;

                if (e && e.status === 404) {
                    this.notFound();
                    return;
                }

                // A cached player stays on screen; without one there is
                // nothing to show but the failure.
                if (!this.player) {
                    this.error = "The server could not be reached.";
                }
            }
        },
        /**
         * Fetches one card on its own: it carries its own loader, fills
         * itself in when its answer lands, and a failure leaves the rest of
         * the page untouched.
         */
        async loadSection(run, name, part, endpoint, params, apply) {
            const answer = await this.playerApiQuietly(endpoint, params);

            // A mode switch while this was in flight makes the answer
            // stale, so it is dropped rather than painted.
            if (run !== this.generation) return;

            apply(answer);
            this.settle(name, part, answer);
        },
        load() {
            // Everything an earlier run may still deliver is stale now.
            const run = ++this.generation;

            const who = this.apiWho(this.identifier);
            const paging = { mode: this.mode, limit: this.pageSize };
            const cached = this.fastLoad(this.cacheKey());

            this.snapshot = cached ? Object.assign({}, cached) : {};

            if (cached) {
                this.apply(cached);

                // A stored page can predate the banner, so say where the
                // painted profile came from.
                console.info("[profile] painted from the stored copy first", this.cacheKey());
            } else {
                // The player card survives a mode switch: the numbers it
                // shows come from details that are already here.
                this.pending.header = !this.player;
                this.pending.best = true;
                this.pending.recent = true;
                this.pending.first = true;
                this.pending.playcounts = true;
                this.pending.rankHistory = true;
                this.pending.mostPlayed = true;
                this.pending.medals = true;
                this.pending.beatmapsets = true;
            }

            // The header is refetched on every visit, cache or not: it carries
            // things that change without anyone editing the page - followers,
            // groups - so only the fresh answer is the truth. A stored copy is
            // painted first and replaced when the answer lands.
            if (this.player) {
                this.snapshot.player = this.player;
            }

            this.resetLazer();
            this.loadHeader(run, who);

            // The requests all leave at once and are not awaited here:
            // whichever card is ready first is on screen first.
            this.loadSection(run, "best", "best", "get_player_scores",
                Object.assign({}, who, paging, { scope: "best" }),
                answer => {
                    this.best = (answer && answer.results) || [];
                    this.counts.best = (answer && answer.count) || this.best.length;
                });

            this.loadSection(run, "recent", "recent", "get_player_scores",
                Object.assign({}, who, paging, { scope: "recent" }),
                answer => {
                    this.recent = (answer && answer.results) || [];
                    this.counts.recent = (answer && answer.count) || this.recent.length;
                });

            this.loadSection(run, "first", "firstPlaces", "get_player_first_places",
                Object.assign({}, who, paging),
                answer => {
                    this.firstPlaces = (answer && answer.results) || [];
                    this.counts.first = (answer && answer.count) || this.firstPlaces.length;
                });

            this.loadSection(run, "rankHistory", "rankHistory", "get_player_rank_history",
                Object.assign({}, who, { mode: this.mode, days: this.rankDays }),
                answer => {
                    this.rankHistory = (answer && answer.days) || [];
                });

            this.loadSection(run, "playcounts", "playcounts", "get_player_playcounts",
                Object.assign({}, who, { mode: this.mode, months: this.playcountMonths }),
                answer => {
                    this.playcounts = (answer && answer.months) || [];
                });

            this.loadSection(run, "mostPlayed", "mostPlayed", "get_player_most_played",
                Object.assign({}, who, paging),
                answer => {
                    this.mostPlayed = (answer && answer.results) || [];
                });

            // Medals belong to the player, not to a mode.
            this.loadSection(run, "medals", "achievements", "get_player_achievements", who,
                answer => {
                    this.achievements = (answer && answer.results) || [];
                    this.medalTotal = (answer && answer.total) || this.achievements.length;
                });

            this.loadSection(run, "beatmapsets", "beatmapsets", "get_player_beatmapsets",
                Object.assign({}, who, { limit: this.pageSize }),
                answer => {
                    this.beatmapsets = (answer && answer.results) || [];
                });
        },
        /**
         * The lazer.jar side of the profile: about-me, beatmap counters, pinned scores, recent activity. Asked through
         * this service, which talks to lazer.jar (a separate server from the one behind everything above). A failure,
         * or lazer.jar not being linked, simply leaves the cards out.
         */
        async loadLazer(run) {
            const id = this.info && this.info.id;

            if (!id) return;

            try {
                const response = await fetch("/data/lazer/profile/" + encodeURIComponent(id)
                    + "?mode=" + encodeURIComponent(this.mode));

                if (!response.ok) return;

                const answer = await response.json();

                if (run !== this.generation) return;

                if (!answer || !answer.enabled || !answer.found) {
                    this.resetLazer();
                    return;
                }

                this.lazer = {
                    page: answer.page || null,
                    counts: answer.counts || null,
                    pinned: answer.pinned || [],
                    activity: answer.activity || []
                };
            } catch (e) {
                // lazer.jar is optional: the rest of the profile does not depend on it.
            }
        },
        resetLazer() {
            this.lazer = { page: null, counts: null, pinned: [], activity: [] };
        },
        lazerGrade(grade) {
            return String(grade || "f").toLowerCase().replace("+", "plus");
        },
        openLazerScore(score) {
            if (score && score.id) window.location.href = "/scores/" + score.id;
        },
        // How the visitor stands with this profile, asked through this
        // service because only the session here holds the access token.
        async loadFriendship() {
            if (!this.$koneko.user || !this.info.id || this.isSelf) return;

            try {
                const answer = await this.session("GET",
                    "/account/friends/status?id=" + encodeURIComponent(this.info.id));

                this.friendship.status = (answer && answer.relationship) || "none";
                this.friendship.blocked = !!(answer && answer.blocked);
                this.friendship.blockedBy = !!(answer && answer.blocked_by);
                this.friendship.error = "";
            } catch (e) {
                // A failed status only means the button stays disabled.
            } finally {
                this.friendship.loaded = true;
            }
        },
        // One button, four states: add, cancel the request, accept it, or
        // remove the friendship. The server answers with the state to show.
        async actOnFriend() {
            if (this.friendship.busy || !this.friendship.loaded || !this.info.id) return;

            const action = {
                none: "add",
                following: "remove",
                followed_by: "add",
                mutual: "remove"
            }[this.friendship.status] || "add";

            this.friendship.busy = true;
            this.friendship.error = "";

            // The follower count on the card counts the visitor's own row, so
            // it is kept in step with the state the server confirms.
            const countedBefore = this.friendship.status === "following"
                || this.friendship.status === "mutual";

            try {
                const answer = await this.session("POST", "/account/friends",
                    { id: this.info.id, action: action });

                // Account actions wrap the API's body; see AccountRoutes.
                const next = (answer && answer.body && answer.body.relationship) || "none";
                this.friendship.status = next;

                const countedNow = next === "following" || next === "mutual";

                if (countedNow !== countedBefore && typeof this.info.followers === "number") {
                    this.player.info.followers = Math.max(0,
                        this.info.followers + (countedNow ? 1 : -1));
                }
            } catch (e) {
                this.friendship.error = (e && e.message) || "The request failed.";
            } finally {
                this.friendship.busy = false;
            }
        },
        reload() {
            this.error = "";
            this.load();
        }
    },
    created() {
        this.identifier = decodeURIComponent(window.location.pathname.split("/").pop());

        const mode = Number(new URLSearchParams(window.location.search).get("mode") || 0);

        if (mode === 8) {
            this.variant = "autopilot";
        } else if (mode >= 4 && mode <= 6) {
            this.base = mode - 4;
            this.variant = "relax";
        } else if (mode >= 1 && mode <= 3) {
            this.base = mode;
        }

        this.load();
    }
});
</script>