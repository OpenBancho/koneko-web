<template id="theme-runtime">
    <div class="theme-runtime-container" v-if="themeLive">
        <!--
            The theme's own JavaScript, if it has any and it was approved, runs in here.

            sandbox="allow-scripts" without allow-same-origin is the whole mechanism: the
            browser gives this document an opaque origin, so the code inside cannot reach
            parent.document, cannot read the session cookie, and any request it makes leaves
            without the visitor's credentials. Adding allow-same-origin would let it remove
            its own sandbox attribute, which is why the two never appear together.

            Drawn under the page and transparent to clicks, so a theme can decorate but
            cannot put anything in front of a control or collect what somebody types.
        -->
        <iframe v-if="jsLive" ref="jsFrame" class="theme-js-layer" :srcdoc="frameDocument"
            sandbox="allow-scripts" referrerpolicy="no-referrer" tabindex="-1"
            aria-hidden="true" title="Decorative theme layer"></iframe>

        <canvas ref="particleCanvas" class="theme-particle-canvas" v-if="hasParticles"
            aria-hidden="true"></canvas>

        <template v-for="el in elements" :key="el.id">
            <div class="theme-corner-ribbon" v-if="el.type === 'corner-ribbon'"
                :class="'is-' + (el.position || 'top-right')"
                :style="ribbonStyle(el)">
                <span>{{ el.text }}</span>
            </div>
        </template>
    </div>
</template>

<script>
    /**
     * Applies the active event theme: custom properties, a stylesheet, particles, and - when
     * the deployment allows it and a staff member approved it - the theme's own JavaScript.
     *
     * <p>The JavaScript is the reason this component is shaped the way it is. Themes come from
     * another server, so their code is confined to a sandboxed frame with an opaque origin
     * rather than executed on the page. Nothing here calls eval or Function on theme content.
     *
     * <p>The CSS cannot be confined the same way, because styling the real page is what it is
     * for. Two things limit it instead: the server refuses stylesheets carrying @import,
     * remote url() or expression(), and themes are not applied at all on the pages where
     * somebody types a password.
     */
    app.component("theme-runtime", {
        template: "#theme-runtime",
        data: () => ({
            animId: null,
            particles: [],
            resizeHandler: null,
            heartbeatTimer: null,
            lastHeartbeat: 0,
            frameFailed: false
        }),
        computed: {
            themeState() {
                return (this.$koneko && this.$koneko.theme) || { enabled: false, active: null };
            },

            /**
             * Pages where no theme is applied, whatever it contains.
             *
             * A theme's stylesheet can reposition and restyle anything on the page it is
             * applied to. On a page with a password field that is not a cosmetic risk, so
             * these pages keep the stylesheet they shipped with.
             */
            onProtectedPage() {
                const path = (window.location.pathname || "").toLowerCase();

                return path === "/login"
                    || path === "/register"
                    || path.startsWith("/settings")
                    || path.startsWith("/admin/verify")
                    || path.startsWith("/reset");
            },

            themeLive() {
                return Boolean(this.themeState.enabled
                    && this.themeState.active
                    && !this.onProtectedPage);
            },

            theme() {
                return this.themeLive ? this.themeState.active : null;
            },

            /**
             * Whether the frame is built at all.
             *
             * The server strips custom_js unless the deployment allows it and this payload
             * was approved, so its presence here already carries both decisions. jsEnabled is
             * sent alongside and checked too, rather than inferred from the field surviving.
             */
            jsLive() {
                return Boolean(this.themeLive
                    && this.themeState.jsEnabled
                    && this.theme.custom_js
                    && !this.frameFailed);
            },

            elements() {
                return (this.theme && this.theme.elements) || [];
            },

            hasParticles() {
                const particles = this.theme && this.theme.particles;
                return Boolean(particles && particles.type && particles.type !== "none");
            },

            /**
             * The frame's document.
             *
             * The inner CSP is a second boundary rather than a spare one: default-src 'none'
             * removes fetch, XHR, WebSocket and beacon, so code in here cannot send anywhere
             * what it can see.
             *
             * script-src carries 'unsafe-inline' and 'unsafe-eval' because running the theme's
             * code is the point of the frame. Neither weakens anything here: the origin is
             * opaque, so evaluated code has no page, no cookie and no storage to reach, and
             * default-src 'none' still leaves it no way out. What the CSP is actually for is
             * the network, and that part is not relaxed.
             *
             * The theme's code goes in as a JS string literal with every "<" written as an
             * escape, so a closing script tag inside it cannot end the block early and write
             * markup of its own.
             *
             * The same care is needed for this file, and for the same reason. The HTML parser
             * ends a script block at the first closing-script sequence it meets, without
             * caring that it sits inside a string or a comment - so every script tag emitted
             * below is written with a \u003c escape. Spelled out literally, one of them would
             * end this component early and drop the rest of the file onto the page as text.
             */
            frameDocument() {
                const code = JSON.stringify(String(this.theme.custom_js || ""))
                    .replace(/</g, "\\u003c");

                return [
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">",
                    "<meta http-equiv=\"Content-Security-Policy\" content=\"",
                    "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; ",
                    "style-src 'unsafe-inline'; img-src data: blob:; font-src data:\">",
                    "<style>html,body{margin:0;height:100%;overflow:hidden;",
                    "background:transparent;pointer-events:none}</style>",
                    "</head><body>\u003cscript>",
                    "(function(){",
                    // The frame reports in so the parent can tell a hung theme from a
                    // finished one, and asks for the little context it is allowed.
                    "var beat=function(){try{parent.postMessage({type:'theme:alive'},'*')}catch(e){}};",
                    "beat();setInterval(beat,2000);",
                    "try{parent.postMessage({type:'theme:ready'},'*')}catch(e){}",
                    "try{(new Function(", code, "))()}",
                    "catch(e){try{parent.postMessage({type:'theme:error',",
                    "message:String(e&&e.message||e)},'*')}catch(_){}}",
                    "})();",
                    "\u003c/script></body></html>"
                ].join("");
            }
        },
        watch: {
            theme: {
                handler(theme) {
                    this.applyTheme(theme);
                },
                deep: true
            }
        },
        methods: {
            ribbonStyle(el) {
                // Built here rather than bound straight from the theme, so a colour field
                // cannot carry a url() or a second declaration into the style attribute.
                return {
                    color: this.safeColor(el.color, "#fff"),
                    background: this.safeColor(el.bg_color, "var(--accent)")
                };
            },

            safeColor(value, fallback) {
                if (typeof value !== "string") {
                    return fallback;
                }

                return /^(#[0-9a-f]{3,8}|[a-z-]+|(rgb|hsl)a?\([\d\s.,%/-]+\))$/i.test(value.trim())
                    ? value.trim()
                    : fallback;
            },

            applyTheme(theme) {
                this.cleanup();

                if (!theme || !this.themeLive) {
                    this.removeThemeStyles();
                    return;
                }

                this.applyVariables(theme);
                this.applyStylesheet(theme);
                this.applyBodyClasses(theme);

                if (this.hasParticles) {
                    this.$nextTick(() => this.initParticles(theme.particles));
                }

                if (this.jsLive) {
                    this.startFrameWatchdog();
                }
            },

            applyVariables(theme) {
                const variables = theme.css_variables;

                if (!variables) {
                    return;
                }

                const root = document.documentElement;

                // Recorded as they are set, so removeThemeStyles can undo exactly this
                // theme rather than a hardcoded list that drifts out of step with it.
                this.appliedVariables = [];

                Object.keys(variables).forEach(name => {
                    if (!/^--[a-zA-Z0-9-]+$/.test(name)) {
                        return;
                    }

                    root.style.setProperty(name, variables[name]);
                    this.appliedVariables.push(name);
                });
            },

            applyStylesheet(theme) {
                const css = String(theme.custom_css || "") + String(theme.css || "");

                let styleEl = document.getElementById("koneko-dynamic-theme-css");

                if (!styleEl) {
                    styleEl = document.createElement("style");
                    styleEl.id = "koneko-dynamic-theme-css";
                    document.head.appendChild(styleEl);
                }

                // textContent, never innerHTML: this is a stylesheet, and it stays one.
                styleEl.textContent = css;
            },

            applyBodyClasses(theme) {
                document.body.classList.add("theme-active");

                if (typeof theme.id === "string" && /^[\w-]{1,64}$/.test(theme.id)) {
                    document.body.classList.add("theme-" + theme.id);
                }
            },

            removeThemeStyles() {
                document.body.classList.remove("theme-active");

                Array.from(document.body.classList).forEach(name => {
                    if (name.indexOf("theme-") === 0) {
                        document.body.classList.remove(name);
                    }
                });

                const styleEl = document.getElementById("koneko-dynamic-theme-css");

                if (styleEl) {
                    styleEl.textContent = "";
                }

                const root = document.documentElement;

                (this.appliedVariables || []).forEach(name => root.style.removeProperty(name));
                this.appliedVariables = [];
            },

            /**
             * Messages from the frame.
             *
             * The sender is checked by comparing contentWindow, not by origin: a sandboxed
             * frame's origin is the string "null", which any other sandboxed frame on the
             * page would also present. Anything that is not one of the three known types is
             * dropped without a reply.
             */
            onFrameMessage(event) {
                const frame = this.$refs.jsFrame;

                if (!frame || event.source !== frame.contentWindow) {
                    return;
                }

                const type = event.data && event.data.type;

                if (type === "theme:alive") {
                    this.lastHeartbeat = Date.now();
                    return;
                }

                if (type === "theme:ready") {
                    this.lastHeartbeat = Date.now();

                    // Only what a decoration needs, and nothing that identifies anybody:
                    // no user id, no username, nothing out of the session.
                    frame.contentWindow.postMessage({
                        type: "theme:context",
                        page: window.location.pathname,
                        viewport: { width: window.innerWidth, height: window.innerHeight },
                        reducedMotion: this.prefersReducedMotion()
                    }, "*");
                    return;
                }

                if (type === "theme:error") {
                    console.warn("[theme] the theme's script reported:",
                        String(event.data.message || "").slice(0, 200));
                }
            },

            /**
             * Drops the frame when the theme's code stops responding.
             *
             * A sandbox limits what code can reach, not how long it can run, so a theme with
             * a runaway loop is the one failure the origin boundary does not cover. Two
             * missed heartbeats and the frame goes; the rest of the theme stays.
             */
            startFrameWatchdog() {
                this.lastHeartbeat = Date.now();

                this.heartbeatTimer = window.setInterval(() => {
                    if (Date.now() - this.lastHeartbeat < 6000) {
                        return;
                    }

                    console.warn("[theme] the theme's script stopped responding; removing it.");
                    this.frameFailed = true;
                    this.stopFrameWatchdog();
                }, 3000);
            },

            stopFrameWatchdog() {
                if (this.heartbeatTimer) {
                    window.clearInterval(this.heartbeatTimer);
                    this.heartbeatTimer = null;
                }
            },

            prefersReducedMotion() {
                return Boolean(window.matchMedia
                    && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
            },

            initParticles(config) {
                if (this.prefersReducedMotion()) {
                    return;
                }

                const canvas = this.$refs.particleCanvas;

                if (!canvas) {
                    return;
                }

                const ctx = canvas.getContext("2d");

                if (!ctx) {
                    return;
                }

                let width = canvas.width = window.innerWidth;
                let height = canvas.height = window.innerHeight;

                this.resizeHandler = () => {
                    if (!this.$refs.particleCanvas) {
                        return;
                    }

                    width = canvas.width = window.innerWidth;
                    height = canvas.height = window.innerHeight;
                };

                window.addEventListener("resize", this.resizeHandler);

                const type = config.type || "snow";
                const color = this.safeColor(config.color, "#ffffff");

                // Clamped on the server too. Repeated here because this is the number that
                // decides how much work every frame does, and a browser that has been handed
                // a bad one has already stopped responding by the time anything else notices.
                const count = Math.min(300, Math.max(1, Number(config.count) || 40));
                const speed = Math.min(10, Math.max(0, Number(config.speed) || 1));

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
                    if (!this.$refs.particleCanvas) {
                        return;
                    }

                    ctx.clearRect(0, 0, width, height);

                    for (const p of this.particles) {
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
                        ctx.fillStyle = color;

                        this.drawParticle(ctx, type, p, color);

                        ctx.restore();
                    }

                    this.animId = requestAnimationFrame(render);
                };

                this.animId = requestAnimationFrame(render);
            },

            drawParticle(ctx, type, p, color) {
                if (type === "sakura") {
                    ctx.beginPath();
                    ctx.ellipse(0, 0, p.radius * 2, p.radius, 0, 0, Math.PI * 2);
                    ctx.fill();
                    return;
                }

                if (type === "neon-matrix") {
                    ctx.fillRect(-p.radius, -p.radius * 2, p.radius * 2, p.radius * 4);
                    return;
                }

                if (type === "spooky-bats") {
                    ctx.beginPath();
                    ctx.arc(-p.radius * 2, 0, p.radius, 0, Math.PI);
                    ctx.arc(p.radius * 2, 0, p.radius, 0, Math.PI);
                    ctx.lineTo(0, p.radius * 2);
                    ctx.closePath();
                    ctx.fill();
                    return;
                }

                if (type === "cloud-puffs" || type === "clouds") {
                    const r = p.radius * 2.5 + 4;

                    ctx.beginPath();
                    ctx.arc(-r * 0.4, 0, r * 0.6, 0, Math.PI * 2);
                    ctx.arc(0, -r * 0.3, r * 0.7, 0, Math.PI * 2);
                    ctx.arc(r * 0.4, 0, r * 0.6, 0, Math.PI * 2);
                    ctx.fill();
                    return;
                }

                if (type === "pastel-bubbles") {
                    ctx.beginPath();
                    ctx.arc(0, 0, p.radius * 2, 0, Math.PI * 2);
                    ctx.fill();
                    ctx.fillStyle = "#ffffff";
                    ctx.beginPath();
                    ctx.arc(-p.radius * 0.6, -p.radius * 0.6, p.radius * 0.6, 0, Math.PI * 2);
                    ctx.fill();
                    return;
                }

                // snow, and anything the theme names that this build does not draw.
                ctx.beginPath();
                ctx.arc(0, 0, p.radius, 0, Math.PI * 2);
                ctx.fill();
            },

            cleanup() {
                if (this.animId) {
                    cancelAnimationFrame(this.animId);
                    this.animId = null;
                }

                if (this.resizeHandler) {
                    window.removeEventListener("resize", this.resizeHandler);
                    this.resizeHandler = null;
                }

                this.stopFrameWatchdog();
            }
        },
        mounted() {
            this.appliedVariables = [];
            window.addEventListener("message", this.onFrameMessage);

            if (this.themeLive) {
                this.applyTheme(this.theme);
            }
        },
        unmounted() {
            window.removeEventListener("message", this.onFrameMessage);
            this.cleanup();
            this.removeThemeStyles();
        }
    });
</script>
