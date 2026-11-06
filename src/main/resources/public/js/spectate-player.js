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
            mode: 0, formatVersion: 14, objects: [], comboColors: [], breaks: [], timing: []
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
            o.colour = m.comboColors[colour % m.comboColors.length];
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

    // ------------------------------------------------------------------ the player

    var START_BUFFER = 900;   // beatmap ms gathered before playback starts
    var DELAY = 1600;         // how far behind the newest frame playback runs
    var MAX_LAG = 5000;       // further behind than this, jump ahead
    var MAX_FRAMES = 60000;

    function Player(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext("2d");
        this.view = null;
        this.opts = { mods: [], rate: 1, mode: 0, source: "stable" };
        this.audio = null;
        this.soundOn = false;
        this.bg = null;
        this.reset();

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

        for (var i = 0; i < this.scores.length; i++) this.judge(this.scores[i]);
    };

    /** The mods and mode of the play while its map is still loading, so the clock runs at the right rate. */
    Player.prototype.setOptions = function (opts) {
        this.opts = Object.assign({ mods: [], rate: 1, mode: 0, source: "stable" }, opts || {});
    };

    Player.prototype.setBackground = function (url) {
        var self = this;
        this.bg = null;

        if (!url) return;

        var img = new Image();
        img.onload = function () { self.bg = img; };
        img.src = url;
    };

    Player.prototype.setAudio = function (url) {
        if (this.audio) {
            this.audio.pause();
            this.audio.removeAttribute("src");
            this.audio.load();
        }

        this.audio = null;

        if (!url) return;

        var a = new Audio();
        a.preload = "auto";
        a.src = url;
        a.volume = 0.6;
        this.audio = a;
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

        // the playfield takes 80% of the height, a little below centre, as in the game
        this.s = h * 0.8 / 384;
        this.ox = (w - 512 * this.s) / 2;
        this.oy = (h - 384 * this.s) / 2 + h * 0.02;
    };

    Player.prototype.px = function (x) { return this.ox + x * this.s; };
    Player.prototype.py = function (y) { return this.oy + y * this.s; };

    Player.prototype.draw = function () {
        this.resize();

        var g = this.ctx;
        var W = this.W, H = this.H;

        g.globalAlpha = 1;
        g.fillStyle = "#0b0b10";
        g.fillRect(0, 0, W, H);

        if (this.bg) {
            var r = Math.max(W / this.bg.width, H / this.bg.height);
            var bw = this.bg.width * r, bh = this.bg.height * r;
            g.globalAlpha = 0.22;
            g.drawImage(this.bg, (W - bw) / 2, (H - bh) / 2, bw, bh);
            g.globalAlpha = 1;
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

        // song progress
        var info = this.info();
        if (this.view) {
            g.fillStyle = "rgba(255,255,255,0.15)";
            g.fillRect(0, H - 4 * u, W, 4 * u);
            g.fillStyle = "#ff66aa";
            g.fillRect(0, H - 4 * u, W * info.progress, 4 * u);
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

        // judgement counts
        if (score) {
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
