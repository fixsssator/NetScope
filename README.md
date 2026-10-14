# NetScope

**NetScope is a free, open-source HTTP(S) debugging proxy for Android** - a small, fully free take on
[Reqable](https://github.com/reqable/reqable-app) (Fiddler + Charles + Postman in one app).
It is written in Java as my [CS50](https://cs50.harvard.edu/) final project.

Everything is free: no ads, no accounts, no subscription, no cloud. Captured traffic never leaves the device.

## Features

| Area | What NetScope does |
| --- | --- |
| Capture | Local proxy on `127.0.0.1:8899`, registered as the system proxy through a VPN network (Android 10+) |
| Protocols | HTTP/1.x, HTTPS (TLS interception), WebSocket upgrade (relayed), CONNECT tunnels |
| Viewer | Headers, JSON pretty-print, form decoding, gzip/deflate decoding, hex dump for binary bodies |
| App selection | Choose which apps are captured: all, only selected, or all except selected (like Reqable's app list) |
| Traffic source | Shows which app produced each request |
| Search / filter | Free text over method, host, path, status and app; `2xx`-`5xx` quick tokens; *Filters* dialog by status class and method, optional hiding of CONNECT tunnels |
| Type chips | One-tap filter above the list: All / JSON / XML / HTML / JS / CSS / Image / Media / Font / Other |
| Grouping | Group by domain, by **IP address**, by app, or **app → domain** (nested tree); tap a header to collapse |
| Favorites | Star requests; *Favorites only* filter; *Clear* keeps favorites |
| Multi-select | Long-press a request (or a group header) to select; copy domains / URLs / cURL, export domains `.txt` or HAR, share URLs |
| Body viewer | Pretty / Raw / Hex modes, image preview, copy body, save body to Downloads |
| Copy as | cURL, Python (requests), JavaScript (fetch), raw HTTP request |
| Breakpoints | Pause matching requests, edit method / URL / headers / body, then resume or drop |
| SSL proxying list | Decrypt all hosts, only listed ones, or all except listed (`example.com`, `*.example.com`) |
| Composer | Build and send requests (GET/POST/PUT/PATCH/DELETE/...), or replay a captured one |
| Rules | Block, map local, map remote, rewrite request/response headers, breakpoints; create a rule straight from a captured request |
| Export | HAR 1.2 (opens in Chrome DevTools, Reqable, Charles), copy as cURL, share as text |
| Privacy | No network access except the traffic you proxy; the CA key is generated on-device and stays there |

## How it works

```
 app / browser ──► system HTTP proxy (127.0.0.1:8899) ──► ProxyServer ──► ProxyConnection ──► the Internet
                        ▲                                      │
        VpnService.setHttpProxy()                              ├─ TLS interception (CertManager signs a leaf cert per host)
        (no packets are routed through the tunnel)             ├─ RuleEngine (block / map / rewrite)
                                                               └─ TrafficStore ──► UI (list, details, HAR, cURL)
```

* **`vpn/NetScopeVpnService`** creates a VPN network whose only job is to publish the HTTP proxy setting. NetScope itself
  is excluded from that VPN so its upstream connections do not loop back into the proxy.
* **`proxy/ProxyServer` + `ProxyConnection`** parse HTTP/1.1 by hand, handle `CONNECT`, decrypt TLS with a certificate
  minted for the requested host, apply rules, forward the request on a fresh upstream connection (`Connection: close`),
  and stream the response back while keeping up to 2 MB per body for the UI.
* **`proxy/CertManager`** (BouncyCastle) creates a local CA (RSA-2048, 10 years) and one RSA key reused for all leaf
  certificates (valid 300 days, SAN + serverAuth, so Chrome accepts them).
* **`data/AppFilter` + `ui/AppPickerActivity`** store the app selection; the VPN service turns it into
  `addAllowedApplication` / `addDisallowedApplication`, so only the chosen apps are routed to the proxy.
* **`proxy/AppResolver`** uses `ConnectivityManager#getConnectionOwnerUid` to map a connection to an app.
* **`data/TrafficStore`** is a thread-safe in-memory ring buffer (3000 entries) with throttled UI notifications.
* **`rules/RuleEngine`** persists rules in `SharedPreferences` as JSON.

## Using it

1. Install the APK (see the Releases page or build it yourself).
2. Open NetScope, tap the banner (or *Certificate* in the menu) and save the CA to Downloads.
3. Install it: *Settings → Security → Encryption & credentials → Install a certificate → CA certificate*
   (the exact path differs per vendor).
4. Tap **▶**, accept the VPN prompt, and use a browser or any app that honours the system proxy.

## Limitations (please read)

* **Only apps that respect the system proxy are captured.** Chrome, WebView apps, OkHttp/HttpURLConnection apps do.
  Apps that open raw sockets, use QUIC/HTTP3 or ignore the proxy do not. A full packet-level VPN is future work.
* **User-installed CAs are not trusted by every app** (Android 7+ opt-in) and **certificate-pinned apps** reject the
  NetScope certificate. NetScope detects the failed handshake and tunnels that host untouched from then on.
* Upstream connections use **HTTP/1.1**. Clients that offer HTTP/2 are served HTTP/1.1, and `Accept-Encoding` is reduced
  to `gzip, deflate` so bodies can be displayed (Brotli is not decoded).
* Request bodies are buffered in memory (max 64 MB); only the first 2 MB of each body is kept for display.
* WebSocket frames are relayed but not recorded.
* Breakpoints pause the request thread; the client app may time out while you edit (typically 10-60 s). Paused requests are listed in an orange banner - there is no notification yet. Only request breakpoints exist (no response breakpoints).
* Favorites and traffic live in memory only and are lost when the app process ends.
* Requires **Android 10+ (API 29)**.
* The VPN trick needs on-device verification per device/ROM. If an app's traffic does not show up, check that the app
  honours the system proxy.

## Build

Requirements: JDK 17, Gradle 8.11.1, Android SDK platform 35.

```
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/NetScope-<version>-debug.apk`.
Pushing a tag such as `v0.3.0` makes GitHub Actions build the APK and attach it to a GitHub Release.

## Versioning

`appVersionName` / `appVersionCode` live in `gradle.properties`. Follow semantic versioning and always increase
`appVersionCode` for a new build. See [CHANGELOG.md](CHANGELOG.md).

## Design decisions (CS50)

* **Java + plain Android views** - keeps the code easy to read and grade; the only third-party libraries are Material,
  RecyclerView and BouncyCastle (needed to create X.509 certificates).
* **`setHttpProxy` instead of a user-space TCP/IP stack** - a packet-level VPN needs thousands of lines to be correct;
  the proxy approach gives a working, auditable tool and is the same model desktop proxies use.
* **One upstream connection per request** - slower than connection pooling, but removes a whole class of framing bugs
  and keeps the code short.
* **Rules as flat JSON** - small, human-readable, no database needed.

## Security & legal

Only inspect traffic of devices and apps you own or are allowed to test. Treat the generated CA like a password:
remove it from the Android credential store when you no longer need it.

## License

[MIT](LICENSE)
