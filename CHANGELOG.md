# Changelog

## 0.3.0

Complete rewrite: NetScope is now a free, open-source HTTP(S) debugging proxy for Android in the spirit of
Reqable / Charles / HTTP Toolkit.

- Local MITM proxy (HTTP/1.1, HTTPS via on-device CA, WebSocket upgrade passthrough, CONNECT tunnels)
- System-wide capture through a VPN network that only registers the proxy (`VpnService.Builder#setHttpProxy`)
- Traffic list with live updates, text filter and status-class filter (`2xx`, `4xx`, `5xx`)
- Filters dialog (status class, method, hide tunnels) and grouping by domain or app with collapsible groups
- Multi-select with long press: copy domains/URLs/cURL, export domains (.txt) or HAR, share URLs
- Request/response viewer: headers, pretty-printed JSON, form decoding, gzip/deflate decoding, hex view for binary
- App selection: capture all apps, only selected apps, or all except selected (searchable list, system apps optional); changing it restarts a running capture
- Traffic source: shows which app made each request
- Composer (API tester) and one-tap replay of captured requests
- Rules: block, map local, map remote, request/response header rewrite
- Export HAR 1.2 and copy as cURL
- Per-device certificate authority; hosts that reject it (pinning) are tunnelled automatically
- MIT license, no ads, no accounts, no paid tier

## 0.2.0

- Initial Android scaffold: installed-app picker, per-UID byte counters, sample domain list
