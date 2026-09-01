# EkoAlert frontend — design and build brief

Read `API_CONTRACT.md` first. It is authoritative on every payload, and it was
copied from a live server. Where this document and the contract disagree, the
contract wins.

React + Vite + TypeScript + Leaflet. The backend is done and running on
`http://localhost:8080`. Build the frontend only.

---

## 1. Who this is for

Lagos residents, on phones, on mobile data, often on inexpensive Android
devices with mediocre screens. Some of them are opening this outdoors in rain,
one-handed, with wet fingers, while water rises. Design for that person first
and let the desktop layout follow from it. Desktop is a widened phone, not a
dashboard.

There are two situations, not two apps:

**Residents** open it dry and curious. They want the map, they want to
understand what is happening upstream of them, they want to subscribe to the
areas they care about. They have minutes.

**Reporters** open it standing in the rain with one job. They have ten seconds
of patience and one hand. Anything on screen that is not "log the water" is
noise.

Same codebase, same URL, same deployment. The only difference is where you land
after auth: residents and anonymous visitors land on the map, verified
reporters land on the report screen. Reporters can reach the map; residents
never see the report screen. Roughly 80% of the code is shared.

---

## 2. The idea the design has to carry

Most flood tools describe water that is already there. This one predicts where
it goes next, by walking a drainage graph built from what residents know.

On day one that graph is guessed. Every edge is `INFERRED`, so the map is
complete and the system is almost silent. Residents confirm edges one tap at a
time, and as they do, the system gets progressively louder. That transition is
the whole product, and the interface has to make it legible: you should be able
to look at the map and see, instantly, how much of it is real and how much is
still a guess.

---

## 3. Design direction

### The organising rule

**Colour means water. Nothing else in the interface is saturated.**

Chrome, labels, controls, edges, borders, the map itself: all neutral. The only
place chroma appears is water depth. This is the discipline that makes the
product legible at a glance in bad light, and it removes at a stroke the
temptation to decorate.

That also means: no coloured buttons, no accent-tinted links, no brand colour
on the header. If it is not water, it is grey.

### Palette

Six values. Named for what they are.

```
--ink        #14181A   near-black with a green cast, all text and strokes
--slate      #5A6468   secondary text, inactive states
--mist       #AEB6B8   hairlines, disabled, map edge base
--paper      #F2F4F3   app background, faint cool grey, NOT cream, NOT white
--surface    #FFFFFF   cards, sheets, the one true white

--ankle      #C79A2E   muted ochre
--knee       #D2621C   strong orange
--impassable #9E2B25   deep oxide red
```

The depth ramp is deliberately earthy rather than neon. It reads as floodwater
and silt, not as a traffic light, and it holds up in sunlight on a cheap LCD.
Never rely on the ramp alone: every severity is always accompanied by its depth
glyph and its word, so it survives colour blindness and glare.

Dark mode: invert `paper`/`ink`, keep the depth ramp identical, lift `mist` to
`#3A4245`. Ship it. People check this at night during storms.

### Typography

One family: **Archivo** (variable, from Google Fonts). Use its width axis
instead of introducing a second typeface.

- UI, body, labels: Archivo, normal width, 400/500/600.
- Time-to-impact numerals and zone codes: Archivo Expanded, 600, tabular
  figures, tight tracking. This is the only display treatment in the product
  and it is reserved for the number that matters.

Scale, mobile: 13 / 15 / 17 / 22 / 34 / 56. Body is 17, not 14. This is read in
rain. Nothing below 13 exists anywhere in the interface.

Sentence case throughout. No all-caps labels. No eyebrow text above headings.
No monospace for data.

### The signature element: the depth glyph

Build one component and use it at every scale. A fixed baseline with a fill
rising against it, in the depth colour, at three heights:

```
   ankle          knee         impassable
  ┌──────┐      ┌──────┐      ┌──────┐
  │      │      │      │      │██████│
  │      │      │██████│      │██████│
  │██████│      │██████│      │██████│
  └──────┘      └──────┘      └──────┘
```

Rendered properly as SVG, not box characters. It appears as the map marker, on
the three report buttons, in every alert row, in the zone sheet. One idea,
repeated, learned once. This is where the design's boldness is spent; keep
everything around it quiet.

### Map treatment

Leaflet with **CARTO Positron** tiles, not default OSM. Default OSM tiles are
loud and coloured, and they would compete with the depth ramp for meaning. A
near-monochrome basemap lets zone status be the only colour on screen.

Edge rendering, which carries the confidence story:

- `CONFIRMED` and not blocked: solid stroke, `--ink`, 2.5px, arrowhead at the
  downstream end.
- `INFERRED`: dashed stroke, `--mist`, 2px, no arrowhead, 60% opacity.
- `REJECTED`: not drawn at all.
- `blocked` (on any confidence): the stroke gets a short perpendicular bar at
  its midpoint. Blockage is a property of the channel, so it reads as a barrier
  across it.

Read `alertable` from the server. Do not recompute it from `confidence` and
`blocked`.

Zones: circular markers, `--mist` outline on `--surface` when clear, filled with
the depth colour and carrying the depth glyph when active. Active zones get a
slow pulse; nothing else on the map moves.

### Motion

One orchestrated moment, and it is this: the tap that returns
`thresholdMet: true` and flips an edge from dashed to solid. The line redraws
from dashed to solid along its length, the arrowhead appears, and a single line
of text states what changed. Roughly 600ms, once.

Everything else is functional response only: sheets slide, buttons depress,
loading states appear. No entrance animations on lists or cards. Respect
`prefers-reduced-motion` by replacing the redraw with a crossfade.

### What not to build

These are the tells that would make this look generated rather than designed:

- Glassmorphism or blurred translucent panels floating over the map.
- Identical rounded cards with the same soft grey shadow under each.
- Purple or blue-violet gradients anywhere.
- Emoji used as icons.
- Arrows appended to button text.
- All-caps tracked-out labels above sections.
- A colour that does not mean water.
- Rounded corners applied uniformly. Use radius as hierarchy: sheets 20px,
  buttons 12px, markers circular, hairlines none.

---

## 4. Screens

### 4.1 Map — the default landing, public, no login

Full-bleed map. Above it, only two things: a compact status strip and a search
or locate control.

The status strip is the honest summary of the system, and it is the one place
the "complete but silent" idea becomes a sentence. From `GET /graph` counts:

> 20 zones · 17 connections · 3 confirmed

Tapping it opens a short explanation of why unconfirmed connections do not send
warnings, and invites the user to help confirm.

Live updates via `EventSource` on `/alerts/stream`. Recolour zones on
`zone-status`, including the literal `"CLEAR"` sentinel. On reconnect, refetch
`GET /graph` rather than assuming nothing was missed, and show a quiet
disconnected indicator while the stream is down. Never silently pretend to be
live.

Empty state, when nothing is happening, which is most of the time: the map is
the content. Do not fill the screen with an illustration or a "no alerts yet"
card. A calm map that shows the network at rest is the correct empty state.

### 4.2 Zone sheet — tap a zone

A bottom sheet, two detents: peek (~30%) and full.

Peek shows `displayName`, corridor, current status with depth glyph, and if
active, when it escalated in relative terms ("escalated 20 minutes ago").

Full adds inbound and outbound connections from `GET /zones/{id}`. Each row is
the other zone's name, the travel time, and the confidence state drawn the same
way it is drawn on the map, so the vocabulary carries over.

Every connection row carries the one-tap confirm and reject, with
`confirmations` and `rejections` shown as progress toward the threshold. Phrase
it as people, because that is what it counts: "1 of 2 people confirm this."

Bind labels to `displayName`. Never invent a zone name. Four zones return
`needsFieldNaming: true`; surface that honestly in the admin view rather than
papering over it.

If the reporter is not logged in, the confirm control is still visible but
prompts for login on tap. Do not hide it. Seeing that correction is possible is
half of what makes people do it.

### 4.3 Report — the reporter's landing screen

The most important screen in the product and the easiest to get wrong, because
it looks trivial and so it gets built last and carelessly.

One screen. No navigation, no map, no scroll.

Top: the reporter's zone name, large. He must be able to confirm at a glance
that he is reporting the right place.

Middle: three buttons, stacked, each at least 88px tall, each carrying its depth
glyph and its word. Ankle-deep, Knee-deep, Impassable. Full-width, high
contrast, no icons other than the glyph.

Below: one optional toggle, "Drain is blocked here." Off by default and sent
only when the reporter actually touched it. Omitting it is not the same as
saying the drain is clear, so never send `false` when untouched.

Submit is a single confirmation press after choosing a level, not a separate
form. Two taps total.

Then the part that carries the most weight in the entire application: **the
confirmation state.** The reporter must know, unambiguously, that the report
left his phone. A full-screen acknowledgement, not a toast. If the network
failed, say so plainly and queue the report for retry, and show that it is
queued. If a reporter ever taps submit, sees nothing, and walks away believing
he warned people when he did not, the system has failed at the exact moment it
existed for.

Implement the offline queue with IndexedDB and retry on reconnect. `observedAt`
is set at the moment of the tap, not the moment of the successful send, so a
delayed report still carries the right time.

After a successful report, show what happened, using the response:

- `escalated: false`, `countedTowardQuorum: false` → "Logged. One more report
  from another reporter in your zone will raise the alarm."
- `escalated: true` with alerts that all carry `suppressedBy: "inferred_edge"`
  → "Your zone is now flagged. Nothing was sent downstream, because the
  connections below you are still unconfirmed." Then link straight to those
  edges so he can go and confirm them. This turns the system's biggest
  limitation into its main call to action.
- `escalated: true` with delivered alerts → name the zones warned and the
  times. "Z02 warned, water expected in 16 minutes."

Never show a raw `suppressedBy` string. Explain it.

### 4.4 Activity

A reverse-chronological list of alerts and all-clears from the stream.

Give `all-clear` the same visual weight as `alert`. It is not a dismissal, it
is the message that keeps the alerts trustworthy. A person who never sees an
all-clear stops believing the warnings.

Undelivered alerts appear here too, greyed, with the reason in plain language
and a link to the edge that blocked them. A suppressed alert is the system
explaining itself, not a failure to hide.

### 4.5 Subscribe

`POST /subscriptions` is public and needs no login, so keep it that way. From
the zone sheet, one control: "Warn me about this area." Repeat calls are
idempotent, so the control can be optimistic.

### 4.6 Admin

Kill switch first, on its own, at the top, current state always visible and
unambiguous. Two states, plainly labelled: alerts are being sent / alerts are
halted. Toggling to halted asks for confirmation; toggling back does not.

Below it, reporter suspension, and the list of `needsFieldNaming` zones as
outstanding work.

Replay gets its own view and deserves care, because it is the project defence
demo. Paste or load a scenario, run it, and show a timeline of predicted
arrivals. Lead with `suppressedByUnconfirmedPath` from the summary, stated as
what it is: the number of warnings that would not have gone out because nobody
had confirmed those connections yet. That single figure is the argument for the
whole confirmation mechanism.

### 4.7 Login

One screen, username and password. The demo logins are `admin`, `ada`, `bola`,
password `ekoalert-demo`.

The 401 message is identical for wrong password and unknown username by design.
Do not add client-side logic that distinguishes them.

Route by role on success: `REPORTER` to the report screen, `ADMIN` to admin.
Token in memory or `sessionStorage`, never a cookie. No refresh endpoint
exists, so on a 401 mid-session, route to login without losing an unsent report
from the queue.

---

## 5. Contract handling

**Null fields are omitted, not null.** Type every optional field as optional and
test with `?.` or `in`. Never `=== null`. This is the most likely source of
runtime crashes.

`GET /zones/{id}` returns **404 with an empty body**. Do not call `.json()` on
it.

Errors all share `{ error, message, at }`. Show `message` for `bad_request` and
`validation_failed`, since it is written to be read. For `forbidden`, do not
offer to log in again; it will not help.

CORS allows only ports 5173 on localhost and 127.0.0.1. Stay on the Vite
default.

Severity is ordered ANKLE < KNEE < IMPASSABLE. Water decays one step per hop.

---

## 6. Quality floor

Not optional, and not to be announced in the UI.

- Every touch target at least 44px, and the report buttons far larger.
- Visible keyboard focus everywhere.
- Contrast at least 4.5:1 for text, verified against both themes.
- `prefers-reduced-motion` respected.
- Works at 320px wide.
- Skeletons on first load, never a spinner over a blank screen.
- The map is usable one-handed: primary controls in the lower half of the
  screen, nothing critical under the thumb-unreachable top corners.

---

## 7. Build order

1. `GET /graph` on the Leaflet map with the confidence-aware edge styling and
   the depth glyph markers. No login. This alone is a demonstrable deliverable.
2. `EventSource` wiring, live recolouring, activity list. Open two tabs and run
   `backend/demo.sh` to watch it move.
3. Login and the report screen, including the offline queue and the
   confirmation state.
4. One-tap edge correction with the threshold moment. This is the feature the
   pilot exists to exercise, so build the motion properly here and nowhere else.
5. Admin: kill switch, suspension, replay timeline.

---

## 8. Before you write code

Produce a short design plan first: the six palette values in use, the type
scale, an ASCII wireframe of the map screen and the report screen, and one
paragraph on how the confidence states read at a glance.

Then check it against section 3. If any part of it is what you would produce for
any other mapping app, change that part and say what you changed. Then build.

No em dashes in any copy, comment, or documentation you generate.
