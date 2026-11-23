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
| Bootloader | locked as shipped; no fastboot path found; cmdline has `androidboot.unlocked_kernel=false`. Not investigated further, so an unlock is not ruled out. |
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
3. **Verify.** `try_cfi_stage()` opens the ashmem device (the first
   `/dev/ashmem<boot_id>` path that exists, else `/dev/ashmem`) through
   the hijacked fops, restores the clobbered `.read` slot, and probes
   `ashmem_miscdev.fops` back.
4. **KASLR.** The ashmem fops table gives the kernel base; the text
   pointers in the fake table are refreshed (KASLR is normally 0 here,
   so this usually confirms rather than corrects; the llseek slot is
   repaired separately).
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

### Measured success rate

34 clean-boot runs on one Echo Show 15 (`PS75751.7809N`, kernel
`5.4.86-ab7`), 24 rooted, 70.6% (95% Wilson interval 54% to 83%):

| Attempt that landed | Runs |
|---|---|
| 1 | 4 |
| 2, 3, 6, 11 | 2 each |
| 4, 5, 7, 10 | 1 each |
| 8 | 5 |
| 9 | 3 |
| none (12 misses) | 10 |

Successful runs took 62–196s. The failures took 177–242s, all used the
full 12 attempts, and all ended at `cfi miss attempt=12 step=4`: the
deposit did not land in the target slot and `try_cfi_stage()` rejected
it. All reported `failed. Reboot before retrying`. None of the 34 runs
panicked.

Misses in the full log cluster at step 4 (`cfi step4 fd=3 pre_rb=0
pre_fops=0000000000000000`), so a miss is a deposit that did not take,
not a wrong address.

#### What the number means

Maximum-likelihood fit with the 10 failures treated as right-censored at
12 gives **p ≈ 0.09 per attempt**, and that model predicts the observed
rate closely (68.6% predicted at cap 12 versus 70.6% observed). A
two-component mixture (easy boots and hard boots) fits no better: the
easy-boot weight collapses to 0.01 and the likelihood-ratio statistic is
0.02 against a critical value of 5.99. So the attempts behave like
independent tries with a uniform probability, and the cap is the binding
constraint.

Projections from that fit, at roughly 11-15s per attempt plus ~55s of
setup:

| Cap | P(root) | Worst case |
|---|---|---|
| 12 (current) | 68.6% | 187s |
| 16 | 78.7% | 231s |
| 20 | 85.5% | 275s |
| 24 | 90.1% | 319s (exceeds `EXPLOIT_TIMEOUT_MS`) |

The runs arrived in three batches, which did not agree:

| Batch | Runs | Rooted | Rate |
|---|---|---|---|
| 1 | 10 | 8 | 80% |
| 2 | 4 | 3 | 75% |
| 3 | 20 | 13 | 65% |

The batch-to-batch spread is consistent with chance (two-proportion
z = 0.85 between batches 1-2 and batch 3), but it shows the figure
moves.

#### Caveats on the measurement

This is a property of one device, not of the exploit.

- **Single device, single build.** Every run used `PS75751.7809N`,
  kernel `5.4.86-ab7`. The deposit depends on heap and slab state, which
  the kernel build, boot configuration, prior allocations and uptime all
  affect.
- **Another device may differ a lot,** in either direction. The 54% to
  83% interval describes this unit only and does not transfer.
- **34 runs** is enough to exclude a rate near 100% and to show that a
  larger cap is not a quick fix. It is not enough to predict another
  device.

Two log lines are easy to misread:

- `slide child park r=1 wr=0` appears in successful runs as well; it is
  the cleanup thread parking after root is established, not a failure.
- `slide_dep cfi ok attempt=N` is a landed deposit;
  `slide_dep cfi miss attempt=N step=4` is a miss.

Reproduce with `tools/measure.sh`, which reboots between runs and
records each outcome to `tools/results/runs.csv`.

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
| `GHOST_WAITER_CORE`, `GHOST_OWNER_CORE`, `GHOST_MAIN_CORE` | Core pinning for the slide trio. Waiter and consumer must differ. `GHOST_CONSUMER_CORE` is honored only on the `GHOST_SIGNAL` path; the default trio pins the consumer to core 1. |
| `GHOST_KASLR_BASE` | Seed the kernel base and skip the slide stage. |
| `GHOST_ROUTE=slide` | Use `run_slide_deposit_route()` (forked trio + `try_cfi_stage`) instead of the main-thread pselect route. |
| `GHOST_PMODE` | `dep` (default) or `slide` payload shape. |
| `GHOST_ROOTDIR` | Root mailbox directory (see below). |
| `GHOST_ENABLE_ADB=1` | After root, copy init's SELinux SID onto the child cred (instead of `u:r:kernel:s0`) and bring up `adbd` over TCP in-process (`service.adb.tcp.port=5555`, `ctl.start=adbd`). A host with a pre-authorized adb key then lands in `u:r:shell:s0` with full binder (`pm`/`am`/`settings`/`service`/`dumpsys`). Needed because the kernel SID gets no binder and flipping global enforcing does not reach daemons that cached it at boot. Trade-off: with this set the `ghost_rootd` nc shell runs as `toolbox` (init→toolbox exec transition), so use adb for the shell. The app sets this by default; unset it (or remove it from the app's env) for the classic kernel-SID nc shell only. |
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

`GHOST_KASLR_BASE` and `GHOST_ROUTE` are read by the older
`run_exploit` path, not by `slide_dep`: that stage hardcodes base and
slide 0 and always uses the DEP payload shape. `GHOST_PMODE` is read by
`perf_zero`, `arbwrite`, and the fops route.

## Root mailbox

`ghost_rootd` polls `<dir>/rootcmd` (a shell script), executes it as
`u:r:kernel:s0` uid 0, and appends output plus `[rc=N]` to
`<dir>/rootout`. It also writes `<dir>/rootd.pid` and `<dir>/ROOTED`.

The handoff is atomic on both sides: the app writes `rootcmd.tmp` and
renames it over `rootcmd`, and the daemon renames `rootcmd` to
`rootcmd.run` before reading it, then unlinks it. A command that
arrives while one is running waits in a fresh `rootcmd` instead of
being truncated away, so `rootcmd` is legitimately absent from `ls`
most of the time. It reads at most 65535 bytes and reports
`command too large` with rc 126 above that; the app rejects scripts
over 4000 bytes first. A command that runs past 120 seconds is killed
with SIGKILL and reported as `command timed out after 120s` with rc
124, so a hung command cannot lock the mailbox. The SIGKILL goes to the
`sh` that runs the command, not to the whole process tree, so a
grandchild can outlive it. A command that times out on the app side may
still be running; the app waits up to 5 seconds for the previous
`[rc=N]` before sending anything else, and fails the new command rather
than reporting the old output. Use one client at a time.

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

If the device panics, the last `ktrace.txt` line names the last
completed step.
