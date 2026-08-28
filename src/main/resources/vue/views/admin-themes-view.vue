<template id="admin-themes-view">
    <div class="admin-page admin-themes-view">
        <!-- Top Section: Header & Source Configuration -->
        <section class="card admin-themes-header-card">
            <div class="admin-themes-header-row">
                <div class="admin-themes-header-text">
                    <h2>Event Themes Management</h2>
                    <p class="muted small">
                        Dynamically fetch, preview, and activate seasonal and custom themes for the entire website.
                    </p>
                </div>

                <div class="admin-themes-status-pills" role="status" aria-live="polite">
                    <span class="admin-badge" :class="state.enabled && state.activeThemeId ? 'is-good' : 'is-muted'">
                        {{ (state.enabled && state.activeThemeId) ? '● Active Event Theme' : '○ Default Site Look' }}
                    </span>

                    <span class="admin-badge is-good" v-if="state.availableThemes && state.availableThemes.length">
                        {{ state.availableThemes.length }} Themes Available
                    </span>
                </div>
            </div>

            <!-- Toast alert with aria-live -->
            <div class="admin-theme-alert is-success" v-if="successMsg" role="status" aria-live="polite">
                <span>{{ successMsg }}</span>
                <button type="button" class="admin-theme-alert-close" aria-label="Close notification" @click="successMsg = ''">&times;</button>
            </div>
            <div class="admin-theme-alert is-error" v-if="errorMsg" role="alert" aria-live="polite">
                <span>{{ errorMsg }}</span>
                <button type="button" class="admin-theme-alert-close" aria-label="Close error" @click="errorMsg = ''">&times;</button>
            </div>

            <!--
                A problem the server is still having, as opposed to a request that just failed.
                It has no close button on purpose: it describes a condition that is still true,
                so dismissing it would only hide a broken deployment until the next reload. It
                disappears when the server stops reporting it.
            -->
            <div class="admin-theme-alert is-warning" v-if="state.error" role="alert" aria-live="polite">
                <span>{{ state.error }}</span>
            </div>

            <!-- Remote Endpoint Input & Actions -->
            <div class="admin-themes-config-box">
                <div class="admin-field-grow">
                    <label class="admin-field-label" for="theme-repo-endpoint">Theme Repository API Endpoint (Service URL)</label>
                    <div class="admin-input-group">
                        <input id="theme-repo-endpoint" class="input" type="url" v-model="customUrl"
                            name="theme_endpoint" placeholder="http://localhost:3000/api/themes"
                            autocomplete="off" spellcheck="false" :disabled="fetching || saving">
                        
                        <button class="button" type="button" :disabled="fetching || saving" @click="fetchRemoteThemes">
                            <span v-if="fetching">Fetching…</span>
                            <span v-else>↻ Fetch Themes</span>
                        </button>

                        <button class="button button-ghost" type="button" :disabled="fetching || saving" @click="saveSourceSettings">
                            Save Endpoint
                        </button>
                    </div>
                </div>

                <div class="admin-themes-master-actions">
                    <label class="admin-check admin-toggle-switch">
                        <input type="checkbox" v-model="state.enabled" @change="toggleEnabled">
                        <span>Event Themes Engine Enabled</span>
                    </label>

                    <button class="button button-ghost button-small is-danger" type="button"
                        v-if="state.activeThemeId" :disabled="saving" @click="deactivate">
                        ✕ Deactivate & Reset to Default
                    </button>

                    <button class="button button-ghost button-small" type="button" @click="openDirectJsonModal">
                        { } Import Custom JSON
                    </button>
                </div>

                <p class="muted small admin-fetch-meta" v-if="state.lastFetched">
                    Last synchronized: {{ fmtRelative(state.lastFetched) }} from <code>{{ state.sourceUrl }}</code>
                </p>

                <p class="muted small admin-fetch-meta" v-if="state.allowedHosts && state.allowedHosts.length">
                    Themes may be fetched from: <code v-for="host in state.allowedHosts" :key="host">{{ host }}</code>
                    — set in <code>THEME_SOURCE_HOSTS</code> on the server.
                </p>
                <p class="muted small admin-fetch-meta" v-else>
                    No theme source hosts are configured, so nothing can be fetched yet.
                    Set <code>THEME_SOURCE_HOSTS</code> in <code>.env</code> on the server first.
                </p>

                <p class="muted small admin-fetch-meta" v-if="state.approvedBy">
                    Active theme approved by <strong>{{ state.approvedBy }}</strong>
                    {{ state.approvedAt ? fmtRelative(state.approvedAt) : '' }}.
                    Custom scripts: {{ state.jsApproved ? 'approved' : 'not running' }}.
                </p>
            </div>
        </section>

        <!-- Skeleton loader -->
        <div class="skeleton-rows" v-if="loading && !state.availableThemes" aria-busy="true" aria-label="Loading themes…">
            <div class="skeleton skeleton-row" v-for="n in 4" :key="n"></div>
        </div>

        <template v-else>
            <!-- Currently Active Theme Spotlight -->
            <section class="card admin-active-theme-spotlight" v-if="state.enabled && activeTheme">
                <div class="admin-spotlight-badge">★ Currently Live Across Site</div>
                <div class="admin-spotlight-grid">
                    <div class="admin-spotlight-preview">
                        <img v-if="activeTheme.preview_image" :src="activeTheme.preview_image"
                            :alt="activeTheme.name + ' preview banner'" width="600" height="240" loading="lazy">
                        <div class="admin-spotlight-fallback" v-else :style="{ background: activeTheme.accent_color || 'var(--accent)' }">
                            <span>{{ activeTheme.name }}</span>
                        </div>
                    </div>

                    <div class="admin-spotlight-info">
                        <div class="admin-spotlight-top">
                            <span class="admin-theme-badge">{{ activeTheme.badge || activeTheme.category }}</span>
                            <span class="admin-theme-version">v{{ activeTheme.version }} by {{ activeTheme.author }}</span>
                        </div>

                        <h3 class="admin-spotlight-title">{{ activeTheme.name }}</h3>
                        <p class="admin-spotlight-desc">{{ activeTheme.description }}</p>

                        <div class="admin-theme-tags">
                            <span class="admin-tag-pill" v-for="tag in activeTheme.tags" :key="tag">{{ tag }}</span>
                        </div>

                        <div class="admin-spotlight-actions">
                            <button class="button button-small" type="button" @click="openPreview(activeTheme)">
                                👁 Full Preview
                            </button>
                            <button class="button button-small button-ghost" type="button" @click="openJson(activeTheme)" aria-label="View JSON payload">
                                { } View JSON
                            </button>
                            <button class="button button-small button-ghost is-danger" type="button" @click="deactivate">
                                Revert to Default Theme
                            </button>
                        </div>
                    </div>
                </div>
            </section>

            <!-- Available Themes Header & Filters -->
            <section class="admin-themes-list-header">
                <div class="admin-themes-list-title">
                    <h3>Available Themes ({{ themesList.length }})</h3>
                    <p class="muted small">Select any theme below to activate it instantly for all visitors.</p>
                </div>

                <!-- Category filter tabs -->
                <div class="admin-theme-filter-tabs" v-if="categories.length > 1" role="tablist" aria-label="Theme categories">
                    <button class="admin-filter-btn" :class="{ 'is-active': selectedCategory === 'all' }"
                        type="button" role="tab" :aria-selected="selectedCategory === 'all'" @click="selectedCategory = 'all'">
                        All ({{ state.availableThemes.length }})
                    </button>
                    <button class="admin-filter-btn" v-for="cat in categories" :key="cat"
                        :class="{ 'is-active': selectedCategory === cat }"
                        type="button" role="tab" :aria-selected="selectedCategory === cat" @click="selectedCategory = cat">
                        {{ cat }}
                    </button>
                </div>
            </section>

            <!-- Themes Grid -->
            <div class="admin-themes-grid" v-if="themesList.length > 0">
                <div class="card admin-theme-card" v-for="theme in themesList" :key="theme.id"
                    :class="{ 'is-active-theme': isCurrentTheme(theme.id) }">
                    
                    <!-- Card Top Banner Image / Preview Button -->
                    <button type="button" class="admin-theme-card-media" @click="openPreview(theme)"
                        :aria-label="'Preview ' + theme.name">
                        <img v-if="theme.preview_image" :src="theme.preview_image" :alt="theme.name + ' preview banner'"
                            width="600" height="240" loading="lazy">
                        <div class="admin-theme-card-fallback" v-else
                            :style="{ background: 'linear-gradient(135deg, ' + (theme.accent_color || '#ff4d8d') + '33 0%, #161618 100%)' }">
                            <span class="admin-fallback-icon" aria-hidden="true">🎨</span>
                        </div>

                        <span class="admin-theme-card-active-pill" v-if="isCurrentTheme(theme.id)">
                            ✓ ACTIVE SITE THEME
                        </span>

                        <span class="admin-theme-card-badge" v-if="theme.badge">
                            {{ theme.badge }}
                        </span>
                    </button>

                    <!-- Card Body -->
                    <div class="admin-theme-card-body">
                        <div class="admin-theme-card-meta">
                            <span class="admin-theme-category">{{ theme.category || 'Theme' }}</span>
                            <span class="admin-theme-author">v{{ theme.version }} · {{ theme.author }}</span>
                        </div>

                        <h4 class="admin-theme-card-title">{{ theme.name }}</h4>
                        <p class="admin-theme-card-desc">{{ theme.description }}</p>

                        <!-- Theme tags / features -->
                        <div class="admin-theme-tags">
                            <span class="admin-tag-pill" v-for="tag in (theme.tags || []).slice(0, 4)" :key="tag">
                                {{ tag }}
                            </span>
                        </div>

                        <!-- Accent Color Indicator -->
                        <div class="admin-theme-palette-preview">
                            <span class="admin-palette-label">Accent:</span>
                            <span class="admin-palette-dot" :style="{ background: theme.accent_color || 'var(--accent)' }" aria-hidden="true"></span>
                            <code>{{ theme.accent_color || '#ff4d8d' }}</code>
                        </div>
                    </div>

                    <!-- Card Footer Actions -->
                    <div class="admin-theme-card-footer">
                        <button class="button button-small" v-if="!isCurrentTheme(theme.id)"
                            type="button" :disabled="saving" @click="activate(theme)">
                            Apply Theme
                        </button>
                        <button class="button button-small button-ghost is-active-btn" v-else
                            type="button" disabled>
                            ✓ Active
                        </button>

                        <button class="button button-small button-ghost" type="button" @click="openPreview(theme)">
                            Preview
                        </button>

                        <button class="button button-small button-ghost" type="button" @click="openJson(theme)"
                            :aria-label="'View JSON for ' + theme.name" title="View JSON">
                            { }
                        </button>
                    </div>
                </div>
            </div>

            <!-- Empty state -->
            <section class="card admin-themes-empty" v-else>
                <p class="muted">No themes found matching the current filter. Try fetching from the source URL above.</p>
                <button class="button button-small" type="button" @click="fetchRemoteThemes">Fetch Themes</button>
            </section>
        </template>

        <!-- Live Preview Modal -->
        <div class="admin-modal-overlay" v-if="previewModalTheme" @click.self="closePreview"
            role="dialog" aria-modal="true" aria-labelledby="preview-modal-title">
            <div class="admin-modal-container admin-preview-modal">
                <div class="admin-modal-header">
                    <h3 id="preview-modal-title">Theme Live Preview: {{ previewModalTheme.name }}</h3>
                    <button class="admin-modal-close" type="button" aria-label="Close preview modal" @click="closePreview">&times;</button>
                </div>

                <div class="admin-modal-body">
                    <!-- Color Swatches -->
                    <div class="admin-preview-section">
                        <h4>Color Palette & CSS Variables</h4>
                        <div class="admin-color-swatches-grid">
                            <div class="admin-swatch-item" v-for="(val, key) in (previewModalTheme.css_variables || {})" :key="key">
                                <div class="admin-swatch-color" :style="{ background: val }" aria-hidden="true"></div>
                                <div class="admin-swatch-name">{{ key }}</div>
                                <code>{{ val }}</code>
                            </div>
                        </div>
                    </div>

                    <!-- Theme Elements & Particle Specs -->
                    <div class="admin-preview-section">
                        <h4>Theme Effects & Elements</h4>
                        <table class="table">
                            <tbody>
                                <tr>
                                    <td><strong>Particles Simulation:</strong></td>
                                    <td>{{ previewModalTheme.particles ? previewModalTheme.particles.type + ' (' + previewModalTheme.particles.count + ' items)' : 'None' }}</td>
                                </tr>
                                <tr>
                                    <td><strong>Interactive Elements:</strong></td>
                                    <td>{{ (previewModalTheme.elements || []).map(e => e.title || e.type).join(', ') || 'Standard' }}</td>
                                </tr>
                                <tr>
                                    <td><strong>Custom CSS Rules:</strong></td>
                                    <td>{{ (previewModalTheme.custom_css || '').length }} bytes of stylesheet</td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <div class="admin-modal-footer">
                    <button class="button" type="button" @click="activate(previewModalTheme); closePreview();">
                        Apply This Theme Now
                    </button>
                    <button class="button button-ghost" type="button" @click="closePreview">
                        Close Preview
                    </button>
                </div>
            </div>
        </div>

        <!-- JSON Inspector Modal -->
        <div class="admin-modal-overlay" v-if="jsonModalTheme" @click.self="closeJson"
            role="dialog" aria-modal="true" aria-labelledby="json-modal-title">
            <div class="admin-modal-container">
                <div class="admin-modal-header">
                    <h3 id="json-modal-title">Theme JSON Payload: {{ jsonModalTheme.name }}</h3>
                    <button class="admin-modal-close" type="button" aria-label="Close JSON modal" @click="closeJson">&times;</button>
                </div>
                <div class="admin-modal-body">
                    <pre class="admin-code-block">{{ JSON.stringify(jsonModalTheme, null, 2) }}</pre>
                </div>
                <div class="admin-modal-footer">
                    <button class="button button-ghost" type="button" @click="closeJson">Close</button>
                </div>
            </div>
        </div>

        <!-- Direct JSON Importer Modal -->
        <div class="admin-modal-overlay" v-if="directJsonModalOpen" @click.self="directJsonModalOpen = false"
            role="dialog" aria-modal="true" aria-labelledby="import-modal-title">
            <div class="admin-modal-container">
                <div class="admin-modal-header">
                    <h3 id="import-modal-title">Import & Apply Custom Theme JSON</h3>
                    <button class="admin-modal-close" type="button" aria-label="Close import modal" @click="directJsonModalOpen = false">&times;</button>
                </div>
                <div class="admin-modal-body">
                    <label class="admin-field-label" for="raw-json-input">Paste raw theme JSON object to test or activate custom theme directly:</label>
                    <textarea id="raw-json-input" class="admin-json-textarea" v-model="directJsonText" rows="12"
                        placeholder="{\n  &quot;id&quot;: &quot;custom-theme&quot;,\n  &quot;name&quot;: &quot;My Custom Theme&quot;,\n  ...\n}"
                        spellcheck="false" autocomplete="off"></textarea>
                </div>
                <div class="admin-modal-footer">
                    <button class="button" type="button" :disabled="saving" @click="applyDirectJson">
                        Import & Apply
                    </button>
                    <button class="button button-ghost" type="button" @click="directJsonModalOpen = false">
                        Cancel
                    </button>
                </div>
            </div>
        </div>

        <!--
            The consent step. Only appears for a theme that actually carries scripts, so it
            stays a decision rather than a dialog people learn to click through.
        -->
        <div class="admin-modal-overlay" v-if="pendingTheme" @click.self="cancelConsent"
            role="dialog" aria-modal="true" aria-labelledby="consent-modal-title">
            <div class="admin-modal-container">
                <div class="admin-modal-header">
                    <h3 id="consent-modal-title">“{{ pendingTheme.name }}” includes its own scripts</h3>
                    <button class="admin-modal-close" type="button" aria-label="Close"
                        @click="cancelConsent">&times;</button>
                </div>

                <div class="admin-modal-body">
                    <p>
                        This theme ships {{ (pendingTheme.custom_js || '').length }} bytes of
                        JavaScript from <code>{{ pendingSourceLabel }}</code>.
                    </p>

                    <p class="muted small">
                        The script runs in a sandboxed frame with its own empty origin. It cannot
                        read this site's pages, cannot read the session cookie, cannot act as a
                        logged-in player, and cannot reach the network. The frame is drawn beneath
                        the page and ignores clicks, and themes are never applied on the login,
                        register, settings or two-factor pages.
                    </p>

                    <p class="muted small">
                        What it can still do: make the site look wrong, and use a visitor's CPU.
                        Approving records your name against this exact version of the theme — if
                        the source changes the script later, it stops running until somebody
                        approves the new version.
                    </p>

                    <p class="muted small" v-if="!state.customJsAllowedByServer">
                        <strong>Note:</strong> <code>THEME_ALLOW_CUSTOM_JS</code> is off on this
                        server, so the script will not run even if you approve it. The rest of the
                        theme — colours, stylesheet, particles — is applied either way.
                    </p>

                    <label class="admin-check">
                        <input type="checkbox" v-model="jsConsent">
                        <span>
                            I have reviewed this theme's script, I accept the risk described
                            above, and I trust <code>{{ pendingSourceLabel }}</code>.
                        </span>
                    </label>
                </div>

                <div class="admin-modal-footer">
                    <button class="button" type="button" :disabled="saving" @click="confirmConsent">
                        {{ jsConsent ? 'Approve scripts and apply' : 'Apply without scripts' }}
                    </button>
                    <button class="button button-ghost" type="button" @click="cancelConsent">
                        Cancel
                    </button>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
    /**
     * Admin Event Themes Management View
     * Conforms to Web Interface Guidelines (Accessibility, Focus States, Form UX)
     */
    app.component("admin-themes-view", {
        template: "#admin-themes-view",
        props: {
            // Closed by default, like every other admin view: a panel that assumes
            // permission is a panel that grants it.
            can: { type: Function, default: () => () => false }
        },
        data: () => ({
            loading: true,
            fetching: false,
            saving: false,
            errorMsg: "",
            successMsg: "",
            customUrl: "",
            selectedCategory: "all",
            previewModalTheme: null,
            jsonModalTheme: null,
            directJsonModalOpen: false,
            directJsonText: "",

            // The theme waiting on the consent step, and the box itself. The box resets to
            // false for every theme, so an approval is never inherited from the last one.
            pendingTheme: null,
            pendingIsImport: false,
            jsConsent: false,

            state: {
                enabled: true,
                sourceUrl: "",
                activeThemeId: null,
                activeTheme: null,
                availableThemes: [],
                lastFetched: null,
                jsApproved: false,

                // A problem the server is reporting about itself, such as settings that are
                // live in memory but could not be written to disk. Separate from errorMsg,
                // which is about the request this panel just made.
                error: null,

                customJsAllowedByServer: false,
                allowedHosts: []
            }
        }),
        computed: {
            activeTheme() {
                if (!this.state.activeThemeId || !this.state.availableThemes) {
                    return this.state.activeTheme || null;
                }
                const found = this.state.availableThemes.find(t => t.id === this.state.activeThemeId);
                return found || this.state.activeTheme || null;
            },
            categories() {
                const cats = new Set();
                (this.state.availableThemes || []).forEach(t => {
                    if (t.category) cats.add(t.category);
                });
                return Array.from(cats);
            },
            themesList() {
                const list = this.state.availableThemes || [];
                if (this.selectedCategory === "all") return list;
                return list.filter(t => t.category === this.selectedCategory);
            },

            pendingSourceLabel() {
                return this.pendingIsImport
                    ? "a payload pasted by hand"
                    : (this.state.sourceUrl || "the configured source");
            }
        },
        methods: {
            isCurrentTheme(id) {
                return Boolean(this.state.enabled && this.state.activeThemeId === id);
            },
            async load() {
                this.loading = true;
                this.errorMsg = "";
                try {
                    const res = await this.session("GET", "/admin/api/themes");
                    if (res) {
                        this.state = res;
                        if (res.sourceUrl) this.customUrl = res.sourceUrl;
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Could not load theme settings.";
                } finally {
                    this.loading = false;
                }
            },
            async fetchRemoteThemes() {
                this.fetching = true;
                this.errorMsg = "";
                this.successMsg = "";
                try {
                    const res = await this.session("POST", "/admin/api/themes/fetch", { url: this.customUrl });
                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = `Successfully fetched ${this.state.availableThemes.length} event themes!`;
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Failed to fetch themes from the specified URL.";
                } finally {
                    this.fetching = false;
                }
            },
            async saveSourceSettings() {
                this.saving = true;
                this.errorMsg = "";
                this.successMsg = "";
                try {
                    const res = await this.session("POST", "/admin/api/themes/settings", {
                        sourceUrl: this.customUrl,
                        enabled: this.state.enabled
                    });
                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = "Theme repository endpoint saved successfully.";
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Could not save theme settings.";
                } finally {
                    this.saving = false;
                }
            },
            async toggleEnabled() {
                this.saving = true;
                try {
                    const res = await this.session("POST", "/admin/api/themes/settings", {
                        sourceUrl: this.customUrl,
                        enabled: this.state.enabled
                    });
                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = this.state.enabled
                            ? "Event themes engine enabled."
                            : "Event themes engine disabled.";
                        if (window.__koneko && window.__koneko.theme) {
                            window.__koneko.theme.enabled = this.state.enabled;
                        }
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Could not toggle theme engine.";
                } finally {
                    this.saving = false;
                }
            },
            /**
             * Applies a theme, asking about its scripts first when it has any.
             *
             * The theme is never sent from here: only its id goes to the server, which
             * applies the copy it fetched and checked itself. Sending the object would mean
             * the panel could apply a payload the server never validated.
             */
            async activate(theme) {
                if (theme.custom_js) {
                    this.pendingTheme = theme;
                    this.pendingIsImport = false;
                    this.jsConsent = false;
                    return;
                }

                await this.sendActivation(theme, false);
            },

            async sendActivation(theme, jsConsent) {
                this.saving = true;
                this.errorMsg = "";
                this.successMsg = "";

                try {
                    const res = await this.session("POST", "/admin/api/themes/select", {
                        id: theme.id,
                        jsConsent: jsConsent
                    });

                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = res.state.jsApproved
                            ? `Applied “${theme.name}” with its scripts approved.`
                            : `Applied “${theme.name}”.`;
                        this.publishTheme(res);
                    }
                } catch (e) {
                    this.errorMsg = e.message || "That theme could not be applied.";
                } finally {
                    this.saving = false;
                }
            },

            /**
             * Mirrors the server's answer into the bootstrap so the runtime picks it up
             * without a reload. Read from the response rather than assembled here, so the
             * page shows what the server actually stored, including a stripped script.
             */
            publishTheme(res) {
                if (!window.__koneko) {
                    return;
                }

                const theme = res.theme || null;

                window.__koneko.theme = {
                    enabled: Boolean(theme),
                    activeThemeId: res.state ? res.state.activeThemeId : null,
                    active: theme,
                    jsEnabled: Boolean(res.state && res.state.jsApproved
                        && res.state.customJsAllowedByServer && theme && theme.custom_js)
                };
            },

            async confirmConsent() {
                const theme = this.pendingTheme;
                const consent = this.jsConsent;
                const isImport = this.pendingIsImport;

                this.pendingTheme = null;

                if (isImport) {
                    await this.sendImport(theme, consent);
                } else {
                    await this.sendActivation(theme, consent);
                }
            },

            cancelConsent() {
                this.pendingTheme = null;
                this.jsConsent = false;
            },
            async deactivate() {
                this.saving = true;
                this.errorMsg = "";
                this.successMsg = "";
                try {
                    const res = await this.session("POST", "/admin/api/themes/reset");
                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = "Event theme deactivated. Site returned to default look.";
                        if (window.__koneko && window.__koneko.theme) {
                            window.__koneko.theme.enabled = false;
                            window.__koneko.theme.active = null;
                            window.__koneko.theme.activeThemeId = null;
                            window.__koneko.theme.jsEnabled = false;
                        }
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Could not deactivate theme.";
                } finally {
                    this.saving = false;
                }
            },
            openPreview(theme) {
                this.previewModalTheme = theme;
            },
            closePreview() {
                this.previewModalTheme = null;
            },
            openJson(theme) {
                this.jsonModalTheme = theme;
            },
            closeJson() {
                this.jsonModalTheme = null;
            },
            openDirectJsonModal() {
                this.directJsonText = "";
                this.directJsonModalOpen = true;
            },
            /**
             * Applies a pasted theme through its own endpoint.
             *
             * Separate from activate() because the two are different acts: one picks a theme
             * the server fetched and checked, the other hands it a payload from outside
             * altogether. Sharing a route let the second borrow the first's trust.
             */
            async applyDirectJson() {
                if (!this.directJsonText.trim()) {
                    return;
                }

                let parsed;

                try {
                    parsed = JSON.parse(this.directJsonText);
                } catch (e) {
                    this.errorMsg = "That is not valid JSON: " + e.message;
                    return;
                }

                if (!parsed || !parsed.id) {
                    this.errorMsg = "The pasted theme needs an \"id\" field.";
                    return;
                }

                this.directJsonModalOpen = false;

                if (parsed.custom_js) {
                    this.pendingTheme = parsed;
                    this.pendingIsImport = true;
                    this.jsConsent = false;
                    return;
                }

                await this.sendImport(parsed, false);
            },

            async sendImport(theme, jsConsent) {
                this.saving = true;
                this.errorMsg = "";
                this.successMsg = "";

                try {
                    const res = await this.session("POST", "/admin/api/themes/import", {
                        theme: theme,
                        jsConsent: jsConsent
                    });

                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = `Imported and applied “${theme.name || theme.id}”.`;
                        this.publishTheme(res);
                    }
                } catch (e) {
                    this.errorMsg = e.message || "That theme could not be imported.";
                } finally {
                    this.saving = false;
                }
            },
            onKey(e) {
                if (e.key === "Escape") {
                    this.closePreview();
                    this.closeJson();
                    this.directJsonModalOpen = false;
                    this.cancelConsent();
                }
            }
        },
        mounted() {
            window.addEventListener("keydown", this.onKey);
        },
        unmounted() {
            window.removeEventListener("keydown", this.onKey);
        },
        created() {
            this.setTitle("Event Themes");
            this.load();
        }
    });
</script>
