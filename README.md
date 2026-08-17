<div align="center">

![LeviLauncher Logo](https://avatars.githubusercontent.com/u/78095377?s=200&v=4)

# PocketCosmosLevi (Pls)

**A lightweight Android launcher for Minecraft: Bedrock Edition — with extra features**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green?style=flat-square&logo=android)](https://www.android.com/)
[![Build](https://img.shields.io/github/actions/workflow/status/khoadangkim2014-arch/Pls/android.yml?style=flat-square&label=CI)](https://github.com/khoadangkim2014-arch/Pls/actions)

</div>

---

## What this is

This repository is a fork of [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid), merged with additional features from [sharath-5br2r-apps/LeviLaunchroid-Extra](https://github.com/sharath-5br2r-apps/LeviLaunchroid-Extra). All credit for the original launcher goes to the LeviMC team; this fork layers on extra tooling on top.

LeviLauncher itself lets you import your own licensed Minecraft: Bedrock Edition APK and run it without a system install, manage multiple isolated game versions, load native SO modules, and manage resource packs and worlds.

### What's added on top of upstream

- **Cosmos module** — an in-launcher panel (`CosmosActivity`) for news/changelog display and session tracking, under `app/src/main/java/.../mods/inbuilt/cosmos/`
- **Memory editor** — a native (C++) memory search/edit tool for loaded game processes, under `app/src/main/cpp/memoryeditor/` and `app/src/main/java/.../mods/memoryeditor/`
- **Pojav-style on-screen controls** — touch control overlay support under `pojav_controls/`
- **Extra docs & examples** — a documentation site (`docs/`) and sample native/mod-menu API projects (`examples/`)

> ⚠️ These additions come from third-party forks and haven't been extensively battle-tested. Review the code yourself before relying on it, especially the native memory-editor module.

---

## System Requirements

- **OS:** Android 9.0 (API 28) or higher
- **Architecture:** ARM64 (v8a)
- **RAM:** 2 GB+ recommended
- **Storage:** 2–5 GB free
- **License:** A legitimately purchased copy of Minecraft: Bedrock Edition

---

## Installing a prebuilt APK

1. Grab the latest debug build from this repo's [Actions runs](https://github.com/khoadangkim2014-arch/Pls/actions) (Artifacts section), or check [Releases](https://github.com/khoadangkim2014-arch/Pls/releases) if one exists
2. Enable "Install from unknown sources" in Android Settings
3. Install the APK and launch it

> **Important:** Use only with a legitimate, licensed copy of Minecraft Bedrock Edition. Don't use this with pirated game files.

---

## Building from source

### Prerequisites

- JDK 21
- Android SDK (compileSdk 36, build-tools 35.0.0)
- Android NDK r28c
- [Xmake](https://xmake.io/)
- Git

### Important: native submodules

This project depends on two native C++ submodules declared in `.gitmodules`:

- [`preloader-android`](https://github.com/LiteLDev/preloader-android) → `app/src/main/cpp/preloader`
- [`libHttpClient`](https://github.com/microsoft/libHttpClient) (Android build target only) → `app/src/main/cpp/libHttpClient`

**If you download this repo as a ZIP from GitHub, these folders will be empty** — GitHub's ZIP export does not include submodule content. Clone with submodules instead:

```bash
git clone --recurse-submodules https://github.com/khoadangkim2014-arch/Pls.git
```

If you already cloned without `--recurse-submodules`, run:

```bash
git submodule update --init --recursive
```

### Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

To build with Firebase Crashlytics enabled, place a real `google-services.json` in `app/` (see `app/google-services.json` for the placeholder format used by CI when no real config is available).

### CI

`.github/workflows/android.yml` builds automatically on push to `main`. It installs the full Android/NDK/Xmake toolchain, builds a debug APK (with or without a real Firebase config), and uploads it as a workflow artifact.

---

## Contributing

- Keep commits small and focused, with clear messages
- Match existing Kotlin/Java style
- Test on-device before opening a PR
- Update this README if you add a feature that changes build steps or app behavior

---

## Usage Guidelines

- Use only with a legitimately owned copy of Minecraft: Bedrock Edition
- Don't use this launcher to violate Mojang's or Microsoft's terms of service
- Credit the LeviMC team and upstream contributors if you fork or redistribute

> **Disclaimer:** This software is provided as-is. The maintainers of this fork and of upstream LeviLauncher are not responsible for bans, data loss, or other issues arising from its use.

---

## Credits

- **[LiteLDev / LeviMC team](https://github.com/LiteLDev/LeviLaunchroid)** — original LeviLauncher
- **[sharath-5br2r-apps](https://github.com/sharath-5br2r-apps/LeviLaunchroid-Extra)** — memory editor, Pojav controls, and other extras merged into this fork
- **[Microsoft libHttpClient](https://github.com/microsoft/libHttpClient)** and **[preloader-android](https://github.com/LiteLDev/preloader-android)** — native dependencies

See `LICENSE` and `NOTICE` for full license details (Apache 2.0).

---

## Support

Open an issue on this repository, or check the upstream [LeviLaunchroid issues](https://github.com/LiteLDev/LeviLaunchroid/issues) for launcher-wide (non-fork-specific) problems.

<div align="center">

**Based on LeviLauncher, made by the LeviMC Community**

</div>
