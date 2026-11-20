# hoya-runtime-root

GhostLock kernel exploit (CVE-2026-43499, futex PI-requeue
use-after-free) for the **Amazon Echo Show 15 (`hoya_ftv`)**, plus a
self-contained Android app that runs it on the device.

> ## Developer preview
>
> This is unfinished research code, not a product. It is **not** ready
> for general use.
>
> - **Undocumented and difficult.** There is no installer and no
>   troubleshooting guide. Expect to read the source and the logs to get
>   anywhere. Parts of the app are still rough.
> - **Dangerous.** A run can panic the kernel and reboot the device at
>   any moment, including during an update or while something is
>   writing. Do not run it on a device you depend on. Do not run it on a
>   device you do not own. Authorized use only, see [NOTICE](NOTICE).
> - **Limited support.** Issues are read when there is time, and there is
>   no guarantee of a fix. A pull request gets looked at much sooner than
>   an issue: if you can, send the change instead of the report.
> - **Unverified beyond one device.** It has been run on one Echo Show
>   15. A matching build fingerprint is not a promise that it works on
>   yours.
>
> Read [Limitations](#limitations) before you try anything.

Root is runtime-only: the exploit patches a running process's credentials
and sets SELinux permissive, so a reboot clears it. The bootloader stays
locked and `dm-verity` is untouched.

## Contents

| Path | What |
|---|---|
| `exploit/src/` | Exploit sources for target `hoya-PS75741.7082N` |
| `exploit/build.sh` | Cross-compile `ghostpw` with `zig cc` (aarch64-linux-musl) → `exploit/build/ghostpw` |
| `app/` | "GhostLock Runner" APK, plain Java, no Gradle |
| `docs/DEVICE.md` | Device facts, exploit recipe, tuning knobs, root mailbox |
| `docs/APP.md` | App architecture, build and file layout |
| `tools/measure.sh` | Measures the exploit's success rate over N clean-boot runs |
| `app/store/` | Store icons for LAT: `tablet_large_icon.png` (512x512, transparent), `fire_tv_icon.png` (1280x720, opaque), and their SVG sources |
| `CREDITS.md` | Upstream provenance (NebuSec/CyberMeowfia IonStack, Apache-2.0; KernelSnitch, MIT) |

## Build

No binaries are committed.

```sh
./exploit/build.sh     # payload  -> exploit/build/ghostpw
./app/build.sh         # payload + APK -> app/hoya-runner.apk
```

- `exploit/build.sh` needs `zig` (`brew install zig`).
- `app/build.sh` needs a JDK plus Android build-tools 34 and platform
  android-30 (`ANDROID_SDK_ROOT` / `ANDROID_HOME`, or the default
  `~/Library/Android/sdk`). It takes the payload from `GHOSTPW=/path/to/ghostpw`
  when set, otherwise from `exploit/build/ghostpw` (so run
  `exploit/build.sh` first).
- On the first run `app/build.sh` creates a local signing key at
  `$HOME/.hoya-runner/runner.jks` (`KS`, `KS_PASS` override it; the
  default password is `android`, fine only for a local sideload key).
  If build-tools' bundled `d8` is broken, it downloads a stock `r8.jar`
  into `$HOME/.hoya-runner`.
- `VERSION_CODE` / `VERSION_NAME` override the APK version (default
  `1` / `0.1`). Bump `VERSION_CODE` for each LAT upload.

## Install

Two steps: get the APK, then get it onto the device.

**1. Get the APK.** Download `hoya-runner.apk` from
[Releases](../../releases), or build it yourself with the steps above.
Release builds are signed with the project's local key, so they can be
upgraded in place only by another build from here.

**2. Get it onto the device.** As far as I know, the only way to get an
APK onto the device is **LAT** (Amazon Live App Testing). You need your
own Amazon developer account and your own LAT app; this project cannot
ship one for you. Upload the APK to your LAT app, then install it on the
device through the Appstore.

LAT is the step most likely to fail, and it is outside this project's
control: Amazon's review, device compatibility rules and permission
handling can change or reject the app. If LAT refuses it, that is not
something this repository can fix.

## Limitations

Read these before running anything.

- **Root is per-boot.** A reboot clears it. There is no persistent root
  and no way to make one without touching the bootloader.
- **Force-stopping the app kills root.** `ghost_rootd` reparents to
  init but stays in the app's cgroup, so force-stop or a normal kill
  takes the daemon with it. Root is then gone until you run the exploit
  again.
- **A failed run needs a reboot.** Failed runs leave parked kernel
  threads; stacking runs without a reboot makes panics likelier. The app
  says so when it fails.
- **A panic loses whatever was in flight.** Not just the run: anything
  unsaved on the device at that moment.
- **The network shell is an unauthenticated root listener.** When root
  is up, port 4444 hands out a uid 0 shell to anyone who can reach the
  device on the LAN. It is wildcard-bound on purpose. Enable it only on
  a network you trust, and turn it off when you are done. Without root
  it still gives a shell as the app's uid, which can read the app's
  files.
- **The exploit is probabilistic and its success rate is not measured
  yet.** Do not assume it will work, and do not assume it will fail
  cleanly.
- **No uninstall path for root.** Root is runtime-only, so a reboot is
  the reset. There is nothing to uninstall, and nothing to clean up.

## Using the app

1. Open **GhostLock Runner**.
2. Tap **Get root**.
3. The activity closes on purpose: a visible activity pins
   `oom_score_adj=0`, which breaks the exploit's spray. `ExploitService`
   (a foreground service) runs the payload instead, at the adj class the
   grooming counts are tuned for.
4. Wait. A run takes 1–5 minutes and is probabilistic. The device may
   beep when it finishes, and the app reopens itself if the screen is on.
   If nothing answers after 5 minutes the app reports that and leaves the
   payload running; check the log before rebooting.
5. If it fails, **reboot before retrying**. Failed runs leave parked
   kernel threads; stacking runs without a reboot makes panics likelier.
6. On success the app shows **ROOTED**. **Root shell** and **Network
   shell** are always available; the network shell also works without
   root (`nc <device-ip> 4444`; unauthenticated, so enable it
   deliberately).

Headless trigger over adb: `am broadcast -a dev.ghostlock.runner.RUN`, which
only adb shell and root can deliver.

## Success rate

Not measured yet. The numbers below are what `tools/measure.sh` reports
once there are enough runs to mean anything; two runs is not a rate.

```sh
tools/measure.sh 10
```

Each run reboots the device first, triggers the run, and records the
outcome to `tools/results/runs.csv` as `rooted`, `failed`, `no result`,
`panic` or `timeout`, along with the `slide child park r=… wr=…` line and
the elapsed time. Reboots are not optional: a failed run leaves parked
kernel threads, so a run without a fresh boot would measure something
else.

| Runs | Rooted | Rate |
|---|---|---|
| 0 | 0 | not measured |

## The exploit

The app runs the payload with:

```
GHOST_STAGE=slide_dep GHOST_WAITER_CORE=2 GHOST_PARK=1 GHOST_FOPS_TRUE=1 ./ghostpw
```

`slide_dep` makes up to 12 attempts of the proven slide trio, then uses
the deposited fake `file_operations` table as an arbitrary kernel
read/write to patch a helper child's cred to uid 0 with full caps, start
`ghost_rootd`, and set SELinux permissive. A failed attempt can panic the
kernel.

Started with no `GHOST_STAGE`, `./ghostpw` takes the older
`run_exploit()` path (KernelSnitch plus the pselect route). That route
does not produce a usable deposit on this target; the app always uses
`slide_dep`. `GHOST_STAGE=slide` only leaks the kernel base. See
[docs/DEVICE.md](docs/DEVICE.md) for the recipe, the environment knobs
and what the logs mean.

## Contributing

Send a pull request, not an issue. A patch that fixes the problem gets
merged much faster than a report that describes it, especially for a
project this rough: by the time an issue is read, the useful work in it
is usually the diff anyway.

If you must open an issue, include what device and build fingerprint you
are on, the relevant `ghost_run.log` tail, and the `slide child park
r=… wr=…` line. Without those it is hard to tell a real bug from the
exploit's usual probabilistic failure.

## License

GPL-3.0, see [LICENSE](LICENSE). Upstream IonStack is Apache-2.0, see
[CREDITS.md](CREDITS.md).
