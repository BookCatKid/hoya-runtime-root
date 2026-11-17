# hoya-runtime-root

GhostLock kernel exploit (CVE-2026-43499) for the **Amazon Echo Show 15
(`hoya_ftv`)**, plus a self-contained Android app that runs it.

> **Authorized use only.** Kernel exploit, see [NOTICE](NOTICE). Private
> repository, do not publish or mirror.

## Contents

| Path | What |
|---|---|
| `exploit/src/` | Exploit source for `hoya-PS75741.7082N` |
| `exploit/build.sh` | Build `ghostpw` with `zig cc` → `exploit/build/ghostpw` |
| `app/` | "GhostLock Runner" APK, plain Java, no Gradle |
| `docs/` | Device notes, exploit recipe, app architecture |
| `CREDITS.md` | Upstream provenance (NebuSec/CyberMeowfia IonStack, Apache-2.0) |

No binaries are committed. `app/build.sh` takes the payload from
`GHOSTPW=/path/to/ghostpw` or `exploit/build/ghostpw` (run
`exploit/build.sh` first).

## The app

`dev.ghostlock.runner`, install via LAT/adb, tap **Get root**:

- extracts `ghostpw` to its files dir and runs it inside a foreground
  service. The spray is tuned for `oom_score_adj=200`, which a visible
  activity (adj=0) breaks;
- on success, `ghost_rootd` answers the `rootcmd`/`rootout` mailbox and the
  app offers an on-device root shell plus a LAN shell (`nc <ip> 4444`);
- rooting is manual-only. No boot receiver, so a panicky run cannot
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

GPL-3.0, see [LICENSE](LICENSE). Upstream IonStack is Apache-2.0, see
[CREDITS.md](CREDITS.md).
