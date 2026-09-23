<template id="mapset-card">
    <div class="mapset-card beatmapset-panel beatmapset-panel--size-normal"
         :class="{
             'popup-visible': popupVisible,
             'beatmapset-panel--beatmaps-popup-visible': popupVisible,
             'is-playing': isPlaying,
             'is-loading': isLoading
         }"
         @mouseenter="openPopup"
         @mouseleave="closePopup">

        <!-- Background cover container: left thumb for play area, right banner with gradient wash -->
        <a class="beatmapset-panel__cover-container" :href="'/beatmapsets/' + set.set_id">
            <div class="beatmapset-panel__cover-col beatmapset-panel__cover-col--play">
                <div class="beatmapset-cover beatmapset-cover--full"
                     :style="playCoverStyle"></div>
            </div>
            <div class="beatmapset-panel__cover-col beatmapset-panel__cover-col--info">
                <div class="beatmapset-cover beatmapset-cover--full"
                     :style="infoCoverStyle"></div>
            </div>
        </a>

        <!-- Content overlay -->
        <div class="beatmapset-panel__content">
            <!-- Left: Play container with curved inverted fillets & audio preview -->
            <div class="beatmapset-panel__play-container">
                <button class="beatmapset-panel__play"
                        type="button"
                        :title="isPlaying ? 'Pause preview' : 'Play preview'"
                        @click.stop.prevent="toggleAudio">
                    <span class="play-button" :class="{ 'is-playing': isPlaying }">
                        <i v-if="isLoading" class="fas fa-circle-notch fa-spin"></i>
                        <i v-else-if="isPlaying" class="fas fa-pause"></i>
                        <i v-else class="fas fa-play"></i>
                    </span>
                </button>

                <!-- Circular audio progress -->
                <div class="beatmapset-panel__play-progress" v-if="isPlaying || isLoading">
                    <svg class="circular-progress" viewBox="0 0 36 36">
                        <path class="circular-progress__bg"
                              d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                              fill="none" stroke="rgba(255, 255, 255, 0.15)" stroke-width="2.5" />
                        <path class="circular-progress__fill"
                              d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                              fill="none" stroke="currentColor" stroke-width="2.5"
                              stroke-linecap="round"
                              :stroke-dasharray="(playProgress * 100) + ', 100'" />
                    </svg>
                </div>

                <!-- Video / Storyboard indicator badges in top left of thumbnail -->
                <div class="beatmapset-panel__play-icons">
                    <div class="beatmapset-panel__play-icon" v-if="set.has_video" title="Has video">
                        <i class="fas fa-film"></i>
                    </div>
                </div>
            </div>

            <!-- Middle: Main beatmapset details -->
            <div class="beatmapset-panel__info">
                <!-- Title Row -->
                <div class="beatmapset-panel__info-row beatmapset-panel__info-row--title">
                    <a class="beatmapset-panel__main-link u-ellipsis-overflow"
                       :href="'/beatmapsets/' + set.set_id"
                       :title="set.title">{{ set.title }}</a>
                    <div class="beatmapset-panel__badge-container" v-if="set.nsfw">
                        <span class="beatmapset-badge beatmapset-badge--nsfw">18+</span>
                    </div>
                </div>

                <!-- Artist Row -->
                <div class="beatmapset-panel__info-row beatmapset-panel__info-row--artist">
                    <a class="beatmapset-panel__main-link u-ellipsis-overflow"
                       :href="'/beatmapsets/' + set.set_id"
                       :title="set.artist">by {{ set.artist }}</a>
                    <div class="beatmapset-panel__badge-container" v-if="set.featured">
                        <span class="beatmapset-badge beatmapset-badge--featured_artist">Featured</span>
                    </div>
                </div>

                <!-- Source Row (if present) -->
                <div class="beatmapset-panel__info-row beatmapset-panel__info-row--source" v-if="set.source">
                    <div class="u-ellipsis-overflow">{{ set.source }}</div>
                </div>

                <!-- Mapper Row -->
                <div class="beatmapset-panel__info-row beatmapset-panel__info-row--mapper">
                    <div class="u-ellipsis-overflow">
                        mapped by
                        <a class="beatmapset-panel__mapper-link"
                           v-if="set.creator_id"
                           :href="'/u/' + set.creator_id"
                           @click.stop>{{ set.creator }}</a>
                        <span v-else>{{ set.creator }}</span>
                    </div>
                </div>

                <!-- Stats Row: Plays, Favourites, Date -->
                <div class="beatmapset-panel__info-row beatmapset-panel__info-row--stats">
                    <div class="beatmapset-panel__stats-item beatmapset-panel__stats-item--play-count"
                         :title="'Plays: ' + fmtNumber(set.plays)">
                        <span class="beatmapset-panel__stats-item-icon"><i class="fa-fw fas fa-play-circle"></i></span>
                        <span>{{ fmtCompact(set.plays) }}</span>
                    </div>
                    <div class="beatmapset-panel__stats-item beatmapset-panel__stats-item--favourite-count"
                         :title="'Favourites: ' + fmtNumber(set.favourites || 0)">
                        <span class="beatmapset-panel__stats-item-icon"><i class="fa-fw far fa-heart"></i></span>
                        <span>{{ fmtNumber(set.favourites || 0) }}</span>
                    </div>
                    <div class="beatmapset-panel__stats-item beatmapset-panel__stats-item--date"
                         v-if="set.ranked_date || set.last_update"
                         :title="fmtDate(set.ranked_date || set.last_update)">
                        <span class="beatmapset-panel__stats-item-icon"><i class="fa-fw fas fa-check-circle"></i></span>
                        <time>{{ fmtDate(set.ranked_date || set.last_update) }}</time>
                    </div>
                </div>

                <!-- Extra / Bottom Bar: Status badge, Mode icon, Difficulty capsules -->
                <a class="beatmapset-panel__info-row beatmapset-panel__info-row--extra"
                   :href="'/beatmapsets/' + set.set_id">
                    <div class="beatmapset-panel__extra-item">
                        <span class="beatmapset-status beatmapset-status--panel"
                              :class="'status-' + statusKey">{{ statusLabel }}</span>
                    </div>
                    <div class="beatmapset-panel__extra-item beatmapset-panel__extra-item--dots">
                        <div class="beatmapset-panel__beatmap-icon" :title="modeName(set.mode)">
                            <span class="mode-icon-glyph" :class="'mode-' + modeKey(set.mode)">
                                <svg v-if="Number(set.mode) === 1" viewBox="0 0 24 24" fill="currentColor">
                                    <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none"/>
                                    <path d="M12 6a6 6 0 1 0 0 12V6z"/>
                                </svg>
                                <svg v-else-if="Number(set.mode) === 2" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M12 2C8 2 6 5 6 9c0 6 6 13 6 13s6-7 6-13c0-4-2-7-6-7zm-1 3h2v2h-2V5z"/>
                                </svg>
                                <svg v-else-if="Number(set.mode) === 3" viewBox="0 0 24 24" fill="currentColor">
                                    <rect x="4" y="4" width="16" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>
                                    <line x1="8" y1="4" x2="8" y2="20" stroke="currentColor" stroke-width="2"/>
                                    <line x1="12" y1="4" x2="12" y2="20" stroke="currentColor" stroke-width="2"/>
                                    <line x1="16" y1="4" x2="16" y2="20" stroke="currentColor" stroke-width="2"/>
                                </svg>
                                <svg v-else viewBox="0 0 24 24" fill="currentColor">
                                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2" fill="none"/>
                                    <circle cx="12" cy="12" r="4" fill="currentColor"/>
                                </svg>
                            </span>
                        </div>
                        <div class="beatmapset-panel__beatmap-dot"
                             v-for="(dot, index) in dots"
                             :key="index"
                             :style="{ background: dot.color }"
                             :title="dot.title"></div>
                        <span class="diff-dots-more" v-if="extra">+{{ extra }}</span>
                    </div>
                </a>
            </div>

            <!-- Right: Action menu that expands on hover -->
            <div class="beatmapset-panel__menu-container">
                <div class="beatmapset-panel__menu">
                    <a class="beatmapset-panel__menu-item"
                       :href="'/beatmapsets/' + set.set_id"
                       title="View beatmapset"
                       @click.stop>
                        <i class="far fa-heart"></i>
                    </a>
                    <a class="beatmapset-panel__menu-item"
                       :href="downloadUrl"
                       title="Download beatmapset"
                       @click.stop>
                        <i class="fas fa-file-download"></i>
                    </a>
                </div>
            </div>
        </div>

        <!-- Unfolding difficulty popup matching osu!web reference -->
        <div class="beatmaps-popup" v-if="sorted.length && popupVisible">
            <div class="beatmaps-popup__content u-fancy-scrollbar">
                <div class="beatmaps-popup__group">
                    <a class="beatmaps-popup-item"
                       v-for="(diff, index) in sorted"
                       :key="index"
                       :href="'/beatmapsets/' + set.set_id + '#' + (diff.id || slug(diff.version))">
                        <div class="beatmap-list-item">
                            <div class="beatmap-list-item__col--icon">
                                <span class="mode-icon-glyph mode-icon-small" :class="'mode-' + modeKey(diff.mode !== undefined ? diff.mode : set.mode)">
                                    <svg v-if="Number(diff.mode !== undefined ? diff.mode : set.mode) === 1" viewBox="0 0 24 24" fill="currentColor">
                                        <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none"/>
                                        <path d="M12 6a6 6 0 1 0 0 12V6z"/>
                                    </svg>
                                    <svg v-else-if="Number(diff.mode !== undefined ? diff.mode : set.mode) === 2" viewBox="0 0 24 24" fill="currentColor">
                                        <path d="M12 2C8 2 6 5 6 9c0 6 6 13 6 13s6-7 6-13c0-4-2-7-6-7zm-1 3h2v2h-2V5z"/>
                                    </svg>
                                    <svg v-else-if="Number(diff.mode !== undefined ? diff.mode : set.mode) === 3" viewBox="0 0 24 24" fill="currentColor">
                                        <rect x="4" y="4" width="16" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>
                                        <line x1="8" y1="4" x2="8" y2="20" stroke="currentColor" stroke-width="2"/>
                                        <line x1="12" y1="4" x2="12" y2="20" stroke="currentColor" stroke-width="2"/>
                                        <line x1="16" y1="4" x2="16" y2="20" stroke="currentColor" stroke-width="2"/>
                                    </svg>
                                    <svg v-else viewBox="0 0 24 24" fill="currentColor">
                                        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2" fill="none"/>
                                        <circle cx="12" cy="12" r="4" fill="currentColor"/>
                                    </svg>
                                </span>
                            </div>
                            <div class="difficulty-badge" :style="{ background: starColor(diff.diff) }">
                                <span class="difficulty-badge__icon">&#9733;</span>
                                <span class="difficulty-badge__rating">{{ starBadgeText(diff) }}</span>
                            </div>
                            <div class="beatmap-list-item__col--main">
                                <span class="beatmap-list-item__version">{{ diff.version }}</span>
                            </div>
                        </div>
                    </a>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
    // Global shared audio manager for preview playback across cards
    if (!window.__konekoBeatmapAudio) {
        window.__konekoBeatmapAudio = Vue.reactive({
            activeSetId: null,
            isPlaying: false,
            isLoading: false,
            progress: 0,
            element: null
        });
    }

    app.component("mapset-card", {
        template: "#mapset-card",
        props: {
            set: { type: Object, required: true }
        },
        data() {
            return {
                popupVisible: false,
                popupTimer: null
            };
        },
        computed: {
            audioState() {
                return window.__konekoBeatmapAudio;
            },
            isPlaying() {
                return this.audioState.activeSetId === this.set.set_id && this.audioState.isPlaying;
            },
            isLoading() {
                return this.audioState.activeSetId === this.set.set_id && this.audioState.isLoading;
            },
            playProgress() {
                return (this.audioState.activeSetId === this.set.set_id) ? this.audioState.progress : 0;
            },
            playCoverStyle() {
                const url = this.coverUrl(this.set.set_id, "list") || this.coverUrl(this.set.set_id, "card");
                return url ? { backgroundImage: "url(" + url + ")" } : {};
            },
            infoCoverStyle() {
                const url = this.coverUrl(this.set.set_id, "card");
                return url ? { backgroundImage: "url(" + url + ")" } : {};
            },
            statusKey() {
                return String(this.statusName(this.set.status) || "unknown")
                    .toLowerCase()
                    .replace(/[^a-z0-9]+/g, "-");
            },
            statusLabel() {
                const label = this.statusName(this.set.status);
                return String(label || "unknown").toUpperCase();
            },
            downloadUrl() {
                if (this.$koneko && this.$koneko.domain) {
                    return "https://osu." + this.$koneko.domain + "/d/" + this.set.set_id;
                }
                return "/api/v1/beatmapsets/" + this.set.set_id + "/download";
            },
            sorted() {
                return (this.set.difficulties || []).slice().sort((left, right) => {
                    return (left.diff || 0) - (right.diff || 0);
                });
            },
            dots() {
                return this.sorted.slice(0, 10).map(diff => ({
                    color: this.starColor(diff.diff),
                    title: (diff.version || "") + " (" + this.fmtDecimal(diff.diff, 2) + "\u2605)"
                }));
            },
            extra() {
                return Math.max(0, this.sorted.length - 10);
            }
        },
        beforeUnmount() {
            clearTimeout(this.popupTimer);
            if (this.audioState.activeSetId === this.set.set_id) {
                this.stopAudio();
            }
        },
        methods: {
            openPopup() {
                clearTimeout(this.popupTimer);
                this.popupTimer = setTimeout(() => {
                    this.popupVisible = true;
                }, 100);
            },
            closePopup() {
                clearTimeout(this.popupTimer);
                this.popupVisible = false;
            },
            slug(version) {
                return String(version || "")
                    .toLowerCase()
                    .replace(/[^a-z0-9]+/g, "-")
                    .replace(/^-|-$/g, "");
            },
            modeKey(mode) {
                const map = { "0": "osu", "1": "taiko", "2": "fruits", "3": "mania" };
                return map[String(mode)] || "osu";
            },
            fmtCompact(value) {
                const n = Number(value || 0);
                if (n >= 1000000) {
                    return (n / 1000000).toFixed(1).replace(/\.0$/, "") + "M";
                }
                if (n >= 1000) {
                    return (n / 1000).toFixed(1).replace(/\.0$/, "") + "k";
                }
                return String(n);
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
            starBadgeText(diff) {
                const value = Number(diff.diff || 0);
                return value > 0 ? this.fmtDecimal(value, 2) : "-";
            },
            toggleAudio() {
                const state = this.audioState;
                const setId = this.set.set_id;

                if (state.activeSetId === setId && state.isPlaying) {
                    this.pauseAudio();
                    return;
                }

                this.playAudio();
            },
            playAudio() {
                const state = this.audioState;
                const setId = this.set.set_id;

                if (state.element) {
                    state.element.pause();
                    state.element.src = "";
                }

                const audio = new Audio();
                audio.src = "https://b.ppy.sh/preview/" + setId + ".mp3";
                state.element = audio;
                state.activeSetId = setId;
                state.isLoading = true;
                state.isPlaying = false;
                state.progress = 0;

                audio.addEventListener("playing", () => {
                    state.isLoading = false;
                    state.isPlaying = true;
                });

                audio.addEventListener("timeupdate", () => {
                    if (audio.duration) {
                        state.progress = Math.min(1, audio.currentTime / audio.duration);
                    }
                });

                audio.addEventListener("ended", () => {
                    state.isPlaying = false;
                    state.progress = 0;
                    state.activeSetId = null;
                });

                audio.addEventListener("error", () => {
                    state.isLoading = false;
                    state.isPlaying = false;
                    state.progress = 0;
                    state.activeSetId = null;
                });

                audio.play().catch(() => {
                    state.isLoading = false;
                    state.isPlaying = false;
                    state.activeSetId = null;
                });
            },
            pauseAudio() {
                const state = this.audioState;
                if (state.element) {
                    state.element.pause();
                }
                state.isPlaying = false;
            },
            stopAudio() {
                const state = this.audioState;
                if (state.element) {
                    state.element.pause();
                    state.element.src = "";
                    state.element = null;
                }
                state.activeSetId = null;
                state.isPlaying = false;
                state.isLoading = false;
                state.progress = 0;
            }
        }
    });
</script>
