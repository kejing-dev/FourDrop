# Four Drop · 四子棋

A 7 × 6 rack stands on your table in PICO OS Shared Space. Pinch a column twice, or carry a disc from your tray to a
column mouth and let go: it rattles down to the bottom, one tick per row. Four in a row wins. Pull the brass slide
toward you and all the discs pour out and stack back into the trays.

- Opponents: Easy (misses diagonals), Serious (looks six moves ahead), Hard (bitboard α-β, hardly slips — so you go
  first), or two players passing one headset.
- Undo, dump and restart, mute, three volume stops, reduce motion, 中文 / English.
- Everything is local: no network, no account.

## Build

```bash
./gradlew assembleDebug testDebugUnitTest
```

Spatial SDK BOM 6.1.9, minSdk 35. The rack, discs and trays are runtime meshes (exact hole and column sizes); the oak
grain is drawn procedurally. Icons: Material Symbols (Apache 2.0). Font: Noto Sans (OFL, `third_party/noto-sans`).

## Sounds

CC0 recordings: freesound 840425 (Hongsungbock, "baduk2") for the disc ticks, landing and pick-up; freesound 254343
(cmorris035, wood slide) for the slide; Kenney Interface Sounds for the full-column, win and lose cues.
