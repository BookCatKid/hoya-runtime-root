# Device details

| Property | Value |
|---|---|
| Model | Amazon Echo Show 15 (1st gen), `AEOHY` |
| Device codename | `hoya_ftv` (`hoya`) |
| Platform | Amlogic T7 (`amlogic, t7`), ARM64, 8 cores |
| Fire OS build | `PS75751.7809N` |
| Android release | 9 (API 28) |
| Security patch | 2026-03-01 |
| Kernel | `5.4.86-ab7` |
| Bootloader | no fastboot path; cmdline has `androidboot.unlocked_kernel=false` |
| dm-verity | `eio` (`ro.boot.veritymode=eio`; I/O errors are returned) |
| SELinux | enforcing (set permissive by the root chain) |

`exploit/src/targets/hoya-PS75741.7082N/` holds the offsets and build
constants for this kernel. The directory keeps the upstream target label
(`hoya-PS75741.7082N`); the device build string is `PS75751.7809N`. The
offsets are not portable to other Fire OS builds.

## Exploit summary

`ghostpw` is a static aarch64 binary implementing a kernel LPE derived
from CVE-2026-43499 (futex PI-requeue UAF). Build it with
`exploit/build.sh` (zig cc, aarch64-linux-musl). The working recipe:

```sh
GHOST_STAGE=slide_dep GHOST_WAITER_CORE=2 GHOST_PARK=1 GHOST_FOPS_TRUE=1 ./ghostpw
```

Pipeline:

1. **Reclaim.** KernelSnitch leaks a freed order-3 `mm_struct` slab; the
   page is reclaimed with AF_UNIX skb frags carrying the fake objects
   (fake `rt_mutex`, fake waiter, fake `task_struct`, fake
   `file_operations`).
2. **Forge + deposit.** The slide trio (waiter/owner/consumer) leaves a
   stale `rt_mutex_waiter` on the waiter's kernel stack, `process_vm_readv`
   stamps the forged waiter over it, and the failed PI requeue walks the
   chain and `rb_erase` writes `fake_fops` into `ashmem_miscdev.fops`.
3. **Verify.** `try_cfi_stage()` opens `/dev/ashmem` through the hijacked
   fops, restores the clobbered `.read` slot, and probes
   `ashmem_miscdev.fops` back.
4. **KASLR.** The ashmem fops table gives the kernel base; every text
   pointer in the fake table is refreshed (KASLR is normally 0 here, so
   this usually confirms rather than corrects).
5. **Root.** The fake table now provides arbitrary kernel read/write
   (`configfs_read_file` / `configfs_write_bin_file` through a forged
   `configfs_buffer`). `install_android_root()` walks `init_task.tasks`
   for the helper child, widens the slab caches' usercopy whitelists,
   patches the child's `cred` (uid/gid 0, full caps, kernel SELinux SID)
   and blanks its seccomp state, then that child sets
   `selinux_enforcing=0`, forks `ghost_rootd` and exits.

Probabilistic: 12 deposit attempts per run, and a failed attempt can
panic the kernel. Reboot before retrying; each failed run leaves parked
kernel threads behind.

## Run knobs

Set through the environment (`Runner.java` sets `GHOST_STAGE`,
`GHOST_WAITER_CORE`, `GHOST_PARK`, `GHOST_FOPS_TRUE` and `GHOST_ROOTDIR`
for the app). The documented set:

| Variable | Meaning |
|---|---|
| `GHOST_STAGE` | `slide_dep` (the app path), `slide` (KASLR leak only), `perf_zero`, `arbwrite`, `slide_amf`, `payload_dump` (offline payload self-test). Unset runs the older `run_exploit()` path, which does not produce a usable deposit on this target. |
| `GHOST_DEP_ATTEMPTS` | 1–12 deposit attempts for `slide_dep` (default 12). |
| `GHOST_PARK=1` | Freeze the exploit threads after the trigger. Required on the deposit path: any thread exit runs futex/PI teardown on the stale state and panics. |
| `GHOST_FOPS_TRUE=1` | Apply the 0x40 fops correction for the -0xe80 skb delta so the deposit points at the table that was actually written. |
| `GHOST_WAITER_CORE`, `GHOST_CONSUMER_CORE`, `GHOST_OWNER_CORE`, `GHOST_MAIN_CORE` | Core pinning for the slide trio. Waiter and consumer must differ. |
| `GHOST_KASLR_BASE` | Seed the kernel base and skip the slide stage. |
| `GHOST_ROUTE=slide` | Use `run_slide_deposit_route()` (forked trio + `try_cfi_stage`) instead of the main-thread pselect route. |
| `GHOST_PMODE` | `dep` (default) or `slide` payload shape. |
| `GHOST_ROOTDIR` | Root mailbox directory (see below). |
| `GHOST_NOROOT=1` | Stop after the fake-fops read/write; do not install root. |
| `GHOST_DUMP_INIT=1` | Dump `init_task` fields and stop before root install (layout check; `slide_dep` then reports a cfi miss). |
| `GHOST_LEAK_ONLY=1` | Run KernelSnitch and exit. |
| `GHOST_ROOT_MODPROBE=1`, `GHOST_ROOT_CORE=1`, `GHOST_CORE_CMD` | Alternate root paths for kernels where the cred patch cannot run (write `modprobe_path` / `core_pattern` instead). |
| `GHOST_DEPOSIT` | Oracles: `bootid`, `bootid_amf`, `read:<off>`, `readabs:<addr>`, `hangtest`, `badlock`. `slide_dep` handles `bootid`, `read:`, and `readabs:`; `bootid_amf`, `hangtest`, and `badlock` belong to the fops route. |
| `GHOST_DEP_ZERO=1` + `GHOST_ZERO_ADDR` | Single 8-byte zero store at the data alias instead of the fops deposit. |

Diagnostic-only knobs used while re-proving a shape (they change the
heap layout or skip verification, so they are not part of the working
run): `GHOST_GROOM_OLD`, `GHOST_SPRAY_SLABS`, `GHOST_SKB_DELTA`,
`GHOST_RECLAIM_SENDS`, `GHOST_WARM_HEADS`, `GHOST_NO_PCP_SHAPING`,
`GHOST_NOSPRAY`, `GHOST_READ_BIN`, `GHOST_SKIP_STEP4`,
`GHOST_SKIP_LLSEEK`, `GHOST_NO_UNLOCK`, `GHOST_FAKELOCK`,
`GHOST_IOV_PAD`, `GHOST_SIG_*`, `GHOST_TRIGGER_*`, `GHOST_SLIDE_TRIES`,
`GHOST_NOFORGE`, `GHOST_NOCONSUME`, `GHOST_NO_STAMP`,
`GHOST_SENTINEL`, `GHOST_LIN_MARK`, `GHOST_DUMP_PAYLOAD`.

`GHOST_KASLR_BASE`, `GHOST_ROUTE`, and `GHOST_PMODE` are read by the
`run_exploit` and route paths, not by `slide_dep`: that stage hardcodes
base and slide 0 and always uses the DEP payload shape.

## Root mailbox

`ghost_rootd` polls `<dir>/rootcmd` (a shell script), executes it as
`u:r:kernel:s0` uid 0, and appends output plus `[rc=N]` to
`<dir>/rootout`. It also writes `<dir>/rootd.pid` and `<dir>/ROOTED`.

The channel is not transactional. The daemon reads then truncates
`rootcmd`, so a command written while it is between those steps is
lost, and a command that times out may still run later. Use one client
at a time and do not retry a long command until it returns.

`<dir>` resolves, in order:

1. `GHOST_ROOTDIR` (the app sets this to its private files dir),
2. the directory containing the running binary (`/proc/self/exe`),
3. the current working directory.

The modprobe and `core_pattern` root paths write their helper script into
the same directory.

## SELinux-context rules when driving the daemon

- `pm`/`dumpsys`/`settings` fail in kernel context ("Can't find service:
  package"). Prefix with `runcon u:r:shell:s0`.
- `persist.mm.*` properties require `runcon u:r:init:s0 setprop ...`.
- Kill processes by explicit PID; `pkill -f` can match the daemon's own
  command line.

## App-uid note (oom_score_adj)

The spray/grooming counts are calibrated for `oom_score_adj=200`, the
value Android assigns a process whose only component is a foreground
service. A visible activity forces `oom_score_adj=0`, under which the LMK
never trims idle shaping children and the deposit misses. The companion
app therefore runs the payload inside a foreground service.

## Logs

All of these land in the mailbox directory when the payload runs from
there (the app sets its files dir as the working directory):

| File | Written by |
|---|---|
| `ghost_run.log` | payload stdout/stderr (app appends) |
| `child.log` | the root helper child, with monotonic timestamps |
| `ktrace.txt` | `ktrace()` panic-proof progress trace |
| `rootout`, `rootcmd` | mailbox command/output |
| `ROOTED`, `rootd.pid` | root daemon markers |
| `exploit_status.txt`, `runner_ui.log` | the app (service status, UI console) |

`ktrace.txt` is opened relative to the working directory, so a manual
run from another directory writes it there.

If the device panics, the last `ktrace.txt` line names the last step
that ran, which is the best clue to where it faulted.
