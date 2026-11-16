# GhostLock Runner (app/)

Plain-Java Android app (`dev.ghostlock.runner`), targetSdk 28 — chosen so
the payload can `exec()` straight out of the app data dir (blocked for
targetSdk 29+).

## What it does

1. Ships `ghostpw` as an APK asset; extracts it to `files/` on first run.
2. "Get root" starts `ExploitService` (foreground service) and the activity
   steps aside — a visible activity pins `oom_score_adj=0`, which breaks the
   exploit's tuned spray. The service waits for AMS to assign the service
   class (~200), then runs the payload with output to `files/ghost_run.log`.
3. Polls for `files/rootd.pid` + a live `id` answer on the mailbox, then
   flips to ROOTED.
4. "Root shell" sends scripts through `rootcmd`/`rootout`.
5. "Network shell" starts a TCP server (port 4444) that proxies each line to
   the root mailbox — `nc <device-ip> 4444` from any machine on the LAN.
   Unauthenticated; enable deliberately.
6. `BootReceiver` re-runs the exploit automatically after every boot/panic,
   capped at 5 auto-starts (counter persists across reboots, resets on
   success) so a panic-loop can't brick the device.

## Build (no Gradle)

```sh
app/build.sh        # aapt2 + javac + d8/r8 + apksigner
adb install -r app/hoya-runner.apk
```

Requires `ANDROID_SDK_ROOT` with build-tools + android-30 platform. The
script falls back to a stock `r8.jar` if the bundled d8 crashes.

## Layout

- `Payload.java` — asset extraction + exec bit.
- `Runner.java` — spawn ghostpw (quiet, log-to-file), mailbox client.
- `ExploitService.java` — foreground service: exploit loop, status file,
  notification, net-shell toggle.
- `RootServer.java` — TCP -> rootcmd/rootout proxy.
- `MainActivity.java` — status view, log console, shell dialogs.
- `BootReceiver.java` / `RunReceiver.java` — auto-root on boot, adb trigger.

## Files the app owns (all under its private files dir)

`ghostpw`, `ghost_run.log`, `rootcmd`, `rootout`, `rootd.pid`, `ROOTED`,
`exploit_status.txt`, `auto_runs.txt`, `net_wanted.txt`, `runner_ui.log`.
