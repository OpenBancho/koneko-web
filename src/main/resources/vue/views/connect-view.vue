<template id="connect-view">
    <div class="page connect-page-wrapper">
        <site-nav></site-nav>

        <koneko-slot name="connect.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <div class="connect-container">
            <!-- Hero Header -->
            <div class="connect-hero-header">
                <div class="connect-badge-pill"><i class="fas fa-flag-checkered" aria-hidden="true"></i> Getting Started</div>
                <h1 class="connect-main-title">How to connect to {{ site.name }}</h1>
                <p class="connect-main-subtitle">
                    Follow these two simple steps to set up your game client, register your account, and start competing on the leaderboards.
                </p>
            </div>

            <!-- Steps List -->
            <div class="connect-cards-stack">
                <!-- Step 1: Registration -->
                <section class="card connect-step-panel">
                    <div class="connect-step-head">
                        <div class="connect-step-badge">1</div>
                        <div class="connect-step-head-text">
                            <h2>Register on the website</h2>
                            <p class="muted small">Create your player profile. Your credentials will be used to log in both on the website and inside the game.</p>
                        </div>
                    </div>

                    <div class="connect-step-body">
                        <div class="connect-action-row" v-if="!user">
                            <a class="button button-primary" href="/register" v-if="registration.enabled">
                                Create Account
                            </a>
                            <a class="button button-ghost" href="/login">
                                Already have an account? Log in
                            </a>
                        </div>
                        <div class="connect-logged-status" v-else>
                            <span class="status-check"><i class="fas fa-check-circle" aria-hidden="true"></i></span>
                            <span>You are currently signed in as <strong>{{ user.name }}</strong></span>
                        </div>
                    </div>
                </section>

                <!-- Step 2: Verification / Game Setup -->
                <section class="card connect-step-panel">
                    <div class="connect-step-head">
                        <div class="connect-step-badge">2</div>
                        <div class="connect-step-head-text">
                            <h2>Verify your account by logging into the game</h2>
                            <p class="muted small">Activate your account and link your stats by connecting your osu! client to {{ site.name }}.</p>
                        </div>
                    </div>

                    <div class="connect-step-body">
                        <p class="muted small connect-pick-hint" v-if="lazerEnabled">Pick the client you play on. Either one verifies the account on its first successful login.</p>

                        <!-- osu! stable -->
                        <details class="connect-spoiler" :class="{ 'connect-spoiler--single': !lazerEnabled }" :open="!lazerEnabled ? true : null">
                            <summary class="connect-spoiler-summary">
                                <span class="connect-spoiler-icon"><i class="fas fa-gamepad" aria-hidden="true"></i></span>
                                <span class="connect-spoiler-title">
                                    <strong>Stable connect</strong>
                                    <small>The classic osu! client, started with a server argument</small>
                                </span>
                                <i class="fas fa-chevron-down connect-spoiler-caret" aria-hidden="true"></i>
                            </summary>

                            <div class="connect-spoiler-body">
                                <div class="connect-timeline">
                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">1</div>
                                        <div class="connect-timeline-content">
                                            <h4>Close osu!</h4>
                                            <p class="muted">Make sure the osu! game client is completely closed before launching with custom server arguments.</p>
                                        </div>
                                    </div>

                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">2</div>
                                        <div class="connect-timeline-content">
                                            <h4>Start osu! with the server argument</h4>
                                            <p class="muted">Launch your game client pointing to this server using the command line or a desktop shortcut:</p>

                                            <div class="connect-terminal-box">
                                                <span class="connect-terminal-prompt">$</span>
                                                <code class="connect-terminal-code">osu!.exe -devserver {{ domain }}</code>
                                                <button class="button button-small button-ghost connect-copy-btn" type="button" @click="copyText('osu!.exe -devserver ' + domain, 'stable')">
                                                    <span v-if="copied === 'stable'"><i class="fas fa-check" aria-hidden="true"></i> Copied</span>
                                                    <span v-else><i class="far fa-copy" aria-hidden="true"></i> Copy</span>
                                                </button>
                                            </div>

                                            <div class="connect-tip-box">
                                                <span class="connect-tip-icon"><i class="fas fa-lightbulb" aria-hidden="true"></i></span>
                                                <span class="connect-tip-text">
                                                    <strong>Shortcut tip:</strong> Right-click your desktop <code>osu!</code> shortcut &rarr; choose <strong>Properties</strong> &rarr; in the <strong>Target</strong> field add a space and <code>-devserver {{ domain }}</code> at the end.
                                                </span>
                                            </div>
                                        </div>
                                    </div>

                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">3</div>
                                        <div class="connect-timeline-content">
                                            <h4>Log in to activate your account</h4>
                                            <p class="muted">
                                                Enter your registered username and password in the game. Upon your first successful in-game login, your account will be activated and verified automatically!
                                            </p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </details>

                        <!-- osu!lazer -->
                        <details class="connect-spoiler" v-if="lazerEnabled">
                            <summary class="connect-spoiler-summary">
                                <span class="connect-spoiler-icon"><i class="fas fa-bolt" aria-hidden="true"></i></span>
                                <span class="connect-spoiler-title">
                                    <strong>Lazer connect</strong>
                                    <small>The osu!lazer based client (jarsu)</small>
                                </span>
                                <i class="fas fa-chevron-down connect-spoiler-caret" aria-hidden="true"></i>
                            </summary>

                            <div class="connect-spoiler-body">
                                <div class="connect-timeline">
                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">1</div>
                                        <div class="connect-timeline-content">
                                            <h4>Download the lazer client</h4>
                                            <p class="muted">Regular osu!lazer cannot connect to a private server. Use <strong>jarsu</strong>, a build made for it. Only the Windows version is available for now.</p>

                                            <div class="connect-download-row">
                                                <a class="button" :href="lazerDownload" target="_blank" rel="noopener">
                                                    <i class="fas fa-download" aria-hidden="true"></i> Download jarsu for Windows
                                                </a>
                                                <span class="muted small">win-x64-jarsu.zip, from GitHub Releases</span>
                                            </div>
                                        </div>
                                    </div>

                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">2</div>
                                        <div class="connect-timeline-content">
                                            <h4>Unpack and start it</h4>
                                            <p class="muted">Extract the archive into a folder of its own and run the game from there. It keeps its data apart from a regular osu!lazer install.</p>
                                        </div>
                                    </div>

                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">3</div>
                                        <div class="connect-timeline-content">
                                            <h4>Point it at this server</h4>
                                            <p class="muted">Open the game settings and set the server endpoint to the address of this server:</p>

                                            <div class="connect-terminal-box">
                                                <span class="connect-terminal-prompt"><i class="fas fa-link" aria-hidden="true"></i></span>
                                                <code class="connect-terminal-code">{{ domain }}</code>
                                                <button class="button button-small button-ghost connect-copy-btn" type="button" @click="copyText(domain, 'lazer')">
                                                    <span v-if="copied === 'lazer'"><i class="fas fa-check" aria-hidden="true"></i> Copied</span>
                                                    <span v-else><i class="far fa-copy" aria-hidden="true"></i> Copy</span>
                                                </button>
                                            </div>
                                        </div>
                                    </div>

                                    <div class="connect-timeline-item">
                                        <div class="connect-timeline-marker">4</div>
                                        <div class="connect-timeline-content">
                                            <h4>Log in to activate your account</h4>
                                            <p class="muted">
                                                Sign in with the username and password you registered on the website. The first successful login activates and verifies the account, the same as in the stable client.
                                            </p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </details>
                    </div>
                </section>
            </div>

            <!-- Help / Discord Support Banner -->
            <div class="card connect-support-card" v-if="discord">
                <div class="connect-support-inner">
                    <div class="connect-support-text">
                        <h3>Need help connecting?</h3>
                        <p class="muted">Our friendly community and staff team are available 24/7 on Discord to help you troubleshoot.</p>
                    </div>
                    <a class="button button-ghost connect-support-btn" :href="discord" target="_blank" rel="noopener">
                        <i class="fab fa-discord" aria-hidden="true"></i> Join our Discord
                    </a>
                </div>
            </div>
        </div>

        <koneko-slot name="page.bottom"></koneko-slot>
        <koneko-slot name="connect.bottom"></koneko-slot>

        <site-footer></site-footer>
    </div>
</template>

<script>
    app.component("connect-view", {
        template: "#connect-view",
        data: () => ({
            copied: "",
            lazerDownload: "https://github.com/OpenBancho/jarsu/releases/tag/main"
        }),
        computed: {
            site() {
                return this.$koneko.site || {};
            },
            domain() {
                return this.$koneko.domain || "sancochat.fun";
            },
            user() {
                return this.$koneko.user;
            },
            lazerEnabled() {
                return !!this.$koneko.lazerEnabled;
            },
            registration() {
                return this.$koneko.registration || { enabled: true };
            },
            discord() {
                return ((this.$koneko.site || {}).links || {}).discord;
            }
        },
        methods: {
            async copyText(text, key) {
                const done = () => {
                    this.copied = key;
                    setTimeout(() => {
                        if (this.copied === key) this.copied = "";
                    }, 2500);
                };

                try {
                    await navigator.clipboard.writeText(text);
                } catch (e) {
                    const input = document.createElement("input");
                    input.value = text;
                    document.body.appendChild(input);
                    input.select();
                    document.execCommand("copy");
                    document.body.removeChild(input);
                }

                done();
            }
        },
        created() {
            this.setTitle("How to connect");
        }
    });
</script>
