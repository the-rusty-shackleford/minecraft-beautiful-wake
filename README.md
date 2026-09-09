# Beautiful Wake

The water behind a boat, and the water when something goes into it. A
client-side mod for NeoForge 1.21.1.

- **A wake behind every boat that stands up out of the water.** The water
  is a surface in relief: a bow wave pushed up at the bow, a trough behind
  the stern, ripples across the churn, and the chevrons -- ridges running
  back and outward inside the V, nested one behind another with their
  points at the hull, the pattern every boat draws -- each with its height
  and its own light and shadow. The V opens at the Kelvin angle, 19.47
  degrees either side of the track whatever the speed, bends where the
  track bends, and comes to a point at the bow. It is drawn the way a
  pixel sea draws one: a pale sheet of disturbed water edged in a white
  line that steps in and out along its length, white lines along the
  chevron ridges, foam breaking at the bow's shoulders and streaming back
  along the sides, a churn of round bubbles behind the stern, and a burst
  of bubbles thrown up off the bow that fall back, float a moment and pop
  -- plus spray, more the faster. All the foam is drawn at the game's own
  sixteen pixels to the block and shimmers between frames. All of it
  scales with speed, from a proper wake at a paddle to the full pattern at
  a boat's top speed, so it grows as the boat gathers way and dies as it
  drifts; and it is sized to a rowboat, with a `scale` to make more of it.
- **The same behind a swimmer**, smaller and lower. A player wading or an
  animal crossing the surface leaves a V of its own with a little churn and
  a few bubbles; nothing under water, and nothing riding a boat, whose wake
  is the boat's.
- **A splash where anything enters the water.** A ring that spreads and
  fades, droplets thrown up and bubbles left under, sized by what fell and
  how fast: an apple is light, an ingot dense, a block heavy, a stack heavier
  than one, a player heavier still, and a fall from height bigger than a drop
  from the hand. A heavy enough splash leaves foam on its ring -- white
  pixel flecks round the crest, churning as the ring spreads, more the
  heavier -- and a gentle one leaves none. The game itself splashes only
  for living things.

Client only. Every client already knows where every boat is and how fast it
moves, so each draws the wakes it can see; a server needs nothing and a
server without the mod is no mismatch. In a modpack it goes on the client
side only.

## How it works

The trail is sampled from where a craft actually was, tick by tick -- not
from its motion vector -- so a boat any mod moves any way makes a wake, and
the speed a client reads off a remote boat, which arrives in steps, is
averaged over a few ticks.

The wake is a height field in the hull's frame: at every point so far
behind the hull and so far across, how high the water stands and how much
foam covers it. A mesh is laid over the water along the trail each frame --
a row per sample (thinned with age), a few rows ahead of the hull, 25
columns across each from one edge of the V to the other -- with every
vertex at the water's height there and its normal, so the surface tilts
into the light on one side of a ridge and away on the other. The field is
sampled into a table once per hull width, off the render thread, so a mesh
costs a fraction of a millisecond. The game's own water cannot be
displaced from a mod, so the wake is drawn as its own surface just over
it: crests stand proud of the water, troughs sink out of sight under it.

The mesh is drawn three times with the entity translucent render type,
right after the level's translucent blocks, taking the water's own light:
the pale sheet with the edge line, mapped across by the distance from the
V's edge so the line is crisp however coarse the grid; the chevron lines,
mapped along by the chevron phase so a line lies on every ridge; and the
foam, pinned to the water so the churn stays where the hull churned it.
Each has two texture frames with the pixels stepped differently, shown
turn and turn about every four ticks, so the foam shimmers and boils at no
cost beyond picking a texture. The bow's bubbles are a small simulation --
thrown up and outward, falling under gravity, settling and popping --
drawn as billboards facing the camera, the one part of the wake that is
not a surface. A splash's foam is three frames of flecks over its ring,
cycled every three ticks from a frame and a turn its birth tick picks, so
no two splashes foam alike.

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
| `wake.skin` | true | the pale sheet in relief, with the white edge |
| `wake.lines` | true | the white lines along the chevron ridges |
| `wake.foam` | true | the churn and the bow's bubbles |
| `wake.spray` | true | droplets off the bow |
| `wake.minSpeed` | 0.075 | blocks per tick below which there is no wake (a slow paddle) |
| `wake.fullSpeed` | 0.35 | blocks per tick at which the wake is at full strength |
| `wake.lifeSeconds` | 4.5 | how long the wake lasts |
| `wake.relief` | 1.0 | how high the wake stands: 1 is a bow wave a fifth of a block tall, 0 lays it flat |
| `wake.scale` | 0.65 | the wake's overall size: how far behind the hull the sheet reaches, how high it stands, how many bubbles; 1 is a big wake for a rowboat |
| `wake.sprayMax` | 8 | droplets per tick at full speed |
| `wake.maxDistance` | 96 | blocks from the camera beyond which nothing is drawn |
| `wake.extraWatercraft` | [] | entity type ids counted as watercraft besides boats |
| `splashes.enabled` | true | rings, droplets and bubbles on entry |
| `swimmers.enabled` | true | a wake behind swimmers |
| `swimmers.minSpeed` | 0.03 | below this a swimmer leaves nothing |
| `swimmers.fullSpeed` | 0.14 | a brisk wade or an easy swim |
| `swimmers.strength` | 0.9 | a swimmer's wake next to a boat's |

The sheet, the lines, the foam and the bubbles are custom geometry in the
translucent pass, lit by the water's light and shaded by their own normals.
Vanilla and Sodium are fine with that; Iris shader packs usually are, but a
pack can light entity geometry its own way, and then `wake.skin`,
`wake.lines` and `wake.foam` are the switches and `wake.relief` the dial.
Spray and splashes are particles and are always fine.

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
-- `Wake` (intensity from speed, fade, spray), `Trail` (a craft's samples
and the speed and heading read off them), `WakeField` (the height and foam
at any point behind a hull), `WakeTable` (the field sampled for one hull
width), `WakeMesh` (the surface along a trail), `BowFoam` (the bow's
bubbles), `Splash` and `Ripple` (a splash's strength, ring and count).
`src/main` is the client: `Craft` (what makes a wake, and the water surface
under it), `WakeTracker` and `SplashTracker` (a tick each), `Mass` (how
heavy a thing is), `WakeRenderer`. `src/gametest` is the booth, a mod of its
own, never shipped. `devtools/art/build.py` writes every texture.

## License

AGPL-3.0-or-later. Copyright (C) 2026 Rusty Shackleford and nfx.
