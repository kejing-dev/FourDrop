# 四子棋 Four Drop: project guidance

A vertical four-in-a-row rack for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.fourdrop`, v0.1.0.
Design contract: spatial-app-factory `apps/four-drop/design-package.json` (v4, gate B passed), PRD and sample beside it.
Skeleton: the factory's volume + control-window pattern (two containers, theme, sound pool, BACK keys, board-input
template, debug receiver, harness); the rules, engine, flow, scene and pages are this app's own.

## Surfaces
- **Rack volume** (default container `FourDropVolume`, Volumetric 0.90 × 0.76 × 0.40 m, fixed scale, gravity aligned,
  uniform resize → `VolumeScale` scales the content root and divides hand movement by the same k). Base with a slot,
  two posts, front / back oak plates with 42 Ø 6.4 cm holes, 8 ribs, the slide (pulls +Z) with a brass handle, two
  trays (red left, yellow right, 3 stacks × 7), 42 discs, the drop-point ring, the light bar, three plaques on the base
  front (seat line, two count plaques). Every metre lives in `domain/Layout.kt` (unit-tested).
- **Control window** (`FourDropControl`, Planar 560 × 440 dp, `Placement.right(FourDropVolume)`): native SpatialUI
  `TabBar`, three pages 对局 / 规则 / 设置; dialogs (opponent, language, confirmations) replace the page. No page scrolls.

## Tap / drag targets (volume)
| Holder | What | Size |
|---|---|---|
| `col:0..6` | column target; its visible child `mouth:c` (6.15 × 2 cm number plate) carries the system hover | 6.15 × 56 × 4 cm, 2.01° at 1.75 m, 1.85 cm apart |
| `tray:RED` / `tray:YELLOW` | the hand seat's tray only (the other has no collider): tap = put the hovering disc back, drag = carry the top disc | 9 × 10.5 × 25 cm |
| `handle` | the brass handle: drag toward you pulls the slide (5 cm on an empty / finished rack, 10 cm mid-game with a detent at 5 cm) | 14 × 6.2 × 5 cm |

All input goes through `ui/board/input/BoardInput.kt` (factory board-input template; DragTotal accumulates deltas).
Routing: `routeTap` / `routeDragStart` / `routeDragMove` / `routeDragEnd` in `ui/board/BoardScreen.kt`.

## Code
| Path | What |
|---|---|
| `domain/FourDropRules.kt` | `Position` (first seat + column list, bitboards derived), `Rules` (four check, win line, refusals, giveaway) |
| `domain/Engine.kt` | Easy (one ply, 30 % diagonal-blind), Serious (α-β 6), Hard (iterative deepening + TT, 1.5 s / node budget) |
| `domain/GameFlow.kt` | the one `PlayState`; aim / grab / drop / think / undo / dump / slide / opponent; commit before animation |
| `domain/Motion.kt` | gravity 3.3 m/s², ticks per row, bounce, undo rise, dump plan |
| `ui/board/*` | `BoardScene` (entities, per-frame poses via `BoardFrameSystem`), `DiscPoses` (pure), `Meshes`, `BoardTextures` |
| `ui/control/*` | the pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `build_icon.py`, `icons.py` (Material Symbols → vectors), `loudness.py`, `contrast.py`, `blank.py`, `fd.sh`, `harness/` |

## Build / test (kj-alienware)
```bash
export JAVA_HOME="C:/Users/Admin/.pico/primer-cli/jdk/jdk-21.0.12.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
./gradlew --offline assembleDebug testDebugUnitTest            # strength ladder at 40 games per pair
./gradlew --offline testDebugUnitTest --tests "*EngineTest" -PstrengthGames=200   # the evidence run
python -I tools/build_icon.py --icon512 <factory>/apps/four-drop/icon.png
python -I tools/loudness.py <factory>/apps/four-drop/qa/loudness-v0.1.0.wav
```
Reports land in `app/build/reports/` (strength-N.txt, engine-depth.txt, targets.txt, d35.txt).
Emulators are shared: `python -I bin/emu_borrow.py borrow four-drop` in the factory, `return` when done.
Offline release builds: add `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.fourdrop.DEBUG -p io.embodit.fourdrop --es cmd <cmd> --es a "<arg>"`
(or `DEV=emulator-55xx tools/fd.sh cmd <cmd> <arg>`). Log tag `FourDrop`. Taps go through `routeTap` with the holder
name; `grab` / `pull` go through the drag routing with physical metres. Columns in `aim` / `drop` / `grab` are 0-based
(0..6); `import` takes the 1-based column string. Send `reset` before each state (it clears slow, dialogs, the rack).

| State | Command (`goto <id>` unless noted) | Notes |
|---|---|---|
| S00 准备中 | `goto S00` (held) — or `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | |
| S01 第一次 | `goto S01` | rules page with [知道了]; mouths glow (first game) |
| S02 选对手 | `goto S02` | four rows + [取消] |
| S03 待投 | `goto S03`, then `import 4435` for a mid game | |
| S04 瞄准 | `goto S04` (tap path); `reset` · `import 4435` · `grab 3` (carried, held until `release`) | |
| S05 落子中 | `goto S05` (slow 4; capture ~0.8 s after the second tap) | |
| S06 对手在想 | `goto S06` (slow 4; ~2.4 s thinking, then its disc hovers 1.2 s) | |
| S07 清盘 | `goto S07` (slow 4); slide detent: `reset` · `import 4435` · `pull 0.05`, then `pull 0.10` · `pullrelease` | |
| S08 提醒 | a: `goto S08` (column 4 full, aimed); b: `goto S08B` (Easy giveaway, orange ring) | |
| S09 终局 | `goto S09` (red wins on the bottom row); draw: `goto S09D` then wait ~2 s for the computer's 42nd disc | |
| S10 控制窗已关 | `goto S10` (closes the panel; the next pinch on the volume only reopens it) | |
| S11 设置 | `goto S11`; language dialog: tap the language row; `lang zh|en` | |
| S12 规则 | `goto S12` | |
| S13 确认 | `goto S13 restart` · `goto S13 undo` (two players) · `goto S13 exit` | |
| S16 接着下 | force-stop + launch with a saved game (or `goto S16`), capture within 1.5 s | |

Other commands: `aim c` · `drop [c]` · `undo` · `confirm` · `no` · `dump` · `opp easy|serious|hard|two` · `lang zh|en|system`
· `sound on|off` · `vol 45|80|100` · `reduced on|off` · `slow 1|2|4` · `seed n` · `page play|rules|settings` · `closepanel`
· `reopen` · `replay "col:3 eye-hand"` (real entities through resolveUp) · `hover <holder>` · `ftest on|off` (「F↑」 on
plaques and numbers) · `restart` · `clearsave` · `state` · `evidence`.

## Screenshot list (both languages)
S00, S01, S02, S03 (empty + 4435), S04 (tap + grab), S05, S06, S07 (+ pull 0.05 / 0.10), S08a, S08b, S09 (win + draw),
S10, S11 (+ language dialog), S12, S13 × 3, S16; the launcher; `ftest on` once for the plaques and numbers.
