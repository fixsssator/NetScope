# Changelog

All notable changes to NetScope are documented here.

## [0.2.0] - 2026-10-11

### Added
- Per-app received and sent byte counters using Android UID traffic statistics.
- Refresh action and traffic-volume sorting on the statistics screen.
- Version metadata managed centrally through Gradle properties.
- Tag-based GitHub Actions release workflow for versioned APK releases.

### Fixed
- Android SDK setup in GitHub Actions now uses the runner's preinstalled SDK paths.

### Limitations
- Live DNS observation and packet capture are not implemented.
- Domain list entries remain sample data.
- UID byte counters do not identify destination domains and may be unavailable for some apps/devices.

## [0.1.0] - Initial scaffold

- Installed-app picker and search.
- Selectable sample domain/IP list.
- Copy selected addresses to clipboard.
- Initial Android CI workflow.
