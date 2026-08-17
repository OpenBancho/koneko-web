<template id="friends-view">
<div class="page">
    <site-nav></site-nav>

    <koneko-slot name="page.top"></koneko-slot>

    <section class="card" v-if="pending">
        <h2>Followers</h2>

        <div class="skeleton-rows">
            <div class="skeleton skeleton-row" v-for="n in 4" :key="n"></div>
        </div>
    </section>

    <section class="card" v-else-if="error">
        <h2>Followers</h2>
        <p class="muted">{{ error }}</p>
    </section>

    <template v-else>
        <p class="muted" v-if="actionError">{{ actionError }}</p>

        <!-- Both directions at once: the closest thing to friends this model has. -->
        <section class="card" v-if="mutual.length">
            <h2>Mutual <span class="muted">({{ mutual.length }})</span></h2>

            <div class="friend-row" v-for="player in mutual" :key="player.id">
                <a class="friend-identity" :href="'/u/' + player.id">
                    <img class="friend-avatar" :src="avatar(player.id)" :alt="player.name" loading="lazy">

                    <span class="friend-text">
                        <span class="friend-name">{{ player.name }}</span>
                        <span class="friend-meta">
                            <span :class="flagClass(player.country)"></span>
                            <span>{{ (player.country || "").toUpperCase() }}</span>
                        </span>
                    </span>
                </a>

                <span class="friend-online" v-if="player.online">
                    <span class="friend-dot"></span>online
                </span>

                <span class="friend-actions">
                    <button class="button button-small button-ghost" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'remove')">Unfollow</button>
                </span>
            </div>
        </section>

        <section class="card">
            <h2>Followers <span class="muted" v-if="followers.length">({{ followers.length }})</span></h2>

            <p class="muted" v-if="!followers.length">
                No followers yet.
            </p>

            <div class="friend-row" v-for="player in followers" :key="player.id">
                <a class="friend-identity" :href="'/u/' + player.id">
                    <img class="friend-avatar" :src="avatar(player.id)" :alt="player.name" loading="lazy">

                    <span class="friend-text">
                        <span class="friend-name">{{ player.name }}</span>
                        <span class="friend-meta">
                            <span :class="flagClass(player.country)"></span>
                            <span>{{ (player.country || "").toUpperCase() }}</span>
                        </span>
                    </span>
                </a>

                <span class="friend-online" v-if="player.online">
                    <span class="friend-dot"></span>online
                </span>

                <span class="friend-actions">
                    <button class="button button-small" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'add')">Follow back</button>
                    <button class="button button-small button-ghost" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'decline')">Remove</button>
                </span>
            </div>
        </section>

        <section class="card" v-if="following.length">
            <h2>Following <span class="muted">({{ following.length }})</span></h2>

            <div class="friend-row" v-for="player in following" :key="player.id">
                <a class="friend-identity" :href="'/u/' + player.id">
                    <img class="friend-avatar" :src="avatar(player.id)" :alt="player.name" loading="lazy">

                    <span class="friend-text">
                        <span class="friend-name">{{ player.name }}</span>
                        <span class="friend-meta">
                            <span :class="flagClass(player.country)"></span>
                            <span>{{ (player.country || "").toUpperCase() }}</span>
                        </span>
                    </span>
                </a>

                <span class="friend-online" v-if="player.online">
                    <span class="friend-dot"></span>online
                </span>

                <span class="friend-actions">
                    <button class="button button-small button-ghost" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'remove')">Unfollow</button>
                </span>
            </div>
        </section>
    </template>

    <koneko-slot name="page.bottom"></koneko-slot>
    <site-footer></site-footer>
</div>
</template>

<script>
    /**
     * The followers page: who follows the account, who the account follows,
     * and where the two overlap. Everything here needs the session, so it all
     * comes from the account routes of this service rather than from the
     * public API.
     */
    app.component("friends-view", {
        template: "#friends-view",
        data: () => ({
            pending: true,
            error: "",
            actionError: "",
            mutual: [],
            followers: [],
            following: [],
            // One flag per row, so a slow answer never locks the whole page.
            busy: {}
        }),
        methods: {
            avatar(id) {
                return "https://a." + this.$koneko.domain + "/" + id;
            },
            apply(answer) {
                this.mutual = (answer && answer.mutual) || [];
                this.followers = (answer && answer.followers) || [];
                this.following = (answer && answer.following) || [];
            },
            async load() {
                try {
                    const answer = await this.session("GET", "/account/friends");

                    this.apply(answer);
                    this.error = "";
                } catch (e) {
                    this.error = (e && e.message) || "The followers list could not be loaded.";
                } finally {
                    this.pending = false;
                }
            },
            async act(player, action) {
                if (this.busy[player.id]) return;

                this.busy[player.id] = true;
                this.actionError = "";

                try {
                    await this.session("POST", "/account/friends",
                        { id: player.id, action: action });

                    // The three lists move together - following a follower moves
                    // them into mutual - so they are simply re-read.
                    await this.load();
                } catch (e) {
                    this.actionError = (e && e.message) || "The request failed.";
                } finally {
                    this.busy[player.id] = false;
                }
            }
        },
        created() {
            this.setTitle("Followers");
            this.load();
        }
    });
</script>
