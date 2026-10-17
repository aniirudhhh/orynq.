<h1 align="center">orynq.</h1>

<p align="center">A native Android music player with YouTube Music discovery and Material You design.</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11 or newer">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Design-Material%203-6750A4" alt="Material Design 3">
  <img src="https://img.shields.io/badge/Status-Beta-orange" alt="Beta">
</p>

orynq. brings online music discovery, streaming, and your local library together in a Material You interface. It builds on [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer), extending its native playback and design foundation with YouTube Music features.

This is an **unofficial client**, independently developed and not affiliated with Google or YouTube.

## Discover and listen

- **Personalised Home:** YouTube Music shelves, Quick picks, and recommendations from your connected account.
- **Instant Home:** saved feed content opens without waiting for a fresh request, with quiet refreshes and pull-to-refresh.
- **Focused Search:** separate filters for All, Songs, Albums, Artists, and Playlists, plus collection browsing.
- **Recent searches:** keep the latest 20 unique queries locally, with quick search, editing, individual removal, and clear-all controls.
- **Online playback:** background streaming with Media3, a full player, media notifications, and queue controls.
- **Song radio:** related recommendations when you start an individual song from Home or Search.
- **Daily Mix:** selections informed by your online listening history.
- **Playlists and favourites:** create playlists, add songs, queue tracks, or play a song next through a shared song menu.
- **Lyrics:** multiple sources, with synchronised lyrics when available.
- **Optional listening sync:** report eligible listens to your connected YouTube Music account history.
- **Local music:** retain PixelPlayer's local library browsing and playback alongside online features.

## Made for Android

Built in Kotlin with Jetpack Compose and Material Design 3, with dynamic colours, light and dark themes, artwork-aware styling, and a consistent native player interface.

| Layer | Technology |
| --- | --- |
| Interface | Jetpack Compose · Material 3 |
| Playback | AndroidX Media3 / ExoPlayer |
| YouTube Music integration | InnerTubeX |
| App state | ViewModels · Kotlin Coroutines · Flow |
| Storage | Room · DataStore |
| Artwork | Coil |
| Dependency injection | Hilt |
| Background work | WorkManager |

## Project status

orynq. is in beta. Online features depend on community integrations and service availability. Some recommendations require a connected account, and lyrics coverage varies by song.

Home and artwork caching are implemented. **Offline audio downloads are not implemented.**

See the [performance and regression audit](PERFORMANCE_AUDIT_2026-10-17.md) for measured results, known issues, and remaining verification. Implementation notes are available in [ONLINE_PLAYER.md](ONLINE_PLAYER.md).

## Build locally

Requirements:

- An Android Studio version compatible with the checked-in Android Gradle Plugin.
- JDK 21; the Gradle daemon configuration also specifies a Java 21 toolchain.
- Android SDK Platform 37 and the build tools requested by Gradle.
- An Android 11 / API 30 or newer device or emulator.

Clone this repository:

```sh
git clone https://github.com/aniirudhhh/orynq..git orynq
cd orynq
```

Open the `orynq` folder in Android Studio, allow Gradle to sync, and run the `app` configuration. Configure your Android SDK through Android Studio or a local `local.properties` file.

Build a debug APK on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```sh
./gradlew :app:assembleDebug
```

Run unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`.

Signing keys and local configuration are excluded from version control. The current build configuration falls back to debug signing when the local release keystore is absent; configure your own signing credentials for a distributable release.

The application ID remains `com.theveloper.pixelplay` to preserve the existing installation and its data. The displayed app name is **orynq.**

## Feedback

Report orynq.-specific bugs and feature requests in [this repository's issues](https://github.com/aniirudhhh/orynq./issues). Include your device model, Android version, app version, and steps to reproduce. Please omit account cookies, credentials, and private account data.

## Credits

**PixelPlayer is the foundation of orynq.** Credit goes to [Theo Vilardo](https://github.com/theovilardo), the [PixelPlayer project](https://github.com/PixelPlayerHQ/PixelPlayer), and its contributors for the original player, Material UI, local library, and supporting infrastructure.

The inherited PixelPlayer logo artwork was designed by [Aureal / NPSummers](https://github.com/NPSummers). Additional libraries and attribution are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

orynq.-specific development is maintained in [aniirudhhh/orynq.](https://github.com/aniirudhhh/orynq.). Please direct support requests for this version here rather than to the upstream maintainers.

## License

The existing upstream [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md) remain in place. Refer to those files for the applicable terms and attribution.
