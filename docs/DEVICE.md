# Device details

| Property | Value |
|---|---|
| Model | Amazon Echo Show 15 |
| Device codename | `hoya_ftv` (portrait variant "hoya") |
| Platform | Amlogic POP1-C / T7, ARM64, 8 cores |
| Fire OS build | `PS75751.7809N` |
| Android release | 9 (API 28) |
| Security patch | 2026-03-01 |
| Kernel | `5.4.86-ab7` |
| Bootloader | locked |
| dm-verity | enforcing |
| SELinux | Enforcing (set permissive by the root chain) |

## Why there is no persistent root

- Locked bootloader; no fastboot flashing.
- `dm-verity` enforcing — `/system` modifications fail verification.
- No Magisk-style ramdisk injection or recovery access.
- Root lives only in the running kernel and is lost on reboot.

## Exploit summary

`ghostpw` is a static aarch64 binary implementing a kernel LPE derived from
CVE-2026-43499 (futex PI-requeue UAF). **The proven payload is NOT in git** —
it is the upstream build kept at `~/ghostlock-echo-show-15/bin/ghostpw`
(191880 bytes, sha256 `bf0d17b9769da957ae9eeedb2ef621d7928da99caec110865c179ab9b8b88375`).
`app/build.sh` pulls it from there (override with `GHOSTPW=`). Do not
substitute a fresh `build.sh` rebuild for runs: `exploit/src/` is an older
source rev (`MM_PARTIALS=5` → `spray=216`) that never lands the deposit —
verified 24/24 misses. `src/common.h` now carries `MM_PARTIALS=13`
(`spray=504`), but that still is not the exact rev the proven binary came
from. The working recipe:

```
GHOST_STAGE=slide_dep GHOST_WAITER_CORE=2 GHOST_PARK=1 GHOST_FOPS_TRUE=1 ./ghostpw
```

- `GHOST_WAITER_CORE=2` must differ from the consumer core.
- `GHOST_PARK=1` is part of the verified run but leaves parked futex
  waiters — killing those threads later can panic the kernel. Drop it for
  routine runs if stability matters more than the verified path.
- On success the payload patches a root child's cred (uid 0, full caps,
  kernel SELinux SID), sets `selinux_enforcing=0`, and spawns
  `ghost_rootd`, a polling command daemon.

## Root command mailbox

`ghost_rootd` polls `<dir>/rootcmd` (a shell script), executes it as
`u:r:kernel:s0` uid 0, and appends output plus `[rc=N]` to `<dir>/rootout`.
`<dir>` resolves from `GHOST_ROOTDIR`, then the binary's own directory,
then a legacy fallback.

SELinux-context rules when driving the daemon:

- `pm`/`dumpsys`/`settings` fail in kernel context ("Can't find service:
  package") — prefix with `runcon u:r:shell:s0`.
- `persist.mm.*` properties require `runcon u:r:init:s0 setprop ...`.
- Kill processes by explicit PID; `pkill -f` can match the daemon's own
  command line.

## App-uid note (oom_score_adj)

The spray/grooming counts are calibrated for `oom_score_adj=200`, the value
Android assigns a process whose only component is a foreground service.
A visible activity forces `oom_score_adj=0`, under which the LMK never trims
idle shaping children and the deposit misses. The companion app therefore
runs the payload inside a foreground service.
