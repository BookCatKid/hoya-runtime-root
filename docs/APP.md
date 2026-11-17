# GhostLock Runner (app/)

Plain-Java Android app (`dev.ghostlock.runner`), targetSdk 28 so the
payload can `exec()` from the app data dir (Android 10+ blocks that for
apps targeting API 29 or newer).

## What it does

1. Ships `ghostpw` as an APK asset and extracts it to `files/ghostpw`
   when it is missing or its size differs from the asset, then sets the
   exec bit. `app/build.sh` takes the payload from `GHOSTPW=` or from
   `exploit/build/ghostpw`. No binary patch is applied any more: the
   payload resolves its mailbox directory at runtime, and the app passes
   `GHOST_ROOTDIR=<files dir>` in the child environment, so
   `rootcmd`/`rootout`/`ROOTED`/`child.log` land in app data.
2. **Get root** starts `ExploitService` (foreground service) and closes
   the activity. A visible activity pins `oom_score_adj=0`, which breaks
   the exploit's tuned spray; the service waits for AMS to give it a
   non-zero adj (service class, ~200), writes `oom_score_adj=200`
   itself, then runs the payload with output appended to
   `files/ghost_run.log`.
3. Polls for a live root daemon: reads `rootd.pid`, checks
   `/proc/<pid>/comm` is `ghost_rootd`, and runs `id` through the
   mailbox. Only a `uid=0` answer flips the UI to ROOTED; the `ROOTED`
   marker alone is not trusted.
4. **Root shell** sends scripts through `rootcmd` and shows `rootout`.
5. **Network shell** starts a TCP server (port 4444). It executes each
   line with `/system/bin/sh` as the app UID until `ghost_rootd` answers,
   then switches to the mailbox automatically. `nc <device-ip> 4444`.
   Unauthenticated — anyone on the LAN can use it, so enable it
   deliberately. The choice is persisted in `net_wanted.txt`.
6. No boot receiver: auto-running a panicky exploit on every boot could
   boot-loop the device, so rooting is always manual (**Get root** or
   `am broadcast -a dev.ghostlock.runner.RUN`).
7. On completion the service beeps (chime on success, low buzz on
   failure), writes the result to `exploit_status.txt`, and reopens the
   activity only if the display is interactive (dozing surfaces abort
   RenderThread with `EGL_BAD_ALLOC`).
8. State from a previous boot is wiped when `/proc/sys/kernel/random/boot_id`
   changes: `rootcmd`, `rootout`, `rootd.pid`, `ROOTED`,
   `exploit_status.txt`.

## Build (no Gradle)

```sh
./exploit/build.sh    # payload (or set GHOSTPW=...)
./app/build.sh        # aapt2 + javac + d8/r8 + apksigner
adb install -r app/hoya-runner.apk
```

Requires `ANDROID_SDK_ROOT` (or the default `~/Library/Android/sdk`) with
build-tools 34.0.0 and platform android-30. The script probes the bundled
`d8` and falls back to a stock `r8.jar` (downloaded into
`$HOME/.hoya-runner`) if it crashes. Signing uses
`$HOME/.hoya-runner/runner.jks`, created on first run (`KS`/`KS_PASS`
override).

## Layout

- `Payload.java`: asset extraction, size-based refresh, exec bit.
- `Runner.java`: spawns ghostpw (quiet, log-to-file, env from
  `EXPLOIT_ENV`), mailbox client, log tailing, boot-id state wipe.
- `ExploitService.java`: foreground service: adj wait, exploit run,
  status file, notification, result chime, net-shell toggle.
- `RootServer.java`: TCP shell (app context, upgrades to root mailbox).
- `MainActivity.java`: status view, log console, shell dialog; closes
  itself when a run starts.
- `RunReceiver.java`: adb/headless trigger (`am broadcast ... RUN`).

## Files the app owns (all under its private files dir)

`ghostpw`, `ghostpw.tmp`, `ghost_run.log`, `child.log`, `ktrace.txt`,
`rootcmd`, `rootout`, `rootd.pid`, `ROOTED`, `exploit_status.txt`,
`net_wanted.txt`, `runner_ui.log`, `boot_id.txt`, and — only on the
alternate root paths — `root.sh`, `trigger`, `trigger.err`, `ran`.
