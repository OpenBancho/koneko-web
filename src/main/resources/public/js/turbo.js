/*
 * Turbo navigation for koneko-web.
 *
 * Following a link between the public pages does not reload the document. The shell (Vue, the shared components, the
 * stylesheets, the theme) stays; only the page component is swapped:
 *
 *   1. the click (or, earlier, the hover) asks the server for the target page as data - the same address, with the
 *      X-Koneko-Turbo header - and gets back the name of the page component plus whatever components are new;
 *   2. the new components are registered, the address is pushed to the history, and the root component switches view.
 *
 * Anything this script is unsure about falls back to an ordinary page load, so the site works exactly as before for
 * everything it does not handle: the address is not in the list below, the server answered something that is not a
 * page, the request failed, a modifier key was held, the link opens in a new tab, and so on.
 *
 * A browser reload is not a turbo visit: the document is loaded afresh and layout.html drops the cached scores then.
 */
(function () {
    "use strict";

    var root = window.__konekoRoot;

    if (!root || !window.fetch || !window.history || !window.history.pushState || !window.URL) {
        return;
    }

    // Public browsing pages only. Login, registration, settings, verification and the staff panel stay ordinary loads:
    // they change who the visitor is (the session in the page state), or carry gates of their own.
    var ALLOWED = [
        /^\/$/,
        /^\/u\/[^/]+\/?$/,
        /^\/me\/?$/,
        /^\/leaderboard\/?$/,
        /^\/beatmaps\/?$/,
        /^\/beatmapsets\/\d+\/?$/,
        /^\/scores\/\d+\/?$/,
        /^\/players\/?$/,
        /^\/connect\/?$/,
        /^\/restrictions\/?$/
    ];

    // How long a fetched page may be reused by a click that follows a hover.
    var PAGE_TTL_MS = 30000;
    var HOVER_DELAY_MS = 70;
    var PROGRESS_DELAY_MS = 120;

    var pages = new Map();
    var current = window.location.pathname + window.location.search;
    var hoverTimer = null;
    var navigation = 0;

    function eligible(url) {
        if (url.origin !== window.location.origin) return false;

        return ALLOWED.some(function (pattern) { return pattern.test(url.pathname); });
    }

    function load(url) {
        var key = url.pathname + url.search;
        var hit = pages.get(key);

        if (hit && Date.now() - hit.at < PAGE_TTL_MS) return hit.promise;

        var promise = fetch(url.pathname + url.search, {
            headers: { "X-Koneko-Turbo": "1", "Accept": "application/json" },
            credentials: "same-origin"
        }).then(function (response) {
            var type = response.headers.get("content-type") || "";

            // Redirected to a page that is not served as data (login, verification, another origin): not ours.
            if (type.indexOf("application/json") === -1) return null;

            var finalUrl = new URL(response.url, window.location.href);

            if (finalUrl.origin !== window.location.origin || !eligible(finalUrl)) return null;

            return response.json().then(function (data) {
                if (!data || !data.view || typeof data.html !== "string") return null;

                data.url = finalUrl;
                return data;
            });
        }).catch(function () {
            return null;
        });

        pages.set(key, { at: Date.now(), promise: promise });

        promise.then(function (data) {
            if (!data) pages.delete(key);
        });

        return promise;
    }

    /**
     * Registers the components of a page that the shell does not have yet. Each .vue file arrives as a template
     * element followed by its script (and possibly a style); a file whose template is already in the document is
     * skipped as a whole.
     */
    function register(html) {
        var holder = document.createElement("template");
        holder.innerHTML = html;

        var skipping = false;

        Array.prototype.forEach.call(holder.content.children, function (node) {
            var tag = node.tagName;

            if (tag === "TEMPLATE" && node.id) {
                skipping = !!document.getElementById(node.id);

                if (!skipping) document.body.appendChild(document.importNode(node, true));
                return;
            }

            if (skipping) return;

            if (tag === "SCRIPT") {
                // A script created by hand runs when it is attached, in the page's global scope - where `app` is.
                var script = document.createElement("script");
                script.textContent = node.textContent;
                document.body.appendChild(script);
                document.body.removeChild(script);
            } else if (tag === "STYLE") {
                document.head.appendChild(document.importNode(node, true));
            }
        });
    }

    // A thin bar along the top while a page is on its way; it only shows when the wait is noticeable.
    var bar = null;
    var barTimer = null;

    function progress(on) {
        clearTimeout(barTimer);

        if (!on) {
            if (bar) bar.classList.remove("turbo-progress-on");
            return;
        }

        barTimer = setTimeout(function () {
            if (!bar) {
                bar = document.createElement("div");
                bar.className = "turbo-progress";
                bar.setAttribute("aria-hidden", "true");
                document.body.appendChild(bar);
            }

            bar.classList.add("turbo-progress-on");
        }, PROGRESS_DELAY_MS);
    }

    function fallback(url) {
        progress(false);
        window.location.href = url.href;
    }

    function visit(url, push) {
        var ticket = ++navigation;

        progress(true);

        return load(url).then(function (data) {
            // A newer click superseded this one while it was loading.
            if (ticket !== navigation) return;

            if (!data) {
                fallback(url);
                return;
            }

            try {
                register(data.html);
            } catch (e) {
                console.warn("[turbo] a component failed to register, loading the page normally", e);
                fallback(url);
                return;
            }

            // The address first: the page components read it when they are created.
            var target = data.url;
            var next = target.pathname + target.search + (url.hash || "");

            if (push) window.history.pushState({ turbo: true }, "", next);

            current = target.pathname + target.search;

            if (push) window.scrollTo(0, 0);

            root.view = data.view;
            // A new key rebuilds the component even when the view is the same one (one profile to the next).
            root.navKey++;

            progress(false);
        });
    }

    document.addEventListener("click", function (event) {
        if (event.defaultPrevented || event.button !== 0
            || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

        var link = event.target && event.target.closest ? event.target.closest("a[href]") : null;

        if (!link) return;
        if (link.target && link.target !== "_self") return;
        if (link.hasAttribute("download") || link.getAttribute("data-turbo") === "off") return;

        var url = new URL(link.href, window.location.href);

        if (!eligible(url)) return;

        // Same page, only the fragment differs: the browser scrolls to it by itself.
        if (url.pathname + url.search === current && url.hash) return;

        event.preventDefault();
        visit(url, true);
    });

    // Warm the page up while the pointer is on its way to the click.
    document.addEventListener("mouseover", function (event) {
        var link = event.target && event.target.closest ? event.target.closest("a[href]") : null;

        if (!link || link.getAttribute("data-turbo") === "off") return;

        var url = new URL(link.href, window.location.href);

        if (!eligible(url) || url.pathname + url.search === current) return;

        clearTimeout(hoverTimer);
        hoverTimer = setTimeout(function () { load(url); }, HOVER_DELAY_MS);
    }, { passive: true });

    document.addEventListener("mouseout", function () {
        clearTimeout(hoverTimer);
    }, { passive: true });

    document.addEventListener("touchstart", function (event) {
        var link = event.target && event.target.closest ? event.target.closest("a[href]") : null;

        if (!link) return;

        var url = new URL(link.href, window.location.href);

        if (eligible(url) && url.pathname + url.search !== current) load(url);
    }, { passive: true });

    // Back and forward between pages this script rendered (or the first, ordinary one).
    window.addEventListener("popstate", function () {
        var url = new URL(window.location.href);

        // Only the fragment moved (the beatmap page's difficulty tabs): the page handles that itself.
        if (url.pathname + url.search === current) return;

        if (!eligible(url)) {
            window.location.reload();
            return;
        }

        visit(url, false);
    });
})();
