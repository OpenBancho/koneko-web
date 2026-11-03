<template id="settings-view">
    <div class="page">
        <site-nav></site-nav>

        <koneko-slot name="settings.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <section class="card" v-if="loading">
            <div class="skeleton skeleton-title"></div>
            <div class="skeleton-lines">
                <div class="skeleton skeleton-line" v-for="n in 4" :key="n"></div>
            </div>
        </section>

        <section class="card" v-else-if="!info">
            <h2>Settings</h2>
            <p class="muted">{{ error || "Log in to change your account." }}</p>
            <a class="button" href="/login">Log in</a>
        </section>

        <template v-else>
            <section class="card settings-head">
                <button class="avatar-picker" type="button" @click="chooseAvatar"
                    aria-label="Choose a new profile picture">
                    <img class="settings-avatar" :src="avatar" :alt="info.name">
                    <span class="avatar-picker-label">Change</span>
                </button>
                <!--
                    Hidden inline as well as in the stylesheet: /css/koneko.css is
                    served from a stable path, so a stale cached copy must never be
                    able to expose the browser's own file button.
                -->
                <input ref="avatarInput" class="avatar-input" type="file" hidden
                    style="display: none"
                    accept="image/png,image/jpeg" @change="openAvatarEditor">

                <div>
                    <h2>{{ info.name }}</h2>
                    <p class="muted small">
                        <span :class="flagClass(info.country)"></span>
                        {{ (info.country || "").toUpperCase() }}
                        &middot; joined {{ fmtDate(info.creation_time) }}
                    </p>
                    <p class="settings-tags">
                        <span class="badge" v-if="info.custom_badge_name">{{ info.custom_badge_name }}</span>
                        <span class="badge badge-quiet" v-if="donor">Supporter until {{ fmtDate(info.donor_end) }}</span>
                        <span class="badge badge-warn" v-if="silenced">Silenced until {{ fmtDate(info.silence_end) }}</span>
                    </p>
                    <p class="muted small">
                        <a :href="'/u/' + info.id">View public profile</a>
                    </p>
                </div>
            </section>

            <p class="form-note" v-if="notice">{{ notice }}</p>
            <p class="form-error" v-if="error">{{ error }}</p>

            <section class="card">
                <h3>Profile</h3>
                <p class="muted small">Everything here is shown on your public profile.</p>

                <form class="stack" @submit.prevent="save('update', profile, 'Profile saved.')">
                    <label class="field">
                        <span class="field-label">Default mode</span>
                        <select class="filter-input" v-model.number="profile.preferred_mode">
                            <option v-for="mode in modes" :key="mode" :value="mode">
                                {{ modeName(mode) }}
                            </option>
                        </select>
                    </label>

                    <label class="field">
                        <span class="field-label">Play style</span>
                        <span class="checks">
                            <label class="check" v-for="style in playStyles" :key="style.bit">
                                <input type="checkbox" :value="style.bit" v-model="styleBits">
                                <span>{{ style.label }}</span>
                            </label>
                        </span>
                    </label>

                    <label class="field">
                        <span class="field-label">Userpage</span>
                        <textarea class="filter-input" rows="8" v-model="profile.userpage_content"
                            placeholder="Tell people about yourself"></textarea>
                    </label>

                    <button class="button" type="submit" :disabled="busy">Save profile</button>
                </form>
            </section>

            <div class="avatar-editor-backdrop" v-if="avatarEditor.open"
                @click.self="closeAvatarEditor">
                <section class="avatar-editor" role="dialog" aria-modal="true"
                    aria-labelledby="avatar-editor-title">
                    <div class="avatar-editor-head">
                        <div>
                            <h3 id="avatar-editor-title">
                                {{ avatarEditor.target === "badge" ? "Crop badge picture"
                                    : (avatarEditor.target === "banner" ? "Crop profile banner"
                                        : "Crop profile picture") }}
                            </h3>
                            <p class="muted small">
                                Drag a region, or move and resize it by its corners.
                                <template v-if="avatarEditor.target === 'badge'">
                                    The badge is a wide plate, so the middle of your
                                    selection is kept at twice as wide as it is tall.
                                </template>
                                <template v-else-if="avatarEditor.target === 'banner'">
                                    The banner is a wide strip, so the middle of your
                                    selection is kept at four times as wide as it is tall.
                                </template>
                            </p>
                        </div>
                        <button class="avatar-editor-close" type="button"
                            @click="closeAvatarEditor" aria-label="Close">&times;</button>
                    </div>

                    <div ref="cropStage" class="avatar-crop-stage" :style="stageStyle"
                        @pointerdown="beginSelection" @pointermove="dragSelection"
                        @pointerup="endSelection" @pointercancel="endSelection">
                        <img ref="cropImage" class="avatar-crop-image" :style="imageStyle"
                            :src="avatarEditor.objectUrl" alt="" draggable="false"
                            @load="onCropImageLoad">

                        <div class="avatar-crop-shade" v-if="avatarEditor.crop.w > 0"
                            :style="shadeStyle"></div>

                        <div class="avatar-crop-box" v-if="avatarEditor.crop.w > 0"
                            :style="boxStyle" @pointerdown.stop="grabSelection($event, 'move')">
                            <span class="avatar-crop-handle handle-nw"
                                @pointerdown.stop="grabSelection($event, 'nw')"></span>
                            <span class="avatar-crop-handle handle-ne"
                                @pointerdown.stop="grabSelection($event, 'ne')"></span>
                            <span class="avatar-crop-handle handle-sw"
                                @pointerdown.stop="grabSelection($event, 'sw')"></span>
                            <span class="avatar-crop-handle handle-se"
                                @pointerdown.stop="grabSelection($event, 'se')"></span>
                        </div>
                    </div>

                    <p class="muted small avatar-crop-size">Selection: {{ cropSizeLabel }}</p>

                    <p class="form-error" v-if="avatarEditor.error">{{ avatarEditor.error }}</p>

                    <div class="avatar-editor-actions">
                        <button class="button button-quiet" type="button"
                            @click="resetSelection" :disabled="avatarEditor.saving">Reset</button>
                        <button class="button button-ghost" type="button"
                            @click="closeAvatarEditor" :disabled="avatarEditor.saving">Cancel</button>
                        <button class="button" type="button" @click="saveCrop"
                            :disabled="avatarEditor.saving || avatarEditor.crop.w < 16">
                            {{ avatarEditor.saving ? "Saving…" : "Save picture" }}
                        </button>
                    </div>
                </section>
            </div>

            <section class="card">
                <h3>Supporter</h3>

                <!--
                    Locked accounts get the same panel behind a blur with one line
                    on top, instead of a status paragraph plus a wall of disabled
                    fields: there is nothing to read here until it is unlocked.
                    The inputs stay disabled underneath, so the blur is decoration
                    and not the thing keeping them out.
                -->
                <div class="locked" :class="{ 'is-locked': !donor }">
                    <div class="locked-veil" v-if="!donor">
                        <span>You will need supporter for this</span>
                    </div>

                    <div class="locked-body">
                        <form class="stack" @submit.prevent="changeUsername">
                            <label class="field">
                                <span class="field-label">Username</span>
                                <input class="filter-input" type="text" v-model="username.name"
                                    :disabled="!donor" maxlength="15" :placeholder="info.name">
                                <span class="field-hint muted small">
                                    2-15 characters. Signs you out of the game client.
                                </span>
                            </label>

                            <label class="field">
                                <span class="field-label">Current password</span>
                                <input class="filter-input" type="password" v-model="username.current_password"
                                    autocomplete="current-password" :disabled="!donor">
                            </label>

                            <button class="button" type="submit"
                                :disabled="busy || !donor || !username.name.trim() || !username.current_password">
                                Change username
                            </button>
                        </form>

                        <hr class="settings-split">

                        <!-- The cover shown at the top of your profile. -->
                        <div class="stack">
                            <input ref="bannerInput" class="avatar-input" type="file" hidden
                                accept="image/png,image/jpeg" @change="openBannerEditor">

                            <div class="field">
                                <span class="field-label">Profile banner</span>
                                <span class="field-hint muted small">
                                    The picture at the top of your profile. PNG or JPEG,
                                    up to 4 MB; a wide image works best.
                                </span>
                            </div>

                            <div class="banner-preview" v-if="bannerUrl">
                                <img :src="bannerUrl" alt="">
                            </div>

                            <div class="settings-actions">
                                <button class="button" type="button"
                                    :disabled="busy || bannerUploading || !donor" @click="chooseBanner">
                                    {{ bannerUploading ? "Uploading…" : (bannerPath ? "Replace banner" : "Upload banner") }}
                                </button>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || bannerUploading || !donor || !bannerPath"
                                    @click="clearBanner">
                                    Remove banner
                                </button>
                            </div>
                        </div>

                        <hr class="settings-split">

                        <!-- The picture behind the whole profile page. -->
                        <div class="stack">
                            <input ref="backgroundInput" class="avatar-input" type="file" hidden
                                accept="image/png,image/jpeg" @change="onBackgroundPicked">

                            <div class="field">
                                <span class="field-label">Profile background</span>
                                <span class="field-hint muted small">
                                    The picture behind your whole profile page. PNG or JPEG,
                                    at least 320px on each side; large images are scaled down
                                    to fit before they are uploaded. A dark, calm picture
                                    keeps the text readable.
                                </span>
                            </div>

                            <div class="background-preview" v-if="backgroundUrl">
                                <img :src="backgroundUrl" alt="">
                            </div>

                            <div class="settings-actions">
                                <button class="button" type="button"
                                    :disabled="busy || backgroundUploading || !donor" @click="chooseBackground">
                                    {{ backgroundUploading ? "Uploading…" : (backgroundPath ? "Replace background" : "Upload background") }}
                                </button>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || backgroundUploading || !donor || !backgroundPath"
                                    @click="clearBackground">
                                    Remove background
                                </button>
                            </div>
                        </div>

                        <hr class="settings-split">

                        <!-- The colours of the profile page: accent and panels. -->
                        <div class="stack">
                            <div class="field">
                                <span class="field-label">Profile colour scheme</span>
                                <span class="field-hint muted small">
                                    Shown to everyone who visits your profile. The primary colour is
                                    the accent (buttons, links, highlights); the secondary colour tints
                                    the panels everything sits on. Pick them, or let both follow your
                                    background.
                                </span>
                            </div>

                            <div class="swatch-group">
                                <span class="swatch-label">Primary</span>
                                <div class="swatch-row">
                                    <button class="swatch" type="button" v-for="preset in colorPresets"
                                        :key="'p' + preset.value" :title="preset.label" :aria-label="'Primary ' + preset.label"
                                        :class="{ active: colorDraft === preset.value }"
                                        :style="{ background: preset.value }" :disabled="!donor"
                                        @click="colorDraft = preset.value"></button>

                                    <label class="swatch-custom" title="Custom primary colour">
                                        <input type="color" v-model="colorDraft" :disabled="!donor"
                                            aria-label="Custom primary colour">
                                    </label>

                                    <span class="swatch-value muted small">{{ colorDraft || "Default" }}</span>
                                </div>
                            </div>

                            <div class="swatch-group">
                                <span class="swatch-label">Secondary</span>
                                <div class="swatch-row">
                                    <button class="swatch" type="button" v-for="preset in colorPresets"
                                        :key="'s' + preset.value" :title="preset.label" :aria-label="'Secondary ' + preset.label"
                                        :class="{ active: colorDraft2 === preset.value }"
                                        :style="{ background: preset.value }" :disabled="!donor"
                                        @click="colorDraft2 = preset.value"></button>

                                    <label class="swatch-custom" title="Custom secondary colour">
                                        <input type="color" v-model="colorDraft2" :disabled="!donor"
                                            aria-label="Custom secondary colour">
                                    </label>

                                    <span class="swatch-value muted small">{{ colorDraft2 || "Follows primary" }}</span>
                                </div>
                            </div>

                            <div class="settings-actions">
                                <button class="button" type="button"
                                    :disabled="busy || !donor || !colorDraft || (colorDraft === savedColor && colorDraft2 === savedColor2)"
                                    @click="saveColor">
                                    Save colours
                                </button>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || colorMatching || !donor || !backgroundPath"
                                    :title="backgroundPath ? '' : 'Upload a background first'"
                                    @click="matchBackground">
                                    {{ colorMatching ? "Reading background…" : "Match background style" }}
                                </button>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || !donor || (!savedColor && !savedColor2)" @click="resetColor">
                                    Reset to default
                                </button>
                            </div>
                        </div>

                        <hr class="settings-split">

                        <form class="stack" @submit.prevent="saveBadge">
                            <input ref="badgeInput" class="avatar-input" type="file" hidden
                                accept="image/png,image/jpeg" @change="openBadgeEditor">

                            <label class="field">
                                <span class="field-label">Badge</span>
                                <input class="filter-input" type="text" v-model="badge.custom_badge_name"
                                    :disabled="!donor" maxlength="16" placeholder="Badge name">
                                <span class="field-hint muted small">
                                    Name and picture, shown next to your rank. PNG or JPEG, up to 2 MB.
                                </span>
                            </label>

                            <div class="settings-actions">
                                <span class="user-badge" v-if="badgeIcon" :title="badgeName">
                                    <img class="user-badge-icon" :src="badgeIconUrl" alt="">
                                    <span class="user-badge-name">{{ badgeName || "Badge" }}</span>
                                </span>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || badgeUploading || !donor" @click="chooseBadgeIcon">
                                    {{ badgeUploading ? "Uploading…" : (badgeIcon ? "Replace picture" : "Upload picture") }}
                                </button>
                            </div>

                            <div class="settings-actions">
                                <button class="button" type="submit"
                                    :disabled="busy || badgeUploading || !donor || !badgeIcon">
                                    Save badge
                                </button>
                                <button class="button button-ghost" type="button"
                                    :disabled="busy || badgeUploading || !donor || !hasBadge"
                                    @click="clearBadge">
                                    Remove badge
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            </section>

            <section class="card">
                <h3>Email</h3>
                <p class="muted small">Currently {{ info.email }}.</p>

                <form class="stack" @submit.prevent="save('email', email, 'Email changed.')">
                    <label class="field">
                        <span class="field-label">New email</span>
                        <input class="filter-input" type="email" v-model="email.email" required>
                    </label>

                    <label class="field">
                        <span class="field-label">Current password</span>
                        <input class="filter-input" type="password" v-model="email.current_password"
                            autocomplete="current-password" required>
                    </label>

                    <button class="button" type="submit" :disabled="busy">Change email</button>
                </form>
            </section>

            <section class="card">
                <h3>Two-factor authentication</h3>

                <!-- Already on: the only thing left to offer is taking it off again. -->
                <template v-if="twoFactor.enabled">
                    <p class="muted small">
                        On. Logging in from a computer this account has not been used on before
                        opens a page in your browser asking for a code from your app; the game
                        lets you in once you have entered it.
                    </p>

                    <form class="stack" @submit.prevent="disableTwoFactor">
                        <label class="field">
                            <span class="field-label">Current password</span>
                            <input class="filter-input" type="password"
                                v-model="twoFactor.current_password"
                                autocomplete="current-password" required>
                        </label>

                        <p class="error" v-if="twoFactor.error">{{ twoFactor.error }}</p>
                        <p class="muted small" v-if="twoFactor.notice">{{ twoFactor.notice }}</p>

                        <button class="button button-danger" type="submit" :disabled="twoFactor.busy">
                            Turn off
                        </button>
                    </form>
                </template>

                <!-- Being set up: the code is asked for before anything is stored, so an app
                     that never got the secret cannot lock the account out of the game. -->
                <template v-else-if="twoFactor.setup">
                    <p class="muted small">
                        Scan this with Google Authenticator, Aegis, or any other authenticator
                        app, then type the six digits it shows to finish.
                    </p>

                    <div class="totp-setup">
                        <div class="totp-qr">
                            <img v-if="qrSource()" :src="qrSource()" alt="Setup QR code"
                                width="180" height="180">
                            <p class="muted small" v-else>
                                The QR code is unavailable. Enter the secret beside it by hand
                                instead - it is the same thing.
                            </p>
                        </div>

                        <div class="totp-manual">
                            <span class="field-label">Or enter it by hand</span>
                            <code class="totp-secret">{{ twoFactor.setup.secret_formatted }}</code>
                            <p class="muted small">
                                Time based, six digits, thirty seconds. The account name is
                                {{ info.name }}.
                            </p>
                        </div>
                    </div>

                    <form class="stack" @submit.prevent="confirmTwoFactor">
                        <label class="field">
                            <span class="field-label">Code from your app</span>
                            <input class="filter-input code-input" type="text" v-model="twoFactor.code"
                                inputmode="numeric" autocomplete="one-time-code" maxlength="7"
                                placeholder="000000" required>
                        </label>

                        <p class="error" v-if="twoFactor.error">{{ twoFactor.error }}</p>

                        <div class="row-buttons">
                            <button class="button" type="submit" :disabled="twoFactor.busy">
                                Turn on
                            </button>

                            <button class="button button-ghost" type="button"
                                :disabled="twoFactor.busy" @click="cancelTwoFactor">
                                Cancel
                            </button>
                        </div>
                    </form>
                </template>

                <template v-else>
                    <p class="muted small">
                        Off. With it on, a login from an unfamiliar computer needs a code from
                        an authenticator app on your phone as well as your password.
                    </p>

                    <p class="error" v-if="twoFactor.error">{{ twoFactor.error }}</p>
                    <p class="muted small" v-if="twoFactor.notice">{{ twoFactor.notice }}</p>

                    <button class="button" type="button" :disabled="twoFactor.busy"
                        @click="startTwoFactor">
                        Set up
                    </button>
                </template>
            </section>

            <section class="card">
                <h3>Password</h3>
                <p class="muted small">
                    Changing it signs you out here and everywhere else, the game client
                    included.
                </p>

                <form class="stack" @submit.prevent="changePassword">
                    <label class="field">
                        <span class="field-label">Current password</span>
                        <input class="filter-input" type="password" v-model="password.current_password"
                            autocomplete="current-password" required>
                    </label>

                    <label class="field">
                        <span class="field-label">New password</span>
                        <input class="filter-input" type="password" v-model="password.new_password"
                            autocomplete="new-password" required>
                    </label>

                    <label class="field">
                        <span class="field-label">Repeat new password</span>
                        <input class="filter-input" type="password" v-model="passwordRepeat"
                            autocomplete="new-password" required>
                    </label>

                    <button class="button" type="submit" :disabled="busy">Change password</button>
                </form>
            </section>

            <section class="card card-danger">
                <h3>Delete account</h3>
                <p class="muted small">
                    Your scores, your profile and your name go with it, and none of it
                    can be brought back.
                </p>

                <form class="stack" @submit.prevent="deleteAccount">
                    <label class="field">
                        <span class="field-label">Type your name to confirm</span>
                        <input class="filter-input" type="text" v-model="deleteName"
                            :placeholder="info.name">
                    </label>

                    <label class="field">
                        <span class="field-label">Current password</span>
                        <input class="filter-input" type="password" v-model="remove.current_password"
                            autocomplete="current-password" required>
                    </label>

                    <button class="button button-danger" type="submit"
                        :disabled="busy || deleteName !== info.name">
                        Delete my account
                    </button>
                </form>
            </section>
        </template>

        <koneko-slot name="page.bottom"></koneko-slot>
        <site-footer></site-footer>
    </div>
</template>

<script>
    /**
     * The own account. Everything on this page goes through this service rather
     * than straight to the API, because all of it needs the access token that
     * the session holds - the browser never has one.
     *
     * The API asks for the current password again on the email, password and
     * delete forms, so those three ask for it here too.
     */
    app.component("settings-view", {
        template: "#settings-view",
        data: () => ({
            loading: true,
            busy: false,
            error: "",
            notice: "",
            info: null,
            scope: "",
            modes: [0, 1, 2, 3, 4, 5, 6, 8],
            // The bits of the play style field, as the game client sends them.
            playStyles: [
                { bit: 1, label: "Mouse" },
                { bit: 2, label: "Tablet" },
                { bit: 4, label: "Keyboard" },
                { bit: 8, label: "Touchscreen" }
            ],
            styleBits: [],
            profile: {
                userpage_content: "",
                preferred_mode: 0,
                play_style: 0
            },
            // Supporter perks: both live here rather than in game.
            username: { name: "", current_password: "" },
            badge: { custom_badge_name: "" },
            // The picture is not typed in: it is uploaded, and this is the path
            // the server gave back for it.
            badgeIcon: "",
            badgeVersion: 0,
            badgeUploading: false,
            // Same idea for the profile cover: uploaded, so the page only keeps
            // the path the server answered with.
            bannerPath: "",
            bannerVersion: 0,
            bannerUploading: false,
            // And the page background, which needs no crop: the browser only
            // scales it down, so it has no editor dialog of its own.
            backgroundPath: "",
            backgroundVersion: 0,
            backgroundUploading: false,
            // The accent colour: what the picker shows now, and what is stored.
            colorDraft: "",
            savedColor: "",
            colorDraft2: "",
            savedColor2: "",
            colorMatching: false,
            colorPresets: [
                { label: "Pink", value: "#ff4d8d" },
                { label: "Purple", value: "#8c66ff" },
                { label: "Blue", value: "#4da3ff" },
                { label: "Cyan", value: "#35d0c8" },
                { label: "Green", value: "#4ade80" },
                { label: "Yellow", value: "#facc15" },
                { label: "Orange", value: "#ff9f43" },
                { label: "Red", value: "#f05252" }
            ],
            email: { email: "", current_password: "" },
            // Two factor authentication. The secret only lives here while it is being set up;
            // once confirmed the server keeps it and the browser has no use for it.
            twoFactor: {
                enabled: false,
                busy: false,
                error: "",
                notice: "",
                setup: null,
                code: "",
                current_password: ""
            },
            password: { new_password: "", current_password: "" },
            passwordRepeat: "",
            remove: { current_password: "" },
            deleteName: "",
            avatarVersion: 0,
            avatarEditor: {
                open: false,
                image: null,
                objectUrl: "",
                crop: { x: 0, y: 0, w: 0, h: 0 },
                stage: { w: 0, h: 0 },
                mode: "",
                pointerId: null,
                originX: 0,
                originY: 0,
                startCrop: null,
                saving: false,
                error: "",
                // Which picture the dialog is cropping: "avatar" or "badge".
                target: "avatar"
            }
        }),
        computed: {
            avatar() {
                const suffix = this.avatarVersion ? "?v=" + this.avatarVersion : "";
                return "https://a." + this.$koneko.domain + "/" + this.info.id + suffix;
            },
            /**
             * Whether the supporter perks are open. The server answers this,
             * because donor_end is only one of the two ways to have it: the
             * permanent supporter bits count as well, and a page that only
             * looked at the timestamp told those accounts they had nothing.
             */
            donor() {
                // Any of the three is enough, and none of them is required: an
                // older API answer has no supporter flag at all, and an account
                // with the permanent bits has no donor_end. Reading only one of
                // them is exactly what used to call supporters non-supporters.
                if (this.info.supporter === true) return true;

                // SUPPORTER (1 << 4) and PREMIUM (1 << 5) of the privilege mask.
                if ((Number(this.info.priv || 0) & 48) !== 0) return true;

                return !!(this.info.donor_end && this.asDate(this.info.donor_end) > new Date());
            },
            silenced() {
                return this.info.silence_end && this.asDate(this.info.silence_end) > new Date();
            },
            hasBadge() {
                return !!(this.info.custom_badge_name || this.info.custom_badge_icon);
            },
            badgeName() {
                return (this.badge.custom_badge_name || "").trim();
            },
            // The path never changes when a picture is replaced, so the preview
            // gets a version of its own to stop the browser showing the old one.
            badgeIconUrl() {
                if (!this.badgeIcon) return "";

                return this.badgeVersion
                    ? this.badgeIcon + "?v=" + this.badgeVersion
                    : this.badgeIcon;
            },
            bannerUrl() {
                if (!this.bannerPath) return "";

                return this.bannerVersion
                    ? this.bannerPath + "?v=" + this.bannerVersion
                    : this.bannerPath;
            },
            backgroundUrl() {
                if (!this.backgroundPath) return "";

                return this.backgroundVersion
                    ? this.backgroundPath + "?v=" + this.backgroundVersion
                    : this.backgroundPath;
            },
            boxStyle() {
                const crop = this.avatarEditor.crop;
                return {
                    position: "absolute",
                    boxSizing: "border-box",
                    left: crop.x + "px",
                    top: crop.y + "px",
                    width: crop.w + "px",
                    height: crop.h + "px"
                };
            },
            shadeStyle() {
                const crop = this.avatarEditor.crop;
                // One element dims everything outside the selection.
                return {
                    position: "absolute",
                    inset: "0",
                    pointerEvents: "none",
                    clipPath: "polygon(0 0, 100% 0, 100% 100%, 0 100%, 0 " + crop.y + "px, "
                        + crop.x + "px " + crop.y + "px, "
                        + crop.x + "px " + (crop.y + crop.h) + "px, "
                        + (crop.x + crop.w) + "px " + (crop.y + crop.h) + "px, "
                        + (crop.x + crop.w) + "px " + crop.y + "px, 0 " + crop.y + "px)"
                };
            },
            // Sizing lives inline for the same reason the file input does: a
            // cached stylesheet must not be able to let the image run off screen.
            stageStyle() {
                return {
                    position: "relative",
                    display: "block",
                    width: "fit-content",
                    maxWidth: "100%",
                    margin: "16px auto 10px",
                    overflow: "hidden",
                    touchAction: "none",
                    userSelect: "none"
                };
            },
            imageStyle() {
                return {
                    display: "block",
                    maxWidth: "100%",
                    maxHeight: "52vh",
                    width: "auto",
                    height: "auto"
                };
            },
            cropSizeLabel() {
                const source = this.sourceRect();
                if (!source) return "none";
                return source.w + " \u00d7 " + source.h + " px";
            }
        },
        methods: {
            chooseAvatar() {
                this.$refs.avatarInput.click();
            },
            openAvatarEditor(event) {
                this.openEditor(event, "avatar");
            },
            openBadgeEditor(event) {
                this.openEditor(event, "badge");
            },
            openBannerEditor(event) {
                this.openEditor(event, "banner");
            },
            /**
             * One cropper for both pictures. The target only decides how small a
             * source image may be, what the dialog says, and where the result is
             * posted, so a badge is chosen exactly the way an avatar is.
             */
            openEditor(event, target) {
                const file = event.target.files && event.target.files[0];
                event.target.value = "";
                if (!file) return;

                const allowed = ["image/png", "image/jpeg"];
                if (!allowed.includes(file.type)) {
                    this.error = "Choose a PNG or JPEG image.";
                    return;
                }
                if (file.size > 12 * 1024 * 1024) {
                    this.error = "The source image may be at most 12 MB.";
                    return;
                }

                // A badge is tiny, so it may come from a much smaller file than an
                // avatar; a banner is drawn wide and needs a little more to work with.
                const minSide = target === "badge" ? 16 : (target === "banner" ? 50 : 64);

                const objectUrl = URL.createObjectURL(file);
                const image = new Image();
                image.onload = () => {
                    if (image.naturalWidth < minSide || image.naturalHeight < minSide
                        || image.naturalWidth > 8192 || image.naturalHeight > 8192
                        || image.naturalWidth * image.naturalHeight > 40000000) {
                        URL.revokeObjectURL(objectUrl);
                        this.error = "Use an image between " + minSide
                            + "px and 8192px (40 megapixels max).";
                        return;
                    }

                    this.avatarEditor.target = target;
                    this.avatarEditor.image = image;
                    this.avatarEditor.objectUrl = objectUrl;
                    this.avatarEditor.crop = { x: 0, y: 0, w: 0, h: 0 };
                    this.avatarEditor.error = "";
                    this.avatarEditor.open = true;
                    this.$nextTick(() => {
                        this.measureStage();
                        this.resetSelection();
                        const close = this.$el.querySelector(".avatar-editor-close");
                        if (close) close.focus();
                    });
                };
                image.onerror = () => {
                    URL.revokeObjectURL(objectUrl);
                    this.error = "That file could not be decoded as an image.";
                };
                image.src = objectUrl;
            },
            // The natural size is only known once the file has decoded, so the
            // first selection is measured when the <img> reports it is loaded.
            onCropImageLoad() {
                this.measureStage();
                this.resetSelection();
            },
            // The displayed size of the image, which every selection coordinate is in.
            measureStage() {
                const image = this.$refs.cropImage;
                if (!image) return;
                this.avatarEditor.stage = { w: image.clientWidth, h: image.clientHeight };
            },
            // A sensible starting selection: the largest centred square.
            resetSelection() {
                this.measureStage();
                const stage = this.avatarEditor.stage;
                if (!stage.w || !stage.h) return;
                const side = Math.min(stage.w, stage.h);
                this.avatarEditor.crop = {
                    x: (stage.w - side) / 2,
                    y: (stage.h - side) / 2,
                    w: side,
                    h: side
                };
            },
            stagePoint(event) {
                const bounds = this.$refs.cropStage.getBoundingClientRect();
                const stage = this.avatarEditor.stage;
                return {
                    x: Math.max(0, Math.min(stage.w, event.clientX - bounds.left)),
                    y: Math.max(0, Math.min(stage.h, event.clientY - bounds.top))
                };
            },
            // Dragging on the image itself draws a completely new region.
            beginSelection(event) {
                this.measureStage();
                const point = this.stagePoint(event);
                this.avatarEditor.mode = "draw";
                this.avatarEditor.pointerId = event.pointerId;
                this.avatarEditor.originX = point.x;
                this.avatarEditor.originY = point.y;
                this.avatarEditor.crop = { x: point.x, y: point.y, w: 0, h: 0 };
                this.$refs.cropStage.setPointerCapture(event.pointerId);
            },
            // Dragging the box moves it; dragging a corner resizes that corner freely.
            grabSelection(event, mode) {
                this.measureStage();
                const point = this.stagePoint(event);
                this.avatarEditor.mode = mode;
                this.avatarEditor.pointerId = event.pointerId;
                this.avatarEditor.originX = point.x;
                this.avatarEditor.originY = point.y;
                this.avatarEditor.startCrop = Object.assign({}, this.avatarEditor.crop);
                this.$refs.cropStage.setPointerCapture(event.pointerId);
            },
            dragSelection(event) {
                const editor = this.avatarEditor;
                if (!editor.mode || event.pointerId !== editor.pointerId) return;

                const point = this.stagePoint(event);
                const stage = editor.stage;

                if (editor.mode === "draw") {
                    editor.crop = {
                        x: Math.min(editor.originX, point.x),
                        y: Math.min(editor.originY, point.y),
                        w: Math.abs(point.x - editor.originX),
                        h: Math.abs(point.y - editor.originY)
                    };
                    return;
                }

                const start = editor.startCrop;
                if (!start) return;

                if (editor.mode === "move") {
                    editor.crop = {
                        x: Math.max(0, Math.min(stage.w - start.w,
                            start.x + (point.x - editor.originX))),
                        y: Math.max(0, Math.min(stage.h - start.h,
                            start.y + (point.y - editor.originY))),
                        w: start.w,
                        h: start.h
                    };
                    return;
                }

                const left = editor.mode.includes("w") ? point.x : start.x;
                const top = editor.mode.includes("n") ? point.y : start.y;
                const right = editor.mode.includes("e") ? point.x : start.x + start.w;
                const bottom = editor.mode.includes("s") ? point.y : start.y + start.h;

                editor.crop = {
                    x: Math.min(left, right),
                    y: Math.min(top, bottom),
                    w: Math.abs(right - left),
                    h: Math.abs(bottom - top)
                };
            },
            endSelection(event) {
                if (event.pointerId !== this.avatarEditor.pointerId) return;
                this.avatarEditor.mode = "";
                this.avatarEditor.pointerId = null;
                this.avatarEditor.startCrop = null;

                // A stray click rather than a drag leaves the previous region alone.
                if (this.avatarEditor.crop.w < 16 || this.avatarEditor.crop.h < 16) {
                    this.resetSelection();
                }
            },
            // The selection translated back into pixels of the original file.
            sourceRect() {
                const editor = this.avatarEditor;
                const image = editor.image;
                if (!image || !editor.stage.w || !editor.stage.h || editor.crop.w < 16) return null;

                const scaleX = image.naturalWidth / editor.stage.w;
                const scaleY = image.naturalHeight / editor.stage.h;

                return {
                    x: Math.round(editor.crop.x * scaleX),
                    y: Math.round(editor.crop.y * scaleY),
                    w: Math.max(1, Math.round(editor.crop.w * scaleX)),
                    h: Math.max(1, Math.round(editor.crop.h * scaleY))
                };
            },
            // The dialog is shared, so its button asks the target where to post.
            saveCrop() {
                if (this.avatarEditor.target === "badge") return this.saveBadgeCrop();
                if (this.avatarEditor.target === "banner") return this.saveBannerCrop();

                return this.saveAvatar();
            },
            async saveAvatar() {
                const source = this.sourceRect();
                if (this.avatarEditor.saving || !source) return;
                this.avatarEditor.saving = true;
                this.avatarEditor.error = "";

                try {
                    // Downscaled to at most 512px on the long edge, keeping the
                    // exact region and proportions the player selected.
                    const longest = Math.max(source.w, source.h);
                    const factor = longest > 512 ? 512 / longest : 1;
                    const canvas = document.createElement("canvas");
                    canvas.width = Math.max(1, Math.round(source.w * factor));
                    canvas.height = Math.max(1, Math.round(source.h * factor));

                    const context = canvas.getContext("2d");
                    context.imageSmoothingEnabled = true;
                    context.imageSmoothingQuality = "high";
                    context.drawImage(this.avatarEditor.image,
                        source.x, source.y, source.w, source.h,
                        0, 0, canvas.width, canvas.height);

                    const blob = await new Promise((resolve, reject) => {
                        canvas.toBlob(value => value ? resolve(value)
                            : reject(new Error("Could not prepare the image.")), "image/png");
                    });

                    if (blob.size > 4 * 1024 * 1024) {
                        throw new Error("The cropped image is too large. Try another photo.");
                    }

                    const response = await fetch("/account/avatar", {
                        method: "POST",
                        headers: { "Accept": "application/json", "Content-Type": "image/png" },
                        credentials: "same-origin",
                        body: blob
                    });
                    let answer = null;
                    try { answer = await response.json(); } catch (ignored) {}

                    if (!response.ok) {
                        throw new Error((answer && answer.status) || "The avatar could not be saved.");
                    }

                    this.avatarVersion = Date.now();
                    this.notice = "Profile picture saved.";
                    this.error = "";
                    this.closeAvatarEditor();
                } catch (e) {
                    this.avatarEditor.error = e.message || "The avatar could not be saved.";
                } finally {
                    this.avatarEditor.saving = false;
                }
            },
            closeAvatarEditor() {
                if (this.avatarEditor.saving) return;
                this.avatarEditor.open = false;
                this.avatarEditor.image = null;
                this.avatarEditor.mode = "";
                this.avatarEditor.pointerId = null;
                this.avatarEditor.crop = { x: 0, y: 0, w: 0, h: 0 };
                this.avatarEditor.target = "avatar";
                if (this.avatarEditor.objectUrl) {
                    URL.revokeObjectURL(this.avatarEditor.objectUrl);
                    this.avatarEditor.objectUrl = "";
                }
            },
            async load() {
                try {
                    const answer = await this.session("GET", "/account/me");

                    this.info = (answer && answer.info) || null;
                    this.scope = (answer && answer.scope) || "";

                    if (this.info) {
                        this.profile.userpage_content = this.info.userpage_content || "";
                        this.profile.preferred_mode = this.info.preferred_mode || 0;
                        this.badge.custom_badge_name = this.info.custom_badge_name || "";
                        this.badgeIcon = this.info.custom_badge_icon || "";
                        this.bannerPath = this.info.custom_banner || "";
                        this.backgroundPath = this.info.custom_background || "";
                        this.savedColor = this.info.profile_color || "";
                        this.colorDraft = this.savedColor;
                        this.savedColor2 = this.info.profile_color_secondary || "";
                        this.colorDraft2 = this.savedColor2;
                        this.styleBits = this.playStyles
                            .filter(style => (this.info.play_style & style.bit) !== 0)
                            .map(style => style.bit);
                    }
                } catch (e) {
                    // 401 is not an error worth shouting about: it only means
                    // the session is gone, and the card below says so.
                    if (e && e.status !== 401) this.error = e.message;
                    } finally {
                        this.loading = false;
                        // The game client links to /home/account/edit#avatar, which
                        // is redirected here; the fragment survives the redirect.
                        if (this.info) this.$nextTick(this.jumpToAvatar);
                    }
                },
            jumpToAvatar() {
                if (window.location.hash !== "#avatar") return;

                const picker = this.$el.querySelector(".avatar-picker");
                if (!picker) return;

                picker.scrollIntoView({ block: "center", behavior: "smooth" });
                // Focused rather than clicked: browsers only open a file dialog
                // in response to a real gesture, so the button is offered instead.
                picker.focus();
            },
            async save(action, body, done) {
                if (this.busy) return;

                this.busy = true;
                this.error = "";
                this.notice = "";

                // Only the profile form owns the play style checkboxes; the badge
                // form posts to the same action but must not touch them.
                if (action === "update" && body === this.profile) {
                    body.play_style = this.styleBits.reduce((sum, bit) => sum + bit, 0);
                }

                try {
                    await this.session("POST", "/account/" + action, body);

                    this.notice = done;

                    return true;
                } catch (e) {
                    this.error = e.message;

                    return false;
                } finally {
                    this.busy = false;
                }
            },
            async changeUsername() {
                const name = this.username.name.trim();

                if (!name || !this.username.current_password) return;

                const ok = await this.save("name", {
                    name: name,
                    current_password: this.username.current_password
                }, "Username changed.");

                this.username.current_password = "";

                // The name is on every card of this page and in the navbar, so the
                // page is reloaded rather than patched in a dozen places.
                if (ok) window.location.reload();
            },
            chooseBadgeIcon() {
                this.$refs.badgeInput.click();
            },
            /**
             * Posts the selected region as the badge picture. The server decodes
             * and re-encodes it again, so this is only about not sending a 12
             * megapixel photo to have it shown at 16 pixels.
             */
            async saveBadgeCrop() {
                const source = this.sourceRect();

                if (this.avatarEditor.saving || !source) return;

                this.avatarEditor.saving = true;
                this.avatarEditor.error = "";
                this.badgeUploading = true;

                try {
                    // A badge is a wide plate, so the selection is centre-cropped
                    // to 2:1 - the very shape the profile draws it in, which keeps
                    // the picture from being letterboxed or squashed there.
                    const cropW = Math.min(source.w, source.h * 2);
                    const cropH = Math.max(1, Math.round(cropW / 2));
                    const factor = cropW > 192 ? 192 / cropW : 1;
                    const canvas = document.createElement("canvas");
                    canvas.width = Math.max(1, Math.round(cropW * factor));
                    canvas.height = Math.max(1, Math.round(cropH * factor));

                    const context = canvas.getContext("2d");
                    context.imageSmoothingEnabled = true;
                    context.imageSmoothingQuality = "high";
                    context.drawImage(this.avatarEditor.image,
                        source.x + (source.w - cropW) / 2, source.y + (source.h - cropH) / 2,
                        cropW, cropH, 0, 0, canvas.width, canvas.height);

                    const blob = await new Promise((resolve, reject) => {
                        canvas.toBlob(value => value ? resolve(value)
                            : reject(new Error("Could not prepare the image.")), "image/png");
                    });

                    const response = await fetch("/account/badge-icon", {
                        method: "POST",
                        headers: { "Accept": "application/json", "Content-Type": "image/png" },
                        credentials: "same-origin",
                        body: blob
                    });

                    let answer = null;
                    try { answer = await response.json(); } catch (ignored) {}

                    if (!response.ok) {
                        throw new Error((answer && (answer.status || answer.error_description))
                            || "The picture could not be uploaded.");
                    }

                    this.badgeIcon = (answer && answer.custom_badge_icon) || this.badgeIcon;
                    this.badgeVersion = Date.now();
                    this.info.custom_badge_icon = this.badgeIcon;

                    this.notice = this.badgeName
                        ? "Picture uploaded."
                        : "Picture uploaded. Give the badge a name and save it.";
                    this.error = "";
                    this.closeAvatarEditor();
                } catch (e) {
                    this.avatarEditor.error = e.message || "The picture could not be uploaded.";
                } finally {
                    this.avatarEditor.saving = false;
                    this.badgeUploading = false;
                }
            },
            chooseBanner() {
                this.$refs.bannerInput.click();
            },
            /**
             * Posts the selected region as the profile banner. Cropped to the wide
             * strip a cover is drawn in, and scaled down, so the browser is not
             * asked to upload a photo at full size for a 1600px wide band.
             */
            async saveBannerCrop() {
                const source = this.sourceRect();

                if (this.avatarEditor.saving || !source) return;

                this.avatarEditor.saving = true;
                this.avatarEditor.error = "";
                this.bannerUploading = true;

                try {
                    // 4:1, the shape the profile draws the cover in, taken from the
                    // middle of the selection so nothing is squashed.
                    const cropW = Math.min(source.w, source.h * 4);
                    const cropH = Math.max(1, Math.round(cropW / 4));
                    const factor = cropW > 1600 ? 1600 / cropW : 1;
                    const canvas = document.createElement("canvas");
                    canvas.width = Math.max(1, Math.round(cropW * factor));
                    canvas.height = Math.max(1, Math.round(cropH * factor));

                    const context = canvas.getContext("2d");
                    context.imageSmoothingEnabled = true;
                    context.imageSmoothingQuality = "high";
                    context.drawImage(this.avatarEditor.image,
                        source.x + (source.w - cropW) / 2, source.y + (source.h - cropH) / 2,
                        cropW, cropH, 0, 0, canvas.width, canvas.height);

                    const blob = await new Promise((resolve, reject) => {
                        canvas.toBlob(value => value ? resolve(value)
                            : reject(new Error("Could not prepare the image.")), "image/png");
                    });

                    const response = await fetch("/account/banner", {
                        method: "POST",
                        headers: { "Accept": "application/json", "Content-Type": "image/png" },
                        credentials: "same-origin",
                        body: blob
                    });

                    let answer = null;
                    try { answer = await response.json(); } catch (ignored) {}

                    if (!response.ok) {
                        throw new Error((answer && (answer.status || answer.error_description))
                            || "The banner could not be uploaded.");
                    }

                    this.bannerPath = (answer && answer.custom_banner) || this.bannerPath;
                    this.bannerVersion = Date.now();
                    this.info.custom_banner = this.bannerPath;

                    this.notice = "Banner uploaded.";
                    this.error = "";
                    this.closeAvatarEditor();
                } catch (e) {
                    this.avatarEditor.error = e.message || "The banner could not be uploaded.";
                } finally {
                    this.avatarEditor.saving = false;
                    this.bannerUploading = false;
                }
            },
            chooseBackground() {
                this.$refs.backgroundInput.click();
            },
            /**
             * A background is not cropped, so the picked file goes straight from the
             * input to a canvas that fits it into 1920x1200 and re-encodes it as
             * JPEG. The server does the same again and is the one that decides; this
             * only keeps the upload small enough to be accepted.
             */
            async onBackgroundPicked(event) {
                const file = event.target.files && event.target.files[0];
                event.target.value = "";
                if (!file) return;

                if (!["image/png", "image/jpeg"].includes(file.type)) {
                    this.error = "Choose a PNG or JPEG image.";
                    return;
                }
                if (file.size > 25 * 1024 * 1024) {
                    this.error = "The source image may be at most 25 MB.";
                    return;
                }

                this.backgroundUploading = true;
                this.error = "";
                this.notice = "";

                const objectUrl = URL.createObjectURL(file);

                try {
                    const image = await new Promise((resolve, reject) => {
                        const img = new Image();
                        img.onload = () => resolve(img);
                        img.onerror = () => reject(new Error("That file could not be decoded as an image."));
                        img.src = objectUrl;
                    });

                    const width = image.naturalWidth;
                    const height = image.naturalHeight;

                    if (width < 320 || height < 320) {
                        throw new Error("Use an image that is at least 320px on each side.");
                    }
                    if (width > 8192 || height > 8192 || width * height > 40000000) {
                        throw new Error("Use an image up to 8192px on a side (40 megapixels max).");
                    }

                    const factor = Math.min(1, 1920 / width, 1200 / height);
                    const canvas = document.createElement("canvas");
                    canvas.width = Math.max(1, Math.round(width * factor));
                    canvas.height = Math.max(1, Math.round(height * factor));

                    const context = canvas.getContext("2d");
                    context.imageSmoothingEnabled = true;
                    context.imageSmoothingQuality = "high";
                    // JPEG has no transparency, so a PNG with holes is laid on the page colour.
                    context.fillStyle = "#0e0e10";
                    context.fillRect(0, 0, canvas.width, canvas.height);
                    context.drawImage(image, 0, 0, canvas.width, canvas.height);

                    let blob = null;
                    for (const quality of [0.88, 0.75, 0.6]) {
                        blob = await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", quality));
                        if (!blob) throw new Error("Could not prepare the image.");
                        if (blob.size <= 3.8 * 1024 * 1024) break;
                    }
                    if (blob.size > 3.8 * 1024 * 1024) {
                        throw new Error("The image is still too large (4 MB maximum) after scaling.");
                    }

                    const response = await fetch("/account/background", {
                        method: "POST",
                        headers: { "Accept": "application/json", "Content-Type": "image/jpeg" },
                        credentials: "same-origin",
                        body: blob
                    });

                    let answer = null;
                    try { answer = await response.json(); } catch (ignored) {}

                    if (!response.ok) {
                        throw new Error((answer && (answer.status || answer.error_description))
                            || "The background could not be uploaded.");
                    }

                    this.backgroundPath = (answer && answer.custom_background) || this.backgroundPath;
                    this.backgroundVersion = Date.now();
                    this.info.custom_background = this.backgroundPath;
                    this.notice = "Background uploaded.";
                } catch (e) {
                    this.error = e.message || "The background could not be uploaded.";
                } finally {
                    URL.revokeObjectURL(objectUrl);
                    this.backgroundUploading = false;
                }
            },
            async saveColor() {
                const color = (this.colorDraft || "").toLowerCase();
                const second = (this.colorDraft2 || "").toLowerCase();

                if (!/^#[0-9a-f]{6}$/.test(color)) {
                    this.error = "Pick a primary colour first.";
                    return false;
                }

                const ok = await this.save("update", {
                    profile_color: color,
                    profile_color_secondary: /^#[0-9a-f]{6}$/.test(second) ? second : null
                }, "Colour scheme saved.");

                if (ok) {
                    this.savedColor = color;
                    this.colorDraft = color;
                    this.savedColor2 = /^#[0-9a-f]{6}$/.test(second) ? second : "";
                    this.colorDraft2 = this.savedColor2;
                    this.info.profile_color = color;
                    this.info.profile_color_secondary = this.savedColor2 || null;
                }

                return ok;
            },
            async resetColor() {
                const ok = await this.save("update",
                    { profile_color: null, profile_color_secondary: null }, "Colour scheme reset.");

                if (ok) {
                    this.savedColor = "";
                    this.colorDraft = "";
                    this.savedColor2 = "";
                    this.colorDraft2 = "";
                    this.info.profile_color = null;
                    this.info.profile_color_secondary = null;
                }
            },
            /**
             * Reads the background picture and picks both colours out of it: the
             * primary one is the most common vivid hue, the secondary one the next
             * hue that is clearly different (or a neighbouring hue when the picture
             * has only one). Both are then saved like any other colour.
             */
            async matchBackground() {
                if (!this.backgroundUrl) return;

                this.colorMatching = true;
                this.error = "";
                this.notice = "";

                try {
                    const image = await new Promise((resolve, reject) => {
                        const img = new Image();
                        img.onload = () => resolve(img);
                        img.onerror = () => reject(new Error("The background could not be read."));
                        img.src = this.backgroundPath + "?m=" + Date.now();
                    });

                    const canvas = document.createElement("canvas");
                    canvas.width = 64;
                    canvas.height = Math.max(1, Math.round(64 * image.naturalHeight / image.naturalWidth));

                    const context = canvas.getContext("2d", { willReadFrequently: true });
                    context.drawImage(image, 0, 0, canvas.width, canvas.height);

                    const picked = this.pickColors(context.getImageData(0, 0, canvas.width, canvas.height).data);
                    this.colorDraft = picked.primary;
                    this.colorDraft2 = picked.secondary;

                    if (await this.saveColor()) {
                        this.notice = "Colours matched to your background.";
                    }
                } catch (e) {
                    this.error = e.message || "The background could not be read.";
                } finally {
                    this.colorMatching = false;
                }
            },
            /** {primary, secondary} as #rrggbb, taken from RGBA pixel data. */
            pickColors(data) {
                const bins = 24;
                const weight = new Array(bins).fill(0);
                const sums = Array.from({ length: bins }, () => [0, 0, 0]);
                const avg = [0, 0, 0];
                let count = 0;

                for (let i = 0; i < data.length; i += 4) {
                    if (data[i + 3] < 128) continue;

                    const r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
                    const max = Math.max(r, g, b), min = Math.min(r, g, b);
                    const d = max - min;

                    avg[0] += r; avg[1] += g; avg[2] += b; count++;

                    if (max < 0.12 || d < 0.08) continue;

                    let h;
                    if (max === r) h = ((g - b) / d + 6) % 6;
                    else if (max === g) h = (b - r) / d + 2;
                    else h = (r - g) / d + 4;

                    const w = d;
                    const bin = Math.min(bins - 1, Math.floor(h / 6 * bins));

                    weight[bin] += w;
                    sums[bin][0] += r * w; sums[bin][1] += g * w; sums[bin][2] += b * w;
                }

                let best = -1;
                for (let k = 0; k < bins; k++) {
                    if (weight[k] > 0 && (best < 0 || weight[k] > weight[best])) best = k;
                }

                // The next hue that is at least 60 degrees away and carries a fair share.
                let second = -1;
                if (best >= 0) {
                    for (let k = 0; k < bins; k++) {
                        const gap = Math.min((k - best + bins) % bins, (best - k + bins) % bins);
                        if (gap >= 4 && weight[k] >= weight[best] * 0.15
                            && (second < 0 || weight[k] > weight[second])) second = k;
                    }
                }

                const vivid = best >= 0 && weight[best] > 0.5;
                const mean = count > 0 ? avg.map(v => v / count) : [0.55, 0.4, 1];
                const rgbOf = k => sums[k].map(v => v / weight[k]);

                const primaryRgb = vivid ? rgbOf(best) : mean;
                const primary = this.toAccent(primaryRgb, vivid, 0);
                // No second hue in the picture: a neighbour of the first one reads as a
                // matching pair instead of a random one.
                const secondary = second >= 0
                    ? this.toAccent(rgbOf(second), true, 0)
                    : this.toAccent(primaryRgb, vivid, 40);

                return { primary: primary, secondary: secondary };
            },
            /** RGB 0..1 into a readable accent #rrggbb, optionally turning the hue by some degrees. */
            toAccent(rgb, vivid, turn) {
                const max = Math.max(...rgb), min = Math.min(...rgb);
                let l = (max + min) / 2, h = 0, s = 0;
                if (max !== min) {
                    const d = max - min;
                    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
                    if (max === rgb[0]) h = ((rgb[1] - rgb[2]) / d + 6) % 6;
                    else if (max === rgb[1]) h = (rgb[2] - rgb[0]) / d + 2;
                    else h = (rgb[0] - rgb[1]) / d + 4;
                    h /= 6;
                }

                h = (h + turn / 360 + 1) % 1;
                s = vivid ? Math.max(0.55, Math.min(0.95, s)) : Math.min(0.5, s);
                l = Math.max(0.58, Math.min(0.7, l));

                const hue = (p, q, t) => {
                    if (t < 0) t += 1;
                    if (t > 1) t -= 1;
                    if (t < 1 / 6) return p + (q - p) * 6 * t;
                    if (t < 1 / 2) return q;
                    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
                    return p;
                };
                const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
                const p = 2 * l - q;
                const out = [hue(p, q, h + 1 / 3), hue(p, q, h), hue(p, q, h - 1 / 3)];

                return "#" + out.map(v => Math.round(v * 255).toString(16).padStart(2, "0")).join("");
            },
            /** Clearing it is a normal profile update; only uploading needs the file route. */
            async clearBackground() {
                const ok = await this.save("update", { custom_background: null }, "Background removed.");

                if (ok) {
                    this.backgroundPath = "";
                    this.backgroundVersion = 0;
                    this.info.custom_background = null;
                }
            },
            /** Clearing it is a normal profile update; only uploading needs the file route. */
            async clearBanner() {
                const ok = await this.save("update", { custom_banner: null }, "Banner removed.");

                if (ok) {
                    this.bannerPath = "";
                    this.bannerVersion = 0;
                    this.info.custom_banner = null;
                }
            },
            async saveBadge() {
                const name = this.badgeName;

                if (!this.badgeIcon) {
                    this.error = "Upload a picture for the badge first.";

                    return;
                }

                if (!name) {
                    this.error = "A badge needs a name.";

                    return;
                }

                const ok = await this.save("update", {
                    custom_badge_name: name,
                    custom_badge_icon: this.badgeIcon
                }, "Badge saved.");

                if (ok) {
                    this.info.custom_badge_name = name;
                    this.info.custom_badge_icon = this.badgeIcon;
                }
            },
            async clearBadge() {
                const ok = await this.save("update", {
                    custom_badge_name: null,
                    custom_badge_icon: null
                }, "Badge removed.");

                if (ok) {
                    this.badge.custom_badge_name = "";
                    this.badgeIcon = "";
                    this.info.custom_badge_name = null;
                    this.info.custom_badge_icon = null;
                }
            },
            /**
             * Reads whether an authenticator is set up. Failures are swallowed: a session that
             * has expired is already reported by the card above, and this section simply shows
             * its "off" state rather than a second copy of the same complaint.
             */
            async loadTwoFactor() {
                try {
                    const answer = await this.session("GET", "/account/2fa");

                    this.twoFactor.enabled = !!(answer && answer.enabled);
                } catch (e) {
                    this.twoFactor.enabled = false;
                }
            },
            /**
             * Asks for a secret to scan. Nothing changes on the account yet - the secret waits
             * on the server until a code proves the app really has it.
             */
            async startTwoFactor() {
                if (this.twoFactor.busy) return;

                this.twoFactor.busy = true;
                this.twoFactor.error = "";
                this.twoFactor.notice = "";

                try {
                    const answer = await this.session("POST", "/account/2fa", { action: "setup" });

                    this.twoFactor.setup = (answer && answer.body) || null;
                    this.twoFactor.code = "";
                } catch (e) {
                    this.twoFactor.error = e.message;
                } finally {
                    this.twoFactor.busy = false;
                }
            },
            /**
             * The QR code as an image source.
             *
             * The picture itself is drawn by the server and arrives as SVG markup, so there is
             * nothing to fetch and nothing to compute here - it is wrapped in a data URI and
             * handed to an <img>. Inlined rather than dropped into the document as markup so the
             * browser treats it as an image and not as part of the page.
             */
            qrSource() {
                const svg = this.twoFactor.setup && this.twoFactor.setup.qr_svg;

                if (!svg) return "";

                return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
            },
            /** Drops a setup that was never confirmed; the server forgets it on its own too. */
            cancelTwoFactor() {
                this.twoFactor.setup = null;
                this.twoFactor.code = "";
                this.twoFactor.error = "";
            },
            async confirmTwoFactor() {
                if (this.twoFactor.busy) return;

                const code = this.twoFactor.code.replace(/[^0-9]/g, "");

                if (code.length !== 6) {
                    this.twoFactor.error = "The code is six digits.";
                    return;
                }

                this.twoFactor.busy = true;
                this.twoFactor.error = "";

                try {
                    await this.session("POST", "/account/2fa", { action: "enable", code: code });

                    // The secret has no business staying in a browser tab once it is in the app.
                    this.twoFactor.setup = null;
                    this.twoFactor.code = "";
                    this.twoFactor.enabled = true;
                    this.twoFactor.notice = "Two-factor authentication is on.";
                } catch (e) {
                    this.twoFactor.error = e.message;
                } finally {
                    this.twoFactor.busy = false;
                }
            },
            async disableTwoFactor() {
                if (this.twoFactor.busy) return;

                this.twoFactor.busy = true;
                this.twoFactor.error = "";

                try {
                    await this.session("POST", "/account/2fa", {
                        action: "disable",
                        current_password: this.twoFactor.current_password
                    });

                    this.twoFactor.enabled = false;
                    this.twoFactor.current_password = "";
                    this.twoFactor.notice = "Two-factor authentication is off.";
                } catch (e) {
                    this.twoFactor.error = e.message;
                } finally {
                    this.twoFactor.busy = false;
                }
            },
            async changePassword() {
                if (this.password.new_password !== this.passwordRepeat) {
                    this.error = "The two new passwords are not the same.";

                    return;
                }

                const ok = await this.save("password", this.password, "Password changed.");

                // The session is gone on the server the moment this succeeds,
                // so the browser is sent to the login page rather than left on
                // a page whose every button would now fail.
                if (ok) window.location.href = "/login";
            },
            async deleteAccount() {
                if (this.deleteName !== this.info.name) return;

                const ok = await this.save("delete", this.remove, "Account deleted.");

                if (ok) window.location.href = "/";
            }
        },
        created() {
            this.setTitle("Settings");
            this.load();
            this.loadTwoFactor();
        },
        mounted() {
            // Escape and a window resize both have to reach the dialog, and
            // neither of them is delivered to an element that is not focused.
            this.onEditorKey = event => {
                if (event.key === "Escape" && this.avatarEditor.open) this.closeAvatarEditor();
            };
            this.onEditorResize = () => {
                if (this.avatarEditor.open) this.resetSelection();
            };
            document.addEventListener("keydown", this.onEditorKey);
            window.addEventListener("resize", this.onEditorResize);
        },
        beforeUnmount() {
            document.removeEventListener("keydown", this.onEditorKey);
            window.removeEventListener("resize", this.onEditorResize);
            if (this.avatarEditor.objectUrl) URL.revokeObjectURL(this.avatarEditor.objectUrl);
        }
    });
</script>
