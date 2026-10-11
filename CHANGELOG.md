# Changelog

## [0.5.0] - 2026-10-11
### Added
- Dark theme with a persistent light/dark preference.
- Installed-app icons in the app picker and per-app traffic list.
- A toggle to include or hide system apps.
- Custom adaptive NetScope launcher icon.

### Improved
- App list entries use compact cards and preserve selected packages when the screen is refreshed.


## [0.4.0] - 2026-10-11
### Improved
- Redesigned the main screen with a clearer capture status card and refreshed visual styling.
- Capture notification now shows an active state and live DNS query count, opens the app on tap, and provides a Stop action.
- DNS history shows resolved IPv4/IPv6 addresses and a per-domain detail screen.
- Added Android notification permission request so the foreground capture notification can appear in the notification shade.


## [0.3.0] - 2026-10-11
### Added
- Initial local VPN-based IPv4 UDP DNS observation for selected apps.
- Persistent notification with a Stop action.
- On-device domain history with timestamps.

### Limitations
- DNS-over-TLS, DNS-over-HTTPS, IPv6 DNS, hard-coded resolvers, and other bypass paths may not be observed.
- DNS queries are forwarded to Cloudflare DNS (1.1.1.1).
- DNS observations are not attributed to individual apps.
- VPN/DNS behavior varies by Android version and device; stop the session if connectivity is affected.

## [0.2.0] - 2026-10-11
- Added per-app RX/TX counters, centralized versioning, and tagged GitHub Releases.
- Fixed BuildConfig generation.
- Domain entries were sample data; live DNS observation was not implemented.

## [0.1.0]
- Initial Android app scaffold.
