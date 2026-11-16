# Credits and provenance

This repository combines work from several sources. All upstream licenses
are preserved; see LICENSE (GPL-3.0) for the license applied to this
combined work.

## Upstream exploit: GhostLock / IonStack (CVE-2026-43499)

- **Source**: `NebuSec/CyberMeowfia`, `IonStack/CVE-2026-43499/exploit/`
  (local checkout: `~/dev/security/CyberMeowfia/IonStack`)
- **Authors**: NebuSec / CyberMeowfia
- **License**: Apache License 2.0
- **CVE**: CVE-2026-43499 — Linux kernel `futex` priority-inheritance requeue
  use-after-free, exploited here as a kernel LPE on the Amazon Echo Show 15
  (`hoya`, Fire OS 7 / Android 9, kernel 5.4.86).

## KernelSnitch

- `exploit/src/kernelsnitch/` — KASLR slide leak helpers derived from
  KernelSnitch (public research tooling for leaking kernel pointers via
  side channels). Bundled headers only; see upstream for full license.

## Device-specific modifications (this repository)

- `src/targets/hoya-PS75741.7082N/` — target offsets, KASLR profile and
  build constants for the Echo Show 15 (`hoya_ftv`, build PS75751.7809N).
- Hardened-usercopy bypass: swap the target slab page's `slab_cache` to
  `kmalloc-4k` and widen the real cache's `useroffset/usersize`.
- `root.c` — persistent `ghost_rootd` command daemon; mailbox directory is
  resolved from `GHOST_ROOTDIR`, then `/proc/self/exe`, then a legacy
  fallback, so the same binary works from any app's files dir.
- `oom_score_adj` self-tuning note: the spray counts are calibrated for the
  diag app's `oom_score_adj=200` (foreground-service class).

## Companion app

- `app/` — "GhostLock Runner" (`dev.ghostlock.runner`), a plain-Java Android
  app (no Gradle, no native code) that ships the `ghostpw` payload as an
  asset, runs it as a foreground service at the tuned `oom_score_adj`, and
  proxies the `rootcmd`/`rootout` mailbox over TCP for LAN access.

## Operational documentation

`docs/` contains the device notes used while developing this: exploit
recipe, SELinux context rules, C2 channel description, and known-broken
areas. Sanitized versions live in the private operations repo; these are
the safe-to-share excerpts.
