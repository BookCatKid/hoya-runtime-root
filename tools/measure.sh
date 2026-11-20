#!/usr/bin/env bash
# Measure the exploit's success rate on a real device.
#
# One run = reboot, wait for boot, trigger the run over adb, wait for the
# app to record an outcome. Every run starts from a clean boot because a
# failed run leaves parked kernel threads and stacking runs makes panics
# likelier, so a run that skipped the reboot would not measure the same
# thing.
#
# Usage:
#   tools/measure.sh [runs]        default 10
#
# Env:
#   SERIAL   adb serial (default 192.168.1.28:5555)
#   OUT      results directory (default tools/results)
#   INSTALL  set to 1 to install app/hoya-runner.apk before the first run
#   BOOT_WAIT, RESULT_WAIT   seconds (default 240, 480)
#
# Results append to $OUT/runs.csv, one row per run:
#   run,utc,boot_id,outcome,seconds,park,cfi,status
#
# outcome is one of:
#   rooted     the mailbox answered uid=0
#   failed     the app recorded a failed run
#   no result  the app gave up after its 5 minute timeout
#   panic      the device rebooted on its own before an outcome
#   timeout    no outcome inside RESULT_WAIT
#   aborted    adb was unusable for the whole boot wait
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

serial=${SERIAL:-192.168.1.28:5555}
runs=${1:-10}
outdir=${OUT:-$here/results}
csv=$outdir/runs.csv
boot_wait=${BOOT_WAIT:-240}
result_wait=${RESULT_WAIT:-480}
pkg=dev.ghostlock.runner

mkdir -p "$outdir"
[ -f "$csv" ] || echo "run,utc,boot_id,outcome,seconds,park,cfi,status" > "$csv"

adbs() { adb -s "$serial" "$@" 2>/dev/null; }

# A device that is mid-reboot refuses connections; keep retrying until it
# answers or the deadline passes.
wait_boot() {
    local deadline=$(( $(date +%s) + boot_wait ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        adb connect "$serial" >/dev/null 2>&1
        if [ "$(adbs shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
            return 0
        fi
        sleep 3
    done
    return 1
}

boot_id() { adbs shell cat /proc/sys/kernel/random/boot_id | tr -d '\r'; }

status() {
    adbs shell "run-as $pkg cat files/exploit_status.txt" | tr -d '\r'
}

# The two lines that say why a run landed where it did, read only from
# the bytes this run appended. An old log would otherwise repeat the
# previous run's values and look like a consistent failure.
log_field() {
    adbs shell "run-as $pkg tail -c +$((log_before + 1)) files/ghost_run.log 2>/dev/null | grep -o '$1' | tail -1" \
        | tr -d '\r'
}

if [ "${INSTALL:-0}" = "1" ]; then
    echo "installing $root/app/hoya-runner.apk"
    adb -s "$serial" install -r "$root/app/hoya-runner.apk" \
        || { echo "install failed" >&2; exit 1; }
fi

ok=0
bad=0

for i in $(seq 1 "$runs"); do
    printf '\n=== run %d/%d ===\n' "$i" "$runs"

    adbs reboot >/dev/null 2>&1
    # adb drops the moment the device goes down; do not treat that as fatal.
    sleep 10
    if ! wait_boot; then
        echo "device did not come back; aborting"
        printf '%d,%s,%s,aborted,0,,,%s\n' "$i" \
            "$(date -u +%FT%TZ)" "" "" >> "$csv"
        bad=$((bad + 1))
        continue
    fi

    bid=$(boot_id)
    printf 'boot_id %s\n' "$bid"

    # Clear any stale status so an old outcome cannot be read as this run's.
    adbs shell "run-as $pkg rm -f files/exploit_status.txt" >/dev/null 2>&1
    log_before=$(adbs shell "run-as $pkg stat -c %s files/ghost_run.log" 2>/dev/null | tr -d '\r')
    [ -n "$log_before" ] || log_before=0

    start=$(date +%s)
    # FLAG_RECEIVER_INCLUDE_BACKGROUND (0x01000000): without it the system
    # drops the implicit broadcast with "Background execution not allowed"
    # and the run never starts, which reads as a silent failure.
    adbs shell "am broadcast -a $pkg.RUN -f 0x01000000" >/dev/null 2>&1

    # The trigger must actually start something. If ghost_run.log did not
    # grow and no status appeared, this run never happened: do not record
    # an outcome for it, or an untriggered run looks like a failure.
    started=0
    for _ in 1 2 3 4 5 6; do
        sleep 5
        log_now=$(adbs shell "run-as $pkg stat -c %s files/ghost_run.log" 2>/dev/null | tr -d '\r')
        [ -n "$log_now" ] || log_now=0
        if [ "$log_now" -gt "$log_before" ] || [ -n "$(status)" ]; then
            started=1
            break
        fi
    done
    if [ "$started" = "0" ]; then
        echo "trigger did not start a run; not recording an outcome"
        printf '%d,%s,%s,not started,0,,,%s\n' "$i" \
            "$(date -u +%FT%TZ)" "$bid" "" >> "$csv"
        bad=$((bad + 1))
        continue
    fi

    outcome=""
    deadline=$(( start + result_wait ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        sleep 10
        s=$(status)
        case "$s" in
            ROOTED*)
                outcome=rooted
                break
                ;;
            failed*|"start failed"*|"thread died"*|interrupted*)
                outcome=failed
                break
                ;;
            "no result"*)
                outcome="no result"
                break
                ;;
        esac
        # A panic reboots the device by itself: a new boot id means this
        # run never reached an outcome.
        if [ "$(boot_id)" != "$bid" ]; then
            outcome=panic
            break
        fi
    done
    [ -n "$outcome" ] || outcome=timeout

    elapsed=$(( $(date +%s) - start ))
    # "slide child park r=1 wr=0" shows up in successful runs too (it is
    # the cleanup thread), so it is not a failure signal. The deposit
    # handshake and the cfi line are what distinguish the two outcomes:
    # "slide child signal r=1 wr=1" then "slide_dep cfi ok" means the
    # deposit landed.
    park=$(log_field 'slide child signal r=[0-9]* wr=[0-9]*')
    cfi=$(log_field 'slide_dep cfi ok attempt=[0-9]*')

    printf '%-10s %3ds  %s %s\n' "$outcome" "$elapsed" "$park" "$cfi"
    printf '%d,%s,%s,%s,%d,%s,%s,%s\n' "$i" \
        "$(date -u +%FT%TZ)" "$bid" "$outcome" "$elapsed" \
        "$park" "$cfi" "$s" >> "$csv"

    case "$outcome" in
        rooted) ok=$((ok + 1)) ;;
        *) bad=$((bad + 1)) ;;
    esac
done

total=$((ok + bad))
printf '\n=== %d/%d rooted' "$ok" "$total"
if [ "$total" -gt 0 ]; then
    printf ' (%.0f%%)' "$(awk -v a="$ok" -v b="$total" 'BEGIN{print 100*a/b}')"
fi
printf ' ===\n'
printf 'results: %s\n' "$csv"
