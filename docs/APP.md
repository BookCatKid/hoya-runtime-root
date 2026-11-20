# GhostLock Runner (app/)

Plain-Java Android app (`dev.ghostlock.runner`), targetSdk 28 so the
payload can `exec()` from the app data dir (Android 10+ blocks that for
apps targeting API 29 or newer).

## What it does

1. Ships `ghostpw` as an APK asset and extracts it to `files/ghostpw`
   when it is missing or its SHA-256 differs from the asset, then sets
   the exec bit. `app/build.sh` takes the payload from `GHOSTPW=` or from
   `exploit/build/ghostpw`. No binary patch is applied: the payload
   resolves its mailbox directory at runtime, and the app passes
   `GHOST_ROOTDIR=<files dir>` in the child environment, so
   `rootcmd`/`rootout`/`ROOTED`/`child.log` land in app data. The asset
   is stored compressed, so the check streams it rather than using
   `openFd`.
2. **Get root** hands the run to `ExploitService` (foreground service)
   and closes the activity. It first asks when a daemon already answers
   or the last run failed. A visible activity pins `oom_score_adj=0`,
   which breaks the exploit's tuned spray; the service waits for AMS to
   give it a non-zero adj (service class, ~200), writes
   `oom_score_adj=200` itself, then runs the payload with output
   appended to `files/ghost_run.log`.
3. The status view probes the mailbox with `id` at most every 10
   seconds; the network shell probes on its own. A `uid=0` answer flips
   the UI to ROOTED and shows the recorded pid. A `ROOTED` file with no
   answer shows "root daemon not answering"; `rootd.pid` alone is never
   treated as proof.
4. **Root shell** sends scripts through `rootcmd` and shows `rootout`.
   One script at a time, and one `[rc=N]` per script. Scripts over 4000
   bytes are rejected rather than truncated. The app renames a temp file
   into place and the daemon renames it away before reading, so neither
   side sees a half-written script. A command running past 120 seconds
   is killed and reported as rc 124. If a script times out on the app
   side, the next one waits up to 5 seconds for the previous `[rc=]` and
   otherwise fails, so a late result is never reported as the new
   command's output.
5. **Network shell** starts a TCP server (port 4444). It executes each
   line with `/system/bin/sh` as the app UID until `ghost_rootd` answers,
   then switches to the mailbox automatically; a connection that starts
   unrooted prints `[ghost_rootd appeared. Switching to uid 0]` and
   continues as root. It never downgrades: if the daemon dies mid-session
   the connection stops answering rather than re-running the line as the
   app uid. `nc <device-ip> 4444`. Unauthenticated. Anyone on the LAN can
   use it, so enable it deliberately. The choice is stored in
   `net_wanted.txt`; opening the activity restarts the service from
   `onResume` when that flag is set, because the listener dies with the
   process (`onCreate` runs while the app is still backgrounded, where
   `startService` is rejected). The button reads whether the socket is
   bound and its accept loop is running.
6. No boot receiver: auto-running a panicky exploit on every boot could
   boot-loop the device, so rooting is always manual (**Get root** or
   `am broadcast -a dev.ghostlock.runner.RUN`, which only adb shell and
   root can deliver because the receiver requires
   `android.permission.DUMP`).
7. On completion the service beeps (chime on success, buzz on failure
   or timeout), writes the result to `exploit_status.txt`, and reopens
   the activity only if the display is interactive (dozing surfaces abort
   RenderThread with `EGL_BAD_ALLOC`). If nothing answers after 5
   minutes it reports that and leaves the payload parked rather than
   killing it, because killing a parked payload runs the futex teardown
   that panics.
8. State from a previous boot is wiped when `/proc/sys/kernel/random/boot_id`
   changes: `rootcmd`, `rootout`, `rootd.pid`, `ROOTED`,
   `exploit_status.txt`.

`ghost_rootd` runs in the app's cgroup, so force-stopping the app or
reinstalling the APK kills it. The files stay (`rootd.pid` keeps the
dead pid), but nothing answers the mailbox and port 4444 refuses
connections. Clearing app data removes the files too. Root again after
any of those.

## Build (no Gradle)

```sh
./exploit/build.sh    # payload (or set GHOSTPW=...)
./app/build.sh        # aapt2 + javac + d8/r8 + apksigner
```

Then upload `app/hoya-runner.apk` to your own LAT app. As far as I know
LAT is the only route onto the device; the account and the LAT app have
to be yours, this project cannot ship one.

Requires `ANDROID_SDK_ROOT` (or the default `~/Library/Android/sdk`) with
build-tools 34.0.0 and platform android-30. The script probes the bundled
`d8` and falls back to a stock `r8.jar` (downloaded into
`$HOME/.hoya-runner`) if it crashes. Signing uses
`$HOME/.hoya-runner/runner.jks`, created on first run (`KS`/`KS_PASS`
override).

## Layout

- `Payload.java`: asset extraction, SHA-256 refresh, exec bit.
- `Runner.java`: spawns ghostpw (quiet, log-to-file, env from
  `EXPLOIT_ENV`), mailbox client, log tailing, boot-id state wipe.
- `ExploitService.java`: foreground service: adj wait, exploit run,
  status file, notification, result chime, net-shell toggle.
- `RootServer.java`: TCP shell (app context, upgrades to root mailbox).
- `MainActivity.java`: status view, log console, shell dialog; closes
  itself when a run starts.
- `RunReceiver.java`: adb/headless trigger (`am broadcast ... RUN`).

## Files in the app's private files dir

The app and the root daemon write these under the app's private files
dir. The package directory is 0700 and `files/` is 0771 on the device.
`rootout`, `rootd.pid`, and `ROOTED` are created by root; the rest by
the app. `rootcmd` is 0666 and becomes app-owned once the app renames
`rootcmd.tmp` over it; the daemon renames it to `rootcmd.run` before
reading and unlinks that, so `rootcmd` is often absent.

`ghostpw`, `ghostpw.tmp`, `ghost_run.log`, `child.log`, `ktrace.txt`,
`rootcmd`, `rootcmd.tmp`, `rootout`, `rootd.pid`, `ROOTED`,
`exploit_status.txt`, `net_wanted.txt`, `runner_ui.log`, `boot_id.txt`,
and, only on the alternate root paths, `root.sh`, `trigger`,
`trigger.err`, `ran`.
