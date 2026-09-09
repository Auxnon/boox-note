# Boox EinkDraw build helpers.
# Requires: a JDK with javac (e.g. java-21-openjdk-devel, not just -headless) and an Android SDK
# (ANDROID_HOME/ANDROID_SDK_ROOT set, or sdk.dir in local.properties - gitignored, per-machine).
# Run `just` with no args to list recipes.

app_id := "com.auxnon.booxnote"

# List available recipes.
default:
    @just --list

# Build a debug APK (app/build/outputs/apk/debug/app-debug.apk).
build:
    ./gradlew :app:assembleDebug

# Build a release APK (unsigned unless BOOXDRAW_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD are set).
release:
    ./gradlew :app:assembleRelease

# Build both debug and release APKs.
build-all: build release

# Install the debug build on a connected/USB-debugging device via adb. Falls back to an
# uninstall+reinstall if a differently-signed build (e.g. a prior release APK) is already on the
# device - adb can't -r over a signature mismatch. That wipes the app's on-device data (e.g. the
# last-opened-file pref), which is expected/harmless for iterative dev testing.
install: build
    adb install -r app/build/outputs/apk/debug/app-debug.apk || \
        (adb uninstall {{app_id}} && adb install app/build/outputs/apk/debug/app-debug.apk)

# Install the release build on a connected device via adb (signed if BOOXDRAW_STORE_FILE etc.
# are set, otherwise app-release-unsigned.apk - which adb can still sideload with USB debugging).
# Same signature-mismatch fallback as `install`.
install-release: release
    apk=$(ls app/build/outputs/apk/release/app-release*.apk | head -1); \
        adb install -r "$apk" || (adb uninstall {{app_id}} && adb install "$apk")

# Uninstall the app from the connected device.
uninstall:
    adb uninstall {{app_id}}

# Launch the app on the connected device.
run:
    adb shell am start -n {{app_id}}/.MainActivity

# Stream logcat filtered to this app's tag and process.
logcat:
    adb logcat --pid=$(adb shell pidof -s {{app_id}}) 2>/dev/null || adb logcat | grep -i {{app_id}}

# Stream ONLY the drawing-pipeline log lines (pen input, raw-drawing lifecycle, crashes),
# filtered at the source via Android's own tag priority spec (*:S silences every other tag) so
# nothing needs manual scrolling/selecting to capture, and tee it to a file for a permanent,
# complete copy. Ctrl+C to stop, then the file has everything even if the terminal scrollback
# didn't. Reproduce (draw a stroke, switch brush, draw again) while this is running.
logcat-drawing:
    adb logcat -v time HardwarePenSurface:D MainActivity:D AndroidRuntime:E *:S | tee /tmp/boox-drawing.log

# Run lint checks.
lint:
    ./gradlew :app:lintDebug

# Run unit tests.
test:
    ./gradlew :app:testDebugUnitTest

# Remove all build outputs.
clean:
    ./gradlew clean
