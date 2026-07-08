# KPE File Manager

A PaperMC plugin that gives you a full web-based file manager for your server,
gated behind an in-game OP command.

## How it works

1. In-game, an OP runs `/filemgr` (alias `/fm`).
2. The plugin generates a one-time login link and sends it as a clickable
   message in chat.
3. Opening the link redeems the token, sets a secure session cookie, and
   drops you into the dashboard.
4. From the dashboard (browser) you can browse, create, rename, delete,
   edit (text files), upload, and download any file under the configured
   root — by default, your entire server directory.

No password is ever typed into the browser — access is entirely gated by
being OP in-game and clicking a link that expires in a few minutes.

## Build

Requires JDK 17+ and Maven.

```bash
mvn clean package
```

The built jar will be at `target/kpe-filemanager.jar`. Drop it into your
server's `plugins/` folder and restart (or `/reload`, though a restart is
safer).

## Configure

Edit `plugins/KPEFileManager/config.yml` after the first run:

```yaml
port: 8080
bind-address: "0.0.0.0"   # keep this if you're reverse-proxying
root-directory: ""          # "" = full server root
public-url: ""              # e.g. https://fm.kpeclub.site
token-expiry-minutes: 5
session-expiry-hours: 12
max-edit-file-size-kb: 2048
```

## Recommended: put it behind Nginx + SSL

Since you already run Nginx/Cloudflare for kpeclub.site, the safest setup is:

- Keep `bind-address: 127.0.0.1` so the plugin only listens locally.
- Add an Nginx server block for e.g. `fm.kpeclub.site` that reverse-proxies
  to `127.0.0.1:8080`, with your existing SSL/Cloudflare setup in front.
- Set `public-url: "https://fm.kpeclub.site"` in the config so `/filemgr`
  prints the correct link.

Example Nginx block:

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

If you'd rather expose it directly without Nginx, set `bind-address: 0.0.0.0`
and open the port in your Alibaba Cloud security group — but this skips SSL,
so your session cookie would travel unencrypted. Not recommended for a
tool with full filesystem access.

## Security notes

- Only players with the `kpe.filemanager.access` permission (OP by default)
  can generate login links.
- Login tokens are single-use and expire after `token-expiry-minutes`.
- Sessions are stored in memory only (cleared on server restart) and expire
  after `session-expiry-hours`.
- All file paths are canonicalized and checked against the configured root
  to block `../` path traversal.
- Root directory itself cannot be deleted through the API.
- There's no built-in rate limiting on login attempts against tokens — since
  tokens are 256-bit random values this isn't brute-forceable, but keep
  `bind-address` local + reverse-proxied if you're paranoid.

## Package layout

```
site.kpeclub.filemanager
├── FileManagerPlugin   - plugin entrypoint, /filemgr command
├── WebServer           - wires up the embedded HTTP server
├── TokenManager        - login tokens + sessions
├── FileUtils           - safe path resolution, JSON escaping
└── handlers/
    ├── BaseApiHandler  - auth check + JSON/byte response helpers
    ├── IndexHandler    - serves dashboard, handles token redemption
    ├── StaticAssetHandler
    ├── ListHandler, ReadHandler, WriteHandler, DeleteHandler,
    │   MkdirHandler, RenameHandler, UploadHandler, DownloadHandler
```
