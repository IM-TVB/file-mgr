# KPE File Manager | NO USER NO UPDATE

A PaperMC plugin that gives you a full web-based server admin panel —
file manager, live console, and command history — gated behind an in-game
OP command. Styled like Pterodactyl, but self-contained: no panel, no
extra services, just a plugin jar.

**Version 1.0.1**

## How it works

1. In-game, an OP runs `/filemgr` (alias `/fm`).
2. The plugin generates a one-time login link and sends it as a clickable
   message in chat.
3. Opening the link redeems the token. If `login-password` is set, you're
   asked for it next. Either way, you land on a session cookie and the
   dashboard.
4. From the dashboard you can browse, create, rename, delete, edit (text
   files, with syntax highlighting), upload/download, zip/unzip, and watch
   + interact with the live server console — all under the configured root
   (by default, your entire server directory).

No password is required by default — access is gated by being OP in-game
and clicking a link that expires in a few minutes. You can layer an
additional password requirement on top (see `login-password` below).

## Features

- **File browser** — sortable columns, search/filter, drag-and-drop upload,
  multi-select with shift-click range selection, bulk delete/rename/download
- **Compress / Extract** — zip a selection into an archive, or extract any
  `.zip` into a new folder (with zip-slip and zip-bomb protection)
- **Code editor** — full-page, line numbers, lightweight syntax highlighting
  for YAML/JSON/properties/TOML/XML/HTML/Java/JS/CSS/shell/Markdown, cursor
  starts at the top of the file, ⌘S/Ctrl+S to save
- **Live console tab** — tails the server log in real time, lets you send
  console commands from the browser, keeps a rolling `console.txt` (capped
  at `console.max-lines`)
- **Command history audit log** — every command a player runs in-game is
  logged to a per-day `cmd-history-yyyy-MM-dd.txt`
- **Optional native HTTPS** — no reverse proxy required if you'd rather the
  plugin terminate TLS itself
- **Optional password step** — require a password in addition to the
  one-time link before granting a session
- **`/filemgr reload`** — reload config.yml live (port, bind address, HTTPS,
  password, size limits, everything) without a server restart. This is a
  plain in-process restart of the plugin's own web server — it doesn't ask
  Paper to reload the plugin at all, so it's unaffected by the PlugManX
  issue below.

## Known limitation: PlugManX / hot-reloading this plugin

`/plugman reload` and `/plugman load` do **not** work for this plugin on
newer Paper builds — you'll see:

```
InvalidPluginException: Plugin didn't load any plugin providers?
```

This is a Paper-level limitation with hot-loading plugins at runtime after
server boot, not specific to this plugin (the same failure shows up for
other plugins' internal hot-load features too) — see
[this write-up](https://madelinemiller.dev/blog/problem-with-reload/) for
why. **Use a full server restart whenever you update the jar.** Config-only
changes don't need this — use `/filemgr reload` instead.

## Build

Requires JDK 17+ and Maven.

```bash
mvn clean package
```

The built jar will be at `target/kpe-filemanager.jar`. Drop it into your
server's `plugins/` folder and restart.

## Configure

Edit `plugins/KPEFileManager/config.yml`, then run `/filemgr reload`
(or restart) to apply changes:

```yaml
port: 8080
bind-address: "0.0.0.0"       # 0.0.0.0 = reachable externally; use 127.0.0.1 if reverse-proxying
root-directory: ""            # "" = full server root
public-url: ""                # e.g. https://fm.kpeclub.site
token-expiry-minutes: 5
login-password: ""            # optional extra password after the link
session-expiry-hours: 12
max-edit-file-size-kb: 20480000
max-upload-size-mb: 512
console:
  max-lines: 400
https:
  enabled: false
  keystore-path: "keystore.jks"
  keystore-password: ""
  key-password: ""
```

See the comments in `config.yml` for the full explanation of each option,
including keystore setup for HTTPS.

## Recommended: put it behind Nginx + SSL (or use built-in HTTPS)

Two ways to get HTTPS:

**Nginx reverse proxy** (least setup if you already run Nginx/Cloudflare):
- Set `bind-address: "127.0.0.1"` so the plugin only listens locally.
- Add an Nginx server block that reverse-proxies to `127.0.0.1:8080`, with
  your existing SSL/Cloudflare cert in front.
- Set `public-url: "https://fm.kpeclub.site"` so `/filemgr` prints the
  right link.

```nginx
server {
    listen 443 ssl;
    server_name fm.kpeclub.site;

    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_cookie_path / "/; Secure";
    }
}
```

**Native HTTPS** (no Nginx needed): set `https.enabled: true` and point
`https.keystore-path` at a `.jks`/`.p12` keystore. See `config.yml` for the
exact `keytool`/`openssl` commands to build one from a Let's Encrypt cert
or a quick self-signed cert for testing.

If you go with neither, and set `bind-address: 0.0.0.0`, the session
cookie and everything else travels unencrypted. The login-password step
and rate limiting still apply, but plain HTTP is best reserved for
LAN-only or testing use.

## Security notes

- Only players with `kpe.filemanager.access` (OP by default) can generate
  login links. `kpe.filemanager.reload` (also OP by default) gates
  `/filemgr reload` separately, so you can grant file access without
  config-reload rights if you want.
- Login tokens are single-use, 256-bit random, and expire after
  `token-expiry-minutes`.
- Optional `login-password` adds a second factor: the link alone isn't
  enough if it leaks or gets screenshotted.
- Sessions are in-memory only (cleared on restart, not on config reload)
  and expire after `session-expiry-hours`. A logout button revokes a
  session immediately.
- Failed session checks, failed token redemptions, and failed passwords
  are all rate-limited per IP (only failures count, so normal use never
  trips it) and logged to the console/`console.txt`.
- Every response carries standard hardening headers (CSP, X-Frame-Options,
  nosniff, etc.) and the session cookie is `HttpOnly` + `SameSite=Strict`
  (+ `Secure` automatically when HTTPS is enabled).
- All file paths are canonicalized and checked against the configured root
  to block `../` path traversal — including inside zip extraction, which
  also has zip-bomb caps (2 GB / 50,000 entries).
- Uploads and file writes are capped (`max-upload-size-mb`,
  `max-edit-file-size-kb`) so a request can't exhaust server memory.
- Root directory itself cannot be deleted through the API.
- The web server's thread pool and console log-hook are explicitly cleaned
  up on disable/restart — otherwise they'd leak the plugin's classloader on
  every reload.

## Package layout

```
site.kpeclub.filemanager
├── FileManagerPlugin     - plugin entrypoint, /filemgr [reload] command
├── WebServer             - wires up the embedded HTTP(S) server
├── TokenManager          - login tokens, password step, sessions, rate limits
├── ConsoleManager        - console ring buffer, console.txt, cmd-history logging
├── ConsoleLogHandler     - JUL handler feeding ConsoleManager
├── PlayerCommandListener - logs every player-issued command
├── RateLimiter           - simple per-key fixed-window rate limiter
├── FileUtils             - safe path resolution, JSON escaping, recursive delete
└── handlers/
    ├── BaseApiHandler       - auth check, rate limiting, JSON/byte helpers
    ├── SecurityHeaders      - CSP/X-Frame-Options/etc. applied to every response
    ├── IndexHandler         - serves dashboard, handles token redemption
    ├── PasswordVerifyHandler
    ├── LogoutHandler
    ├── StaticAssetHandler
    ├── ListHandler, ReadHandler, WriteHandler, DeleteHandler,
    │   MkdirHandler, RenameHandler, UploadHandler, DownloadHandler
    ├── ZipHandler, UnzipHandler
    └── ConsoleHistoryHandler, ConsoleSendHandler
```
