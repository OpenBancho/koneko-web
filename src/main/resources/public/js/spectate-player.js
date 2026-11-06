/*
 * Live spectator for koneko-web: draws a play in the browser from the player's replay frames.
 *
 * Nothing is video here. The page gets the .osu of the map and, as server-sent events, the cursor frames the game
 * client streams (stable through bancho.jar, lazer through lazer.jar, both in the same JSON shape). This file parses
 * the map the way bancho.jar's replay renderer does (OsuMap / SliderCurve, ported), and draws circles, sliders,
 * spinners, the cursor, the keys and the score on a canvas, a couple of seconds behind the player so frames that
 * arrive in bursts still play back smoothly.
 *
 *   const player = new KonekoSpectate.Player(canvas);
 *   player.setMap(KonekoSpectate.parseOsu(text), { mods: ["HR"], rate: 1, mode: 0, source: "stable" });
 *   player.pushFrames(bundle);   // every "frames" event
 *   player.destroy();
 *
 * Only osu! (standard) is drawn as a playfield; the other modes show the keys and the score.
 */
(function () {
    "use strict";

    // ------------------------------------------------------------------ mods

    var MOD_BITS = [
        [1, "NF"], [2, "EZ"], [4, "TD"], [8, "HD"], [16, "HR"], [32, "SD"], [64, "DT"], [128, "RX"],
        [256, "HT"], [512, "NC"], [1024, "FL"], [2048, "AT"], [4096, "SO"], [8192, "AP"], [16384, "PF"],
        [1048576, "FI"], [4194304, "CN"], [536870912, "V2"], [1073741824, "MR"]
    ];

    function modsFromBits(bits) {
        var out = [];
        bits = Number(bits) || 0;

        MOD_BITS.forEach(function (m) {
            if (bits & m[0]) out.push(m[1]);
        });

        // NC carries the DT bit, PF the SD bit: show the stronger one only
        if (out.indexOf("NC") >= 0) out.splice(out.indexOf("DT"), out.indexOf("DT") >= 0 ? 1 : 0);
        if (out.indexOf("PF") >= 0) out.splice(out.indexOf("SD"), out.indexOf("SD") >= 0 ? 1 : 0);

        return out;
    }

    function rateOf(mods) {
        if (mods.indexOf("DT") >= 0 || mods.indexOf("NC") >= 0) return 1.5;
        if (mods.indexOf("HT") >= 0 || mods.indexOf("DC") >= 0) return 0.75;
        return 1;
    }

    // ------------------------------------------------------------------ slider curves (SliderCurve.java)

    function same(a, b) {
        return a[0] === b[0] && a[1] === b[1];
    }

    function addAll(out, add) {
        for (var i = 0; i < add.length; i++) {
            if (!out.length || !same(out[out.length - 1], add[i])) out.push(add[i]);
        }
    }

    function sampleBezier(c) {
        var len = 0;
        for (var i = 1; i < c.length; i++) len += Math.hypot(c[i][0] - c[i - 1][0], c[i][1] - c[i - 1][1]);

        var steps = Math.max(8, Math.min(200, Math.floor(len / 4)));
        var n = c.length;
        var bx = new Array(n), by = new Array(n);
        var out = [];

        for (var s = 0; s <= steps; s++) {
            var t = s / steps;

            for (var j = 0; j < n; j++) {
                bx[j] = c[j][0];
                by[j] = c[j][1];
            }

            for (var k = 1; k < n; k++) {
                for (var m = 0; m < n - k; m++) {
                    bx[m] += (bx[m + 1] - bx[m]) * t;
                    by[m] += (by[m + 1] - by[m]) * t;
                }
            }

            out.push([bx[0], by[0]]);
        }

        return out;
    }

    // a bezier slider is several curves joined where a point is written twice
    function bezier(pts) {
        var out = [];
        var run = [];

        for (var i = 0; i < pts.length; i++) {
            run.push(pts[i]);
            var last = i === pts.length - 1;

            if (last || same(pts[i], pts[i + 1])) {
                addAll(out, run.length === 2 ? run : sampleBezier(run));
                run = [];
            }
        }

        return out;
    }

    function perfect(p) {
        var a = p[0], b = p[1], c = p[2];
        var d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));

        if (Math.abs(d) < 1e-6) return bezier(p);

        var a2 = a[0] * a[0] + a[1] * a[1];
        var b2 = b[0] * b[0] + b[1] * b[1];
        var c2 = c[0] * c[0] + c[1] * c[1];
        var cx = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d;
        var cy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d;
        var r = Math.hypot(a[0] - cx, a[1] - cy);

        var t0 = Math.atan2(a[1] - cy, a[0] - cx);
        var t1 = Math.atan2(b[1] - cy, b[0] - cx);
        var t2 = Math.atan2(c[1] - cy, c[0] - cx);
        var twoPi = 2 * Math.PI;
        var ccwToC = ((t2 - t0) % twoPi + twoPi) % twoPi;
        var ccwToB = ((t1 - t0) % twoPi + twoPi) % twoPi;
        var span = ccwToB <= ccwToC ? ccwToC : ccwToC - twoPi;

        var steps = Math.max(8, Math.floor(Math.abs(span) * r / 4));
        var out = [];

        for (var i = 0; i <= steps; i++) {
            var t = t0 + span * i / steps;
            out.push([cx + r * Math.cos(t), cy + r * Math.sin(t)]);
        }

        return out;
    }

    function catmull(pts) {
        var out = [];

        for (var i = 0; i < pts.length - 1; i++) {
            var p0 = pts[Math.max(i - 1, 0)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(i + 2, pts.length - 1)];

            for (var s = 0; s <= 20; s++) {
                var t = s / 20, t2 = t * t, t3 = t2 * t;
                out.push([
                    0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                        + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
                    0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                        + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
                ]);
            }
        }

        return out;
    }

    function flatten(kind, pts) {
        if (pts.length < 2) return pts.slice();
        if (kind === "L") return pts.slice();
        if (kind === "P") return pts.length === 3 ? perfect(pts) : bezier(pts);
        if (kind === "C") return catmull(pts);
        return bezier(pts);
    }

    // cut to the slider's length, or extend the last segment when the curve is shorter
    function fitLength(poly, length) {
        if (!poly.length) return [];

        var out = [poly[0]];
        var acc = 0;

        for (var i = 1; i < poly.length; i++) {
            var a = poly[i - 1], b = poly[i];
            var seg = Math.hypot(b[0] - a[0], b[1] - a[1]);

            if (acc + seg >= length) {
                var t = seg <= 0 ? 0 : (length - acc) / seg;
                out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
                return out;
            }

            acc += seg;
            out.push(b);
        }

        if (acc < length && out.length >= 2) {
            var p = out[out.length - 2], q = out[out.length - 1];
            var sg = Math.hypot(q[0] - p[0], q[1] - p[1]);

            if (sg > 0) {
                var k = (length - acc) / sg;
                out.push([q[0] + (q[0] - p[0]) * k, q[1] + (q[1] - p[1]) * k]);
            }
        }

        return out;
    }

    // ------------------------------------------------------------------ the beatmap (OsuMap.java)

    var CIRCLE = 0, SLIDER = 1, SPINNER = 2;

    function kv(line) {
        var i = line.indexOf(":");
        return i < 0 ? [line, ""] : [line.substring(0, i).trim(), line.substring(i + 1).trim()];
    }

    function parseOsu(text) {
        var m = {
            title: "", artist: "", version: "", creator: "", audioFile: "",
            hp: 5, cs: 5, od: 5, ar: null, sliderMultiplier: 1.4, sliderTickRate: 1, stackLeniency: 0.7,
            mode: 0, formatVersion: 14, objects: [], comboColors: [], ownColors: false, breaks: [], timing: []
        };
        var section = "";
        var lines = String(text || "").split(/\r?\n/);

        var first = (lines[0] || "").match(/osu file format v(\d+)/);
        if (first) m.formatVersion = Number(first[1]);

        for (var li = 0; li < lines.length; li++) {
            var line = lines[li].trim();

            if (!line || line.indexOf("//") === 0) continue;

            if (line[0] === "[" && line[line.length - 1] === "]") {
                section = line.substring(1, line.length - 1);
                continue;
            }

            try {
                var p;

                if (section === "General") {
                    p = kv(line);
                    if (p[0] === "AudioFilename") m.audioFile = p[1];
                    else if (p[0] === "StackLeniency") m.stackLeniency = Number(p[1]);
                    else if (p[0] === "Mode") m.mode = Number(p[1]);
                } else if (section === "Metadata") {
                    p = kv(line);
                    if (p[0] === "Title") m.title = p[1];
                    else if (p[0] === "Artist") m.artist = p[1];
                    else if (p[0] === "Creator") m.creator = p[1];
                    else if (p[0] === "Version") m.version = p[1];
                } else if (section === "Difficulty") {
                    p = kv(line);
                    var v = Number(p[1]);
                    if (p[0] === "HPDrainRate") m.hp = v;
                    else if (p[0] === "CircleSize") m.cs = v;
                    else if (p[0] === "OverallDifficulty") m.od = v;
                    else if (p[0] === "ApproachRate") m.ar = v;
                    else if (p[0] === "SliderMultiplier") m.sliderMultiplier = v;
                    else if (p[0] === "SliderTickRate") m.sliderTickRate = v;
                } else if (section === "Events") {
                    p = line.split(",");
                    if (p.length >= 3 && (p[0] === "2" || p[0] === "Break")) m.breaks.push([Number(p[1]), Number(p[2])]);
                } else if (section === "TimingPoints") {
                    p = line.split(",");
                    var bl = Number(p[1]);
                    var red = p.length < 7 || p[6].trim() === "1";
                    m.timing.push({ time: Number(p[0]), beatLength: bl, red: red });
                } else if (section === "Colours") {
                    p = kv(line);
                    if (p[0].indexOf("Combo") === 0) {
                        var c = p[1].split(",").map(Number);
                        m.comboColors.push("rgb(" + c[0] + "," + c[1] + "," + c[2] + ")");
                        m.ownColors = true;
                    }
                } else if (section === "HitObjects") {
                    hitObject(m, line);
                }
            } catch (e) {
                // one malformed line must not lose the whole map
            }
        }

        // maps older than v8 have no approach rate: it is the overall difficulty
        if (m.ar === null) m.ar = m.od;

        finish(m);
        return m;
    }

    function hitObject(m, line) {
        var p = line.split(",");
        var o = {
            x: Number(p[0]), y: Number(p[1]), time: Number(p[2]), type: CIRCLE, endTime: 0,
            newCombo: (Number(p[3]) & 4) !== 0, comboSkip: (Number(p[3]) >> 4) & 7,
            slides: 1, pixelLength: 0, path: null, dist: null, stack: 0
        };
        var type = Number(p[3]);

        if (type & 1) {
            o.type = CIRCLE;
            o.endTime = o.time;
        } else if (type & 2) {
            o.type = SLIDER;
            var curve = p[5].split("|");
            var pts = [[o.x, o.y]];

            for (var i = 1; i < curve.length; i++) {
                var xy = curve[i].split(":");
                pts.push([Number(xy[0]), Number(xy[1])]);
            }

            o.kind = curve[0][0];
            o.slides = Math.max(1, Number(p[6]) || 1);
            o.pixelLength = Number(p[7]) || 0;
            o.controls = pts;
        } else if (type & 8) {
            o.type = SPINNER;
            o.x = 256;
            o.y = 192;
            o.endTime = Number(p[5]);
        } else {
            return;
        }

        m.objects.push(o);
    }

    // timing at t: beat length of the red line, slider velocity of the green line after it (a red line resets it)
    function timingAt(m, t) {
        var beat = 500, sv = 1, found = false;

        for (var i = 0; i < m.timing.length; i++) {
            var tp = m.timing[i];

            // objects take the timing point they sit on; a hair of tolerance for rounded times
            if (tp.time > t + 1) break;

            if (tp.red) {
                if (tp.beatLength > 0) beat = tp.beatLength;
                sv = 1;
                found = true;
            } else if (tp.beatLength < 0) {
                sv = Math.min(10, Math.max(0.1, -100 / tp.beatLength));
            }
        }

        if (!found) {
            for (var j = 0; j < m.timing.length; j++) {
                if (m.timing[j].red && m.timing[j].beatLength > 0) {
                    beat = m.timing[j].beatLength;
                    break;
                }
            }
        }

        return { beat: beat, sv: sv };
    }

    function finish(m) {
        m.timing.sort(function (a, b) {
            return a.time - b.time || (a.red === b.red ? 0 : a.red ? -1 : 1);
        });
        m.objects.sort(function (a, b) { return a.time - b.time; });

        if (!m.comboColors.length) {
            m.comboColors = ["rgb(255,192,0)", "rgb(0,202,0)", "rgb(18,124,255)", "rgb(242,24,57)"];
        }

        var combo = 0, colour = 0;

        m.objects.forEach(function (o, i) {
            if (o.type === SLIDER) {
                var poly = fitLength(flatten(o.kind, o.controls), o.pixelLength);
                o.path = poly;
                o.dist = [0];

                for (var k = 1; k < poly.length; k++) {
                    o.dist.push(o.dist[k - 1] + Math.hypot(poly[k][0] - poly[k - 1][0], poly[k][1] - poly[k - 1][1]));
                }

                var tm = timingAt(m, o.time);
                var velocity = m.sliderMultiplier * 100 * tm.sv;
                o.spanDuration = o.pixelLength / velocity * tm.beat;
                o.endTime = o.time + o.spanDuration * o.slides;
                o.ticks = [];

                var tickSpan = tm.beat / m.sliderTickRate;

                if (tickSpan > 0 && o.spanDuration > 0) {
                    for (var t = tickSpan; t < o.spanDuration - 10 && o.ticks.length < 64; t += tickSpan) {
                        o.ticks.push(t / o.spanDuration);
                    }
                }
            }

            if (o.newCombo || i === 0 || o.type === SPINNER) {
                combo = 0;
                if (i > 0) colour += 1 + o.comboSkip;
            }

            combo++;
            o.comboNumber = combo;
            o.comboIndex = colour % m.comboColors.length;
            o.colour = m.comboColors[o.comboIndex];
        });

        m.objects.forEach(function (o, i) {
            var next = m.objects[i + 1];
            // follow points join objects of one combo
            o.followNext = !!(next && !next.newCombo && next.type !== SPINNER && o.type !== SPINNER);
        });
    }

    function pointAt(o, frac) {
        var path = o.path;

        if (!path || !path.length) return [o.x, o.y];

        var dist = o.dist;
        var target = frac * dist[dist.length - 1];
        var lo = 0, hi = dist.length - 1;

        while (lo < hi) {
            var mid = (lo + hi) >> 1;
            if (dist[mid] < target) lo = mid + 1; else hi = mid;
        }

        if (lo === 0) return [path[0][0], path[0][1]];

        var seg = dist[lo] - dist[lo - 1];
        var t = seg <= 0 ? 0 : (target - dist[lo - 1]) / seg;
        return [path[lo - 1][0] + (path[lo][0] - path[lo - 1][0]) * t, path[lo - 1][1] + (path[lo][1] - path[lo - 1][1]) * t];
    }

    function ballAt(o, progress) {
        var span = Math.min(o.slides, Math.max(0, progress));
        var n = Math.floor(span);
        var frac = span - n;

        if (n >= o.slides) {
            n = o.slides - 1;
            frac = 1;
        }

        if (n & 1) frac = 1 - frac;

        return pointAt(o, frac);
    }

    function endPosition(o) {
        if (o.type !== SLIDER) return [o.x, o.y];
        return pointAt(o, o.slides % 2 === 1 ? 1 : 0);
    }

    // ------------------------------------------------------------------ the map as this play sees it

    /**
     * Applies the mods to a copy of the map: flips it for HR, scales the difficulty, and stacks the objects. The
     * cursor frames are recorded on the playfield the player saw, so the map is moved to match them, not the other
     * way round.
     */
    function prepare(map, opts) {
        var mods = opts.mods || [];
        var hr = mods.indexOf("HR") >= 0;
        var ez = mods.indexOf("EZ") >= 0;
        var cs = map.cs, ar = map.ar, od = map.od;
        var da = opts.difficulty || {};

        if (da.circle_size) cs = da.circle_size;
        if (da.approach_rate) ar = da.approach_rate;
        if (da.overall_difficulty) od = da.overall_difficulty;

        if (hr) {
            cs = Math.min(10, cs * 1.3);
            ar = Math.min(10, ar * 1.4);
            od = Math.min(10, od * 1.4);
        } else if (ez) {
            cs *= 0.5;
            ar *= 0.5;
            od *= 0.5;
        }

        var radius = 54.4 - 4.48 * cs;
        var preempt = ar < 5 ? 1200 + 600 * (5 - ar) / 5 : ar === 5 ? 1200 : 1200 - 750 * (ar - 5) / 5;

        var objects = map.objects.map(function (o) {
            var c = Object.assign({}, o);

            if (hr) {
                c.y = 384 - c.y;
                if (c.path) c.path = c.path.map(function (p) { return [p[0], 384 - p[1]]; });
            }

            c.stack = 0;
            c.judge = null;
            return c;
        });

        stack(objects, preempt * map.stackLeniency, map.formatVersion);

        // stable moves stacks up-left on the screen before HR flips the playfield; lazer stacks after the flip
        var dir = hr && opts.source !== "lazer" ? -1 : 1;
        var unit = -radius / 10;

        objects.forEach(function (o) {
            if (!o.stack) return;

            var dx = o.stack * unit, dy = o.stack * unit * dir;
            o.x += dx;
            o.y += dy;
            if (o.path) o.path = o.path.map(function (p) { return [p[0] + dx, p[1] + dy]; });
        });

        // judgements are handed out in the order the game makes them: a slider at its end
        var byJudge = objects.filter(function (o) { return true; }).sort(function (a, b) {
            return a.endTime - b.endTime;
        });

        return {
            map: map, objects: objects, byJudge: byJudge, radius: radius, preempt: preempt,
            fadeIn: 400 * Math.min(1, preempt / 450), od: od, hd: mods.indexOf("HD") >= 0,
            w50: 200 - 10 * od,
            first: objects.length ? objects[0].time : 0,
            last: objects.length ? Math.max.apply(null, objects.map(function (o) { return o.endTime; })) : 0
        };
    }

    // osu! stacking (format v6 and later), so the cursor meets the circles where the player saw them
    function stack(objects, threshold, version) {
        var distance = 3;

        if (version < 6) return;

        for (var i = objects.length - 1; i > 0; i--) {
            var n = i;
            var oi = objects[i];

            if (oi.stack !== 0 || oi.type === SPINNER) continue;

            if (oi.type === CIRCLE) {
                while (--n >= 0) {
                    var on = objects[n];

                    if (on.type === SPINNER) continue;
                    if (oi.time - on.endTime > threshold) break;

                    var end = endPosition(on);

                    if (on.type === SLIDER && Math.hypot(end[0] - oi.x, end[1] - oi.y) < distance) {
                        var offset = oi.stack - on.stack + 1;

                        for (var j = n + 1; j <= i; j++) {
                            if (Math.hypot(end[0] - objects[j].x, end[1] - objects[j].y) < distance) {
                                objects[j].stack -= offset;
                            }
                        }

                        break;
                    }

                    if (Math.hypot(on.x - oi.x, on.y - oi.y) < distance) {
                        on.stack = oi.stack + 1;
                        oi = on;
                    }
                }
            } else if (oi.type === SLIDER) {
                while (--n >= 0) {
                    var os = objects[n];

                    if (os.type === SPINNER) continue;
                    if (oi.time - os.time > threshold) break;

                    var e = endPosition(os);

                    if (Math.hypot(e[0] - oi.x, e[1] - oi.y) < distance) {
                        os.stack = oi.stack + 1;
                        oi = os;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the skin (the pictures of the best-play videos)

    /*
     * The same pictures bancho.jar's replay renderer draws with (SkinSet.java): the server skin, with the bundled
     * default skin behind it. tools/sync-spectate-skin.js copies them to /spectate-skin/ and writes skin.json, which
     * holds each picture's size, its "logical" factor (0.5 for @2x files) and the skin.ini values the renderer reads.
     */
    var SKIN_BASE = "/spectate-skin/";
    var SKIN_VERSION = "1";

    function Skin(manifest) {
        this.ini = manifest.ini || {};
        this.meta = manifest.sprites || {};
        this.pics = {};
        this.derived = {};
        this.spinnerReady = false;
    }

    /** Loads one group of pictures; one that cannot be had is left out, and is then simply not drawn. */
    Skin.prototype.load = function (group) {
        var self = this;

        var jobs = Object.keys(this.meta).filter(function (n) { return self.meta[n].group === group; }).map(function (name) {
            return new Promise(function (resolve) {
                var m = self.meta[name];
                var el = new Image();

                el.onload = function () {
                    self.pics[name] = {
                        el: el, pw: m.w, ph: m.h, logical: m.logical, w: m.w * m.logical, h: m.h * m.logical,
                        // the skin hides an element by giving it a picture of one transparent pixel
                        hidden: m.w <= 2 && m.h <= 2, main: !!m.main
                    };
                    resolve();
                };
                el.onerror = function () { resolve(); };
                el.src = SKIN_BASE + name + ".png?v=" + SKIN_VERSION;
            });
        });

        return Promise.all(jobs);
    };

    Skin.prototype.get = function (name) {
        return this.pics[name] || null;
    };

    /** True when the server skin itself draws it: not hidden, and not left to the default skin. */
    Skin.prototype.has = function (name) {
        var p = this.pics[name];
        return !!p && !p.hidden && p.main;
    };

    function derive(skin, key, name, paint) {
        if (key in skin.derived) return skin.derived[key];

        var base = skin.pics[name];

        if (!base) return null;

        var c = document.createElement("canvas");
        c.width = base.pw;
        c.height = base.ph;
        paint(c.getContext("2d"), base.el, c);

        skin.derived[key] = {
            el: c, pw: base.pw, ph: base.ph, logical: base.logical, w: base.w, h: base.h, hidden: base.hidden, main: base.main
        };

        return skin.derived[key];
    }

    /** The picture multiplied by a colour, alpha kept (SkinSet.tinted). */
    Skin.prototype.tinted = function (name, rgb) {
        return derive(this, name + "#" + rgb.join(","), name, function (x, el, c) {
            x.drawImage(el, 0, 0);
            x.globalCompositeOperation = "multiply";
            x.fillStyle = "rgb(" + rgb.join(",") + ")";
            x.fillRect(0, 0, c.width, c.height);
            x.globalCompositeOperation = "destination-in";
            x.drawImage(el, 0, 0);
        });
    };

    /** The picture's shape painted a single colour (SkinSet.recolored), for the lit state of the dark keys. */
    Skin.prototype.recolored = function (name, rgb) {
        return derive(this, "flat:" + name + "#" + rgb.join(","), name, function (x, el, c) {
            x.drawImage(el, 0, 0);
            x.globalCompositeOperation = "source-in";
            x.fillStyle = "rgb(" + rgb.join(",") + ")";
            x.fillRect(0, 0, c.width, c.height);
        });
    };

    var skinPromise = null;

    /** One skin for the whole page: fetched once, whatever number of plays are watched. */
    function loadSkin() {
        if (skinPromise) return skinPromise;

        skinPromise = fetch(SKIN_BASE + "skin.json?v=" + SKIN_VERSION).then(function (r) {
            if (!r.ok) throw new Error(String(r.status));

            return r.json();
        }).then(function (manifest) {
            var skin = new Skin(manifest);

            // what a play needs comes first; the big spinner pictures follow while the first notes are drawn
            return skin.load("core").then(function () {
                skin.load("spinner").then(function () { skin.spinnerReady = true; });

                return skin;
            });
        });

        // a failure is not kept: the next play tries again
        skinPromise.catch(function () { skinPromise = null; });

        return skinPromise;
    }

    // ------------------------------------------------------------------ the player

    var START_BUFFER = 900;  // beatmap ms gathered before playback starts
    var DELAY = 1600;         // how far behind the newest frame playback runs
    var MAX_LAG = 5000;       // further behind than this, jump ahead
    var MAX_FRAMES = 60000;
    var ASSET_TIMEOUT = 10000; // ms a background or a song may take before the play goes ahead without it
    var SKIN_TIMEOUT = 15000;  // ms the skin may take; after that the play is drawn with plain shapes

    function Player(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext("2d");
        this.view = null;
        this.opts = { mods: [], rate: 1, mode: 0, source: "stable" };
        this.audio = null;
        this.soundOn = false;
        this.bg = null;
        // What the play needs before it is drawn: "none" (not wanted), "loading", "ready" or "failed".
        // reset() leaves these alone: a retry of the same map must not load it again.
        this.assets = { map: "none", bg: "none", audio: "none", skin: "loading" };
        this.skin = null;
        this.clipStart = 0;
        this.reset();

        var self = this;
        var skinTimer = setTimeout(function () {
            if (self.assets.skin === "loading") self.assets.skin = "failed";
        }, SKIN_TIMEOUT);

        // a skin that arrives after the timeout is still used from then on
        loadSkin().then(function (skin) {
            clearTimeout(skinTimer);

            if (self.destroyed) return;

            self.skin = skin;
            self.assets.skin = "ready";
        }, function () {
            clearTimeout(skinTimer);
            self.assets.skin = "failed";
        });

        this._tick = this.tick.bind(this);
        this._last = performance.now();
        this._raf = requestAnimationFrame(this._tick);
    }

    Player.prototype.reset = function () {
        this.frames = [];          // [time, x, y, keys], sorted by time
        this.scores = [];          // score frames, sorted by time
        this.started = false;
        this.buffering = true;
        this.time = 0;
        this.cursorIdx = 0;
        this.keyIdx = 0;
        this.keyCounts = [0, 0, 0, 0];
        this.keyDown = [false, false, false, false];
        this.lastKeys = 0;
        this.judgeAt = 0;
        this.judgePrev = null;
        this.finished = null;

        if (this.view) {
            this.view.objects.forEach(function (o) { o.judge = null; });
        }
    };

    /**
     * The map of the play being watched. Frames that came before the .osu did are kept (a late viewer is sent seconds
     * of them at once), and the score frames held so far are judged against it. A different play is reset() first.
     */
    Player.prototype.setMap = function (map, opts) {
        this.opts = Object.assign({ mods: [], rate: 1, mode: 0, source: "stable" }, opts || {});
        this.view = map && this.opts.mode === 0 ? prepare(map, this.opts) : null;
        this.map = map || null;
        this.judgeAt = 0;
        this.judgePrev = null;
        this.assets.map = "ready";

        for (var i = 0; i < this.scores.length; i++) this.judge(this.scores[i]);
    };

    /** A new play is about to load its files: nothing is drawn until all of them are settled (see isReady). */
    Player.prototype.beginLoad = function () {
        this.assets.map = "loading";
        this.assets.bg = "none";
        this.assets.audio = "none";
        this.map = null;
        this.view = null;
    };

    /** The map could not be fetched or read: the play cannot be drawn. */
    Player.prototype.failMap = function () {
        this.assets.map = "failed";
    };

    /**
     * Ready to draw: the map is parsed, and the background and the song are each either loaded or given up on (a map
     * with no background, or a slow song, must not hold the play back forever; that is a missing extra, not the play).
     */
    Player.prototype.isReady = function () {
        var a = this.assets;

        return a.map === "ready" && a.bg !== "loading" && a.audio !== "loading" && a.skin !== "loading";
    };

    /** The names of what is still being waited for, for the page to say so. */
    Player.prototype.pending = function () {
        var a = this.assets, out = [];

        if (a.map === "loading") out.push("map");
        if (a.bg === "loading") out.push("background");
        if (a.audio === "loading") out.push("song");
        if (a.skin === "loading") out.push("skin");

        return out;
    };

    /** The mods and mode of the play while its map is still loading, so the clock runs at the right rate. */
    Player.prototype.setOptions = function (opts) {
        this.opts = Object.assign({ mods: [], rate: 1, mode: 0, source: "stable" }, opts || {});
    };

    Player.prototype.setBackground = function (url) {
        var self = this;
        this.bg = null;
        this.assets.bg = url ? "loading" : "none";

        if (!url) return;

        var img = new Image();
        var timer = null;

        // only the newest request may settle the state; an answer for a map already left behind is ignored
        function settle(state) {
            clearTimeout(timer);

            if (self._bgImg !== img) return;

            if (state === "ready") self.bg = img;
            self.assets.bg = state;
        }

        this._bgImg = img;
        img.onload = function () { settle("ready"); };
        img.onerror = function () { settle("failed"); };
        timer = setTimeout(function () { settle("failed"); }, ASSET_TIMEOUT);
        img.src = url;
    };

    Player.prototype.setAudio = function (url) {
        if (this.audio) {
            this.audio.pause();
            this.audio.removeAttribute("src");
            this.audio.load();
        }

        this.audio = null;
        this.assets.audio = url ? "loading" : "none";

        if (!url) return;

        var self = this;
        var a = new Audio();
        var timer = null;

        function settle(state) {
            clearTimeout(timer);

            if (self.audio === a && self.assets.audio === "loading") self.assets.audio = state;
        }

        a.preload = "auto";
        a.volume = 0.6;
        a.addEventListener("canplay", function () { settle("ready"); });
        a.addEventListener("error", function () { settle("failed"); });
        timer = setTimeout(function () { settle("failed"); }, ASSET_TIMEOUT);
        this.audio = a;
        a.src = url;
    };

    Player.prototype.setSound = function (on) {
        this.soundOn = !!on;

        if (!on && this.audio) this.audio.pause();
    };

    Player.prototype.finish = function (reason) {
        this.finished = reason || "passed";
    };

    Player.prototype.pushFrames = function (bundle) {
        if (!bundle) return;

        var incoming = bundle.frames || [];

        if (bundle.action === "newsong") {
            this.reset();
        }

        if (incoming.length) {
            var newest = this.frames.length ? this.frames[this.frames.length - 1][0] : -Infinity;

            // time running back by seconds: a retry; start over
            if (incoming[0][0] < newest - 3000) {
                this.reset();
                newest = -Infinity;
            }

            for (var i = 0; i < incoming.length; i++) {
                var f = incoming[i];

                if (!isFinite(f[0])) continue;

                if (f[0] >= newest) {
                    this.frames.push(f);
                    newest = f[0];
                } else {
                    // a late frame inside what is held: put it in its place
                    var at = this.frames.length;
                    while (at > 0 && this.frames[at - 1][0] > f[0]) at--;
                    this.frames.splice(at, 0, f);
                }
            }

            if (this.frames.length > MAX_FRAMES) {
                var drop = this.frames.length - MAX_FRAMES;
                this.frames.splice(0, drop);
                this.cursorIdx = Math.max(0, this.cursorIdx - drop);
                this.keyIdx = Math.max(0, this.keyIdx - drop);
            }
        }

        if (bundle.score) {
            this.scores.push(bundle.score);
            this.judge(bundle.score);
        }

        if (bundle.action === "completion") this.finished = "passed";
        if (bundle.action === "fail") this.finished = "failed";
        if (bundle.action === "newsong" || incoming.length) this.finished = null;
    };

    /**
     * The score frames say how many 300s, 100s, 50s and misses there are, not on which object. The objects are
     * judged in order, so the new judgements go to the next objects due: exact for single hits, close for bursts.
     */
    Player.prototype.judge = function (score) {
        var view = this.view;

        if (!view) return;

        var prev = this.judgePrev || { n300: 0, n100: 0, n50: 0, miss: 0 };
        var add = [
            ["miss", (score.miss || 0) - prev.miss],
            ["50", (score.n50 || 0) - prev.n50],
            ["100", (score.n100 || 0) - prev.n100],
            ["300", (score.n300 || 0) - prev.n300]
        ];

        this.judgePrev = { n300: score.n300 || 0, n100: score.n100 || 0, n50: score.n50 || 0, miss: score.miss || 0 };

        for (var k = 0; k < add.length; k++) {
            for (var c = 0; c < add[k][1] && this.judgeAt < view.byJudge.length; c++) {
                view.byJudge[this.judgeAt++].judge = add[k][0];
            }
        }
    };

    Player.prototype.info = function () {
        var newest = this.frames.length ? this.frames[this.frames.length - 1][0] : 0;

        return {
            started: this.started,
            ready: this.isReady(),
            pending: this.pending(),
            buffering: this.buffering,
            time: this.time,
            lag: newest - this.time,
            hasFrames: this.frames.length > 0,
            finished: this.finished,
            progress: this.view && this.view.last > this.view.first
                ? Math.max(0, Math.min(1, (this.time - this.view.first) / (this.view.last - this.view.first))) : 0
        };
    };

    Player.prototype.destroy = function () {
        cancelAnimationFrame(this._raf);
        this.setAudio(null);
        this.frames = [];
        this.destroyed = true;
    };

    // ------------------------------------------------------------------ clock

    Player.prototype.advance = function (wallMs) {
        var rate = this.opts.rate || 1;

        // the frames are kept meanwhile; the play starts from the live position once everything is in
        if (!this.started && !this.isReady()) {
            this.buffering = true;
            return;
        }

        if (!this.frames.length) {
            this.buffering = true;
            return;
        }

        var first = this.frames[0][0];
        var newest = this.frames[this.frames.length - 1][0];

        if (!this.started) {
            // a late viewer is given seconds at once and may start right away; a fresh play gathers a little first
            if (newest - first < START_BUFFER * rate && !this.finished) return;

            this.started = true;
            this.time = Math.max(first, newest - DELAY * rate);
            this.clipStart = this.time; // the mod icons are shown for the first seconds from here
        }

        var lag = newest - this.time;
        var speed = 1;

        // drift back toward DELAY behind the newest frame without a visible jump
        if (lag > DELAY * rate * 1.5) speed = 1.06;
        else if (lag < DELAY * rate * 0.4) speed = 0.94;

        var next = this.time + wallMs * rate * speed;

        if (lag > MAX_LAG * rate) {
            next = newest - DELAY * rate;
        }

        if (next >= newest) {
            // ran out of frames: the player paused, or the network is late
            this.time = newest;
            this.buffering = !this.finished;
        } else {
            this.time = next;
            this.buffering = false;
        }
    };

    Player.prototype.syncAudio = function () {
        var a = this.audio;

        if (!a) return;

        var rate = this.opts.rate || 1;
        var want = this.time / 1000;
        var shouldPlay = this.soundOn && this.started && !this.buffering && !this.finished && want >= 0
            && !document.hidden;

        if (!shouldPlay) {
            if (!a.paused) a.pause();
            return;
        }

        var nc = (this.opts.mods || []).indexOf("NC") >= 0 || (this.opts.mods || []).indexOf("DC") >= 0;

        if (a.playbackRate !== rate) a.playbackRate = rate;
        if ("preservesPitch" in a) a.preservesPitch = !nc;

        if (a.duration && want > a.duration) {
            a.pause();
            return;
        }

        if (Math.abs(a.currentTime - want) > 0.09) {
            try {
                a.currentTime = want;
            } catch (e) {
                // not seekable yet
            }
        }

        if (a.paused) {
            var p = a.play();
            if (p && p.catch) p.catch(function () {});
        }
    };

    // ------------------------------------------------------------------ frames at a time

    Player.prototype.indexAt = function (t, hint) {
        var f = this.frames;
        var i = Math.min(Math.max(0, hint || 0), f.length - 1);

        if (i < 0) return -1;

        // playback moves forward a little each tick: walk from the last index, search on a jump
        if (f[i][0] <= t && (i + 1 >= f.length || f[i + 1][0] > t)) return i;

        if (f[i][0] <= t && i + 8 < f.length && f[i + 8][0] > t) {
            while (i + 1 < f.length && f[i + 1][0] <= t) i++;
            return i;
        }

        var lo = 0, hi = f.length - 1;

        if (f[0][0] > t) return -1;

        while (lo < hi) {
            var mid = (lo + hi + 1) >> 1;
            if (f[mid][0] <= t) lo = mid; else hi = mid - 1;
        }

        return lo;
    };

    Player.prototype.cursorAt = function (t) {
        var f = this.frames;
        var i = this.indexAt(t, this.cursorIdx);

        if (i < 0) return f.length ? [f[0][1], f[0][2]] : [256, 192];

        var a = f[i];
        var b = f[i + 1];

        if (!b || b[0] <= a[0] || b[0] - a[0] > 250) return [a[1], a[2]];

        var k = (t - a[0]) / (b[0] - a[0]);
        return [a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
    };

    // keys: K1 (4, with M1), K2 (8, with M2), M1 (1), M2 (2)
    function keysOf(bits) {
        var k1 = (bits & 4) !== 0, k2 = (bits & 8) !== 0;
        return [k1, k2, (bits & 1) !== 0 && !k1, (bits & 2) !== 0 && !k2];
    }

    Player.prototype.updateKeys = function () {
        var f = this.frames;
        var end = this.indexAt(this.time, this.keyIdx);

        if (end < this.keyIdx - 1) {
            // a jump back: counts start over
            this.keyIdx = 0;
            this.keyCounts = [0, 0, 0, 0];
            this.lastKeys = 0;
        }

        for (var i = this.keyIdx; i <= end && i < f.length; i++) {
            var now = keysOf(f[i][3]);
            var before = keysOf(this.lastKeys);

            for (var k = 0; k < 4; k++) {
                if (now[k] && !before[k]) this.keyCounts[k]++;
            }

            this.lastKeys = f[i][3];
        }

        this.keyIdx = Math.max(this.keyIdx, end + 1);
        this.keyDown = keysOf(end >= 0 && f[end] ? f[end][3] : 0);
        this.cursorIdx = Math.max(0, end);
    };

    Player.prototype.scoreAt = function (t) {
        var s = this.scores;
        var best = null;

        for (var i = s.length - 1; i >= 0; i--) {
            if (s[i].time <= t) {
                best = s[i];
                break;
            }
        }

        // drop what can no longer be shown
        if (s.length > 4000) s.splice(0, s.length - 4000);

        return best;
    };

    // ------------------------------------------------------------------ drawing

    Player.prototype.tick = function (now) {
        if (this.destroyed) return;

        this._raf = requestAnimationFrame(this._tick);

        var wall = Math.min(100, now - this._last);
        this._last = now;

        this.advance(wall);
        this.updateKeys();
        this.syncAudio();

        if (!document.hidden) this.draw();
    };

    Player.prototype.resize = function () {
        var c = this.canvas;
        var dpr = Math.min(2, window.devicePixelRatio || 1);
        var w = c.clientWidth || 640;
        var h = c.clientHeight || Math.round(w * 9 / 16);

        if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
            c.width = Math.round(w * dpr);
            c.height = Math.round(h * dpr);
        }

        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.W = w;
        this.H = h;

        // the playfield takes 80% of the height, a little below centre, as in the game (and in the best-play videos,
        // which are 1280x720: playfield scale 1.5, 8 pixels below centre)
        this.u = h / 720;
        this.s = h * 0.8 / 384;
        this.ox = (w - 512 * this.s) / 2;
        this.oy = (h - 384 * this.s) / 2 + 8 * this.u;
        // factor that makes the 128 unit circle sprite as wide as a circle of this map
        this.k = this.view ? 2 * this.view.radius * this.s / 128 : 0;
    };

    Player.prototype.px = function (x) { return this.ox + x * this.s; };
    Player.prototype.py = function (y) { return this.oy + y * this.s; };

    Player.prototype.draw = function () {
        this.resize();

        var g = this.ctx;
        var W = this.W, H = this.H;

        g.globalAlpha = 1;

        if (this.bg) {
            // cover, centre-cropped, dimmed by 175/255: the background of the best-play videos
            var r = Math.max(W / this.bg.width, H / this.bg.height);
            var bw = this.bg.width * r, bh = this.bg.height * r;
            g.drawImage(this.bg, (W - bw) / 2, (H - bh) / 2, bw, bh);
            g.fillStyle = "rgba(0,0,0,0.686)";
            g.fillRect(0, 0, W, H);
        } else {
            var grad = g.createLinearGradient(0, 0, W, H);
            grad.addColorStop(0, "rgb(38,30,60)");
            grad.addColorStop(1, "rgb(14,12,24)");
            g.fillStyle = grad;
            g.fillRect(0, 0, W, H);
        }

        if (this.view && this.started) {
            this.drawPlayfield(g, this.time);
        }

        if (this.started) {
            this.drawCursor(g, this.time);
        }

        this.drawHud(g);
    };

    Player.prototype.drawPlayfield = function (g, t) {
        var view = this.view;
        var objs = view.objects;
        var preempt = view.preempt;

        // the objects on screen: from the last one that may still be fading out to the first not yet faded in
        var lo = 0, hi = objs.length;

        while (lo < hi) {
            var mid = (lo + hi) >> 1;
            if (objs[mid].time < t - 4000) lo = mid + 1; else hi = mid;
        }

        var visible = [];

        for (var i = lo; i < objs.length; i++) {
            var o = objs[i];

            if (o.time - preempt > t) break;
            if (o.endTime + 400 < t) continue;

            visible.push(o);
        }

        this.drawFollowPoints(g, objs, lo, t);

        // later objects underneath, so the next one to hit is on top
        for (var j = visible.length - 1; j >= 0; j--) {
            var ob = visible[j];

            if (ob.type === SPINNER) this.drawSpinner(g, ob, t);
            else if (ob.type === SLIDER) this.drawSlider(g, ob, t);
            else this.drawCircle(g, ob, t);
        }

        for (var k = 0; k < visible.length; k++) {
            this.drawJudgement(g, visible[k], t);
        }
    };

    Player.prototype.alphaOf = function (o, t) {
        var view = this.view;
        var start = o.time - view.preempt;
        var a = Math.max(0, Math.min(1, (t - start) / view.fadeIn));

        if (view.hd) {
            // hidden: fades in over 40% of the approach, out over the next 30%
            var fadeOutStart = start + view.preempt * 0.4;
            var fadeOutLen = view.preempt * 0.3;

            if (t > fadeOutStart) a = Math.max(0, 1 - (t - fadeOutStart) / fadeOutLen);
        }

        return a;
    };

    Player.prototype.circleAt = function (g, x, y, colour, alpha, number, scale) {
        var r = this.view.radius * this.s * (scale || 1);

        g.globalAlpha = alpha;
        g.beginPath();
        g.arc(x, y, r * 0.93, 0, Math.PI * 2);
        g.fillStyle = colour;
        g.fill();

        // a darker inside, so the number reads on any colour
        g.beginPath();
        g.arc(x, y, r * 0.78, 0, Math.PI * 2);
        g.fillStyle = "rgba(0,0,0,0.28)";
        g.fill();

        g.lineWidth = Math.max(2, r * 0.13);
        g.strokeStyle = "#fff";
        g.beginPath();
        g.arc(x, y, r * 0.88, 0, Math.PI * 2);
        g.stroke();

        if (number) {
            g.fillStyle = "#fff";
            g.font = "700 " + Math.round(r * 0.85) + "px Torus, 'M PLUS Rounded 1c', sans-serif";
            g.textAlign = "center";
            g.textBaseline = "middle";
            g.fillText(String(number), x, y + r * 0.04);
        }

        g.globalAlpha = 1;
    };

    Player.prototype.approachAt = function (g, o, x, y, t, alpha) {
        if (this.view.hd || t >= o.time) return;

        var p = (o.time - t) / this.view.preempt;
        var r = this.view.radius * this.s * (1 + 2.6 * p);

        g.globalAlpha = alpha * 0.9;
        g.lineWidth = Math.max(1.5, this.view.radius * this.s * 0.09);
        g.strokeStyle = o.colour;
        g.beginPath();
        g.arc(x, y, r, 0, Math.PI * 2);
        g.stroke();
        g.globalAlpha = 1;
    };

    Player.prototype.drawCircle = function (g, o, t) {
        if (this.skin) return this.drawCircleSkin(g, o, t);

        var x = this.px(o.x), y = this.py(o.y);

        if (t <= o.time) {
            var a = this.alphaOf(o, t);
            this.circleAt(g, x, y, o.colour, a, o.comboNumber);
            this.approachAt(g, o, x, y, t, a);
            return;
        }

        // after the hit: a hit swells and fades, a miss fades where it was
        var since = t - o.time;
        var hit = o.judge && o.judge !== "miss";
        var fade = hit ? 160 : 240;

        if (since > fade) return;

        var k = since / fade;
        var base = this.view.hd ? 0 : 1;

        if (hit) this.circleAt(g, x, y, o.colour, (1 - k) * 0.9, 0, 1 + 0.35 * k);
        else if (base) this.circleAt(g, x, y, o.colour, (1 - k) * 0.5, o.comboNumber);
    };

    Player.prototype.drawSlider = function (g, o, t) {
        if (this.skin) return this.drawSliderSkin(g, o, t);

        var s = this.s;
        var r = this.view.radius * s;
        var a = this.alphaOf(o, t);

        // after its end the whole slider fades out
        if (t > o.endTime) a = Math.max(0, 1 - (t - o.endTime) / 200);
        if (this.view.hd && t > o.time) a = Math.max(0, Math.min(a, 1 - (t - o.time) / Math.max(1, o.endTime - o.time)));

        var bodyAlpha = this.view.hd && t > o.time ? a : Math.max(a, t > o.time && t <= o.endTime ? 1 : 0);

        if (bodyAlpha > 0 && o.path && o.path.length > 1) {
            g.save();
            g.lineCap = "round";
            g.lineJoin = "round";
            g.beginPath();
            g.moveTo(this.px(o.path[0][0]), this.py(o.path[0][1]));

            for (var i = 1; i < o.path.length; i++) {
                g.lineTo(this.px(o.path[i][0]), this.py(o.path[i][1]));
            }

            g.globalAlpha = bodyAlpha * 0.95;
            g.strokeStyle = "#fff";
            g.lineWidth = r * 2 * 0.93;
            g.stroke();
            g.strokeStyle = "rgba(14,14,22,1)";
            g.lineWidth = r * 2 * 0.80;
            g.stroke();
            g.globalAlpha = bodyAlpha * 0.35;
            g.strokeStyle = o.colour;
            g.lineWidth = r * 2 * 0.80;
            g.stroke();
            g.restore();
            g.globalAlpha = 1;

            var progress = t <= o.time ? 0 : (t - o.time) / o.spanDuration;
            var span = Math.floor(progress);
            var forward = (span & 1) === 0;
            var inSpan = progress - span;

            // ticks still ahead of the ball on this span
            g.fillStyle = "#fff";
            g.globalAlpha = bodyAlpha * 0.85;

            for (var k = 0; k < o.ticks.length; k++) {
                var f = forward ? o.ticks[k] : 1 - o.ticks[k];

                if (t > o.time && (forward ? f <= inSpan : (1 - f) <= inSpan)) continue;

                var tp = pointAt(o, f);
                g.beginPath();
                g.arc(this.px(tp[0]), this.py(tp[1]), Math.max(1.5, r * 0.1), 0, Math.PI * 2);
                g.fill();
            }

            g.globalAlpha = 1;

            // the arrow at the end the ball turns back from
            var left = o.slides - Math.max(0, span) - 1;

            if (left > 0 && t <= o.endTime) {
                var endFrac = forward ? 1 : 0;
                var at = pointAt(o, endFrac);
                var from = pointAt(o, forward ? 0.97 : 0.03);
                this.arrow(g, this.px(at[0]), this.py(at[1]),
                    Math.atan2(from[1] - at[1], from[0] - at[0]), r * 0.55, bodyAlpha);
            }
        }

        // the head until it is passed, then the ball
        if (t <= o.time) {
            var hx = this.px(o.x), hy = this.py(o.y);
            this.circleAt(g, hx, hy, o.colour, a, o.comboNumber);
            this.approachAt(g, o, hx, hy, t, a);
        } else if (t <= o.endTime) {
            var b = ballAt(o, (t - o.time) / o.spanDuration);
            var bx = this.px(b[0]), by = this.py(b[1]);
            var held = this.keyDown.some(Boolean);
            var cur = this.cursorAt(t);
            var near = Math.hypot(cur[0] - b[0], cur[1] - b[1]) < this.view.radius * 2.4;

            g.globalAlpha = this.view.hd ? 0.9 : 1;
            g.beginPath();
            g.arc(bx, by, r * 0.8, 0, Math.PI * 2);
            g.fillStyle = o.colour;
            g.fill();
            g.lineWidth = Math.max(2, r * 0.12);
            g.strokeStyle = "#fff";
            g.stroke();

            if (held && near) {
                // the follow circle: the ball is being held
                g.globalAlpha = 0.7;
                g.lineWidth = Math.max(2, r * 0.1);
                g.beginPath();
                g.arc(bx, by, r * 2.2, 0, Math.PI * 2);
                g.stroke();
            }

            g.globalAlpha = 1;
        }
    };

    Player.prototype.arrow = function (g, x, y, angle, size, alpha) {
        g.save();
        g.translate(x, y);
        g.rotate(angle);
        g.globalAlpha = alpha;
        g.strokeStyle = "#fff";
        g.lineWidth = Math.max(2, size * 0.28);
        g.lineCap = "round";
        g.lineJoin = "round";
        g.beginPath();
        g.moveTo(-size * 0.35, -size * 0.6);
        g.lineTo(size * 0.35, 0);
        g.lineTo(-size * 0.35, size * 0.6);
        g.stroke();
        g.restore();
        g.globalAlpha = 1;
    };

    Player.prototype.drawSpinner = function (g, o, t) {
        // the spinner pictures are big and come last: until they are in, the plain spinner is drawn
        if (this.skin && this.skin.spinnerReady && this.skin.get("spinner-circle")) return this.drawSpinnerSkin(g, o, t);

        var cx = this.px(256), cy = this.py(192);
        var full = 384 * this.s * 0.46;
        var a = Math.max(0, Math.min(1, (t - (o.time - 400)) / 400));

        if (t > o.endTime) a = Math.max(0, 1 - (t - o.endTime) / 250);
        if (a <= 0) return;

        var len = Math.max(1, o.endTime - o.time);
        var left = t < o.time ? 1 : Math.max(0, 1 - (t - o.time) / len);

        g.globalAlpha = a * 0.25;
        g.fillStyle = "#000";
        g.beginPath();
        g.arc(cx, cy, full, 0, Math.PI * 2);
        g.fill();

        g.globalAlpha = a;
        g.strokeStyle = "#fff";
        g.lineWidth = 3;
        g.beginPath();
        g.arc(cx, cy, Math.max(4, full * left), 0, Math.PI * 2);
        g.stroke();

        // the spin itself: an arm that follows the cursor around the centre
        var cur = this.cursorAt(t);
        var ang = Math.atan2(cur[1] - 192, cur[0] - 256);

        g.lineWidth = 4;
        g.strokeStyle = "rgba(255,255,255,0.8)";
        g.beginPath();
        g.arc(cx, cy, full * 0.92, ang - 0.35, ang + 0.35);
        g.stroke();

        g.beginPath();
        g.arc(cx, cy, 5, 0, Math.PI * 2);
        g.fillStyle = "#fff";
        g.fill();
        g.globalAlpha = 1;
    };

    Player.prototype.drawFollowPoints = function (g, objs, from, t) {
        if (this.skin && this.skin.get("followpoint")) return this.drawFollowPointsSkin(g, objs, from, t);

        var preempt = this.view.preempt;

        for (var i = Math.max(0, from - 1); i < objs.length - 1; i++) {
            var a = objs[i], b = objs[i + 1];

            if (b.time - preempt > t) break;
            if (!a.followNext || t > b.time) continue;

            var e = endPosition(a);
            var x1 = this.px(e[0]), y1 = this.py(e[1]), x2 = this.px(b.x), y2 = this.py(b.y);
            var d = Math.hypot(x2 - x1, y2 - y1);
            var gap = this.view.radius * this.s * 1.4;

            if (d < gap * 2) continue;

            var spacing = 32 * this.s;
            var dots = Math.floor((d - gap * 2) / spacing);
            var ux = (x2 - x1) / d, uy = (y2 - y1) / d;
            var dur = Math.max(1, b.time - a.endTime);

            g.fillStyle = "#fff";

            for (var k = 0; k <= dots; k++) {
                var f = (gap + k * spacing) / d;
                // each dot appears in its turn and is gone once the line has been travelled past it
                var appear = a.endTime - preempt + dur * f;
                var gone = a.endTime + dur * f;
                var alpha = t < appear ? 0 : t > gone ? Math.max(0, 1 - (t - gone) / 120) : Math.min(1, (t - appear) / 200);

                if (alpha <= 0) continue;

                g.globalAlpha = alpha * 0.6;
                g.beginPath();
                g.arc(x1 + ux * (gap + k * spacing), y1 + uy * (gap + k * spacing), Math.max(1.2, 1.6 * this.s), 0, Math.PI * 2);
                g.fill();
            }
        }

        g.globalAlpha = 1;
    };

    var JUDGE_STYLE = {
        "100": { text: "100", colour: "#57e313" },
        "50": { text: "50", colour: "#ffb43c" },
        "miss": { text: "✕", colour: "#ff3d5a" }
    };

    Player.prototype.drawJudgement = function (g, o, t) {
        if (this.skin) return this.drawJudgementSkin(g, o, t);

        var style = o.judge && JUDGE_STYLE[o.judge];

        if (!style) return;

        var at = o.type === SLIDER ? o.endTime : o.type === SPINNER ? o.endTime : o.time;
        var since = t - at;

        if (since < 0 || since > 600) return;

        var pos = o.type === SLIDER ? endPosition(o) : [o.x, o.y];
        var x = this.px(pos[0]), y = this.py(pos[1]);
        var size = this.view.radius * this.s * (o.judge === "miss" ? 1.0 : 0.75);
        var a = since < 450 ? 1 : 1 - (since - 450) / 150;

        g.globalAlpha = Math.max(0, a);
        g.font = "800 " + Math.round(size) + "px Torus, 'M PLUS Rounded 1c', sans-serif";
        g.textAlign = "center";
        g.textBaseline = "middle";
        g.lineWidth = 4;
        g.strokeStyle = "rgba(0,0,0,0.55)";
        g.strokeText(style.text, x, y - since * 0.02 * this.s);
        g.fillStyle = style.colour;
        g.fillText(style.text, x, y - since * 0.02 * this.s);
        g.globalAlpha = 1;
    };

    Player.prototype.drawCursor = function (g, t) {
        if (this.skin) return this.drawCursorSkin(g, t);

        var s = this.s;
        var trail = 14;

        for (var i = trail; i >= 1; i--) {
            var p = this.cursorAt(t - i * 9);
            g.globalAlpha = 0.5 * (1 - i / trail);
            g.fillStyle = "#ffd28a";
            g.beginPath();
            g.arc(this.px(p[0]), this.py(p[1]), Math.max(2, 7 * s * (1 - i / (trail * 1.6))), 0, Math.PI * 2);
            g.fill();
        }

        var c = this.cursorAt(t);
        var x = this.px(c[0]), y = this.py(c[1]);
        var pressed = this.keyDown.some(Boolean);

        g.globalAlpha = 1;
        g.beginPath();
        g.arc(x, y, (pressed ? 11 : 9) * s, 0, Math.PI * 2);
        g.fillStyle = "#ffcc66";
        g.fill();
        g.lineWidth = Math.max(1.5, 2.2 * s);
        g.strokeStyle = "#fff";
        g.stroke();
    };

    function accuracyOf(score, mode) {
        if (!score) return 1;
        if (typeof score.accuracy === "number") return score.accuracy;

        var n300 = score.n300 || 0, n100 = score.n100 || 0, n50 = score.n50 || 0, miss = score.miss || 0;
        var geki = score.geki || 0, katu = score.katu || 0;
        var total;

        if (mode === 1) {
            total = n300 + n100 + miss;
            return total ? (n300 + 0.5 * n100) / total : 1;
        }

        if (mode === 2) {
            total = n300 + n100 + n50 + katu + miss;
            return total ? (n300 + n100 + n50) / total : 1;
        }

        if (mode === 3) {
            total = geki + n300 + katu + n100 + n50 + miss;
            return total ? (300 * (geki + n300) + 200 * katu + 100 * n100 + 50 * n50) / (300 * total) : 1;
        }

        total = n300 + n100 + n50 + miss;
        return total ? (300 * n300 + 100 * n100 + 50 * n50) / (300 * total) : 1;
    }

    Player.prototype.drawHud = function (g) {
        var W = this.W, H = this.H;
        var u = H / 720;
        var score = this.started ? this.scoreAt(this.time) : null;
        var mode = this.opts.mode || 0;
        var font = "Torus, 'M PLUS Rounded 1c', sans-serif";

        g.textBaseline = "alphabetic";

        if (this.skin) this.drawHudSkin(g, score);
        else this.drawHudPlain(g, score, u, font);

        this.drawHudExtras(g, score, u, mode, font);
    };

    /** The score, combo, health and keys drawn with plain shapes: for when the skin could not be loaded. */
    Player.prototype.drawHudPlain = function (g, score, u, font) {
        var W = this.W, H = this.H;
        var mode = this.opts.mode || 0;

        // score, accuracy
        g.fillStyle = "#fff";
        g.textAlign = "right";
        g.font = "700 " + Math.round(40 * u) + "px " + font;
        g.fillText(String((score && score.score) || 0).padStart(8, "0"), W - 18 * u, 48 * u);
        g.font = "600 " + Math.round(24 * u) + "px " + font;
        g.fillText((accuracyOf(score, mode) * 100).toFixed(2) + "%", W - 18 * u, 80 * u);

        // combo
        g.textAlign = "left";
        g.font = "800 " + Math.round(46 * u) + "px " + font;
        g.fillText(((score && score.combo) || 0) + "x", 18 * u, H - 22 * u);

        // health, when the client reports it (stable only)
        if (score && typeof score.hp === "number" && score.hp > 0) {
            var hw = 320 * u;
            g.fillStyle = "rgba(255,255,255,0.12)";
            g.fillRect(18 * u, 18 * u, hw, 8 * u);
            g.fillStyle = "#ff66aa";
            g.fillRect(18 * u, 18 * u, hw * Math.min(1, score.hp / 200), 8 * u);
        }

        // keys
        var labels = ["K1", "K2", "M1", "M2"];
        var kx = W - 58 * u, ky = H / 2 - 100 * u;

        for (var i = 0; i < 4; i++) {
            var y = ky + i * 52 * u;
            g.fillStyle = this.keyDown[i] ? "#ff66aa" : "rgba(255,255,255,0.12)";
            roundRect(g, kx, y, 44 * u, 44 * u, 8 * u);
            g.fill();
            g.fillStyle = "#fff";
            g.textAlign = "center";
            g.font = "700 " + Math.round(this.keyCounts[i] > 0 ? 15 * u : 13 * u) + "px " + font;
            g.fillText(this.keyCounts[i] > 0 ? String(this.keyCounts[i]) : labels[i], kx + 22 * u, y + 27 * u);
        }

    };

    /** What the live page adds to the video's picture: how far the song is, and the counts of a mode that has no playfield. */
    Player.prototype.drawHudExtras = function (g, score, u, mode, font) {
        var W = this.W, H = this.H;

        // song progress
        var info = this.info();
        if (this.view) {
            g.fillStyle = "rgba(255,255,255,0.15)";
            g.fillRect(0, H - 4 * u, W, 4 * u);
            g.fillStyle = "#ff66aa";
            g.fillRect(0, H - 4 * u, W * info.progress, 4 * u);
        }

        // judgement counts: the video does not show them, so with the skin they are only kept where nothing else says
        // how the play goes (the other modes); the plain drawing keeps them always
        if (score && (!this.skin || !this.view)) {
            var counts = mode === 3
                ? [["MAX", score.geki], ["300", score.n300], ["200", score.katu], ["100", score.n100], ["50", score.n50], ["X", score.miss]]
                : [["300", score.n300], ["100", score.n100], ["50", score.n50], ["X", score.miss]];

            g.textAlign = "right";
            g.font = "600 " + Math.round(15 * u) + "px " + font;

            for (var c = 0; c < counts.length; c++) {
                g.fillStyle = counts[c][0] === "X" ? "#ff8095" : "rgba(255,255,255,0.75)";
                g.fillText(counts[c][0] + "  " + (counts[c][1] || 0), W - 18 * u, 112 * u + c * 20 * u);
            }
        }

        if (!this.view && this.started) {
            g.textAlign = "center";
            g.fillStyle = "rgba(255,255,255,0.55)";
            g.font = "600 " + Math.round(18 * u) + "px " + font;
            g.fillText(mode === 0 ? "Loading the beatmap..." : "Only osu! (standard) plays are drawn; the score is live.",
                W / 2, H / 2);
        }
    };

    // ------------------------------------------------------------------ drawing with the skin (FrameRenderer.java, ported)

    /*
     * The video is drawn on a 1280x720 frame; here the canvas is any size, so every length given in frame pixels is
     * multiplied by this.u (height / 720) and the playfield scale is this.s (1.5 on the frame). As in FrameRenderer,
     * "factor" is screen pixels per logical unit of a picture, and a picture's own "logical" turns its pixels into them.
     */

    var HIT_NAMES = { "100": "hit100", "50": "hit50", "miss": "hit0" };
    var MOD_ICONS = {
        NF: "nofail", EZ: "easy", HD: "hidden", HR: "hardrock", SD: "suddendeath", DT: "doubletime", NC: "nightcore",
        HT: "halftime", FL: "flashlight", SO: "spunout", RX: "relax", AP: "relax2", PF: "perfect"
    };

    /** A picture centred on (cx, cy), turned by rot radians (FrameRenderer.draw). */
    Player.prototype.sprite = function (g, img, cx, cy, factor, alpha, rot) {
        if (!img || img.hidden || alpha <= 0.003) return;

        var sc = img.logical * factor;

        g.save();
        g.globalAlpha = Math.min(1, alpha);
        g.translate(cx, cy);
        if (rot) g.rotate(rot);
        g.scale(sc, sc);
        g.drawImage(img.el, -img.pw / 2, -img.ph / 2);
        g.restore();
    };

    // numbers from the skin's digit pictures: "default-N" on circles, "score-N", "combo-N"

    Player.prototype.glyph = function (prefix, c) {
        var name;

        if (c >= "0" && c <= "9") name = prefix + "-" + c;
        else if (c === ".") name = prefix + "-dot";
        else if (c === ",") name = prefix + "-comma";
        else if (c === "%") name = prefix + "-percent";
        else if (c === "x") name = prefix + "-x";
        else return null;

        return this.skin.get(name);
    };

    /** How far the next digit is pulled back over the previous one, in skin units; null when the skin does not say. */
    Player.prototype.overlapOf = function (prefix) {
        var ini = this.skin.ini;

        if (prefix === ini.hitcircleprefix) return ini.hitcircleoverlap;
        if (prefix === ini.scoreprefix) return ini.scoreoverlap;
        if (prefix === ini.comboprefix) return ini.combooverlap;

        return null;
    };

    Player.prototype.advanceOf = function (img, factor, overlap) {
        return overlap == null ? img.w * factor * 0.94 : (img.w - overlap) * factor;
    };

    /** The width a number takes: the pen advances of all digits but the last, plus the last one in full. */
    Player.prototype.numberWidth = function (text, prefix, factor) {
        var overlap = this.overlapOf(prefix);
        var total = 0, last = null;

        for (var i = 0; i < text.length; i++) {
            var img = this.glyph(prefix, text.charAt(i));

            if (!img) continue;

            if (last) total += this.advanceOf(last, factor, overlap);
            last = img;
        }

        return last ? total + last.w * factor : total;
    };

    Player.prototype.drawNumber = function (g, text, prefix, x, y, factor, alpha, right) {
        var overlap = this.overlapOf(prefix);
        var cx = right ? x - this.numberWidth(text, prefix, factor) : x;

        for (var i = 0; i < text.length; i++) {
            var img = this.glyph(prefix, text.charAt(i));

            if (!img) continue;

            var dw = img.w * factor;

            // the picture is centred on its own width, the pen moves on by the (possibly shorter) advance
            this.sprite(g, img, cx + dw / 2, y, factor, alpha, 0);
            cx += this.advanceOf(img, factor, overlap);
        }
    };

    /** A number sized by the on-screen height of a digit, in pixels. */
    Player.prototype.drawNumberH = function (g, text, prefix, x, y, heightPx, alpha, right) {
        var zero = this.glyph(prefix, "0");

        if (!zero) return;

        this.drawNumber(g, text, prefix, x, y, heightPx / zero.h, alpha, right);
    };

    /** The combo colour of an object as [r, g, b]: the skin's, unless the map sets its own (OsuMap.ownColors). */
    Player.prototype.comboRgb = function (o) {
        var own = this.skin.ini.combo || [];

        if (!this.view.map.ownColors && own.length) return own[o.comboIndex % own.length];

        if (!o.rgb) {
            var m = /(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(o.colour || "");
            o.rgb = m ? [Number(m[1]), Number(m[2]), Number(m[3])] : [255, 255, 255];
        }

        return o.rgb;
    };

    function rgbCss(c) {
        return "rgb(" + Math.round(c[0]) + "," + Math.round(c[1]) + "," + Math.round(c[2]) + ")";
    }

    // ---- hit circles

    Player.prototype.circleSprite = function (g, o, cx, cy, alpha, scale, head) {
        var sk = this.skin, k = this.k;
        // a slider's head uses the skin's own picture for it, when the server skin has one
        var own = head && sk.has("sliderstartcircle");
        var base = own ? "sliderstartcircle" : "hitcircle";
        var overlay = own && sk.has("sliderstartcircleoverlay") ? "sliderstartcircleoverlay" : "hitcircleoverlay";
        var prefix = sk.ini.hitcircleprefix;
        var nf = k * scale * 0.8;
        var text = String(o.comboNumber);
        var nx = cx - this.numberWidth(text, prefix, nf) / 2;

        this.sprite(g, sk.tinted(base, this.comboRgb(o)), cx, cy, k * scale, alpha, 0);

        if (sk.ini.hitcircleoverlayabovenumber) {
            this.drawNumber(g, text, prefix, nx, cy, nf, alpha, false);
            this.sprite(g, sk.get(overlay), cx, cy, k * scale, alpha, 0);
        } else {
            this.sprite(g, sk.get(overlay), cx, cy, k * scale, alpha, 0);
            this.drawNumber(g, text, prefix, nx, cy, nf, alpha, false);
        }
    };

    Player.prototype.approachSkin = function (g, o, cx, cy, t, alpha) {
        if (this.view.hd) return;

        var p = (o.time - t) / this.view.preempt;

        if (p <= 0 || p > 1) return;

        this.sprite(g, this.skin.tinted("approachcircle", this.comboRgb(o)), cx, cy, this.k * (1 + 3 * p),
            Math.min(1, alpha) * 0.95, 0);
    };

    Player.prototype.drawCircleSkin = function (g, o, t) {
        var x = this.px(o.x), y = this.py(o.y);
        var since = t - o.time;

        if (since <= 0) {
            var a = this.alphaOf(o, t);
            this.circleSprite(g, o, x, y, a, 1, false);
            this.approachSkin(g, o, x, y, t, a);
            return;
        }

        var d;

        // a hit swells and fades; a miss stays until the last moment it could still have been hit, then fades
        if (o.judge && o.judge !== "miss") {
            d = Math.min(1, since / 240);
            if (d < 1) this.circleSprite(g, o, x, y, 1 - d, 1 + 0.4 * d, false);
            return;
        }

        if (this.view.hd) return;

        var w = this.view.w50;

        if (since < w) {
            this.circleSprite(g, o, x, y, 1, 1, false);
        } else {
            d = Math.min(1, (since - w) / 120);
            if (d < 1) this.circleSprite(g, o, x, y, 1 - d, 1, false);
        }
    };

    // ---- sliders

    Player.prototype.drawSliderSkin = function (g, o, t) {
        var sk = this.skin, view = this.view, k = this.k;
        var a = this.alphaOf(o, t);

        // after its end the whole slider fades out
        if (t > o.endTime) a = Math.max(0, 1 - (t - o.endTime) / 200);
        if (view.hd && t > o.time) a = Math.max(0, Math.min(a, 1 - (t - o.time) / Math.max(1, o.endTime - o.time)));

        var bodyAlpha = view.hd && t > o.time ? a : Math.max(a, t > o.time && t <= o.endTime ? 1 : 0);
        var col = this.comboRgb(o);

        if (bodyAlpha > 0 && o.path && o.path.length > 1) {
            var widthPx = 2 * view.radius * this.s;
            var border = sk.ini.sliderborder || [235, 235, 245];
            var track = sk.ini.slidertrackoverride || [col[0] / 4 + 8, col[1] / 4 + 8, col[2] / 4 + 12];

            g.save();
            g.lineCap = "round";
            g.lineJoin = "round";
            g.beginPath();
            g.moveTo(this.px(o.path[0][0]), this.py(o.path[0][1]));

            for (var i = 1; i < o.path.length; i++) {
                g.lineTo(this.px(o.path[i][0]), this.py(o.path[i][1]));
            }

            g.globalAlpha = bodyAlpha;
            g.strokeStyle = rgbCss(border);
            g.lineWidth = widthPx * 0.98;
            g.stroke();
            g.strokeStyle = rgbCss(track);
            g.lineWidth = widthPx * 0.82;
            g.stroke();
            g.restore();

            // the ticks the ball has not crossed yet
            var progress = t <= o.time ? 0 : (t - o.time) / o.spanDuration;
            var span = Math.floor(progress);
            var forward = (span & 1) === 0;
            var inSpan = progress - span;
            var tick = sk.get("sliderscorepoint");

            for (var n = 0; n < o.ticks.length; n++) {
                var f = forward ? o.ticks[n] : 1 - o.ticks[n];

                if (t > o.time && (forward ? f <= inSpan : (1 - f) <= inSpan)) continue;

                var tp = pointAt(o, f);
                this.sprite(g, tick, this.px(tp[0]), this.py(tp[1]), k, bodyAlpha, 0);
            }

            // reverse arrows at the ends the ball will still turn back from
            var len = o.path.length;

            if (o.slides > 1 && len >= 2 && t <= o.endTime) {
                var raw = (t - o.time) / o.spanDuration;
                var idx = Math.max(0, Math.min(o.slides - 1, Math.floor(raw)));
                var endArrow = false, startArrow = false;

                for (var e = idx; e < o.slides - 1; e++) {
                    if ((e & 1) === 0) endArrow = true;
                    else startArrow = true;
                }

                var arrow = sk.get("reversearrow");
                var p0 = o.path[0], p1 = o.path[1], pe = o.path[len - 1], pf = o.path[len - 2];

                if (endArrow) {
                    this.sprite(g, arrow, this.px(pe[0]), this.py(pe[1]), k, bodyAlpha,
                        Math.atan2(pf[1] - pe[1], pf[0] - pe[0]));
                }

                if (startArrow) {
                    this.sprite(g, arrow, this.px(p0[0]), this.py(p0[1]), k, bodyAlpha,
                        Math.atan2(p1[1] - p0[1], p1[0] - p0[0]) + Math.PI);
                }
            }
        }

        // the head until it is passed
        var hx = this.px(o.x), hy = this.py(o.y);

        if (t <= o.time) {
            this.circleSprite(g, o, hx, hy, a, 1, true);
            this.approachSkin(g, o, hx, hy, t, a);
        } else {
            var since = t - o.time;
            var d;

            if (o.judge !== "miss") {
                d = since / 240;
                if (d < 1) this.circleSprite(g, o, hx, hy, 1 - d, 1 + 0.4 * d, true);
            } else if (!view.hd) {
                var w = view.w50;

                if (since < w) {
                    this.circleSprite(g, o, hx, hy, 1, 1, true);
                } else {
                    d = (since - w) / 240;
                    if (d < 1) this.circleSprite(g, o, hx, hy, 1 - d, 1, true);
                }
            }
        }

        // the ball, with the follow circle while it is being held
        if (t > o.time && t <= o.endTime) {
            var b = ballAt(o, (t - o.time) / o.spanDuration);
            var bx = this.px(b[0]), by = this.py(b[1]);
            var cur = this.cursorAt(t);
            var held = this.keyDown.some(Boolean);
            var near = Math.hypot(cur[0] - b[0], cur[1] - b[1]) < view.radius * 2.4;

            if (held && near) this.sprite(g, sk.get("sliderfollowcircle"), bx, by, k, 0.9, 0);

            this.sprite(g, sk.ini.allowsliderballtint ? sk.tinted("sliderb0", col) : sk.get("sliderb0"), bx, by, k,
                view.hd ? 0.9 : 1, 0);
        }
    };

    // ---- spinners

    Player.prototype.drawSpinnerSkin = function (g, o, t) {
        var sk = this.skin, u = this.u, W = this.W, H = this.H;

        if (t < o.time - 400 || t > o.endTime + 200) return;

        var a = t < o.time ? (t - (o.time - 400)) / 400 : t > o.endTime ? 1 - (t - o.endTime) / 200 : 1;
        var progress = Math.max(0, Math.min(1, (t - o.time) / Math.max(1, o.endTime - o.time)));
        var cx = W / 2, cy = H / 2 + 10 * u;

        // a full-screen backdrop when the skin has one, then the spinning disc, about 560 frame pixels tall
        var backdrop = sk.get("spinner-background");

        if (backdrop && !backdrop.hidden) {
            g.save();
            g.globalAlpha = a * 0.9;
            g.drawImage(backdrop.el, 0, 0, W, H);
            g.restore();
        }

        var circle = sk.get("spinner-circle");
        var f = 560 * u / circle.h;
        // how far the disc has turned is not in the frames; it follows the cursor around the centre
        var cur = this.cursorAt(t);
        var turn = Math.atan2(cur[1] - 192, cur[0] - 256);

        this.sprite(g, sk.get("spinner-bottom"), cx, cy, f, a * 0.8, 0);
        this.sprite(g, circle, cx, cy, f, a, turn);
        this.sprite(g, sk.get("spinner-middle"), cx, cy, f, a, 0);

        var middle2 = sk.get("spinner-middle2");

        if (middle2 && sk.has("spinner-middle2")) {
            this.sprite(g, middle2, cx, cy, 560 * u / middle2.h, a * 0.8, 0);
        }

        this.sprite(g, sk.get("spinner-approachcircle"), cx, cy, f * (1 - 0.92 * progress), a, 0);

        var spin = sk.get("spinner-spin");

        if (spin && t < o.time + 600) {
            this.sprite(g, spin, cx, H - 120 * u, 420 * u / spin.w, a, 0);
        }
    };

    // ---- follow points, judgements, cursor

    Player.prototype.drawFollowPointsSkin = function (g, objs, from, t) {
        var img = this.skin.get("followpoint");
        var view = this.view, r = view.radius;
        // the spacing is in playfield units, as in the video
        var spacing = img.w > 40 ? img.w * 0.9 : 32 * 0.8;

        for (var i = Math.max(0, from - 1); i < objs.length - 1; i++) {
            var a = objs[i], b = objs[i + 1];

            if (b.time - view.preempt > t) break;
            if (!a.followNext) continue;

            var e = endPosition(a);
            var dx = b.x - e[0], dy = b.y - e[1];
            var dist = Math.hypot(dx, dy);

            if (dist < r * 2) continue;

            var start = r;
            var dots = Math.max(1, Math.floor((dist - r * 2) / spacing));
            var ang = Math.atan2(dy, dx);

            for (var d = 0; d < dots; d++) {
                var f = (start + (d + 0.5) * ((dist - 2 * start) / dots)) / dist;
                var tf = a.endTime + (b.time - a.endTime) * f;
                var al = Math.min(1, (t - (tf - view.preempt * 0.55)) / 150);

                if (t >= tf) al = Math.min(al, Math.max(0, 1 - (t - tf) / 150));

                if (al > 0) this.sprite(g, img, this.px(e[0] + dx * f), this.py(e[1] + dy * f), this.s, al * 0.8, ang);
            }
        }
    };

    Player.prototype.drawJudgementSkin = function (g, o, t) {
        var name = o.judge && HIT_NAMES[o.judge];

        // a 300 is not announced, like in the video
        if (!name || o.type === SPINNER) return;

        var d = t - (o.type === CIRCLE ? o.time : o.endTime);

        if (d < 0 || d > 500) return;

        var sk = this.skin;
        // a skin that animates the popup ships name-0.png, which is what stable draws first
        var pop = sk.get(name + "-0") || sk.get(name);
        var pos = o.type === SLIDER ? endPosition(o) : [o.x, o.y];
        var fall = o.judge === "miss" ? d * 0.04 * this.u : 0;
        var grow = d < 80 ? 0.85 + 0.15 * d / 80 : 1;

        this.sprite(g, pop, this.px(pos[0]), this.py(pos[1]) + 20 * this.u + fall, this.s * 0.6 * grow,
            d < 350 ? 1 : 1 - (d - 350) / 150, 0);
    };

    Player.prototype.drawCursorSkin = function (g, t) {
        var sk = this.skin, s = this.s;
        var rate = this.opts.rate || 1;
        var trail = sk.get("cursortrail");

        for (var i = 14; i >= 1; i--) {
            var p = this.cursorAt(t - i * 11 * rate);
            this.sprite(g, trail, this.px(p[0]), this.py(p[1]), s * 0.65, (1 - i / 15) * 0.45, 0);
        }

        var c = this.cursorAt(t);
        var x = this.px(c[0]), y = this.py(c[1]);
        var down = this.keyDown.some(Boolean);
        var scale = s * 0.55 * (down && sk.ini.cursorexpand ? 1.15 : 1);

        this.sprite(g, sk.get("cursor"), x, y, scale, 1, 0);

        // the centre piece is only drawn when the server skin itself has one
        if (sk.has("cursormiddle")) this.sprite(g, sk.get("cursormiddle"), x, y, scale, 1, 0);
    };

    // ---- HUD

    Player.prototype.drawHudSkin = function (g, score) {
        var sk = this.skin, W = this.W, H = this.H, u = this.u;
        var mode = this.opts.mode || 0;

        this.drawKeysSkin(g);
        this.drawModsSkin(g);

        var sp = sk.ini.scoreprefix, cp = sk.ini.comboprefix;
        var shown = String((score && score.score) || 0);

        while (shown.length < 8) shown = "0" + shown;

        // score and accuracy, top right; combo, bottom left
        this.drawNumberH(g, shown, sp, W - 20 * u, 34 * u, 44 * u, 1, true);
        this.drawNumberH(g, (accuracyOf(score, mode) * 100).toFixed(2) + "%", sp, W - 20 * u, 80 * u, 26 * u, 0.9, true);
        this.drawNumberH(g, ((score && score.combo) || 0) + "x", cp, 24 * u, H - 44 * u, 52 * u, 1, false);

        // health: the skin's frame, with the coloured bar laid in it and cut to the health that is left. Only when the
        // client reports it (stable does, lazer does not).
        var bg = sk.get("scorebar-bg"), col = sk.get("scorebar-colour");

        if (bg && score && typeof score.hp === "number" && score.hp > 0) {
            var s = 520 * u / bg.pw;

            g.save();
            g.globalAlpha = 0.95;
            g.drawImage(bg.el, 0, 0, bg.pw * s, bg.ph * s);
            g.restore();

            if (col) {
                var srcW = Math.max(1, Math.min(col.pw, Math.floor(col.pw * Math.min(1, score.hp / 200))));
                g.drawImage(col.el, 0, 0, srcW, col.ph, 24 * s, 24 * s, srcW * s, col.ph * s);
            }
        }
    };

    // The four keys at the right edge: lit while held, with how many times each has been pressed.
    Player.prototype.drawKeysSkin = function (g) {
        var sk = this.skin, W = this.W, H = this.H, u = this.u;
        var panel = sk.get("inputoverlay-background"), key = sk.get("inputoverlay-key");

        if (!key) return;

        var midY = H / 2 + 10 * u;
        var keysX = W - 48 * u;

        // the panel is the skin's artwork: label bar on its left, the pattern the keys sit on at its right
        if (panel && sk.has("inputoverlay-background")) {
            var perPixel = 300 * u / panel.ph;
            var w = panel.pw * perPixel;
            var left = W - 6 * u - w;

            this.sprite(g, panel, left + w / 2, midY, perPixel / panel.logical, 0.95, 0);
            keysX = left + w * 0.6325;
        }

        var lit = sk.recolored("inputoverlay-key", [255, 196, 64]);
        var labels = ["K1", "K2", "M1", "M2"];
        var small = 11 * u, big = 17 * u, gap = 3 * u;
        var font = "Torus, 'M PLUS Rounded 1c', sans-serif";

        g.textAlign = "center";
        g.textBaseline = "alphabetic";

        for (var i = 0; i < 4; i++) {
            var on = this.keyDown[i];
            var cy = midY + (i - 1.5) * 66 * u;

            this.sprite(g, on ? lit : key, keysX, cy, 56 * u / key.h, on ? 1 : 0.9, 0);

            // the two lines are one block, centred on the key both ways (text sits on its baseline)
            var top = cy - (small * 0.75 + gap + big * 0.75) / 2;

            g.fillStyle = on ? "rgb(30,24,10)" : "rgb(235,235,245)";
            g.font = "700 " + Math.round(small) + "px " + font;
            g.fillText(labels[i], keysX, top + small * 0.75);
            g.font = "700 " + Math.round(big) + "px " + font;
            g.fillText(String(this.keyCounts[i] || 0), keysX, top + small * 0.75 + gap + big * 0.75);
        }
    };

    // The mods of the play, shown for the first seconds and then faded out.
    Player.prototype.drawModsSkin = function (g) {
        var elapsed = (this.time - this.clipStart) / (this.opts.rate || 1);

        if (!this.started || elapsed < 0 || elapsed > 4500) return;

        var mods = this.opts.mods || [];
        var names = [];

        mods.forEach(function (m) {
            // NC and DT are one icon (the stronger shows), like PF and SD
            if (m === "DT" && mods.indexOf("NC") >= 0) return;
            if (m === "SD" && mods.indexOf("PF") >= 0) return;

            if (MOD_ICONS[m]) names.push(MOD_ICONS[m]);
        });

        if (!names.length) return;

        var u = this.u;
        var a = Math.min(1, elapsed / 500) * (elapsed > 3600 ? 1 - (elapsed - 3600) / 900 : 1);
        var size = 84 * u, step = 64 * u;
        var x0 = this.W - 24 * u - (size + (names.length - 1) * step);

        for (var i = 0; i < names.length; i++) {
            var icon = this.skin.get("selection-mod-" + names[i]);

            if (icon) this.sprite(g, icon, x0 + size / 2 + i * step, 150 * u, size / icon.h, a, 0);
        }
    };

    function roundRect(g, x, y, w, h, r) {
        g.beginPath();
        g.moveTo(x + r, y);
        g.arcTo(x + w, y, x + w, y + h, r);
        g.arcTo(x + w, y + h, x, y + h, r);
        g.arcTo(x, y + h, x, y, r);
        g.arcTo(x, y, x + w, y, r);
        g.closePath();
    }

    window.KonekoSpectate = {
        Player: Player,
        parseOsu: parseOsu,
        modsFromBits: modsFromBits,
        rateOf: rateOf,
        accuracyOf: accuracyOf,
        // for tests and plugins
        prepare: prepare,
        ballAt: ballAt
    };
})();
