# Credits and provenance

This repository combines work from several sources. All upstream licenses
are preserved; see LICENSE (GPL-3.0) for the license applied to this
combined work.

## Upstream exploit: GhostLock / IonStack (CVE-2026-43499)

- **Source**: `NebuSec/CyberMeowfia`, `IonStack/CVE-2026-43499/exploit/`
- **Authors**: NebuSec / CyberMeowfia
- **License**: Apache License 2.0
- **CVE**: CVE-2026-43499, Linux kernel `futex` priority-inheritance requeue
  use-after-free, exploited here as a kernel LPE on the Amazon Echo Show 15
  (`hoya`, Fire OS 7 / Android 9, kernel 5.4.86-ab7).

## KernelSnitch

- `exploit/src/kernelsnitch/`: ASLR slide leak helpers derived from
  KernelSnitch (public research tooling for leaking kernel pointers via
  timing side channels). Bundled headers only; see upstream for the full
  license.

## Device-specific modifications (this repository)

- `exploit/src/targets/hoya-PS75741.7082N/`: target offsets, KASLR
  profile and build constants for the Echo Show 15 (`hoya_ftv`, build
  `PS75741.7082N`).
- Hardened-usercopy bypass: swap the target slab page's `slab_cache` to
  `kmalloc-4k` and widen the real cache's `useroffset/usersize`.
- `exploit/src/root.c`: persistent `ghost_rootd` command daemon; the
  mailbox directory resolves from `GHOST_ROOTDIR`, then `/proc/self/exe`,
  then the cwd, so the same binary works from any app's files dir.
- `oom_score_adj` self-tuning: the spray counts are calibrated for the
  diag app's `oom_score_adj=200` (foreground-service class).
- `exploit/src/pipe.c`: pipe-based physrw helpers, kept for kernels with
  the `PIPE_BUF_FLAG_CAN_MERGE` write path. On this 5.4 target the
  arbitrary read/write comes from the forged configfs buffer instead
  (`GHOST_CONFIGFS_RW`), and the pipe stage is a no-op.

## Companion app

- `app/`: "GhostLock Runner" (`dev.ghostlock.runner`), a plain-Java
  Android app (no Gradle, no native code) that ships the `ghostpw`
  payload as an asset, runs it as a foreground service at the tuned
  `oom_score_adj`, and proxies the `rootcmd`/`rootout` mailbox over TCP
  for LAN access.
