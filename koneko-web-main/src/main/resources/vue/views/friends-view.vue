<template id="friends-view">
<div class="page">
    <site-nav></site-nav>

    <koneko-slot name="page.top"></koneko-slot>

    <section class="card" v-if="pending">
        <h2>Friends</h2>

        <div class="skeleton-rows">
            <div class="skeleton skeleton-row" v-for="n in 4" :key="n"></div>
        </div>
    </section>

    <section class="card" v-else-if="error">
        <h2>Friends</h2>
        <p class="muted">{{ error }}</p>
    </section>

    <template v-else>
        <p class="muted" v-if="actionError">{{ actionError }}</p>

        <!-- Requests waiting for the account's own answer head the page:
             they are the only ones with a decision attached. -->
        <section class="card" v-if="incoming.length">
            <h2>Friend requests <span class="muted">({{ incoming.length }})</span></h2>

            <div class="friend-row" v-for="player in incoming" :key="player.id">
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

                <span class="friend-actions">
                    <button class="button button-small" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'accept')">Accept</button>
                    <button class="button button-small button-ghost" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'decline')">Decline</button>
                </span>
            </div>
        </section>

        <section class="card">
            <h2>Friends <span class="muted" v-if="friends.length">({{ friends.length }})</span></h2>

            <p class="muted" v-if="!friends.length">
                No friends yet. Open a player's profile and send them a friend request.
            </p>

            <div class="friend-row" v-for="player in friends" :key="player.id">
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
                    <button class="button button-small button-quiet" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'remove')">Remove</button>
                </span>
            </div>
        </section>

        <!-- Requests the account sent itself: they only need a way back. -->
        <section class="card" v-if="outgoing.length">
            <h2>Sent requests <span class="muted">({{ outgoing.length }})</span></h2>

            <div class="friend-row" v-for="player in outgoing" :key="player.id">
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

                <span class="friend-actions">
                    <button class="button button-small button-ghost" type="button" :disabled="busy[player.id]"
                        @click="act(player, 'cancel')">Cancel</button>
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
     * The friends page: the mutual friends, the requests waiting for an
     * answer and the requests the account sent itself. Everything here needs
     * the session, so it all comes from the account routes of this service
     * rather than from the public API.
     */
    app.component("friends-view", {
        template: "#friends-view",
        data: () => ({
            pending: true,
            error: "",
            actionError: "",
            friends: [],
            incoming: [],
            outgoing: [],
            // One flag per row, so a slow answer never locks the whole page.
            busy: {}
        }),
        methods: {
            avatar(id) {
                return "https://a." + this.$koneko.domain + "/" + id;
            },
            apply(answer) {
                this.friends = (answer && answer.friends) || [];
                this.incoming = (answer && answer.incoming) || [];
                this.outgoing = (answer && answer.outgoing) || [];
            },
            async load() {
                try {
                    const answer = await this.session("GET", "/account/friends");

                    this.apply(answer);
                    this.error = "";
                } catch (e) {
                    this.error = (e && e.message) || "The friends list could not be loaded.";
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

                    // The three lists move together - an accept turns a
                    // request into a friend - so they are simply re-read.
                    await this.load();
                } catch (e) {
                    this.actionError = (e && e.message) || "The request failed.";
                } finally {
                    this.busy[player.id] = false;
                }
            }
        },
        created() {
            this.setTitle("Friends");
            this.load();
        }
    });
</script>
