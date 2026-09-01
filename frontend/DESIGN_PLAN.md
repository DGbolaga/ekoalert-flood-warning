# EkoAlert frontend design plan

Written before any code, per FRONTEND_BRIEF section 8.

## Palette in use

Six values, plus the three depth colours. Nothing else has chroma.

| Token | Value | Used for |
|---|---|---|
| `--ink` | `#14181A` | all text, all strokes, confirmed edges, button fills |
| `--slate` | `#5A6468` | secondary text, inactive tabs, vote counts |
| `--mist` | `#AEB6B8` | hairlines, inferred edges, marker outline at rest |
| `--paper` | `#F2F4F3` | app background |
| `--surface` | `#FFFFFF` | sheets, cards, marker fill at rest |
| `--ankle` | `#C79A2E` | depth only |
| `--knee` | `#D2621C` | depth only |
| `--impassable` | `#9E2B25` | depth only |

Dark mode swaps `paper` to `#14181A`, `ink` to `#F2F4F3`, `surface` to `#1C2124`,
`mist` to `#3A4245`, `slate` to `#8B9498`. The three depth values do not move.

The submit button is `--ink` on `--surface`. The kill switch is `--ink`. Nothing
in the chrome is coloured, including destructive actions, because a red button
that does not mean water would break the one rule the interface has.

## Type scale

Archivo variable, `wght 100..900`, `wdth 62.5..125`. One family.

```
13  captions, vote counts, corridor labels
15  secondary text, row subtitles
17  body, buttons, list rows          <- base, not 14
22  sheet titles, zone names in list
34  report screen zone name
56  time to impact, expanded 125, weight 600, tabular, tracking -0.02em
```

The expanded width axis appears in exactly two places: the ETA numeral and the
zone code beside it. Everywhere else is normal width.

## Map screen

```
+------------------------------------------+
|                                          |  paper, 12px inset
|  +------------------------------------+  |
|  | 20 zones . 17 connections .        |  |  status strip, surface,
|  | 3 confirmed                     ?  |  |  radius 12, hairline mist
|  +------------------------------------+  |
|                                          |
|                                          |
|            o---------o                   |  solid ink = confirmed
|             \                            |  dashed mist = inferred
|              o- - - -o- - - -o           |
|                      |                   |
|                   [KNEE]                 |  filled marker + glyph,
|                      :                   |  slow pulse
|                      o                   |
|                                          |
|                                   ( ! )  |  stream state, only when down
|                                          |
|                             +---------+  |  locate, lower right,
|                             |  locate |  |  thumb reachable
|                             +---------+  |
|  +------------------------------------+  |
|  |  Map        Activity        Sign in |  |  tab bar, 56px, ink on surface
|  +------------------------------------+  |
+------------------------------------------+
```

Everything the thumb needs is in the bottom third. The status strip is the only
thing in the top third and it is read, not tapped, except for the `?`.

## Report screen

```
+------------------------------------------+
|  Signed in as ada                  Map   |  13, slate
|                                          |
|  Z01                                     |  34, expanded, ink
|  Drainage Channel                        |  15, slate
|                                          |
|  +------------------------------------+  |
|  |  ___                               |  |
|  | |___|  Ankle-deep                  |  |  96px tall, glyph 40px
|  +------------------------------------+  |
|  +------------------------------------+  |
|  |  ___                               |  |
|  | |###|  Knee-deep                   |  |
|  +------------------------------------+  |
|  +------------------------------------+  |
|  | |###|  Impassable                  |  |
|  +------------------------------------+  |
|                                          |
|  [ ] Drain is blocked here               |  untouched, tri-state
|                                          |
|  +------------------------------------+  |
|  |        Send this report             |  |  appears only after a level
|  +------------------------------------+  |  is chosen. 64px.
+------------------------------------------+
```

No navigation bar, no map, no scroll at 320px. Two taps: level, then send.

## How the confidence states read at a glance

The map answers "how much of this is real" without a legend. Confirmed edges are
solid black hairlines with an arrowhead, so they read as drawn infrastructure.
Inferred edges are dashed grey at sixty percent opacity with no arrowhead, so
they read as pencil that has not been inked, present but provisional, and the
missing arrowhead means the direction itself is not yet a claim anyone has
backed. Blocked edges carry a short bar across the stroke at the midpoint, which
reads as a barrier laid across a channel rather than a property of the line.
Rejected edges are simply gone. On day one the whole network is grey dashes and
the status strip says three confirmed out of seventeen, so the map looks like a
sketch, honestly. Every confirmation turns one more line black. The picture of
the system getting louder is the picture of a drawing being inked.

## Checked against section 3, and changed

Four things I would have produced for any other mapping app, and what they are
instead:

1. **Coloured severity chips and a red destructive button.** Removed. Severity
   is carried by the depth glyph plus the word, and the only chroma anywhere is
   the depth ramp. The kill switch, the one control that most wants to be red,
   is `--ink`.
2. **A floating translucent card over the map for the zone panel.** Replaced with
   an opaque bottom sheet on `--surface` with a hairline top edge and no shadow.
   Nothing on this map is blurred or floating.
3. **A toast after submitting a report.** Replaced with a full-screen
   acknowledgement. A toast is dismissible, times out, and can be missed by
   somebody holding a phone in the rain, which is the exact failure the brief
   names.
4. **Uniform 8px radius on every card.** Replaced with radius as hierarchy:
   sheets 20, buttons 12, markers circular, hairline separators none. Rows in
   lists are separated by hairlines, not by being individual floating cards.

## Two endpoints the contract does not have

Flagged rather than faked:

- Nothing returns the signed-in reporter's own `zoneId`. The report screen has to
  show it, large, before he taps. Handled by caching the `zoneId` returned by his
  last report, and by saying plainly that it is unconfirmed when there is no
  cached value, rather than guessing a zone. A `GET /auth/me` returning
  `{ reporterId, zoneId, displayName }` would fix it.
- Nothing returns the current kill switch state. The admin screen therefore does
  not render a toggle, because a toggle asserts a state it cannot know. It shows
  two explicit commands and reports the state only after the operator has set it
  in this session. A `GET /admin/kill-switch` would let it show live state.
