<template id="admin-groups-view">
    <div class="admin-page">
        <div class="skeleton-rows" v-if="loading">
            <div class="skeleton skeleton-row" v-for="n in 4" :key="n"></div>
        </div>

        <section class="card" v-else-if="error">
            <p class="muted">{{ error }}</p>
        </section>

        <template v-else>
            <section class="card">
                <div class="admin-groups-head">
                    <h3>Groups</h3>

                    <button class="button button-small" v-if="can('groups')" @click="edit(null)">
                        Create group
                    </button>
                </div>

                <p class="muted" v-if="!groups.length">
                    No groups yet. Members are added from a player's staff page.
                </p>

                <div class="admin-group" v-for="group in groups" :key="group.id">
                    <div class="admin-group-main">
                        <span class="group-badge" :style="groupStyle(group)" :title="group.name">
                            <span v-if="group.icon">{{ group.icon }}</span>
                            <span>{{ group.name }}</span>
                        </span>

                        <span class="muted small" v-if="group.description">{{ group.description }}</span>
                    </div>

                    <div class="admin-group-side">
                        <button class="button button-ghost button-small" type="button"
                            @click="toggleMembers(group)">
                            {{ expanded === group.id ? "Hide members" : "Members (" + group.members + ")" }}
                        </button>

                        <template v-if="can('groups')">
                            <button class="button button-ghost button-small" type="button"
                                @click="edit(group)">Edit</button>
                            <button class="button button-ghost button-small button-danger" type="button"
                                @click="askDelete(group)">Delete</button>
                        </template>
                    </div>

                    <!-- The member list is fetched on first expand and kept, so
                         closing and reopening a group costs nothing. -->
                    <div class="admin-group-members" v-if="expanded === group.id">
                        <p class="muted small" v-if="membersBusy">Loading...</p>

                        <p class="muted small" v-else-if="!(members[group.id] || []).length">
                            Nobody is in this group yet. Members are added from a player's staff page.
                        </p>

                        <div class="admin-group-member" v-else v-for="member in members[group.id]"
                            :key="member.id">
                            <span class="country-tag" v-if="flagClass(member.country)"
                                :class="flagClass(member.country)"></span>
                            <a :href="'/admin/moderation/' + member.id">{{ member.name }}</a>

                            <button class="button button-ghost button-small" type="button"
                                v-if="can('groups')" :disabled="memberBusy[member.id]"
                                @click="removeMember(group, member)">Remove</button>
                        </div>
                    </div>
                </div>
            </section>
        </template>

        <admin-action-dialog :open="!!dialog"
            :title="dialog ? dialog.title : ''"
            :text="dialog ? dialog.text || '' : ''"
            :fields="dialog ? dialog.fields : []"
            :confirm-label="dialog ? dialog.confirm : 'Confirm'"
            :danger="dialog ? !!dialog.danger : false"
            :busy="dialogBusy" :error="dialogError"
            @close="closeDialog" @submit="runDialog"></admin-action-dialog>
    </div>
</template>

<script>
    /**
     * The Groups page of the staff panel: every group, who is in it, and the
     * controls to create, edit and delete. Membership is mostly managed from a
     * player's own staff page; removing one from here is the exception.
     *
     * The buttons are hidden without the groups action, but that is a courtesy:
     * every endpoint authorises the caller again.
     */
    app.component("admin-groups-view", {
        template: "#admin-groups-view",
        props: {
            can: { type: Function, default: () => () => false }
        },
        data: () => ({
            loading: true,
            error: "",
            groups: [],
            // Which group's member list is open, and the lists fetched so far.
            expanded: 0,
            members: {},
            membersBusy: false,
            memberBusy: {},
            dialog: null,
            dialogBusy: false,
            dialogError: ""
        }),
        methods: {
            async load() {
                try {
                    const answer = await this.session("GET", "/admin/api/groups");

                    this.groups = (answer && answer.groups) || [];
                    this.error = "";
                } catch (e) {
                    this.error = e.message || "The groups could not be loaded.";
                } finally {
                    this.loading = false;
                }
            },
            edit(group) {
                this.dialogError = "";
                this.dialog = {
                    action: group ? "update" : "create",
                    group: group,
                    title: group ? "Edit " + group.name : "Create a group",
                    confirm: group ? "Save" : "Create",
                    fields: [
                        {
                            key: "name",
                            label: "Name",
                            value: group ? group.name : "",
                            required: true,
                            maxlength: 32
                        },
                        {
                            key: "icon",
                            label: "Icon",
                            value: group ? group.icon : "",
                            maxlength: 16,
                            hint: "One emoji works best, for example \uD83C\uDFA7. Shown in front of the name."
                        },
                        {
                            key: "colour",
                            label: "Colour",
                            value: group ? group.colour : "ff4d8d",
                            required: true,
                            maxlength: 6,
                            hint: "Six hex digits, no hash."
                        },
                        {
                            key: "description",
                            label: "Description",
                            value: group ? group.description : "",
                            maxlength: 128
                        }
                    ]
                };
            },
            askDelete(group) {
                this.dialogError = "";
                this.dialog = {
                    action: "delete",
                    group: group,
                    title: "Delete " + group.name,
                    text: "Every membership in it goes with it. This cannot be undone.",
                    confirm: "Delete",
                    danger: true,
                    fields: []
                };
            },
            async runDialog(values) {
                if (!this.dialog) return;

                this.dialogBusy = true;
                this.dialogError = "";

                try {
                    const body = { action: this.dialog.action };

                    if (this.dialog.group) body.id = this.dialog.group.id;

                    if (this.dialog.action !== "delete") {
                        body.name = values.name;
                        body.icon = values.icon;
                        body.colour = values.colour;
                        body.description = values.description;
                    }

                    await this.session("POST", "/admin/api/groups", body);

                    this.dialog = null;

                    // A deleted group invalidates any member list kept for it.
                    this.members = {};
                    this.expanded = 0;
                    this.loading = true;
                    await this.load();
                } catch (e) {
                    this.dialogError = e.message || "That could not be done.";
                } finally {
                    this.dialogBusy = false;
                }
            },
            async toggleMembers(group) {
                if (this.expanded === group.id) {
                    this.expanded = 0;
                    return;
                }

                this.expanded = group.id;

                if (this.members[group.id]) return;

                this.membersBusy = true;

                try {
                    const answer = await this.session("GET",
                        "/admin/api/group-members?id=" + group.id);

                    this.members[group.id] = (answer && answer.members) || [];
                } catch (e) {
                    this.members[group.id] = [];
                } finally {
                    this.membersBusy = false;
                }
            },
            async removeMember(group, member) {
                if (this.memberBusy[member.id]) return;

                this.memberBusy[member.id] = true;

                try {
                    await this.session("POST", "/admin/api/group-members",
                        { action: "remove", group_id: group.id, user_id: member.id });

                    this.members[group.id] = (this.members[group.id] || [])
                        .filter(entry => entry.id !== member.id);
                    group.members = Math.max(0, (group.members || 0) - 1);
                } catch (e) {
                    // The row stays; the next load tells the truth.
                } finally {
                    this.memberBusy[member.id] = false;
                }
            },
            closeDialog() {
                this.dialog = null;
                this.dialogError = "";
            }
        },
        created() {
            this.setTitle("Groups");
            this.load();
        }
    });
</script>
