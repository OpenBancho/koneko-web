#!/usr/bin/env node
/*
 * Copies the pictures the live spectator draws with out of bancho.jar's replay-render skin, so the page looks like the
 * best-play videos (SkinSet.java / FrameRenderer.java there).
 *
 *   node tools/sync-spectate-skin.js [path to bancho.jar's src/main/resources/render]
 *
 * It resolves every name the way SkinSet.load does: the server skin (skin-misuzu) first, then the bundled default skin,
 * and in each of them name@2x.png before name.png. What it finds goes to src/main/resources/public/spectate-skin/ as
 * <name>.png, together with skin.json: for each picture its size, the factor that turns pixels into the 640x480 plane
 * ("logical": 0.5 for @2x), whether the server skin itself supplies it, and the skin.ini values the renderer reads.
 * Run it again whenever that skin changes, and rebuild.
 */
"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const SRC = path.resolve(process.argv[2] || path.join(ROOT, "..", "bancho.jar", "src", "main", "resources", "render"));
const OUT = path.join(ROOT, "src", "main", "resources", "public", "spectate-skin");

const MAIN = path.join(SRC, "skin-misuzu");
const DEFAULT = path.join(SRC, "skin");

const digits = prefix => Array.from({ length: 10 }, (_, i) => prefix + "-" + i);

// "core" is needed before a play can be drawn; "spinner" is large and is fetched in the background.
const GROUPS = {
    core: [
        "hitcircle", "hitcircleoverlay", "approachcircle", "sliderstartcircle", "sliderstartcircleoverlay",
        "sliderb0", "sliderfollowcircle", "sliderscorepoint", "reversearrow", "followpoint",
        "hit0", "hit0-0", "hit100", "hit100-0", "hit50", "hit50-0",
        "cursor", "cursormiddle", "cursortrail", "inputoverlay-background", "inputoverlay-key",
        "scorebar-bg", "scorebar-colour",
        ...digits("default"), ...digits("score"), "score-dot", "score-comma", "score-percent", "score-x",
        ...digits("combo"), "combo-x",
        ...["doubletime", "easy", "flashlight", "halftime", "hardrock", "hidden", "nightcore", "nofail", "perfect",
            "relax", "relax2", "spunout", "suddendeath"].map(n => "selection-mod-" + n)
    ],
    spinner: [
        "spinner-background", "spinner-bottom", "spinner-circle", "spinner-middle", "spinner-middle2",
        "spinner-approachcircle", "spinner-spin"
    ]
};

function find(dir, name) {
    for (const candidate of [name + "@2x.png", name + ".png"]) {
        const file = path.join(dir, candidate);

        if (fs.existsSync(file)) {
            return { file, logical: candidate.includes("@2x") ? 0.5 : 1 };
        }
    }

    return null;
}

function size(file) {
    const b = fs.readFileSync(file);

    if (b.length < 24 || b.toString("latin1", 1, 4) !== "PNG") {
        throw new Error("not a png: " + file);
    }

    return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

// SkinSet.readIni: "key: value" lines, "//" comments and "[sections]" skipped, the first occurrence of a key wins.
function readIni(file) {
    const ini = {};

    for (const raw of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
        const line = raw.trim();
        const i = line.indexOf(":");

        if (i > 0 && !line.startsWith("//") && !line.startsWith("[")) {
            const key = line.slice(0, i).trim().toLowerCase();

            if (!(key in ini)) ini[key] = line.slice(i + 1).trim();
        }
    }

    return ini;
}

const rgb = v => (v ? v.split(",").slice(0, 3).map(n => parseInt(n.trim(), 10)) : null);
const num = v => (v === undefined || Number.isNaN(Number(v)) ? null : Number(v));
const flag = (v, fallback) => (v === undefined ? fallback : v !== "0");

if (!fs.existsSync(MAIN) || !fs.existsSync(DEFAULT)) {
    console.error("skin folders not found under " + SRC);
    process.exit(1);
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const sprites = {};
const missing = [];

for (const [group, names] of Object.entries(GROUPS)) {
    for (const name of names) {
        const inMain = find(MAIN, name);
        const hit = inMain || find(DEFAULT, name);

        if (!hit) {
            missing.push(name);
            continue;
        }

        fs.copyFileSync(hit.file, path.join(OUT, name + ".png"));
        sprites[name] = { ...size(hit.file), logical: hit.logical, main: !!inMain, group };
    }
}

const raw = readIni(path.join(MAIN, "skin.ini"));
const combo = [];

for (let n = 1; raw["combo" + n]; n++) combo.push(rgb(raw["combo" + n]));

const manifest = {
    ini: {
        combo,
        hitcircleprefix: raw.hitcircleprefix || "default",
        scoreprefix: raw.scoreprefix || "score",
        comboprefix: raw.comboprefix || "score",
        hitcircleoverlap: num(raw.hitcircleoverlap),
        scoreoverlap: num(raw.scoreoverlap),
        combooverlap: num(raw.combooverlap),
        hitcircleoverlayabovenumber: flag(raw.hitcircleoverlayabovenumber, true),
        cursorexpand: flag(raw.cursorexpand, true),
        allowsliderballtint: flag(raw.allowsliderballtint, false),
        sliderborder: rgb(raw.sliderborder),
        slidertrackoverride: rgb(raw.slidertrackoverride)
    },
    sprites
};

fs.writeFileSync(path.join(OUT, "skin.json"), JSON.stringify(manifest));

const total = fs.readdirSync(OUT).reduce((n, f) => n + fs.statSync(path.join(OUT, f)).size, 0);
console.log(Object.keys(sprites).length + " pictures, " + (total / 1048576).toFixed(1) + " MB -> " + OUT);

if (missing.length) {
    console.log("not in either skin (drawn without): " + missing.join(", "));
}
