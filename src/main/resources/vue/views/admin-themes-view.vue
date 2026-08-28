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
            can: { type: Function, default: () => () => true }
        },
        data: () => ({
            loading: true,
            fetching: false,
            saving: false,
            errorMsg: "",
            successMsg: "",
            customUrl: "http://localhost:3000/api/themes",
            selectedCategory: "all",
            previewModalTheme: null,
            jsonModalTheme: null,
            directJsonModalOpen: false,
            directJsonText: "",
            state: {
                enabled: true,
                sourceUrl: "http://localhost:3000/api/themes",
                activeThemeId: null,
                activeTheme: null,
                availableThemes: [],
                lastFetched: null
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
            async activate(theme) {
                this.saving = true;
                this.errorMsg = "";
                this.successMsg = "";
                try {
                    const res = await this.session("POST", "/admin/api/themes/select", {
                        id: theme.id,
                        data: theme
                    });
                    if (res && res.state) {
                        this.state = res.state;
                        this.successMsg = `Activated theme “${theme.name}” site-wide!`;
                        if (window.__koneko) {
                            window.__koneko.theme = {
                                enabled: true,
                                activeThemeId: theme.id,
                                active: res.theme || theme
                            };
                        }
                    }
                } catch (e) {
                    this.errorMsg = e.message || "Failed to activate theme.";
                } finally {
                    this.saving = false;
                }
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
            async applyDirectJson() {
                if (!this.directJsonText.trim()) return;
                try {
                    const parsed = JSON.parse(this.directJsonText);
                    if (!parsed.id) throw new Error("JSON must have an 'id' field.");
                    await this.activate(parsed);
                    this.directJsonModalOpen = false;
                } catch (e) {
                    this.errorMsg = "Invalid JSON: " + e.message;
                }
            },
            onKey(e) {
                if (e.key === "Escape") {
                    this.closePreview();
                    this.closeJson();
                    this.directJsonModalOpen = false;
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
