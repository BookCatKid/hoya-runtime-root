# hoya-runtime-root

GhostLock kernel exploit (CVE-2026-43499) for the **Amazon Echo Show 15
(`hoya_ftv`)**, plus a self-contained Android app that runs it.

> **Authorized use only.** Kernel exploit — see [NOTICE](NOTICE). Private
> repository; do not publish or mirror.

## Contents

| Path | What |
|---|---|
| `exploit/src/` | Exploit source for `hoya-PS75741.7082N` (older upstream rev — see note) |
| `exploit/build.sh` | Rebuild `ghostpw` with `zig cc` → `exploit/build/ghostpw` (weaker spray) |
| payload (not in git) | **Proven** `ghostpw` at `~/ghostlock-echo-show-15/bin/ghostpw`; set `GHOSTPW=` to override |
| `app/` | "GhostLock Runner" APK — plain Java, no Gradle |
| `docs/` | Device notes, exploit recipe, app architecture |
| `CREDITS.md` | Upstream provenance (NebuSec/CyberMeowfia IonStack, Apache-2.0) |

> **No binaries are committed.** The proven payload is the upstream build
> kept at `~/ghostlock-echo-show-15/bin/ghostpw` (191880 bytes, sha256
> prefix `bf0d17b9…`). `app/build.sh` copies it into the APK assets;
> rebuilds from `exploit/src/` are for source archaeology only — they
> produce `spray=216` and never land on hoya.

## The app

`dev.ghostlock.runner` — install via LAT/adb, tap **Get root**:

- extracts `ghostpw` to its files dir and runs it inside a **foreground
  service** — required because the exploit's spray is tuned for
  `oom_score_adj=200`, which a visible activity (adj=0) breaks;
- streams nothing during the run (timing-sensitive), polls the log tail;
- on success, `ghost_rootd` answers the `rootcmd`/`rootout` mailbox and the
  app offers an on-device root shell plus a **LAN shell** (`nc <ip> 4444`);
- rooting is manual-only — no boot receiver, so a panicky run can't
  boot-loop the device.

```sh
app/build.sh
adb install -r app/hoya-runner.apk
```

## The exploit

```
GHOST_STAGE=slide_dep GHOST_WAITER_CORE=2 GHOST_PARK=1 GHOST_FOPS_TRUE=1 ./ghostpw
```

Probabilistic: 12 attempts per run, panics happen. See `docs/DEVICE.md`.

## License

GPL-3.0 — see [LICENSE](LICENSE). Upstream IonStack is Apache-2.0; see
[CREDITS.md](CREDITS.md).
