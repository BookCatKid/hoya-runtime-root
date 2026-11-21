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
> - **Support.** Issues and pull requests are welcome. A patch that
>   fixes the problem tends to land faster than a report that describes
>   it, so send the change if you can, but an issue is a fine way to
>   start.
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

Before installing the LAT build, uninstall any copy of the app you
installed another way. Amazon re-signs the APK with a certificate from
your account before distributing it, so the LAT build does not carry the
signature of the APK you uploaded. Android refuses to install an update
whose certificate differs from the one already on the device, and the
install fails. Uninstalling first avoids that. Uninstalling deletes the
app's data, including `ghost_run.log`.

## Limitations

Read these before running anything.

- **Root is per-boot.** A reboot clears it. As far as I know, there is no
  persistent root and no way to make one without touching the bootloader;
  anything is possible, but nothing here tries.
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
  a network you trust, and turn it off when you are done.
- **The exploit is probabilistic.** Measured at about 7 in 10 runs on
  one device, with a run taking 1 to 4 minutes. A failure needs a reboot
  before retrying, and your own device may land well above or below that
  figure. See [Success rate](#success-rate).
- **No uninstall path for root.** Root is runtime-only, so a reboot is
  the reset. There is nothing to uninstall, and nothing to clean up.

## Using the app

1. Open **GhostLock Runner**.
2. Tap **Get root**.
3. The activity closes on purpose: a visible activity pins
   `oom_score_adj=0`, which breaks the exploit's spray. `ExploitService`
   (a foreground service) runs the payload instead, at the adj class the
   grooming counts are tuned for.
4. Wait. A run takes 1 to 3 minutes and succeeds about 8 times in 10.
   The device may beep when it finishes, and the app reopens itself if
   the screen is on. If nothing answers after 5 minutes the app reports
   that and leaves the payload running; check the log before rebooting.
5. If it fails, **reboot before retrying**. Failed runs leave parked
   kernel threads; stacking runs without a reboot makes panics likelier.
6. On success the app shows **ROOTED**. **Root shell** and **Network
   shell** are always available; the network shell also works without
   root (`nc <device-ip> 4444`; unauthenticated, so enable it
   deliberately).

Headless trigger over adb:

```sh
adb shell am broadcast -a dev.ghostlock.runner.RUN -f 0x01000000
```

The `-f 0x01000000` flag (`FLAG_RECEIVER_INCLUDE_BACKGROUND`) is
required. Without it the system drops the broadcast with "Background
execution not allowed" and nothing runs. Only adb shell and root can
deliver it.

## Success rate

Measured on one Echo Show 15, 34 clean-boot runs, one run per boot:

| Runs | Rooted | Rate | 95% confidence interval |
|---|---|---|---|
| 34 | 24 | 70.6% | 54% to 83% |

Successes landed across the full range of attempts:

| Attempt that landed | Runs |
|---|---|
| 1 | 4 |
| 2 | 2 |
| 3 | 2 |
| 4 | 1 |
| 5 | 1 |
| 6 | 2 |
| 7 | 1 |
| 8 | 5 |
| 9 | 3 |
| 10 | 1 |
| 11 | 2 |
| none (12 misses) | 10 |

All ten failures used all 12 attempts and ended at `cfi miss attempt=12
step=4`, meaning the deposit never landed in the target slot; the CFI
verification caught it and the run reported `failed. Reboot before
retrying`. None of the 34 runs panicked.

Fitting the per-attempt probability with the failures treated as
censored gives about 9% per attempt, and the attempts behave like
independent tries, so the 12-attempt cap is what limits the rate.
Raising the cap would help only slowly: about 69% at 12 attempts, 79% at
16, 85% at 20, and 90% would need 24 attempts, which is longer than the
app's 300s timeout allows.

### How much to trust this number

Treat it as a measurement of one device, not as a property of the
exploit.

- **One device, one build.** Every run was on a single Echo Show 15
  running `PS75751.7809N` with kernel `5.4.86-ab7`. The deposit depends
  on heap and slab state, which the kernel build, the boot
  configuration, prior allocations and uptime all affect.
- **Your rate may be very different.** The 95% interval for this one
  device already spans 54% to 83%, and that interval describes this
  device only. A different unit or a different Fire OS build could sit
  outside it entirely, in either direction.
- **It varies run to run.** Three batches of runs came in at 80%, 75%
  and 65% on the same hardware with the same payload. That spread is
  consistent with chance, but it shows the figure is not stable.
- **34 runs is a modest sample.** Enough to rule out a rate near 100%
  and to show that raising the cap is not a quick fix. Not enough to
  predict your device.

Expect it to usually work within a dozen attempts and sometimes need a
reboot and another go. If you measure your own device with
`tools/measure.sh`, your number is the one that applies to you.

A run that fails needs a reboot before the next attempt.

```sh
tools/measure.sh 10
```

Each run reboots the device first, triggers the run, and records the
outcome to `tools/results/runs.csv` as `rooted`, `failed`, `no result`,
`panic`, `timeout` or `not started`, along with the
`slide child signal r=… wr=…` line, the attempt that landed, and the
elapsed time. Reboots are not optional: a failed run leaves parked
kernel threads, so a run without a fresh boot would measure something
else.

Two lines in `ghost_run.log` are easy to misread. `slide child park
r=1 wr=0` also appears in successful runs, because it is the cleanup
thread parking after root is established, so it does not mean failure.
`slide_dep cfi ok attempt=N` is the line that marks a landed deposit;
`slide_dep cfi miss attempt=N step=4` is the line that marks a miss.

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

Issues and pull requests are both welcome.

A pull request usually gets merged faster than an issue gets fixed,
simply because the work is already done, so if you know the fix, send
it. If you would rather report the problem, that is useful too: a clear
report is often what makes a fix possible.

For anything about a run going wrong, include your device and build
fingerprint, the relevant `ghost_run.log` tail, and the
`slide child park r=… wr=…` line. Those three turn "it didn't work" into
something actionable, since the exploit fails probabilistically by
design.

## License

GPL-3.0, see [LICENSE](LICENSE). Upstream IonStack is Apache-2.0, see
[CREDITS.md](CREDITS.md).
