# Changelog

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
