# Contributing to OpenMDM Android

Thanks for your interest in contributing! This guide covers the basics for working on the Android components of [OpenMDM](https://github.com/azoila/openmdm).

## Prerequisites

- **JDK 17** (required by AGP 8.x)
- **Android SDK 35** (install via Android Studio or `sdkmanager`)
- Android Studio (recommended) or your editor of choice

Point Gradle at your SDK with a `local.properties` file (Android Studio creates this automatically):

```properties
sdk.dir=/path/to/Android/sdk
```

## Module Layout

| Module     | Description                                                        |
| ---------- | ------------------------------------------------------------------ |
| `:library` | Embeddable MDM SDK — published to JitPack, embed it in your own app |
| `:agent`   | Full-featured MDM agent app — fork this for a branded agent        |

## Building

```bash
# Debug build of both modules
./gradlew assembleDebug

# Library AAR only
./gradlew :library:assembleDebug
```

## Testing

```bash
# All unit tests
./gradlew test

# A single module
./gradlew :library:testDebugUnitTest
```

Please also run lint before opening a PR:

```bash
./gradlew :library:lintDebug :agent:lintDebug
```

## Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(library): add silent uninstall support
fix(agent): handle null serial on API 29+
docs: clarify device owner setup
```

## Pull Request Flow

1. Fork the repository and create a branch from `main`.
2. Make your changes; add or update unit tests where it makes sense.
3. Make sure `./gradlew test` and lint pass locally.
4. Open a PR with a clear description of what and why.
5. **CI must pass** (tests, lint, and debug builds) before a PR can be merged.

Keep PRs focused — small, reviewable changes get merged faster.

## Questions?

Open a [GitHub issue](https://github.com/azoila/openmdm-android/issues) or see the main project at [github.com/azoila/openmdm](https://github.com/azoila/openmdm).
