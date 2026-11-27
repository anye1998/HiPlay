#!/usr/bin/env bash
# Live debugging against the head unit over its own adbd.
#
# The point is to stop exporting log files: the car's wireless adb is always on, so a laptop on the
# same network can watch the receiver work while it works. Nothing here replaces a test on the car —
# it only removes the round trip of "drive, reproduce, export, send, read".
#
#   scripts/hicar-live.sh connect 192.168.1.50:5555
#   scripts/hicar-live.sh log
#   scripts/hicar-live.sh shot build/car.png
#   scripts/hicar-live.sh status
#   scripts/hicar-live.sh start | stop | settings
#   scripts/hicar-live.sh install build/hiplay-0.2.22-debug.apk
#   scripts/hicar-live.sh pull-log
#
# The target is $HICAR_SERIAL when set, otherwise the single non-emulator device on `adb devices`,
# otherwise the address the last `connect` used — a TCP device is dropped every time the adb server
# restarts, and re-dialling it is the difference between "works" and "no device" in a fresh shell.
set -euo pipefail

ADB="${ADB:-adb}"
PACKAGE="${HICAR_PACKAGE:-com.anye1998.hiplay}"
LAUNCHER="com.shilapi.xcertplay.HiCarLauncherAlias"
SETTINGS_ENTRY="com.shilapi.xcertplay.HiCarSettingsLaunchActivity"
TARGET_FILE="${HICAR_TARGET_FILE:-${TMPDIR:-/tmp}/hicar-live-target}"

die() { echo "$*" >&2; exit 1; }

devices() { "$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ { print $1 }'; }

serial() {
    if [ -n "${HICAR_SERIAL:-}" ]; then echo "$HICAR_SERIAL"; return 0; fi
    local chosen
    chosen=$(devices)
    if [ -z "$chosen" ] && [ -s "$TARGET_FILE" ]; then
        # adb forgets TCP targets when its server restarts; the car did not go anywhere.
        "$ADB" connect "$(cat "$TARGET_FILE")" >/dev/null 2>&1 || true
        sleep 1
        chosen=$(devices)
    fi
    [ -n "$chosen" ] || { echo "no device: run '$0 connect <host>[:port]' first, or set HICAR_SERIAL" >&2; return 1; }
    [ "$(printf '%s\n' "$chosen" | wc -l)" -le 1 ] || {
        echo "several devices; set HICAR_SERIAL to one of: $(echo "$chosen" | tr '\n' ' ')" >&2
        return 1
    }
    echo "$chosen"
}

run() { local target; target=$(serial) || exit 1; "$ADB" -s "$target" "$@"; }

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; }

command="${1:-}"
case "$command" in
    connect)
        [ $# -ge 2 ] || die "usage: $0 connect <host>[:port]"
        target="$2"
        case "$target" in *:*) ;; *) target="$target:5555" ;; esac
        "$ADB" start-server >/dev/null 2>&1 || true
        "$ADB" connect "$target"
        mkdir -p "$(dirname "$TARGET_FILE")"
        printf '%s' "$target" > "$TARGET_FILE"
        sleep 1
        "$ADB" -s "$target" shell getprop ro.product.model 2>/dev/null | sed 's/^/model: /' || \
            die "connected but no shell: the car may still be showing the 'allow debugging' dialog"
        "$ADB" -s "$target" shell getprop ro.build.version.sdk | sed 's/^/sdk: /'
        ;;
    log)
        if [ -n "${2:-}" ] && [ "$2" != "-" ]; then
            run logcat -d -s HiCar | tail -n "$2"
        else
            echo "tailing HiCar (ctrl-c to stop)" >&2
            run logcat -s HiCar
        fi
        ;;
    shot)
        out="${2:-build/car-$(date +%H%M%S).png}"
        mkdir -p "$(dirname "$out")"
        local_shot=$(mktemp)
        run exec-out screencap -p > "$local_shot"
        mv "$local_shot" "$out"
        echo "$out"
        ;;
    status)
        run shell dumpsys package "$PACKAGE" | grep -m1 -E "versionName" || true
        run shell dumpsys activity activities | grep -m1 -E "mResumedActivity" || true
        # The receiver's own idea of where it is, without opening the app.
        run logcat -d -s HiCar | grep -E "receiver started|receiver stopped|HiCar waiting|Failed|no access point" | tail -n 5 || true
        ;;
    start)
        run shell am start -n "$PACKAGE/$LAUNCHER" >/dev/null
        echo "opened $LAUNCHER"
        ;;
    settings)
        run shell am start -n "$PACKAGE/$SETTINGS_ENTRY" >/dev/null
        echo "opened $SETTINGS_ENTRY (settings overlay up)"
        ;;
    stop)
        run shell am force-stop "$PACKAGE"
        echo "stopped $PACKAGE"
        ;;
    install)
        [ $# -ge 2 ] || die "usage: $0 install <apk>"
        apk="$2"
        [ -f "$apk" ] || die "no such file: $apk"
        run install -r "$apk"
        run shell dumpsys package "$PACKAGE" | grep -m1 versionName || true
        ;;
    pull-log)
        # The app exports into Download/DiPlay on Android 10+. Take the newest file and say where.
        target=$(serial) || exit 1
        dir=$("$ADB" -s "$target" shell 'ls -d /sdcard/Download/DiPlay 2>/dev/null | head -1' | tr -d '\r')
        [ -n "$dir" ] || die "no export folder: use 设置 → 诊断 → 导出日志 in the app first"
        newest=$("$ADB" -s "$target" shell "ls -t $dir | head -1" | tr -d '\r')
        [ -n "$newest" ] || die "the export folder is empty: export a log in the app first"
        mkdir -p build/logs
        "$ADB" -s "$target" pull "$dir/$newest" "build/logs/$newest" >/dev/null
        echo "build/logs/$newest"
        ;;
    ""|-h|--help|help)
        usage
        ;;
    *)
        die "unknown command: $command (try '$0 help')"
        ;;
esac
