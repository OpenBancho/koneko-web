<template id="admin-daily-view">
    <div class="admin-page daily">
        <section class="card">
            <h2>Daily Challenge</h2>
            <p class="muted small">
                One map per day (UTC) that every player can play from the multiplayer lobby. Pick a map below &mdash; click
                a card, or click the star badge of the exact difficulty &mdash; then choose the date and the mods.
                Choosing a date that already has a challenge replaces it.
            </p>

            <p class="daily-notice daily-notice-ok" v-if="notice" role="status">{{ notice }}</p>
            <p class="daily-notice daily-notice-error" v-if="error" role="alert">{{ error }}</p>
        </section>

        <div class="daily-grid">
            <div class="daily-left">
                <!-- 1. FIND A MAP -->
                <section class="card">
                    <h3 class="daily-step"><span class="daily-step-n">1</span> Find a map</h3>

                    <div class="daily-toolbar">
                        <input class="filter-input daily-search" type="search" v-model="query" @input="onQuery"
                            placeholder="Search by title, artist or mapper, or paste a link / ID"
                            autocomplete="off" spellcheck="false" aria-label="Search for a beatmap">

                        <select class="filter-input daily-status" v-model="status" @change="refresh"
                            aria-label="Map status">
                            <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.name }}</option>
                        </select>
                    </div>

                    <div class="daily-segment" role="group" aria-label="Game mode">
                        <button type="button" class="daily-segment-btn" v-for="m in modeOptions" :key="m.value"
                            :class="{ 'is-on': mode === m.value }" @click="setMode(m.value)">{{ m.name }}</button>
                    </div>

                    <div class="daily-tabs" v-if="!isSearching">
                        <span class="daily-tabs-label muted">Suggestions</span>
                        <button type="button" class="daily-tab" v-for="t in tabs" :key="t.value"
                            :class="{ 'is-on': tab === t.value }" @click="setTab(t.value)">{{ t.name }}</button>
                    </div>
                    <p class="muted small daily-tabs" v-else>
                        Search results. Links like <code>/beatmapsets/123#osu/456</code> or <code>/b/456</code> open that
                        exact difficulty.
                    </p>

                    <div class="daily-cards" v-if="loadingList">
                        <div class="skeleton daily-card-skeleton" v-for="n in 6" :key="n"></div>
                    </div>

                    <p class="muted daily-empty" v-else-if="!results.length">
                        {{ isSearching ? "Nothing found for that. Try fewer words or another status." : "No maps to suggest with these filters." }}
                    </p>

                    <div class="daily-cards" v-else>
                        <article class="daily-card" v-for="item in results" :key="item.set_id"
                            :class="{ 'is-picked': set && set.set_id === item.set_id }" @click="pickSet(item)">
                            <div class="daily-card-cover" :style="coverStyle(item.set_id, 'card')">
                                <span class="badge daily-card-status">{{ statusName(item.status) }}</span>
                                <span class="daily-card-time">{{ fmtLength(item.total_length) }} &middot; {{ Math.round(item.bpm) }} BPM</span>
                            </div>

                            <div class="daily-card-body">
                                <strong class="daily-card-title" :title="item.title">{{ item.title }}</strong>
                                <span class="daily-card-artist" :title="item.artist">{{ item.artist }}</span>
                                <span class="daily-card-mapper muted">mapped by {{ item.creator }}</span>

                                <div class="daily-pills">
                                    <button type="button" class="daily-pill" v-for="d in pills(item)" :key="d.id"
                                        :class="{ 'is-picked': diffId === d.id }"
                                        :title="d.version + ' (' + modeName(d.mode) + ', ' + fmtDecimal(d.diff, 2) + ' stars)'"
                                        @click.stop="pickSet(item, d.id)">
                                        <span class="daily-pill-orb" :style="{ background: starColor(d.diff) }"></span>
                                        {{ d.diff > 0 ? fmtDecimal(d.diff, 1) : "?" }}
                                    </button>
                                    <span class="daily-pill-more muted" v-if="hiddenPills(item) > 0">+{{ hiddenPills(item) }}</span>
                                </div>
                            </div>
                        </article>
                    </div>
                </section>

                <!-- 2. DIFFICULTY -->
                <section class="card" v-if="set" ref="step2">
                    <h3 class="daily-step"><span class="daily-step-n">2</span> Choose the difficulty</h3>

                    <div class="daily-banner" :style="coverStyle(set.set_id, 'cover')">
                        <div class="daily-banner-text">
                            <strong>{{ set.artist }} - {{ set.title }}</strong>
                            <span>mapped by {{ set.creator }} &middot; {{ fmtLength(set.total_length) }} &middot; {{ Math.round(set.bpm) }} BPM</span>
                        </div>
                    </div>

                    <div class="daily-diffs">
                        <button type="button" class="daily-diff" v-for="d in diffs" :key="d.id"
                            :class="{ 'is-picked': diff && diff.id === d.id }" @click="pickDiff(d)">
                            <span class="daily-diff-orb" :style="{ background: starColor(d.diff) }"></span>
                            <span class="daily-diff-name">{{ d.version }}</span>
                            <span class="daily-diff-mode">{{ modeName(d.mode) }}</span>
                            <span class="daily-diff-stats muted">
                                CS {{ fmtDecimal(d.cs, 1) }} &middot; AR {{ fmtDecimal(d.ar, 1) }}
                                &middot; OD {{ fmtDecimal(d.od, 1) }} &middot; HP {{ fmtDecimal(d.hp, 1) }}
                            </span>
                            <span class="star-pill" :style="{ background: starColor(d.diff) }">
                                {{ d.diff > 0 ? fmtDecimal(d.diff, 2) : "?" }}
                            </span>
                        </button>
                    </div>
                </section>

                <section class="card daily-locked" v-else>
                    <h3 class="daily-step"><span class="daily-step-n is-idle">2</span> Choose the difficulty</h3>
                    <p class="muted small">Pick a map above and its difficulties show up here.</p>
                </section>

                <!-- 3. WHEN AND HOW -->
                <section class="card" v-if="diff">
                    <h3 class="daily-step"><span class="daily-step-n">3</span> Date and rules</h3>

                    <div class="daily-fields">
                        <label class="daily-field">
                            <span class="field-label">Date (UTC)</span>
                            <input class="filter-input" type="date" v-model="date" :min="today">
                        </label>

                        <label class="daily-field" v-if="rulesetOptions.length > 1">
                            <span class="field-label">Ruleset</span>
                            <select class="filter-input" v-model.number="rulesetId" @change="pruneMods">
                                <option v-for="r in rulesetOptions" :key="r" :value="r">{{ modeName(r) }}</option>
                            </select>
                        </label>
                    </div>

                    <p class="daily-notice daily-notice-warn" v-if="taken && !editing">
                        {{ date }} already has a challenge: <strong>{{ taken.artist }} - {{ taken.title }}
                        [{{ taken.version }}]</strong>. Scheduling this one replaces it.
                    </p>

                    <div class="daily-mods">
                        <span class="field-label">Mods</span>
                        <p class="muted small">
                            Click a mod to cycle it: <span class="daily-chip-legend is-off">off</span> &rarr;
                            <span class="daily-chip-legend is-allowed">allowed</span> (players may add it) &rarr;
                            <span class="daily-chip-legend is-required">required</span> (everyone plays with it).
                        </p>

                        <div class="daily-chips">
                            <button type="button" class="daily-chip" v-for="m in modList" :key="m.a"
                                :class="'is-' + (mods[m.a] || 'off')" :title="m.n" @click="cycleMod(m.a)">
                                <span class="daily-chip-a">{{ m.a }}</span>
                                <span class="daily-chip-n">{{ m.n }}</span>
                            </button>
                        </div>
                    </div>
                </section>

                <!-- 4. CONFIRM -->
                <section class="card daily-confirm" v-if="diff">
                    <h3 class="daily-step"><span class="daily-step-n">4</span> Schedule it</h3>

                    <div class="daily-summary">
                        <span class="daily-cover daily-cover-big" :style="coverStyle(set.set_id, 'card')"></span>
                        <div class="daily-summary-body">
                            <strong>{{ set.artist }} - {{ set.title }} [{{ diff.version }}]</strong>
                            <span class="muted">
                                {{ modeName(rulesetId) }} &middot; {{ fmtDecimal(diff.diff, 2) }}&#9733;
                                &middot; {{ fmtLength(diff.total_length) }} &middot; {{ date || "no date" }}
                            </span>
                            <span class="muted">
                                Required: {{ listMods("required") || "none" }}
                                &middot; Allowed: {{ listMods("allowed") || "none" }}
                            </span>
                        </div>
                    </div>

                    <div class="admin-action-row">
                        <button class="button" type="button" :disabled="saving || !date" @click="save">
                            {{ saving ? "Saving..." : (editing ? "Save changes" : (taken ? "Replace the challenge" : "Schedule challenge")) }}
                        </button>
                        <button class="button button-ghost" type="button" :disabled="saving" @click="reset">Start over</button>
                    </div>
                </section>

                <section class="card daily-locked" v-else>
                    <h3 class="daily-step"><span class="daily-step-n is-idle">3</span> Date, mods and scheduling</h3>
                    <p class="muted small">Available once a difficulty is chosen.</p>
                </section>
            </div>

            <!-- SCHEDULE -->
            <aside class="daily-right">
                <section class="card">
                    <h3>Scheduled <span class="muted" v-if="schedule.length">({{ schedule.length }})</span></h3>

                    <p class="muted small" v-if="!loadingSchedule && roomLive !== null">
                        Today's room is
                        <strong :class="roomLive ? 'daily-live' : 'daily-idle'">{{ roomLive ? "open" : "not running" }}</strong>.
                    </p>

                    <div class="skeleton-rows" v-if="loadingSchedule">
                        <div class="skeleton skeleton-row" v-for="n in 3" :key="n"></div>
                    </div>

                    <p class="muted" v-else-if="scheduleError">{{ scheduleError }}</p>
                    <p class="muted" v-else-if="!schedule.length">Nothing scheduled yet.</p>

                    <ul class="daily-list" v-else>
                        <li class="daily-item" v-for="row in schedule" :key="row.date"
                            :class="{ 'is-today': row.is_today, 'is-past': row.date < today, 'is-editing': editing === row.date }">
                            <span class="daily-cover" :style="coverStyle(row.set_id, 'list')"></span>

                            <span class="daily-item-body">
                                <span class="daily-item-date">
                                    {{ row.date }}
                                    <span class="badge" v-if="row.is_today">today</span>
                                    <span class="badge" v-else-if="row.date < today">past</span>
                                </span>
                                <span class="daily-item-title" v-if="row.title">
                                    {{ row.artist }} - {{ row.title }} <span class="muted">[{{ row.version }}]</span>
                                </span>
                                <span class="daily-item-title muted" v-else>Beatmap #{{ row.beatmap_id }} (not in the database)</span>
                                <span class="daily-item-meta muted">
                                    {{ modeName(row.ruleset_id) }}
                                    <template v-if="row.stars"> &middot; {{ fmtDecimal(row.stars, 2) }}&#9733;</template>
                                    <template v-if="row.required_mods.length"> &middot; req: {{ acronyms(row.required_mods) }}</template>
                                    <template v-if="row.allowed_mods.length"> &middot; opt: {{ acronyms(row.allowed_mods) }}</template>
                                </span>
                            </span>

                            <span class="daily-item-actions">
                                <button class="button button-small button-ghost" type="button" @click="edit(row)"
                                    :disabled="saving">Edit</button>
                                <button class="button button-small button-ghost daily-remove" type="button"
                                    :disabled="saving" @click="remove(row)">
                                    {{ confirming === row.date ? "Sure?" : "Remove" }}
                                </button>
                            </span>
                        </li>
                    </ul>
                </section>
            </aside>
        </div>
    </div>
</template>

<script>
    /**
     * Picking the daily challenge without typing an ID.
     *
     * The page opens with suggestions (the latest and the most played maps, filtered by mode and status) so there is
     * always something to click; the search box narrows them down by title, artist or mapper, or takes a pasted link
     * or ID. Every card shows the star badges of its difficulties, and clicking a badge picks that exact one. The mods
     * are toggled as chips, the date comes with a note when it would replace something, and saving goes through
     * /admin/api/daily, which is limited to administrators and developers and talks to lazer.jar with the website's key.
     */
    (function () {
        // Mods a daily challenge can sensibly allow or require. The ruleset decides which of them exist.
        const MODS = [
            { a: "EZ", n: "Easy" },
            { a: "NF", n: "No Fail" },
            { a: "HT", n: "Half Time" },
            { a: "HD", n: "Hidden" },
            { a: "HR", n: "Hard Rock", not: [3] },
            { a: "DT", n: "Double Time" },
            { a: "NC", n: "Nightcore" },
            { a: "FL", n: "Flashlight" },
            { a: "SO", n: "Spun Out", only: [0] }
        ];

        // Mods that cannot be played together: choosing one clears the others.
        const CONFLICTS = {
            EZ: ["HR"], HR: ["EZ"],
            HT: ["DT", "NC"], DT: ["HT", "NC"], NC: ["HT", "DT"]
        };

        const MAX_PILLS = 8;

        app.component("admin-daily-view", {
            template: "#admin-daily-view",
            // handed down by the panel shell; this page has no use for it but must not leak it onto the DOM
            props: { can: { type: Function, default: null } },
            data: () => ({
                query: "",
                results: [],
                loadingList: true,
                listRun: 0,
                timer: null,

                // filters of the suggestions and of the search
                mode: "",
                status: "1",
                tab: "updated",
                modeOptions: [
                    { value: "", name: "Any mode" },
                    { value: "0", name: "osu!" },
                    { value: "1", name: "taiko" },
                    { value: "2", name: "catch" },
                    { value: "3", name: "mania" }
                ],
                statusOptions: [
                    { value: "1", name: "Ranked" },
                    { value: "4", name: "Loved" },
                    { value: "", name: "Any status" }
                ],
                tabs: [
                    { value: "updated", name: "Latest" },
                    { value: "plays", name: "Most played" }
                ],

                set: null,
                diffId: null,
                rulesetId: 0,
                mods: {},
                date: "",
                // The date of the challenge being edited; empty when scheduling a new one.
                editing: "",

                today: new Date().toISOString().slice(0, 10),
                schedule: [],
                roomLive: null,
                loadingSchedule: true,
                scheduleError: "",

                saving: false,
                confirming: "",
                confirmTimer: null,
                notice: "",
                error: ""
            }),
            computed: {
                isSearching() {
                    return this.query.trim().length > 0;
                },
                diffs() {
                    return this.set ? this.sortedDiffs(this.set) : [];
                },
                diff() {
                    return this.diffs.find(d => d.id === this.diffId) || null;
                },
                // A map made for osu! can be played in every ruleset (converts); any other is played in its own.
                rulesetOptions() {
                    if (!this.diff) return [];

                    return this.diff.mode === 0 ? [0, 1, 2, 3] : [this.diff.mode];
                },
                modList() {
                    return MODS.filter(m => (!m.only || m.only.indexOf(this.rulesetId) !== -1)
                        && (!m.not || m.not.indexOf(this.rulesetId) === -1));
                },
                taken() {
                    return this.schedule.find(row => row.date === this.date) || null;
                }
            },
            methods: {
                coverStyle(setId, size) {
                    return setId ? { backgroundImage: 'url("' + this.coverUrl(setId, size) + '")' } : {};
                },
                starColor(stars) {
                    const value = Number(stars || 0);
                    if (value <= 0) return "#5a5a63";
                    if (value < 2) return "#4fc0ff";
                    if (value < 2.7) return "#4fffd5";
                    if (value < 4) return "#7cff4e";
                    if (value < 5.3) return "#f2f261";
                    if (value < 6.5) return "#ff8068";
                    if (value < 8) return "#ff4e6f";
                    return "#a653ff";
                },
                sortedDiffs(set) {
                    return (set.difficulties || []).slice().sort((a, b) => (a.mode - b.mode) || (a.diff - b.diff));
                },
                /** The badges shown on a card: easiest to hardest, capped so a huge set does not blow the card up. */
                pills(item) {
                    return this.sortedDiffs(item).slice(0, MAX_PILLS);
                },
                hiddenPills(item) {
                    return Math.max(0, (item.difficulties || []).length - MAX_PILLS);
                },
                acronyms(list) {
                    return (list || []).map(m => m.acronym).join(" ");
                },
                listMods(kind) {
                    return Object.keys(this.mods).filter(a => this.mods[a] === kind).join(" ");
                },

                // ---- suggestions and search ----
                setMode(value) {
                    this.mode = value;
                    this.refresh();
                },
                setTab(value) {
                    this.tab = value;
                    this.refresh();
                },
                onQuery() {
                    window.clearTimeout(this.timer);
                    this.loadingList = true;
                    this.timer = window.setTimeout(() => this.refresh(), 320);
                },
                /** The parameters shared by the suggestions and the text search. */
                filters() {
                    return {
                        mode: this.mode,
                        status: this.status,
                        limit: 12
                    };
                },
                /** Reads a pasted link or id: the set and, when it names one, the difficulty. */
                parseReference(text) {
                    const value = text.trim();
                    let m;

                    if ((m = /beatmapsets\/(\d+)(?:#[a-z]+\/(\d+))?/i.exec(value))) {
                        return { setId: Number(m[1]), mapId: m[2] ? Number(m[2]) : null };
                    }
                    if ((m = /(?:\/b\/|\/beatmaps\/)(\d+)/i.exec(value))) {
                        return { mapId: Number(m[1]) };
                    }
                    if (/^\d{1,9}$/.test(value)) {
                        return { anyId: Number(value) };
                    }

                    return null;
                },
                /** Fills the list: suggestions when the box is empty, matches when it is not. */
                async refresh() {
                    const run = ++this.listRun;
                    const text = this.query.trim();

                    this.loadingList = true;
                    this.error = "";

                    try {
                        if (text.length === 1 && !/^\d$/.test(text)) {
                            // One letter matches half the catalogue: wait for a second one.
                            if (run === this.listRun) this.results = [];
                            return;
                        }

                        const ref = text ? this.parseReference(text) : null;

                        if (ref) {
                            const hit = await this.resolve(ref);

                            if (run !== this.listRun) return;

                            if (hit) {
                                this.results = [hit.set];
                                this.pickSet(hit.set, hit.mapId);
                                return;
                            }
                        }

                        const params = Object.assign(this.filters(), text ? { q: text } : { sort: this.tab === "updated" ? "" : this.tab });
                        const body = await this.api("search_beatmapsets", params);

                        if (run !== this.listRun) return;

                        this.results = (body && body.results) || [];
                    } catch (e) {
                        if (run === this.listRun) {
                            this.results = [];
                            this.error = "The map list could not be loaded. Try again.";
                        }
                    } finally {
                        if (run === this.listRun) this.loadingList = false;
                    }
                },
                /** A link or id to its set (and difficulty). Null when this server has no such map. */
                async resolve(ref) {
                    let mapId = ref.mapId || null;
                    let setId = ref.setId || null;

                    if (!setId && (mapId || ref.anyId)) {
                        const asMap = await this.apiQuietly("get_map_info", { id: mapId || ref.anyId });

                        if (asMap && asMap.map) {
                            setId = asMap.map.set_id;
                            mapId = asMap.map.id;
                        } else if (ref.anyId) {
                            setId = ref.anyId;
                        }
                    }

                    if (!setId) return null;

                    const body = await this.apiQuietly("get_beatmapset", { id: setId });
                    const set = body && body.beatmapset;

                    return set ? { set, mapId } : null;
                },
                pickSet(item, mapId) {
                    this.set = item;
                    this.notice = "";

                    const wanted = mapId ? this.sortedDiffs(item).find(d => d.id === mapId) : null;

                    // The hardest difficulty is the usual daily pick, but only when nothing more specific was asked.
                    const fallback = this.sortedDiffs(item).slice().reverse()[0];

                    this.pickDiff(wanted || fallback || null);

                    // Take the eye to the difficulty list: the card that was clicked is a long way up.
                    this.$nextTick(() => {
                        const el = this.$refs.step2;

                        if (el && el.scrollIntoView) el.scrollIntoView({ behavior: "smooth", block: "start" });
                    });
                },
                pickDiff(d) {
                    this.diffId = d ? d.id : null;

                    if (!d) return;

                    this.rulesetId = d.mode;
                    this.pruneMods();

                    if (!this.date) this.date = this.firstFreeDate();
                },

                // ---- mods ----
                cycleMod(a) {
                    const next = { "": "allowed", allowed: "required", required: "" }[this.mods[a] || ""];
                    const mods = Object.assign({}, this.mods);

                    if (next) {
                        mods[a] = next;

                        // Choosing a mod rules out whatever cannot be played with it.
                        (CONFLICTS[a] || []).forEach(other => { delete mods[other]; });
                    } else {
                        delete mods[a];
                    }

                    this.mods = mods;
                },
                /** After the ruleset changed: drop the mods that do not exist there. */
                pruneMods() {
                    const allowed = this.modList.map(m => m.a);
                    const mods = {};

                    Object.keys(this.mods).forEach(a => { if (allowed.indexOf(a) !== -1) mods[a] = this.mods[a]; });
                    this.mods = mods;
                },

                // ---- schedule ----
                firstFreeDate() {
                    const used = new Set(this.schedule.map(row => row.date));
                    const day = new Date(this.today + "T00:00:00Z");

                    for (let i = 0; i < 365; i++) {
                        const iso = day.toISOString().slice(0, 10);

                        if (!used.has(iso)) return iso;

                        day.setUTCDate(day.getUTCDate() + 1);
                    }

                    return this.today;
                },
                async loadSchedule() {
                    this.loadingSchedule = true;
                    this.scheduleError = "";

                    try {
                        const answer = await this.session("GET", "/admin/api/daily");

                        this.schedule = (answer && answer.challenges) || [];
                        this.roomLive = answer ? !!answer.room_live : null;

                        if (answer && answer.today) this.today = answer.today;
                    } catch (e) {
                        this.scheduleError = e.message || "The schedule could not be loaded.";
                    } finally {
                        this.loadingSchedule = false;
                    }

                    if (!this.date && this.diff) this.date = this.firstFreeDate();
                },
                async save() {
                    if (!this.diff || !this.date || this.saving) return;

                    this.saving = true;
                    this.error = "";
                    this.notice = "";

                    const pick = kind => Object.keys(this.mods).filter(a => this.mods[a] === kind)
                        .map(a => ({ acronym: a }));

                    try {
                        await this.session("POST", "/admin/api/daily/set", {
                            date: this.date,
                            beatmap_id: this.diff.id,
                            ruleset_id: this.rulesetId,
                            required_mods: pick("required"),
                            allowed_mods: pick("allowed")
                        });

                        this.notice = (this.editing ? "Saved " : "Scheduled ") + this.set.artist + " - " + this.set.title
                            + " [" + this.diff.version + "] for " + this.date + ".";
                        this.reset(true);
                        await this.loadSchedule();
                    } catch (e) {
                        this.error = e.message || "The challenge could not be saved.";
                    } finally {
                        this.saving = false;
                    }
                },
                /** Loads an existing challenge into the form. */
                async edit(row) {
                    this.error = "";
                    this.notice = "";

                    const info = await this.apiQuietly("get_map_info", { id: row.beatmap_id });
                    const setId = (info && info.map && info.map.set_id) || row.set_id;
                    const body = setId ? await this.apiQuietly("get_beatmapset", { id: setId }) : null;

                    if (!body || !body.beatmapset) {
                        this.error = "That beatmap is not in the database any more, so it cannot be edited. Pick a new one.";
                        this.date = row.date;
                        return;
                    }

                    this.set = body.beatmapset;
                    this.diffId = row.beatmap_id;
                    this.rulesetId = row.ruleset_id;
                    this.date = row.date;
                    this.editing = row.date;

                    const mods = {};
                    (row.allowed_mods || []).forEach(m => { mods[m.acronym] = "allowed"; });
                    (row.required_mods || []).forEach(m => { mods[m.acronym] = "required"; });
                    this.mods = mods;

                    this.$nextTick(() => {
                        const el = this.$refs.step2;

                        if (el && el.scrollIntoView) el.scrollIntoView({ behavior: "smooth", block: "start" });
                    });
                },
                async remove(row) {
                    // Two clicks: the first arms the button for a few seconds.
                    if (this.confirming !== row.date) {
                        this.confirming = row.date;
                        window.clearTimeout(this.confirmTimer);
                        this.confirmTimer = window.setTimeout(() => { this.confirming = ""; }, 4000);
                        return;
                    }

                    this.confirming = "";
                    this.saving = true;
                    this.error = "";
                    this.notice = "";

                    try {
                        await this.session("POST", "/admin/api/daily/remove", { date: row.date });
                        this.notice = "Removed the challenge of " + row.date + ".";

                        if (this.editing === row.date) this.reset(true);

                        await this.loadSchedule();
                    } catch (e) {
                        this.error = e.message || "The challenge could not be removed.";
                    } finally {
                        this.saving = false;
                    }
                },
                reset(keepNotice) {
                    this.set = null;
                    this.diffId = null;
                    this.mods = {};
                    this.editing = "";
                    this.date = "";
                    this.rulesetId = 0;

                    if (keepNotice !== true) this.notice = "";
                }
            },
            created() {
                this.setTitle("Daily Challenge");
                this.refresh();
                this.loadSchedule();
            },
            unmounted() {
                window.clearTimeout(this.timer);
                window.clearTimeout(this.confirmTimer);
            }
        });
    })();
</script>
