---
title: Beautiful Wake — project
type: project
layer: store
tags: [minecraft, neoforge, client-only, rendering]
---

# Beautiful Wake

The water behind a boat: a wake in relief -- the bow wave, the stern's
trough, chevron ridges nested in the Kelvin V -- drawn as a pale sheet
edged and lined in white with a bubbling churn and a bubble burst off the
bow; a touch of it behind a swimmer; a splash sized to the thing wherever
anything enters the water. Client-only NeoForge 1.21.1 mod, `beautifulwake`, one of Rusty's
self-built mods (public at github.com/the-rusty-shackleford), in the shared
modpack as a client-only file.

## Shape

- `domain` (JDK-only, plain JUnit): `Sample`, `Trail` (samples, windowed
  speed, heading), `Wake` (intensity ramp, age fade, spray count, the
  Kelvin half-angle asin(1/3)), `WakeField` (height and foam at any point
  in the hull's frame: bow wave, trough, transverse ripples, chevrons;
  the V's half-width and edge), `WakeTable` (the field sampled per hull
  width, bilinear), `WakeMesh` (rows along the trail, columns across,
  heights, normals, the two texture mappings, the shade, the turn clamp),
  `WakeParams`, `BowFoam` (the bow's bubbles), `Splash`, `Ripple`.
- `main` (client): `BeautifulWake` (entry, config, event listeners),
  `WakeConfig`, `client/Craft` (watercraft = `Boat` or a configured id;
  swimmer = a living entity in water, eyes out, not riding; the water
  surface under an entity), `client/WakeTracker` (a trail and a `BowFoam`
  per craft, spray, the tables built off-thread and warmed at setup),
  `client/SplashTracker` (dry-last-tick, wet-now = a splash), `client/Mass`,
  `client/WakeRenderer` (`RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS`,
  `RenderType.entityTranslucent`: the mesh drawn as skin, lines and foam,
  the churn with two texture frames cycled every four ticks, the bubbles as
  billboards, the splash rings with three frames of foam flecks).
- `gametest`: the photo booth only (`WakeBooth`, `BoothMod`); it creates a
  flat world through `WorldOpenFlows.createFreshLevel`, digs a pool, drives
  a boat, swims a cow, drops items, photographs, writes verdict lines.
- `devtools/art/build.py`: every texture, procedural, at sixteen pixels to the block: skin, lines, foam in two frames, flecks in three, the bubble and the ring.

## How it is verified

`./gradlew build`: 62 JUnit tests on `domain`; the booth's ten checks and
thirteen photographs (`-PskipBooth` to omit), the last four of them the
booth's own player wading and driving; the at-speed checks assert relief
(a bow wave over a tenth of a block, a leaning normal) and bubbles. The
look is judged from the booth's photos and in the pack. A mesh build is
timed by hand at 0.08 ms for a full trail.

## Decisions

D-0001 client-only, geometry not particles for the trail, the Kelvin
angle, sampling positions not velocity; D-0002 the booth makes its own
world; D-0003 the cel-shaded flat look (superseded); D-0004 the wake as
a surface in relief with the Wind Waker treatment, after Rusty's "2D,
flat, messy" verdict and the King of Red Lions screenshot; D-0005 pixel
foam at the game's density, animated, the wake scaled to a rowboat, a
splash foaming by its weight, the nose hidden under the body; D-0006
quads wound counter-clockwise from above (Complementary flips a back
face's normal), the booth able to run under Sodium, Iris and the pack's
shaders on llvmpipe.

## Next

Asked by Rusty 2026-09-08 and delivered in 1.0.0: the wake, swimmers,
entry splashes. 1.1.0 the same day after "it needs to be polished a LOT
more ... like Wind Waker" and "almost no wake behind my player": the bold
look (D-0003), swimmers at 0.9 strength with a floor, the player-driven
booth act. 2.0.0 the same day after "the wake is 2D, its fuckin flat ... the white
doesn't even look like foam" and "Compare to windwaker": the relief, the
pale edged sheet, chevron lines, bubble foam, the bow burst, the pointed
outline (D-0004). 2.1.0 after "a bit too large", the splash-foam ask, the wader and bow
screenshots and the triangle report (D-0005). 2.1.2 after "the white foam behind the boats is gray with the shaders":
the booth run under Complementary showed it, the winding was the cause
(D-0006); bubbles capped at a sixth of a block ("where are the snowballs
coming from"). 2.2.0: chevrons pinned to the water by each sample's arc (a sharp turn
no longer swings the old wake), no wake dragged under a diver or after a
flier, the intensity ramp a curve with no floor ("snapped in"), the
swimmer's wake from twice the body's width at full strength, every quad
culled so a shader pack never sees a back face. Open: a player's wake
reported dark under Complementary from a view the booth has not
reproduced; a ring that is not a perfect circle.
