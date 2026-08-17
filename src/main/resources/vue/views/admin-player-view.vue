<template id="admin-player-view">
    <div class="admin-page">
        <div class="skeleton-rows" v-if="loading">
            <div class="skeleton skeleton-row" v-for="n in 5" :key="n"></div>
        </div>

        <section class="card" v-else-if="error">
            <p class="muted">{{ error }}</p>
            <a class="button button-ghost button-small" href="/admin/moderation">Back to the list</a>
        </section>

        <template v-else>
            <section class="card admin-profile">
                <img class="admin-profile-avatar" :src="avatar" :alt="player.name">

                <div class="admin-profile-text">
                    <h2>
                        <span class="admin-dot" :class="player.online ? 'is-online' : ''"></span>
                        {{ player.name }}
                    </h2>

                    <p class="muted small">
                        <span :class="flagClass(player.country)"></span>
                        #{{ player.id }} &middot; {{ email }} &middot;
                        joined {{ fmtDate(player.creation_time * 1000) }} &middot;
                        last seen {{ fmtRelative(player.latest_activity * 1000) }}
                    </p>

                    <p class="admin-badges">
                        <span class="admin-badge is-bad" v-if="player.restricted">restricted</span>
                        <span class="admin-badge is-warn" v-if="player.silenced">
                            silenced until {{ fmtDate(player.silence_end * 1000) }}
                        </span>
                        <span class="admin-badge is-good" v-if="player.supporter">
                            supporter until {{ fmtDate(player.donor_end * 1000) }}
                        </span>
                        <span class="admin-badge" v-for="role in player.roles" :key="role">{{ role }}</span>

                        <!-- The groups the account carries, in their own colours. -->
                        <span class="group-badge" v-for="group in groups" :key="group.id"
                            :style="groupStyle(group)" :title="group.name">
                            <span v-if="group.icon">{{ group.icon }}</span>
                            <span>{{ group.name }}</span>
                        </span>
                    </p>

                    <p>
                        <a class="button button-ghost button-small" :href="'/u/' + player.id">
                            Public profile
                        </a>
                        <a class="button button-ghost button-small" href="/admin/moderation">
                            Back to the list
                        </a>
                    </p>
                </div>
            </section>

            <section class="card">
                <h3>Actions</h3>

                <div class="admin-action-row">
                    <template v-if="can('restrict')">
                        <button class="button button-small button-ghost" v-if="player.restricted"
                            @click="act('unrestrict')">Unrestrict</button>
                        <button class="button button-small" v-else @click="act('restrict')">Restrict</button>
                    </template>

                    <template v-if="can('silence')">
                        <button class="button button-small button-ghost" v-if="player.silenced"
                            @click="act('unsilence')">Unsilence</button>
                        <button class="button button-small" v-else @click="act('silence')">Silence</button>
                    </template>

                    <button class="button button-small" v-if="can('note')" @click="act('note')">
                        Add note
                    </button>

                    <button class="button button-small" v-if="can('supporter')" @click="act('donator')">
                        Give supporter
                    </button>

                    <button class="button button-small" v-if="can('country')" @click="act('country')">
                        Change country
                    </button>

                    <button class="button button-small" v-if="can('rename')" @click="act('name')">
                        Rename
                    </button>

                    <button class="button button-small" v-if="can('password-reset')"
                        @click="act('password-reset')">Reset password</button>

                    <button class="button button-small" v-if="can('privileges')"
                        @click="act('privileges-add')">Add privilege</button>

                    <button class="button button-small" v-if="can('privileges')"
                        @click="act('privileges-remove')">Remove privilege</button>

                    <button class="button button-small" v-if="can('groups')" @click="act('groups')">
                        Groups
                    </button>

                    <button class="button button-small button-danger" v-if="can('wipe')"
                        @click="act('wipe')">Wipe</button>
                </div>

                <!-- Shown once, here, right after the link is minted: it is not stored
                     anywhere this page can read it back, so a staff member who navigates
                     away has to issue a new one. -->
                <div class="admin-reset-link" v-if="resetLink">
                    <p class="small">
                        <strong>Password reset link for {{ player.name }}.</strong>
                        Send it to them over a channel you trust and nowhere else: anyone
                        who has it can set this account's password.
                    </p>

                    <p class="admin-reset-row">
                        <input class="admin-reset-input" type="text" :value="resetLink" readonly
                            @focus="$event.target.select()">
                        <button class="button button-small button-ghost" @click="copyResetLink">
                            {{ resetCopied ? "Copied" : "Copy" }}
                        </button>
                    </p>

                    <p class="muted small">
                        Valid until {{ fmtDate(resetExpires * 1000) }}
                        ({{ fmtRelative(resetExpires * 1000) }}), and only once.
                        Issuing another link cancels this one.
                    </p>
                </div>
            </section>

            <!-- Play history sits above the action log on purpose: how much someone
                 has actually played is the context that decides whether a
                 restriction is proportionate. -->
            <section class="card" v-if="stats.length">
                <h3>Play history</h3>

                <table class="table">
                    <thead>
                        <tr>
                            <th>Mode</th>
                            <th>PP</th>
                            <th>Accuracy</th>
                            <th>Plays</th>
                            <th>Playtime</th>
                            <th>Ranked score</th>
                            <th>Max combo</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="row in stats" :key="row.mode">
                            <td>{{ modeName(row.mode) }}</td>
                            <td>{{ fmtNumber(Math.round(row.pp)) }}</td>
                            <td>{{ fmtAccuracy(row.accuracy / 100) }}</td>
                            <td>{{ fmtNumber(row.plays) }}</td>
                            <td>{{ fmtPlaytime(row.playtime) }}</td>
                            <td>{{ fmtNumber(row.ranked_score) }}</td>
                            <td>{{ fmtNumber(row.max_combo) }}</td>
                        </tr>
                    </tbody>
                </table>
            </section>

            <section class="card">
                <h3>Staff history</h3>
                <p class="muted small">{{ fmtNumber(logCount) }} recorded</p>

                <p class="muted" v-if="!logs.length">
                    Nothing has been done to this account yet.
                </p>

                <ul class="admin-log-list" v-else>
                    <li class="admin-log" v-for="entry in logs" :key="entry.id">
                        <span class="admin-badge" :class="actionClass(entry.action)">
                            {{ entry.action }}
                        </span>

                        <span class="admin-log-text">
                            <span class="admin-log-message" v-if="entry.message">{{ entry.message }}</span>
                            <span class="admin-log-message muted" v-else>No reason was given.</span>

                            <span class="admin-log-meta muted">
                                by {{ entry.from_name }} &middot;
                                {{ fmtRelative(entry.time) }} ({{ fmtDate(entry.time) }})
                            </span>
                        </span>
                    </li>
                </ul>
            </section>
        </template>

        <admin-action-dialog :open="!!dialog"
            :title="dialog ? dialog.title : ''"
            :text="dialog ? dialog.text || '' : ''"
            :fields="dialog ? dialog.fields : []"
            :confirm-label="dialog ? dialog.confirm : 'Confirm'"
            :danger="dialog ? !!dialog.danger : false"
            :busy="dialogBusy" :error="dialogError"
            @close="closeDialog" @submit="runAction"></admin-action-dialog>
    </div>
</template>

<script>
    /**
     * One account, as staff need to see it: who they are, what has been done to
     * them and by whom, how much they have played, and the buttons to act.
     *
     * The history is the reason this page exists. bancho.jar could already
     * restrict and wipe accounts, but only ever wrote those decisions to the
     * application log, so the question "what happened to this player, and why"
     * had no answer that a moderator could reach.
     */
    app.component("admin-player-view", {
        template: "#admin-player-view",
        props: {
            /**
             * Handed down by admin-panel-view, which owns the shell now.
             *
             * The default refuses everything: a section rendered without it shows
             * no action buttons at all, which is the safe way to be wrong.
             */
            can: { type: Function, default: () => () => false }
        },
        data: () => ({
            loading: true,
            error: "",
            userId: 0,
            player: {},
            email: "",
            logs: [],
            logCount: 0,
            stats: [],
            // The account's current groups, and every group that exists. The
            // second list is only fetched when the caller may change membership.
            groups: [],
            allGroups: [],
            dialog: null,
            dialogBusy: false,
            dialogError: "",
            // The last reset link issued from this page. Kept in memory only: the API
            // never hands a ticket out twice, so there is nothing to come back to.
            resetLink: "",
            resetExpires: 0,
            resetCopied: false
        }),
        computed: {
            avatar() {
                return "https://a." + this.$koneko.domain + "/" + this.userId;
            }
        },
        methods: {
            actionClass(action) {
                if (["restrict", "wipe"].indexOf(action) !== -1) return "is-bad";
                if (["silence", "privileges", "name"].indexOf(action) !== -1) return "is-warn";
                if (["unrestrict", "unsilence", "supporter"].indexOf(action) !== -1) return "is-good";

                return "";
            },
            async load() {
                try {
                    const answer = await this.session("GET",
                        "/admin/api/player?user_id=" + this.userId);

                    this.player = (answer && answer.player) || {};
                    this.email = (answer && answer.email) || "unknown";
                    this.logs = (answer && answer.logs) || [];
                    this.logCount = (answer && answer.log_count) || 0;
                    this.stats = (answer && answer.stats) || [];
                    this.groups = (answer && answer.groups) || [];
                    this.error = "";

                    this.setTitle(this.player.name || "Player");

                    // The groups dialog needs every group that exists, not only
                    // the account's own. Fetched with the page rather than on
                    // click, so the dialog opens instantly.
                    if (this.can("groups")) {
                        try {
                            const groupsAnswer = await this.session("GET", "/admin/api/groups");
                            this.allGroups = (groupsAnswer && groupsAnswer.groups) || [];
                        } catch (e) {
                            this.allGroups = [];
                        }
                    }
                } catch (e) {
                    this.error = e.message || "That account could not be loaded.";
                } finally {
                    this.loading = false;
                }
            },
            act(action) {
                this.dialogError = "";
                this.dialog = Object.assign({ action: action }, this.spec(action));
            },
            modeOptions() {
                const options = [{ value: -1, label: "Every mode" }];

                for (let mode = 0; mode <= 8; mode += 1) {
                    options.push({ value: mode, label: this.modeName(mode) });
                }

                return options;
            },
            privilegeOptions() {
                return [
                    "UNRESTRICTED", "VERIFIED", "WHITELISTED", "SUPPORTER", "PREMIUM",
                    "ALUMNI", "TOURNEY_MANAGER", "NOMINATOR", "MODERATOR",
                    "ADMINISTRATOR", "DEVELOPER"
                ].map(name => ({ value: name, label: name.replace(/_/g, " ") }));
            },
            heldPrivilegeOptions() {
                const held = (this.player.roles || [])
                    .map(role => String(role).trim().toUpperCase())
                    .filter(role => role.length > 0);

                if (!held.length) return this.privilegeOptions();

                return held.map(name => ({ value: name, label: name.replace(/_/g, " ") }));
            },
            spec(action) {
                const who = this.player.name || "this account";
                const reason = required => ({
                    key: "reason",
                    label: "Reason",
                    required: required,
                    hint: "Kept in this account's staff history."
                });

                if (action === "restrict") {
                    return {
                        title: "Restrict " + who,
                        confirm: "Restrict",
                        danger: true,
                        fields: [reason(true)]
                    };
                }

                if (action === "unrestrict") {
                    return {
                        title: "Unrestrict " + who,
                        confirm: "Unrestrict",
                        fields: [reason(false)]
                    };
                }

                if (action === "silence") {
                    return {
                        title: "Silence " + who,
                        confirm: "Silence",
                        fields: [
                            {
                                key: "duration",
                                label: "Duration",
                                value: "1h",
                                required: true,
                                hint: "For example 30m, 2h or 1d."
                            },
                            reason(true)
                        ]
                    };
                }

                if (action === "unsilence") {
                    return {
                        title: "Unsilence " + who,
                        confirm: "Unsilence",
                        fields: [reason(false)]
                    };
                }

                if (action === "note") {
                    return {
                        title: "Add a note to " + who,
                        text: "Only staff will see this.",
                        confirm: "Add note",
                        fields: [{
                            key: "message",
                            label: "Note",
                            type: "textarea",
                            required: true
                        }]
                    };
                }

                if (action === "donator") {
                    return {
                        title: "Give supporter to " + who,
                        confirm: "Give supporter",
                        fields: [{
                            key: "duration",
                            label: "Duration",
                            value: "30d",
                            required: true,
                            hint: "For example 30d. Use 0 to take it away."
                        }]
                    };
                }

                if (action === "country") {
                    return {
                        title: "Change the country of " + who,
                        confirm: "Change country",
                        fields: [{
                            key: "country",
                            label: "Country code",
                            value: String(this.player.country || "").toUpperCase(),
                            required: true,
                            maxlength: 2,
                            hint: "Two letters, for example DE."
                        }]
                    };
                }

                if (action === "name") {
                    return {
                        title: "Rename " + who,
                        confirm: "Rename",
                        fields: [{
                            key: "name",
                            label: "New username",
                            value: this.player.name || "",
                            required: true
                        }]
                    };
                }

                if (action === "privileges-add") {
                    return {
                        title: "Give privileges to " + who,
                        confirm: "Add privileges",
                        fields: [{
                            key: "privs",
                            label: "Privileges",
                            type: "checks",
                            required: true,
                            options: this.privilegeOptions(),
                            hint: "Tick every privilege to add."
                        }]
                    };
                }

                if (action === "privileges-remove") {
                    return {
                        title: "Take privileges from " + who,
                        confirm: "Remove privileges",
                        danger: true,
                        fields: [{
                            key: "privs",
                            label: "Privileges",
                            type: "checks",
                            required: true,
                            options: this.heldPrivilegeOptions(),
                            hint: "Tick every privilege to take away."
                        }]
                    };
                }

                if (action === "groups") {
                    return {
                        title: "Groups of " + who,
                        text: "Tick every group the account belongs to. The rest is removed.",
                        confirm: "Save groups",
                        fields: [{
                            key: "groups",
                            label: "Groups",
                            type: "checks",
                            options: this.allGroups.map(group => ({
                                value: group.id,
                                label: (group.icon ? group.icon + " " : "") + group.name
                            })),
                            value: this.groups.map(group => group.id)
                        }]
                    };
                }

                if (action === "wipe") {
                    return {
                        title: "Wipe " + who,
                        text: "Every score and statistic in the chosen mode is removed."
                            + " This cannot be undone.",
                        confirm: "Wipe",
                        danger: true,
                        fields: [{
                            key: "mode",
                            label: "Mode",
                            type: "select",
                            options: this.modeOptions()
                        }]
                    };
                }

                if (action === "password-reset") {
                    return {
                        title: "Reset the password of " + who,
                        text: "This does not change the password. It makes a one time link that"
                            + " lets " + who + " choose a new one, and cancels any earlier link."
                            + " Make sure you know who you are talking to before you send it,"
                            + " and never set a password on their behalf.",
                        confirm: "Create link",
                        fields: [{
                            key: "expires_in_hours",
                            label: "Valid for",
                            type: "select",
                            value: 24,
                            options: [
                                { value: 1, label: "1 hour" },
                                { value: 6, label: "6 hours" },
                                { value: 24, label: "24 hours" },
                                { value: 72, label: "3 days" },
                                { value: 168, label: "7 days" }
                            ],
                            hint: "Shorter is safer. The link stops working after it is used."
                        }]
                    };
                }

                return { title: "Confirm", confirm: "Confirm", fields: [] };
            },
            async runAction(values) {
                if (!this.dialog) return;

                const body = Object.assign({ user_id: this.userId }, values);

                if (body.mode !== undefined) body.mode = Number(body.mode);
                if (body.expires_in_hours !== undefined) {
                    body.expires_in_hours = Number(body.expires_in_hours);
                }

                this.dialogBusy = true;
                this.dialogError = "";

                try {
                    const action = this.dialog.action;

                    // Membership is a set: it goes to its own endpoint as one
                    // call rather than one per checkbox.
                    const answer = action === "groups"
                        ? await this.session("POST", "/admin/api/group-members", {
                            action: "set",
                            user_id: this.userId,
                            group_ids: (body.groups || []).map(Number)
                        })
                        : await this.session("POST", "/admin/api/" + action, body);

                    this.dialog = null;

                    // A reset link is the one action whose answer matters to the person who
                    // asked for it, so it is kept and shown instead of being thrown away.
                    if (action === "password-reset") {
                        const issued = (answer && answer.body) || {};

                        this.resetLink = window.location.origin
                            + (issued.path || ("/reset-password?token=" + issued.token));
                        this.resetExpires = issued.expires_at || 0;
                        this.resetCopied = false;
                    }

                    // The history has just gained a line, so the page is reloaded
                    // rather than patched: the new line is the point.
                    this.loading = true;
                    await this.load();
                } catch (e) {
                    this.dialogError = e.message || "That could not be done.";
                } finally {
                    this.dialogBusy = false;
                }
            },
            async copyResetLink() {
                try {
                    await navigator.clipboard.writeText(this.resetLink);
                    this.resetCopied = true;
                } catch (e) {
                    // No clipboard permission, or an insecure origin. The field next to the
                    // button is selectable, so there is nothing to recover from.
                    this.resetCopied = false;
                }
            },
            closeDialog() {
                this.dialog = null;
                this.dialogError = "";
            }
        },
        created() {
            const parts = window.location.pathname.split("/").filter(part => part.length > 0);

            this.userId = Number(parts[parts.length - 1]) || 0;
            this.setTitle("Player");
            this.load();
        }
    });
</script>
