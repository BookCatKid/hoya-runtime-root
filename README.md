# hoya-runtime-root

GhostLock kernel exploit (CVE-2026-43499, futex PI-requeue
use-after-free) for the **Amazon Echo Show 15 (`hoya_ftv`)**, plus a
self-contained Android app that runs it on the device.

> Kernel exploit. Authorized use only, see [NOTICE](NOTICE).

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

As far as I know, the only way to get an APK onto the device is **LAT**
(Amazon Live App Testing). Build `app/hoya-runner.apk`, upload it to LAT,
then install it on the device through the Appstore.

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

Headless trigger over adb: `am broadcast -a dev.ghostlock.runner.RUN`.

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

## License

GPL-3.0, see [LICENSE](LICENSE). Upstream IonStack is Apache-2.0, see
[CREDITS.md](CREDITS.md).
