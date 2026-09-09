---
title: Beautiful Wake — project
type: project
layer: store
tags: [minecraft, neoforge, client-only, rendering]
---

# Beautiful Wake

The water behind a boat: a foam trail, the Kelvin V, spray; a touch of it
behind a swimmer; a splash sized to the thing wherever anything enters the
water. Client-only NeoForge 1.21.1 mod, `beautifulwake`, one of Rusty's
self-built mods (public at github.com/the-rusty-shackleford), in the shared
modpack as a client-only file.

## Shape

- `domain` (JDK-only, plain JUnit): `Sample`, `Trail` (samples, windowed
  speed, heading), `Wake` (intensity ramp, foam width and alpha, spray
  count, the Kelvin half-angle asin(1/3)), `WakeGeometry` (foam strip and
  arms as quads along the samples; a `Params` record with a `strength` for
  swimmers), `Splash`, `Ripple`.
- `main` (client): `BeautifulWake` (entry, config, event listeners),
  `WakeConfig`, `client/Craft` (watercraft = `Boat` or a configured id;
  swimmer = a living entity in water, eyes out, not riding; the water
  surface under an entity), `client/WakeTracker` (a trail per craft, spray),
  `client/SplashTracker` (dry-last-tick, wet-now = a splash), `client/Mass`,
  `client/WakeRenderer` (`RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS`,
  `RenderType.entityTranslucent` over the foam and ring textures).
- `gametest`: the photo booth only (`WakeBooth`, `BoothMod`); it creates a
  flat world through `WorldOpenFlows.createFreshLevel`, digs a pool, drives
  a boat, swims a cow, drops items, photographs, writes verdict lines.
- `devtools/art/build.py`: the foam and ring textures, procedural.

## How it is verified

`./gradlew build`: 34 JUnit tests on `domain`; the booth's nine checks and
twelve photographs (`-PskipBooth` to omit), the last four of them the
booth's own player wading and driving. The look is judged from the
booth's photos and in the pack.

## Decisions

D-0001 client-only, geometry not particles for the trail, the Kelvin
angle, sampling positions not velocity; D-0002 the booth makes its own
world; D-0003 the cel-shaded look after Rusty's "v0.0.1" verdict.

## Next

Asked by Rusty 2026-09-08 and delivered in 1.0.0: the wake, swimmers,
entry splashes. 1.1.0 the same day after "it needs to be polished a LOT
more ... like Wind Waker" and "almost no wake behind my player": the bold
look (D-0003), swimmers at 0.9 strength with a floor, the player-driven
booth act. Open: the look in the pack under Iris; a bow wave that curls
rather than a bar; foam that varies more along a long straight run.
