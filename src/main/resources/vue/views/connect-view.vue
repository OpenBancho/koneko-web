<template id="connect-view">
    <div class="page connect-page-wrapper">
        <site-nav></site-nav>

        <koneko-slot name="connect.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <div class="connect-container">
            <!-- Hero Header -->
            <div class="connect-hero-header">
                <div class="connect-badge-pill">🚀 Getting Started</div>
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
                            <span class="status-check">✓</span>
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
                        <div class="connect-timeline">
                            <!-- Substep 2.1 -->
                            <div class="connect-timeline-item">
                                <div class="connect-timeline-marker">2.1</div>
                                <div class="connect-timeline-content">
                                    <h4>Close osu!</h4>
                                    <p class="muted">Make sure the osu! game client is completely closed before launching with custom server arguments.</p>
                                </div>
                            </div>

                            <!-- Substep 2.2 -->
                            <div class="connect-timeline-item">
                                <div class="connect-timeline-marker">2.2</div>
                                <div class="connect-timeline-content">
                                    <h4>Start osu! with the server argument</h4>
                                    <p class="muted">Launch your game client pointing to this server using the command line or a desktop shortcut:</p>
                                    
                                    <div class="connect-terminal-box">
                                        <span class="connect-terminal-prompt">$</span>
                                        <code class="connect-terminal-code">osu!.exe -devserver {{ domain }}</code>
                                        <button class="button button-small button-ghost connect-copy-btn" type="button" @click="copyCommand">
                                            <span v-if="copied">✓ Copied</span>
                                            <span v-else>📋 Copy</span>
                                        </button>
                                    </div>

                                    <div class="connect-tip-box">
                                        <span class="connect-tip-icon">💡</span>
                                        <span class="connect-tip-text">
                                            <strong>Shortcut tip:</strong> Right-click your desktop <code>osu!</code> shortcut &rarr; choose <strong>Properties</strong> &rarr; in the <strong>Target</strong> field add a space and <code>-devserver {{ domain }}</code> at the end.
                                        </span>
                                    </div>
                                </div>
                            </div>

                            <!-- Substep 2.3 -->
                            <div class="connect-timeline-item">
                                <div class="connect-timeline-marker">2.3</div>
                                <div class="connect-timeline-content">
                                    <h4>Log in to activate your account</h4>
                                    <p class="muted">
                                        Enter your registered username and password in the game. Upon your first successful in-game login, your account will be activated and verified automatically!
                                    </p>
                                </div>
                            </div>
                        </div>
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
                        💬 Join our Discord
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
            copied: false
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
            registration() {
                return this.$koneko.registration || { enabled: true };
            },
            discord() {
                return ((this.$koneko.site || {}).links || {}).discord;
            }
        },
        methods: {
            async copyCommand() {
                const cmd = "osu!.exe -devserver " + this.domain;
                try {
                    await navigator.clipboard.writeText(cmd);
                    this.copied = true;
                    setTimeout(() => {
                        this.copied = false;
                    }, 2500);
                } catch (e) {
                    const input = document.createElement("input");
                    input.value = cmd;
                    document.body.appendChild(input);
                    input.select();
                    document.execCommand("copy");
                    document.body.removeChild(input);
                    this.copied = true;
                    setTimeout(() => {
                        this.copied = false;
                    }, 2500);
                }
            }
        },
        created() {
            this.setTitle("How to connect");
        }
    });
</script>
