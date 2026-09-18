# Android dev environment from zero (WSL Ubuntu, no Android Studio)

Captured from this machine's actual state. Do once per PC; app-specific phone
bits live in `SETUP.md`, the daily loop in `WORKFLOW.md`.

## 1. Java 17

```bash
java -version   # want: openjdk 17 (this machine: 17.0.20 Ubuntu)
```

Gradle 9 + AGP 9 run on 17. No `JAVA_HOME` needed if `java` is on PATH
(this machine sets neither `JAVA_HOME` nor `ANDROID_HOME`).

## 2. SDK cmdline-tools (no Studio)

```bash
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -sO https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-13114758_latest.zip
mkdir -p latest && mv cmdline-tools/* latest/ && rmdir cmdline-tools
export PATH="$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH"   # + persist in ~/.bashrc
```

(The `latest/` nesting is required — sdkmanager errors out without it.)

## 3. Platform, build-tools, licenses

```bash
yes | sdkmanager --licenses   # accept once; state lands in ~/Android/Sdk/licenses/
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

This machine also carries `platforms;android-36` + `build-tools 35.0.0/36.0.0`
(the 35 line is legacy — 36.0.0 is what builds). Add other API levels the same way.

## 4. Point Gradle at the SDK

Per repo, untracked (never commit — it's machine-specific):

```properties
# local.properties
sdk.dir=/home/<you>/Android/Sdk
```

(`local.properties` is git-ignored here; confirmed untracked.)

## 5. Project skeleton that works with the above

- Gradle wrapper 9.7.1 (`gradle-wrapper.properties` → `gradle-9.7.1-bin.zip`).
- AGP 9.4.0 via version catalog (`gradle/libs.versions.toml`); Kotlin 2.3.10 via
  the Compose plugin. NOTE: no `kotlin-android` plugin — AGP 9 provides Kotlin
  (KGP via the root build file's buildscript classpath); adding the old plugin
  breaks the build.
- Repos: `google()` + `mavenCentral()` in `settings.gradle.kts` (both plugin
  management and dependency resolution).
- `minSdk 34`, `target/compileSdk 36` for this project; adjust per app.
- `versionCode` tracks `git rev-list --count HEAD` (see `app/build.gradle.kts`) —
  needs a git checkout with history, not a bare source drop.

## 6. Verify the whole chain

```bash
./gradlew ktlintFormat testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First green run here took one sdkmanager pass plus the license accept; after
that the loop is the `./gradlew …` one-liner from `WORKFLOW.md`. Traps hit
along the way: Gradle < 9.6 refused by AGP 9 ("Minimum supported Gradle version
is 9.6.0"); `kotlin-android` plugin redundant since AGP 9; `local.properties`
missing shows up as a cryptic SDK-location error, not a missing-file one.
