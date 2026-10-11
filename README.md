# NetScope

NetScope is an Android networking utility project built in Java for the CS50 final project.

The goal is to help users choose which installed apps they want to inspect, review observed domains and IP addresses, select individual entries, and copy only the selected values.

## Current status

This repository contains the initial Android app scaffold:
- English UI
- Installed-app selection with checkboxes
- Select all / clear selection
- A selectable domain list
- Copy selected domains to the clipboard
- GitHub Actions build workflow

**Important:** live packet capture, per-app traffic attribution, and real domain discovery are not implemented yet. The domain screen currently uses clearly labelled sample entries so the selection and copy workflow can be tested without pretending that sample data is captured traffic.

## Build

Requirements:
- JDK 17
- Gradle 8.11.1
- Android SDK (platform 35)

From the repository root:

```bash
gradle assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

You can also run the **Android CI** workflow from the Actions tab. Successful builds upload the debug APK as a workflow artifact.

## Install in Android Studio

1. Clone or download this repository.
2. Open the project directory in Android Studio.
3. Allow Gradle to sync and install the required Android SDK platform.
4. Run the `app` configuration on an emulator or device.

## Planned work

1. Implement local VPN-based DNS observation with safe packet handling.
2. Investigate reliable per-app attribution and Android-version limitations.
3. Store capture sessions and observations locally.
4. Add filters, deduplication, timestamps, and TXT/CSV export.
5. Add tests and document capture limitations.

## Privacy

The initial version does not send captured data to a remote server. Live capture is not implemented in this scaffold. App visibility is requested only to populate the installed-app picker.

## License

No license has been selected yet.
