<div align="center">
  <img src="Assets/file_00000000a09481f682cb0dad80e83259.png" alt="EndraLink Hydra logo" width="192">

# EndraLink

**One link. Multiple calculators.**

A connection tool for linking Android devices with supported graphing calculators.
</div>

## About

EndraLink is being built to make communication between Android devices and graphing calculators simpler and more accessible. The project is intended to provide a unified experience for transferring files, managing calculator content, and supporting future calculator-connected tools.

The multi-headed hydra represents EndraLink's goal: one application connecting to multiple calculator platforms.

## Planned platform support

- Android
- Casio fx-CG50
- TI-Nspire family

## Planned capabilities

- Detect and connect supported calculators
- Transfer files between calculators and Android devices
- Browse and manage calculator files
- Provide a shared foundation for calculator-specific tools
- Offer a consistent interface across supported calculators

## Project status

EndraLink includes an illustrated Hydra cavern home screen, a circuit-themed fx-CG50 workspace, read-only FAT16 browsing, and exportable diagnostics. TI-Nspire, HP Prime, and Donate are disabled until future releases. Version 0.1.8 follows successful fx-CG50 hardware testing of USB permission, direct calculator access, FAT16 browsing, folder/file listing, and diagnostic export. The calculator rejected the explicit SCSI eject command during that test, so Android/system eject remains the fallback.

## Contributing

Contributions and testing will be welcome once the initial project structure and development guidelines are in place. Until then, use GitHub Issues to share ideas, compatibility findings, and bug reports.

## License

A license has not yet been selected. Until one is added, all rights are reserved by the project owner.

## Build tools and SDK

- [Android Studio and Android SDK](https://developer.android.com/studio)
- Compile/target SDK: Android API 35; minimum: API 26 (Android 8.0).
- JDK 17, Gradle 8.9. CI runs unit tests, APK assembly, and Android lint before publishing.
- [Build results](https://github.com/brandonendall/EndraLink/actions/workflows/android.yml)

## Download Android APK

**[Download EndraLink 0.1.8 APK](https://github.com/brandonendall/EndraLink/releases/download/v0.1.8/EndraLink.apk)**

Latest-release fallback: **[EndraLink.apk](https://github.com/brandonendall/EndraLink/releases/latest/download/EndraLink.apk)**

This public link downloads the APK directly—no GitHub account or ZIP extraction needed. Share it with anyone testing EndraLink. Future successful, versioned builds on `main` publish here automatically.

Requires Android 8.0 or newer. This is a **debug testing build** with fx-CG50 workspace and read-only calculator browsing; calculator file transfers are not enabled yet. TI-Nspire, HP Prime, Donate remain disabled for later releases. The fx-CG50 path is the active calculator workspace.

Open the downloaded APK on Android and allow installation from your browser when asked. If updating an older test build fails because its signing key differs, export any useful logs, uninstall the old app, then install this APK.

For a connection problem, tap **Export debug log**, save the `.txt` file, and send that file with your report. The log records button taps, USB permission checks, connection attempts, and storage errors without recording calculator file contents.

[All releases and checksums](https://github.com/brandonendall/EndraLink/releases)
