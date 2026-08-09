# koneko-web

The web frontend for a [bancho.jar](https://github.com/openbancho/bancho.jar)
server: Java 25, Gradle, Javalin 7 and Vue 3.

Pages are assembled the way the JavalinVue plugin used to do it - one HTML
layout plus one `.vue` file per component - by `vue/KonekoVue.java`, because the
plugin itself does not exist in Javalin 7 any more.

This project renders pages and talks to the bancho.jar public API. It has no
database and no Redis of its own: every piece of data comes from
`https://api.<domain>/api/v1/...`.

## Screenshots

<table>
  <tr>
    <td width="50%" align="center">
      <img src="https://i.ibb.co/kRf2SXJ/image.png" width="440" alt="The front page: hero band, server numbers, new players and the best plays">
      <br><sub><b>Front page</b></sub>
    </td>
    <td width="50%" align="center">
      <img src="https://i.ibb.co/6cq4ppRv/photo-2026-08-09-17-42-49.jpg" width="440" alt="A player profile: ranks, the graph, the medals and the score lists">
      <br><sub><b>Profile</b></sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <img src="https://i.ibb.co/PszCKFSx/image.png" width="440" alt="The beatmap listing: search panel, filters and the set cards">
      <br><sub><b>Beatmaps</b></sub>
    </td>
    <td width="50%" align="center">
      <img src="https://i.ibb.co/tPX9hs4v/image.png" width="440" alt="The staff panel overview: server state and the moderation sections">
      <br><sub><b>Staff panel</b></sub>
    </td>
  </tr>
</table>

## Why this frontend

- **Nothing of its own to run.** No database, no Redis, no migrations, no cron.
  One jar next to bancho.jar, and every number on every page comes from the
  public API - so the site can never disagree with the server about a score.
- **The API token never reaches the browser.** Logging in is a
  backend-for-frontend flow: the token pair lives in memory on this side and the
  browser holds an opaque `HttpOnly` cookie. An XSS here cannot walk away with an
  API token. See [How the login works](#how-the-login-works).
- **Pages that look like the game.** The leaderboard, the beatmap page and the
  score page follow the layouts osu! itself uses - star colours from song select,
  the grade and the total on the cover of the set, the judgements in the client's
  own colours - so nothing has to be re-learned to be read.
- **Every score is a page you can link to.** A row on a leaderboard or a profile
  is a link to `/scores/{id}`, with the judgements, the world rank, the mods and
  the replay download in one place.
- **A plugin host, not a fork target.** `.jar` plugins add nav entries, footer
  links, whole pages and components in named slots, and may hide core entries by
  id. Adding a feature does not mean maintaining a diff against this repo.
- **A staff panel that is gated twice.** The panel routes check staff rights
  before anything is served, and the API checks again on every call. The
  profile-reading endpoints go through a server-side allowlist rather than a
  blanket proxy.
- **One theme, not eleven.** Three corner radii, one accent colour, neutral
  surfaces, `prefers-reduced-motion` respected, skeletons instead of spinners,
  and a layout that folds to one column on a phone.
- **FastLoad.** A page paints from the last answer it cached and replaces it when
  the fresh one arrives, so moving between profiles does not flash empty cards.
- **No build step for the frontend.** No npm, no bundler, no lockfile. Vue is
  pinned in `layout.html`, and in `LEVEL=DEV` the `.vue` files are re-read per
  request - a browser refresh is the whole edit loop.

## What is implemented

| Page | Route | Data |
| --- | --- | --- |
| Front page | `/` | `get_server_stats`, `get_recent_players`, `get_top_scores` |
| Leaderboard | `/leaderboard` | `get_leaderboard`, `get_countries` |
| Beatmap listing | `/beatmaps` | `search_beatmapsets` |
| Beatmap | `/beatmapsets/{setId}` | `get_beatmapset`, `get_map_scores` |
| Score | `/scores/{scoreId}` | `get_score_details`, `get_replay` |
| Profile | `/u/{id or name}` | `get_player_details`, `get_player_scores`, `get_player_beatmapsets`, `get_player_most_played`, through `/data/player/*` |
| Own profile | `/me` | redirects to `/u/<your id>` |
| Login | `/login` | `POST /auth/login` here, then `oauth/token` (password grant) |
| Sign up | `/register` | `POST /auth/register` here |
| Settings | `/settings` | `/account/*` here: avatar, banner, badge icon, 2FA |
| Restrictions | `/restrictions` | `/data/docs/restrictions` |
| Staff panel | `/admin/...` | `/admin/api/*`, staff only |

## First clone

```
cp .env.example .env
cp .config/config.example.yml .config/config.yml
```

Both `.env` and `.config/config.yml` are gitignored: the repository only ever holds the
`.example` versions, filled with placeholders. Nothing in the repo contains a
real domain, host or secret.

## Configuration

Two files, on purpose:

- **`.env`** - deployment settings only: port, domain, API url, level, API
  client id and the FastLoad windows. Same style as bancho.jar. Copy
  `.env.example` to `.env`.
- **`.config/config.yml`** - the texts of the site: server name, front page
  description, the Discord link and the plugin settings. Copy
  `.config/config.example.yml` to `.config/config.yml`.

Everything else - the API scopes, the API and session timeouts and the whole
plugin host layout - is a fixed constant in the code, so there is nothing to
set up for it.

## Running
```
cp .env.example .env
cp .config/config.example.yml .config/config.yml
./gradlew shadowJar
java -jar build/libs/koneko-web-shaded.jar
```

The Gradle wrapper is included (Gradle 9.0.0, the same one bancho.jar uses),
so no local Gradle installation is needed - only a JDK 25.

## Putting it behind nginx

`nginx/koneko-web.conf` serves the apex domain from this app while every
subdomain the game client uses stays on bancho.jar:

| Name | Goes to |
| --- | --- |
| `example.com` | koneko-web, `127.0.0.1:8300` |
| `www.example.com` | redirect to the apex |
| `c*.example.com`, `osu.`, `api.` | bancho.jar, `127.0.0.1:8200` |

The apex has to be **removed** from the `server_name` list of the bancho.jar
block first - two blocks cannot claim the same name. The wildcard certificate
already covers everything, so nothing new has to be issued.

During development set `LEVEL=DEV` in `.env` and use `./gradlew run`: the
`.vue` files are then read from disk on every request, so a browser refresh is
enough to see changes, and session cookies are not marked `Secure` so plain
`http://localhost:8300` works.

## How the login works

The browser never talks to the API directly - that would need CORS with
credentials, and the API answers `Access-Control-Allow-Origin: *`, which
browsers refuse to combine with cookies. Instead this app is a small
backend-for-frontend:

1. The browser posts the username and password to `POST /auth/login` here.
2. koneko-web calls `POST /api/v1/oauth/token` (password grant) server side
   and receives the access/refresh pair.
3. The pair is kept in memory, server side, and the browser only gets an
   opaque `koneko_session` cookie (HttpOnly, SameSite=Lax).
4. Access tokens are refreshed automatically when they are about to expire.
   `POST /auth/logout` revokes the refresh token, which takes the whole chain
   down, and drops the session.

Because the token pair never reaches the browser, an XSS on this frontend
cannot walk away with an API token.

## Layout

```
src/main/java/com/osuserverlist/koneko/
  App.java              bootstrap: env, config, Javalin, KonekoVue
  config/               .env and .config/config.yml loading
  api/                  HTTP client for the bancho.jar API
  auth/                 server side sessions and token refresh
  plugin/               the plugin host: slots, pages, nav contributions
  routes/               page routes, /auth/*, /data/*, /admin/*
  vue/                  the KonekoVue state function
src/main/resources/
  vue/layout.html       the single HTML layout KonekoVue serves
  vue/components/*.vue  navigation, footer, small widgets
  vue/views/*.vue       one file per route
  public/css/koneko.css styling
```
