<template id="spectate-view">
<div class="page">
    <site-nav></site-nav>

    <koneko-slot name="page.top"></koneko-slot>

    <section class="card spectate-card">
        <div class="spectate-head">
            <a class="spectate-player" :href="'/u/' + userId">
                <img class="spectate-avatar" :src="avatarUrl" alt="">
                <span>
                    <span class="spectate-name">{{ playerName || ("Player " + userId) }}</span>
                    <span class="spectate-sub muted small">{{ sourceLabel }}</span>
                </span>
            </a>

            <span class="spectate-live" :class="{ on: isLive }">
                <span class="spectate-dot"></span>{{ isLive ? "LIVE" : "OFFLINE" }}
            </span>

            <div class="spectate-tools">
                <button class="button button-ghost button-small" type="button" @click="toggleSound"
                    :title="sound ? 'Mute the song' : 'Play the song along'">
                    <i class="fas" :class="sound ? 'fa-volume-up' : 'fa-volume-mute'"></i>
                </button>
                <button class="button button-ghost button-small" type="button" @click="fullscreen" title="Full screen">
                    <i class="fas fa-expand"></i>
                </button>
            </div>
        </div>

        <div class="spectate-stage" ref="stage">
            <canvas class="spectate-canvas" ref="canvas"></canvas>

            <div class="spectate-overlay" v-if="overlay">
                <div class="spectate-overlay-text">
                    <i class="fas" :class="overlay.icon"></i>
                    <span>{{ overlay.text }}</span>
                    <span class="muted small" v-if="overlay.hint">{{ overlay.hint }}</span>
                </div>
            </div>

            <span class="spectate-chip" v-else-if="live.buffering && live.started">Paused or waiting for frames...</span>
        </div>

        <div class="spectate-map" v-if="mapInfo">
            <div class="spectate-map-title">
                <span>{{ mapInfo.artist }} - {{ mapInfo.title }}</span>
                <span class="muted"> [{{ mapInfo.version }}]</span>
            </div>
            <div class="spectate-map-meta muted small">
                <span v-if="mapInfo.creator">mapped by {{ mapInfo.creator }}</span>
                <span class="spectate-mod" v-for="mod in mods" :key="mod">{{ mod }}</span>
            </div>
        </div>

        <p class="muted small spectate-note">
            Drawn in your browser from the player's own input, a couple of seconds behind them.
            While you watch, the player sees the server bot in their spectator list.
        </p>
    </section>

    <koneko-slot name="page.bottom"></koneko-slot>

    <site-footer></site-footer>
</div>
</template>

<script>
    app.component("spectate-view", {
        template: "#spectate-view",
        data: () => ({
            userId: 0,
            playerName: "",
            // the latest state of each server, by source ("stable", "lazer")
            states: {},
            source: "",
            connected: false,
            sound: false,
            mapKey: "",
            mapInfo: null,
            mapError: "",
            mods: [],
            live: { started: false, buffering: true, finished: null, hasFrames: false }
        }),
        computed: {
            avatarUrl() {
                return "https://a." + this.$koneko.domain + "/" + this.userId;
            },
            active() {
                return this.states[this.source] || null;
            },
            isLive() {
                return !!(this.active && this.active.playing);
            },
            sourceLabel() {
                const s = this.active;

                if (!s || !s.online) return "not in the game";

                return (this.source === "lazer" ? "osu!lazer" : "osu!stable")
                    + (s.playing ? " - playing" : s.action_text ? " - " + s.action_text : " - in the menus");
            },
            overlay() {
                const name = this.playerName || "The player";
                const s = this.active;

                if (!this.connected) {
                    return { icon: "fa-plug", text: "Connecting..." };
                }

                if (!s || !s.online) {
                    return { icon: "fa-moon", text: name + " is not in the game right now.",
                        hint: "Keep this page open: the play shows up here as soon as they start one." };
                }

                if (this.mapError && s.playing) {
                    return { icon: "fa-exclamation-triangle", text: this.mapError };
                }

                if (this.live.finished) {
                    return { icon: this.live.finished === "failed" ? "fa-times-circle" : "fa-flag-checkered",
                        text: this.live.finished === "failed" ? name + " failed the map." : name + " finished the map.",
                        hint: "Waiting for the next one." };
                }

                if (!s.playing) {
                    return { icon: "fa-hourglass-half", text: name + " is not playing right now.",
                        hint: "The play starts here by itself when they pick a map." };
                }

                if (!this.live.started) {
                    // the play does not begin until everything it needs is in
                    if (this.live.ready === false) {
                        const parts = (this.live.pending || []).join(", ");

                        return { icon: "fa-circle-notch fa-spin", text: "Loading the map...",
                            hint: parts ? "Waiting for: " + parts : "" };
                    }

                    return { icon: "fa-circle-notch fa-spin", text: "Waiting for the first frames..." };
                }

                return null;
            }
        },
        methods: {
            loadScript() {
                if (window.KonekoSpectate) return Promise.resolve();

                if (!window.__konekoSpectateLoading) {
                    window.__konekoSpectateLoading = new Promise((resolve, reject) => {
                        const tag = document.createElement("script");
                        tag.src = "/js/spectate-player.js?v=spectate-3";
                        tag.onload = resolve;
                        tag.onerror = reject;
                        document.head.appendChild(tag);
                    });
                }

                return window.__konekoSpectateLoading;
            },
            connect() {
                if (this.events) this.events.close();

                const events = new EventSource("/spectate/stream/" + this.userId);
                this.events = events;

                events.onopen = () => { this.connected = true; };
                // EventSource reconnects by itself; only the banner says so meanwhile
                events.onerror = () => { this.connected = events.readyState === EventSource.OPEN; };

                events.addEventListener("state", e => this.onState(JSON.parse(e.data)));
                events.addEventListener("frames", e => this.onFrames(JSON.parse(e.data)));
                events.addEventListener("end", e => this.onEnd(JSON.parse(e.data)));
            },
            /** The server to follow: the one the player is in gameplay on, else the one they are online on. */
            pickSource() {
                const all = Object.keys(this.states).map(k => this.states[k]);
                const pick = all.find(s => s.playing) || all.find(s => s.online) || all[0];

                return pick ? pick.source : "";
            },
            onState(state) {
                if (!state || !state.source) return;

                this.states = Object.assign({}, this.states, { [state.source]: state });
                this.source = this.pickSource();

                if (state.username && !this.playerName) this.playerName = state.username;

                const active = this.active;

                if (active && active.playing) {
                    this.preparePlay(active);
                }
            },
            playOptions(state) {
                const mods = Array.isArray(state.mods) ? state.mods : KonekoSpectate.modsFromBits(state.mods);

                return {
                    mods: mods,
                    rate: state.rate || KonekoSpectate.rateOf(mods),
                    mode: Number(state.mode) || 0,
                    source: state.source,
                    difficulty: state.difficulty || null
                };
            },
            /** A new play (another map, other mods, the other client): start the drawing over and fetch its map. */
            async preparePlay(state) {
                const key = state.source + ":" + state.beatmap_id + ":" + JSON.stringify(state.mods);

                if (key === this.mapKey || !this.player) return;

                this.mapKey = key;
                this.mapInfo = null;
                this.mapError = "";

                const opts = this.playOptions(state);
                this.mods = opts.mods;
                this.player.reset();
                this.player.setOptions(opts);
                // nothing is drawn until the map, the background and the song are all settled
                this.player.beginLoad();

                const id = Number(state.beatmap_id) || 0;

                if (!id) {
                    this.player.failMap();
                    this.mapError = "This map is not on the server, so it cannot be drawn.";
                    return;
                }

                this.player.setBackground("/spectate/asset/" + id + "/background");
                this.player.setAudio(this.sound ? "/spectate/asset/" + id + "/audio" : null);

                try {
                    const response = await fetch("/spectate/asset/" + id + "/osu");

                    if (!response.ok) throw new Error(String(response.status));

                    const map = KonekoSpectate.parseOsu(await response.text());

                    if (this.mapKey !== key || !this.player) return;

                    this.player.setMap(map, opts);
                    this.mapInfo = { title: map.title, artist: map.artist, version: map.version, creator: map.creator };
                    this.setTitle("Watching " + (this.playerName || "a player"));
                } catch (e) {
                    if (this.mapKey === key) {
                        if (this.player) this.player.failMap();
                        this.mapError = "The map could not be loaded.";
                    }
                }
            },
            onFrames(bundle) {
                if (!this.player || !bundle) return;

                // frames of the server not followed are left alone, unless it is the only one playing
                if (bundle.source !== this.source) {
                    const other = this.states[bundle.source];

                    if (!other || !other.playing) return;

                    this.source = bundle.source;
                    this.preparePlay(other);
                }

                this.player.pushFrames(bundle);
            },
            onEnd(data) {
                if (this.player && data && data.source === this.source) {
                    this.player.finish(data.reason === "quit" || data.reason === "offline" ? "quit" : data.reason);
                }
            },
            toggleSound() {
                this.sound = !this.sound;

                try {
                    window.localStorage.setItem("koneko:spectate:sound", this.sound ? "1" : "0");
                } catch (e) {
                    // a preference only
                }

                if (!this.player) return;

                const s = this.active;
                const id = s && Number(s.beatmap_id);

                if (this.sound && id && !this.player.audio) {
                    this.player.setAudio("/spectate/asset/" + id + "/audio");
                }

                this.player.setSound(this.sound);
            },
            fullscreen() {
                const stage = this.$refs.stage;

                if (document.fullscreenElement) {
                    document.exitFullscreen();
                } else if (stage && stage.requestFullscreen) {
                    stage.requestFullscreen();
                }
            },
            async loadName() {
                try {
                    const details = await this.api("get_player_details", { id: this.userId, scope: "info" });
                    const info = details && details.player && details.player.info;

                    if (info && info.name) {
                        this.playerName = info.name;
                        this.setTitle("Watching " + info.name);
                    }
                } catch (e) {
                    // the stream names the player as well
                }
            }
        },
        created() {
            this.userId = Number(window.location.pathname.split("/").filter(Boolean).pop()) || 0;
            this.setTitle("Spectating");

            try {
                this.sound = window.localStorage.getItem("koneko:spectate:sound") === "1";
            } catch (e) {
                this.sound = false;
            }
        },
        async mounted() {
            this.loadName();

            try {
                await this.loadScript();
            } catch (e) {
                this.mapError = "The spectator could not be loaded.";
                return;
            }

            if (this._gone) return;

            this.player = new KonekoSpectate.Player(this.$refs.canvas);
            this.player.setSound(this.sound);
            this.connect();

            this.poll = setInterval(() => {
                if (this.player) this.live = this.player.info();
            }, 250);
        },
        beforeUnmount() {
            this._gone = true;

            if (this.events) this.events.close();
            if (this.poll) clearInterval(this.poll);
            if (this.player) this.player.destroy();

            this.events = null;
            this.player = null;
        }
    });
</script>
