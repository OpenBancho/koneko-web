<template id="theme-runtime">
    <div class="theme-runtime-container" v-if="theme && isEnabled">
        <!-- Canvas for particle effects (snow, sakura, cyber sparks, bats, synth stars, cloud puffs) -->
        <canvas ref="particleCanvas" class="theme-particle-canvas" v-if="hasParticles" aria-hidden="true"></canvas>

        <!-- Dynamic Injected Elements / Ribbons -->
        <template v-for="el in elements" :key="el.id">
            <!-- Corner Ribbon -->
            <div class="theme-corner-ribbon" v-if="el.type === 'corner-ribbon'"
                :class="'is-' + (el.position || 'top-right')"
                :style="{ color: el.color || '#fff', background: el.bg_color || 'var(--accent)' }">
                <span>{{ el.text }}</span>
            </div>
        </template>
    </div>
</template>

<script>
    /**
     * Universal Dynamic Theme Runtime for KonekoWeb
     *
     * Injects custom CSS variables, custom stylesheets, canvas particle simulations,
     * and interactive companion widgets dynamically without intrusive banners.
     */
    app.component("theme-runtime", {
        template: "#theme-runtime",
        data: () => ({
            widgetOpen: {},
            animId: null,
            particles: []
        }),
        computed: {
            themeState() {
                return (this.$koneko && this.$koneko.theme) || { enabled: false, active: null };
            },
            isEnabled() {
                return Boolean(this.themeState.enabled && this.themeState.active);
            },
            theme() {
                return this.themeState.active;
            },
            elements() {
                return (this.theme && this.theme.elements) || [];
            },
            hasParticles() {
                return this.theme && this.theme.particles && this.theme.particles.type && this.theme.particles.type !== "none";
            }
        },
        watch: {
            theme: {
                handler(newTheme) {
                    this.applyTheme(newTheme);
                },
                deep: true
            }
        },
        methods: {
            applyTheme(theme) {
                this.cleanup();

                if (!theme || !this.isEnabled) {
                    this.removeThemeStyles();
                    return;
                }

                // 1. Apply Root CSS Variables
                const root = document.documentElement;
                if (theme.css_variables) {
                    Object.keys(theme.css_variables).forEach(varName => {
                        root.style.setProperty(varName, theme.css_variables[varName]);
                    });
                }

                // 2. Inject Custom CSS Rules
                let styleEl = document.getElementById("koneko-dynamic-theme-css");
                if (!styleEl) {
                    styleEl = document.createElement("style");
                    styleEl.id = "koneko-dynamic-theme-css";
                    document.head.appendChild(styleEl);
                }
                styleEl.textContent = (theme.custom_css || "") + (theme.css || "");

                // 3. Update Body Classes
                document.body.classList.add("theme-active");
                if (theme.id) {
                    document.body.classList.add("theme-" + theme.id);
                }

                // 4. Initialize Particle Simulation
                if (this.hasParticles) {
                    this.$nextTick(() => {
                        this.initParticles(theme.particles);
                    });
                }

                // 5. Run optional custom JS hook safely
                if (theme.custom_js) {
                    try {
                        const runHook = new Function(theme.custom_js);
                        runHook();
                    } catch (e) {
                        console.warn("[ThemeRuntime] Custom JS execution notice:", e);
                    }
                }
            },

            removeThemeStyles() {
                document.body.classList.remove("theme-active");
                // Remove theme-* classes
                Array.from(document.body.classList).forEach(cls => {
                    if (cls.startsWith("theme-")) {
                        document.body.classList.remove(cls);
                    }
                });

                const styleEl = document.getElementById("koneko-dynamic-theme-css");
                if (styleEl) {
                    styleEl.textContent = "";
                }

                // Reset standard CSS variables
                const root = document.documentElement;
                const standardVars = [
                    "--accent", "--accent-soft", "--accent-dark", "--accent-ink",
                    "--bg", "--bg-alt", "--bg-panel", "--bg-card", "--bg-soft",
                    "--border", "--line", "--radius-lg", "--radius-md", "--radius-sm", "--theme-glow"
                ];
                standardVars.forEach(v => root.style.removeProperty(v));
            },

            toggleWidget(el) {
                this.widgetOpen[el.id] = !this.widgetOpen[el.id];
            },

            closeWidget(id) {
                this.widgetOpen[id] = false;
            },

            initParticles(config) {
                if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                    return;
                }

                const canvas = this.$refs.particleCanvas;
                if (!canvas) return;

                const ctx = canvas.getContext("2d");
                if (!ctx) return;

                let width = (canvas.width = window.innerWidth);
                let height = (canvas.height = window.innerHeight);

                const onResize = () => {
                    if (!this.$refs.particleCanvas) return;
                    width = canvas.width = window.innerWidth;
                    height = canvas.height = window.innerHeight;
                };

                window.addEventListener("resize", onResize);
                this._onResize = onResize;

                const type = config.type || "snow";
                const count = config.count || 40;
                const speed = config.speed || 1.0;
                const color = config.color || "#ffffff";

                this.particles = [];

                for (let i = 0; i < count; i++) {
                    this.particles.push({
                        x: Math.random() * width,
                        y: Math.random() * height,
                        radius: Math.random() * 3 + 1,
                        speedX: (Math.random() - 0.5) * speed,
                        speedY: (Math.random() * 1.5 + 0.5) * speed,
                        opacity: Math.random() * 0.7 + 0.2,
                        rotation: Math.random() * Math.PI * 2,
                        rotSpeed: (Math.random() - 0.5) * 0.03,
                        swing: Math.random() * Math.PI * 2
                    });
                }

                const render = () => {
                    if (!this.$refs.particleCanvas) return;
                    ctx.clearRect(0, 0, width, height);

                    for (let p of this.particles) {
                        p.y += p.speedY;
                        p.swing += 0.02;
                        p.x += Math.sin(p.swing) * (p.speedX + 0.5);
                        p.rotation += p.rotSpeed;

                        if (p.y > height + 20) {
                            p.y = -20;
                            p.x = Math.random() * width;
                        }
                        if (p.x > width + 20) p.x = -20;
                        if (p.x < -20) p.x = width + 20;

                        ctx.save();
                        ctx.translate(p.x, p.y);
                        ctx.rotate(p.rotation);
                        ctx.globalAlpha = p.opacity;

                        if (type === "snow") {
                            // Snowflake circle with soft glow
                            ctx.fillStyle = color;
                            ctx.beginPath();
                            ctx.arc(0, 0, p.radius, 0, Math.PI * 2);
                            ctx.fill();
                        } else if (type === "sakura") {
                            // Sakura Petal shape
                            ctx.fillStyle = color || "#fbcfe8";
                            ctx.beginPath();
                            ctx.ellipse(0, 0, p.radius * 2, p.radius, 0, 0, Math.PI * 2);
                            ctx.fill();
                        } else if (type === "neon-matrix") {
                            // Cyber sparks / matrix glitch blocks
                            ctx.fillStyle = color || "#00f0ff";
                            ctx.fillRect(-p.radius, -p.radius * 2, p.radius * 2, p.radius * 4);
                        } else if (type === "spooky-bats") {
                            // Spooky bat silhouette
                            ctx.fillStyle = color || "#ffa959";
                            ctx.beginPath();
                            ctx.arc(-p.radius * 2, 0, p.radius, 0, Math.PI);
                            ctx.arc(p.radius * 2, 0, p.radius, 0, Math.PI);
                            ctx.lineTo(0, p.radius * 2);
                            ctx.closePath();
                            ctx.fill();
                        } else if (type === "cloud-puffs" || type === "clouds") {
                            // Soft fluffy pastel cloud puff (tri-circle composite with soft highlights)
                            const r = p.radius * 2.5 + 4;
                            ctx.fillStyle = color || "#bae6fd";
                            ctx.beginPath();
                            ctx.arc(-r * 0.4, 0, r * 0.6, 0, Math.PI * 2);
                            ctx.arc(0, -r * 0.3, r * 0.7, 0, Math.PI * 2);
                            ctx.arc(r * 0.4, 0, r * 0.6, 0, Math.PI * 2);
                            ctx.fill();
                            // Inner soft pastel highlight
                            ctx.fillStyle = "#fbcfe8";
                            ctx.globalAlpha = p.opacity * 0.5;
                            ctx.beginPath();
                            ctx.arc(0, 0, r * 0.4, 0, Math.PI * 2);
                            ctx.fill();
                        } else if (type === "pastel-bubbles") {
                            // Glowing pastel bubbles
                            ctx.fillStyle = color || "#fbcfe8";
                            ctx.beginPath();
                            ctx.arc(0, 0, p.radius * 2, 0, Math.PI * 2);
                            ctx.fill();
                            ctx.fillStyle = "#ffffff";
                            ctx.beginPath();
                            ctx.arc(-p.radius * 0.6, -p.radius * 0.6, p.radius * 0.6, 0, Math.PI * 2);
                            ctx.fill();
                        } else {
                            // Synthwave horizon stars
                            ctx.fillStyle = color || "#f472b6";
                            ctx.beginPath();
                            ctx.arc(0, 0, p.radius * 1.5, 0, Math.PI * 2);
                            ctx.fill();
                        }

                        ctx.restore();
                    }

                    this.animId = requestAnimationFrame(render);
                };

                this.animId = requestAnimationFrame(render);
            },

            cleanup() {
                if (this.animId) {
                    cancelAnimationFrame(this.animId);
                    this.animId = null;
                }
                if (this._onResize) {
                    window.removeEventListener("resize", this._onResize);
                    this._onResize = null;
                }
            }
        },
        mounted() {
            if (this.theme && this.isEnabled) {
                this.applyTheme(this.theme);
            }
        },
        unmounted() {
            this.cleanup();
            this.removeThemeStyles();
        }
    });
</script>
