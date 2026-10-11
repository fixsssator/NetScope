# NetScope

NetScope is an Android networking utility project built in Java for the CS50 final project. It lets users select installed apps, observe DNS query names locally, review domain history, and copy selected domains.

## Versioning

Current development version: **0.5.0** (`versionCode` 5). Versions are defined in `gradle.properties` as `appVersionName` and `appVersionCode`. Follow semantic versioning for the name and always increase the code for a new Android build. Pushing a tag such as `v0.3.0` triggers a tagged build and publishes the versioned debug APK as a GitHub Release.

See [CHANGELOG.md](CHANGELOG.md).

## Features

- English UI with dark/light themes and an installed-app picker featuring icons, search, select all, and clear
- Toggle to include or hide system apps
- Custom adaptive launcher icon
- Local VPN-based IPv4 UDP DNS observation for explicitly selected apps
- On-device domain history with timestamps and returned IP addresses
- Select and copy observed domains
- Per-app received/sent byte counters where Android exposes UID counters
- Persistent capture notification with status, query count, and a Stop action while observation is active
- GitHub Actions build and versioned APK artifacts

## DNS observation scope and limitations

NetScope creates a local Android VPN interface and routes only its synthetic IPv4 DNS address through the tunnel. Queries are forwarded to Cloudflare DNS at **1.1.1.1** and responses are returned to the app. Observed history is stored locally; NetScope does not upload it to a NetScope server.

This is DNS observation, not full packet capture. DNS-over-TLS, DNS-over-HTTPS, IPv6 DNS, hard-coded resolvers, and other bypass paths may not be observed. TUN packets do not reliably identify which selected app originated a DNS query, so captured DNS events are not attributed to individual apps. Behavior varies by Android version and device. Stop the session if name resolution or connectivity behaves unexpectedly.

The app does not inspect TLS traffic, page URLs, messages, or other application payloads. The configured upstream resolver receives the queried domain names.

## Build

Requirements: JDK 17, Gradle 8.11.1, Android SDK platform 35.

```bash
gradle assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`. CI uploads a version-named artifact. Pushing a `v*` tag creates a GitHub Release with the APK attached.

## Privacy

DNS names and timestamps are stored in app preferences on the device. Queries are sent to Cloudflare DNS (1.1.1.1), which receives the queried names. No NetScope-operated server receives the history. Installed-app visibility is requested to populate the picker.

## License

No license has been selected yet.
