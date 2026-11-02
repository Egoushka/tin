# Tune

Tin is meant to be adjusted, so the adjusting is part of the system. Each knob is built from Tin's own controls (chips, a slider, a toggle) and sets one variable that the whole Home reads.

| Knob | Variable | Range | Notes |
|---|---|---|---|
| Preset | all of the below | Default, Bedside, Battery, Deep work | Bedside: room light 40%, tint 70%. Battery: frost 0, room light 60%. Deep work: focus on, gap 8. Any change by hand clears the preset. |
| Attention colour | `glow-1` | violet, blue, rose, amber | Only hues that keep `ink-2` at 4.5:1 on a lit card. Green is excluded: it failed (4.48:1) and would read as "all up". |
| Room light | `--k-amb` | 0–140% | How much colour the frost has to blur. |
| Frost | `--k-frost` | 0–32 px | 0 is the power-saving fallback (no `RenderEffect`). |
| Glass tint | `--k-tint` | 35–85% | Lower is clearer glass; 35% is the contrast floor. |
| Density | `--k-gap`, `--k-pad` | 8 / 10 / 14 | Padding follows the gap. |
| Icon weight | `--k-stroke` | 1.25 / 1.6 / 2 | One stroke for every icon. |
| Focus | class `focus` | on / off | Calm cards dim to 40%; lit ones (needs you, broken, now) stay. |

The panel ends with what the phone would store: the `tin.tune` DataStore entry as JSON, with a count of values changed from Default. Default is also the reset.
