# GhostLock Runner (app/)

Plain-Java Android app (`dev.ghostlock.runner`), targetSdk 28 so the
payload can `exec()` from the app data dir (blocked for targetSdk 29+).

## What it does

1. Ships `ghostpw` as an APK asset; extracts it to `files/` on first run.
   `app/build.sh` takes the payload from `GHOSTPW=` or builds it with
   `exploit/build.sh`. The binary is the upstream payload with three
   hardcoded `/data/user/0/com.device.diag/files` strings relativized to
   cwd-relative paths (`.`, `child.log`, `ROOTED`) so the root mailbox
   lands in the app's own files dir. Binary-only patch, exploit code
   untouched.
2. "Get root" starts `ExploitService` (foreground service) and the activity
   steps aside. A visible activity pins `oom_score_adj=0`, which breaks
   the exploit's tuned spray. The service waits for AMS to assign the
   service class (~200), then runs the payload with output to
   `files/ghost_run.log`.
3. Polls the mailbox for a live `id` answer, then flips to ROOTED.
4. "Root shell" sends scripts through `rootcmd`/`rootout`.
5. "Network shell" starts a TCP server (port 4444) that executes each line
   with `/system/bin/sh` in the app's UID until `ghost_rootd` answers,
   then switches to the mailbox automatically. `nc <device-ip> 4444`.
   Unauthenticated, enable deliberately.
6. No boot receiver: auto-running a panicky exploit on every boot could
   boot-loop the device, so rooting is always manual (Get root button or
   `am broadcast -a dev.ghostlock.runner.RUN`).

## Build (no Gradle)

```sh
app/build.sh        # aapt2 + javac + d8/r8 + apksigner
adb install -r app/hoya-runner.apk
```

Requires `ANDROID_SDK_ROOT` with build-tools + android-30 platform. The
script falls back to a stock `r8.jar` if the bundled d8 crashes.

## Layout

- `Payload.java`: asset extraction + exec bit.
- `Runner.java`: spawn ghostpw (quiet, log-to-file), mailbox client.
- `ExploitService.java`: foreground service: exploit loop, status file,
  notification, net-shell toggle.
- `RootServer.java`: TCP shell (app context, upgrades to root mailbox).
- `MainActivity.java`: status view, log console, shell dialogs.
- `RunReceiver.java`: adb/headless trigger (`am broadcast ... RUN`).

## Files the app owns (all under its private files dir)

`ghostpw`, `ghost_run.log`, `rootcmd`, `rootout`, `rootd.pid`, `ROOTED`,
`exploit_status.txt`, `net_wanted.txt`, `runner_ui.log`, `boot_id.txt`.
