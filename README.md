# Beautiful Wake

The water behind a boat, and the water when something goes into it. A
client-side mod for NeoForge 1.21.1.

- **A wake behind every boat**, drawn the way a cel-shaded sea draws one:
  a solid white churn straight behind the stern, a boat and a half wide,
  breaking into patches a length back and spreading as it fades over a few
  seconds, over a wider, fainter halo; the two arms of a real wake's V, thick
  at the bow and thinning, opening at the Kelvin angle -- 19.47 degrees
  either side of the track, which is the angle whatever the speed -- and
  bending where the track bends, their foam flowing back along them; a bow
  wave across the front; spray off the bow, more the faster. All of it
  scales with speed, from a proper wake at a paddle to the full pattern at a
  boat's top speed, so it grows as the boat gathers way and dies as it drifts.
- **The same behind a swimmer**, smaller. A player wading or an animal
  crossing the surface leaves a clear ribbon and a thin V; nothing under
  water, and nothing riding a boat, whose wake is the boat's.
- **A splash where anything enters the water.** A ring that spreads and
  fades, droplets thrown up and bubbles left under, sized by what fell and
  how fast: an apple is light, an ingot dense, a block heavy, a stack heavier
  than one, a player heavier still, and a fall from height bigger than a drop
  from the hand. The game itself splashes only for living things.

Client only. Every client already knows where every boat is and how fast it
moves, so each draws the wakes it can see; a server needs nothing and a
server without the mod is no mismatch. In a modpack it goes on the client
side only.

## How it works

The trail is sampled from where a craft actually was, tick by tick -- not
from its motion vector -- so a boat any mod moves any way makes a wake, and
the speed a client reads off a remote boat, which arrives in steps, is
averaged over a few ticks. The foam strip and the arms are geometry laid on
the water surface along those samples: one quad per pair of samples, each
corner pushed out sideways by the foam's width at that age or by the
distance behind the bow times the tangent of the Kelvin angle. Their texture
is pinned to the sample's tick, so foam stays where the water put it while
the boat moves on, and streaks longer the faster it was going. The quads are
drawn with the entity translucent render type right after the level's
translucent blocks, take the water's own light, and are rebuilt each frame
from the trail; a few dozen quads per craft.

What counts as a watercraft is any boat -- the vanilla class, which nearly
every modded boat extends -- or any entity type listed in the config. A
swimmer is a living thing with its feet in the water and its eyes out of it,
not riding anything. A splash comes from any entity seen out of the water
one tick and in it the next; a thing first seen in the water (loaded with
its chunk, spawned there) did not fall in and splashes nothing.

## Config

`config/beautifulwake-client.toml`:

| Key | Default | Meaning |
|---|---|---|
| `wake.foam` | true | the foam strip |
| `wake.arms` | true | the arms of the V; switch off if a shader pack lights them oddly |
| `wake.spray` | true | droplets off the bow |
| `wake.minSpeed` | 0.075 | blocks per tick below which there is no wake (a slow paddle) |
| `wake.fullSpeed` | 0.35 | blocks per tick at which the wake is at full strength |
| `wake.lifeSeconds` | 4.5 | how long foam lasts |
| `wake.spread` | 2.2 | how many times its starting width the foam spreads to by the end of its life |
| `wake.sprayMax` | 8 | droplets per tick at full speed |
| `wake.maxDistance` | 96 | blocks from the camera beyond which nothing is drawn |
| `wake.extraWatercraft` | [] | entity type ids counted as watercraft besides boats |
| `splashes.enabled` | true | rings, droplets and bubbles on entry |
| `swimmers.enabled` | true | a wake behind swimmers |
| `swimmers.minSpeed` | 0.03 | below this a swimmer leaves nothing |
| `swimmers.fullSpeed` | 0.14 | a brisk wade or an easy swim |
| `swimmers.strength` | 0.9 | a swimmer's wake next to a boat's |

The foam and the arms are custom geometry in the translucent pass. Vanilla
and Sodium are fine with that; Iris shader packs usually are, but a pack can
light it oddly, and then `wake.arms` and `wake.foam` are the switches. Spray
and splashes are particles and are always fine.

## Building

```
./gradlew build
```

runs the plain-JUnit tests against the pure layer and, unless `-PskipBooth`
is given, the photo booth (`./gradlew runPhotoBooth`): a dev client that
makes a flat world with a pool, moves a boat through it at a paddle, at
speed and round a turn, swims a cow across, drops an apple, an ingot and a
block in, then has its own player wade across the shelf under a held key
and climb into the boat and drive it on the real controls -- slow, flat out,
hard over -- photographs each into `run/booth/screenshots/` and writes one
`booth: PASS` or `booth: FAIL` line per check to its log, which the build
reads. It needs a display; headless, `DISPLAY=:1 Xephyr :7 -screen 1280x720
-ac -br -noreset` then `DISPLAY=:7 __GLX_VENDOR_LIBRARY_NAME=mesa
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./gradlew runPhotoBooth`.

Layout: `src/domain` is the pure layer, compiled against nothing but the JDK
-- `Wake` (intensity from speed, foam width and fade, spray), `Trail` (a
craft's samples and the speed and heading read off them), `WakeGeometry`
(the quads), `Splash` and `Ripple` (a splash's strength, ring and count).
`src/main` is the client: `Craft` (what makes a wake, and the water surface
under it), `WakeTracker` and `SplashTracker` (a tick each), `Mass` (how
heavy a thing is), `WakeRenderer`. `src/gametest` is the booth, a mod of its
own, never shipped. `devtools/art/build.py` writes the two textures.

## License

AGPL-3.0-or-later. Copyright (C) 2026 Rusty Shackleford and nfx.
