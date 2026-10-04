# Linear

**Linear** is a performance plugin for **Paper** and **Folia** servers.
It finds the things that quietly eat your tick time (villager trading halls, packed animal farms and
redstone lag machines) and makes them cheap without breaking what players built.

- **Author:** M4sh3r
- **Supports:** Paper and Folia, Minecraft **1.21 – 26.3** (one jar for every version)
- **Java:** 21+ (Minecraft 26.x itself needs Java 25)
- **Build:** Maven

## Features

### Smart villager AI
A villager's brain (pathfinding, job and bed searches, gossip, schedules) is the most expensive thing a
mob does. Linear switches the brain off where it does nothing useful:

- **Trading halls:** villagers that physically can't walk anywhere (1x1 cells, minecarts, boats).
- **Villagers far from every player** (48 blocks by default). Paper keeps running villager brains outside
  the entity activation range (`tick-inactive-villagers: true` by default), so this saves real work.

What keeps working:

| Feature | How |
| --- | --- |
| Trading | The brain switches on while a player trades and for 10 s afterwards, so level-ups and reputation apply normally. |
| Restocking | Linear restocks trades itself: up to twice a day, during work hours, at the job site, with vanilla's demand/price rules. |
| Getting a job | New and freshly loaded villagers keep their brain for 30 s. Placing or breaking a workstation or bed wakes villagers within 4 blocks, so taking a job and re-rolling librarian trades both work. |
| Breeding | Villagers with enough food to breed keep their brain, so breeders keep breeding even with nobody around. |
| Iron golem farms | Villagers that have claimed a bed keep their brain. |
| Farmers | Farmers that can walk keep their brain (crop farms, food sharing). |
| Opt-out | Name a villager with a tag containing `keepai` or `[ai]` and Linear never touches it. |

### Crowded farm animals
Fifty cows packed into a pen can't wander, yet every one still runs wander, look-around, tempt and
pathfinding goals every tick. Linear switches the AI of packed animals off. Physics, water streams,
aging, chicken eggs and drops are untouched. **Feeding an animal wakes it up**, so breeding by hand
works. Sheep, bees and turtles are deliberately left out because their farm output depends on their AI.

### Lag machine detection
Linear counts redstone updates, piston moves and falling block / TNT activity per chunk, every second.
A chunk that stays over a limit for 3 seconds in a row is flagged:

- staff with `linear.alerts` get a chat alert with a **click-to-teleport** link,
- (in `THROTTLE` mode) the chunk's redstone is frozen in place, and its pistons, falling blocks and TNT stop,
- repeat offenders are throttled for longer each time (30 s, 60 s, 120 s, ... up to 10 min).

Counting is a map lookup and an atomic increment per event, so normal redstone pays next to nothing.

### Chunk limits
Breeding, chicken eggs and spawners stop once a chunk already holds a set number of that mob type
(80 for breeding, 40 for spawners, per-type overrides supported). Farms can't grow until they drag the
server down.

### Adaptive mode
When the average tick time stays above 45 ms, Linear gets stricter until the server recovers. It
shrinks the villager radius, lowers the crowd threshold and halves the lag machine limits.

## Benchmarks

BENCHMARKS_PLACEHOLDER

## Commands and permissions

| Command | Description |
| --- | --- |
| `/linear status` | TPS, ms/tick and what every module is doing. |
| `/linear chunks` | Throttled chunks and the busiest redstone chunks, with click-to-teleport. |
| `/linear toggle <module> [on\|off]` | Switch a module at runtime (until the next reload). Handy for an A/B test on your own server. |
| `/linear restore` | Give every mob its AI back and pause the AI optimizers. |
| `/linear reload` | Reload `config.yml`. |

Modules: `villagers`, `crowded-mobs`, `lag-machines`, `chunk-limits`, `adaptive`.

| Permission | Default | Description |
| --- | --- | --- |
| `linear.admin` | op | All `/linear` commands (includes `linear.alerts`). |
| `linear.alerts` | op | Receive lag machine alerts. |

## Configuration

Everything is in `plugins/Linear/config.yml`, with a comment on every option. The defaults are
chosen so that no farm breaks.

## How it works (and why it is safe)

- Linear only uses the Paper API. AI is switched off with `Mob#setAware(false)`, which stops goals,
  brains, sensors and pathfinding but keeps physics, trading, aging, eggs and drops.
- On **1.21.x**, Paper keeps ticking the brain of villagers outside the activation range even when
  they are unaware (this was fixed in 26.1). On those versions Linear also sets NoAI on villagers, but
  only while they are out of activation range, where the server doesn't move them anyway. It only does
  this for adults standing on the ground or riding something, never in water, and clears the flag
  before a player arrives.
- Every mob Linear touches is tagged in its persistent data, so Linear never takes over mobs that another
  plugin or an admin made unaware, and can always give back what it took.
- When the server stops, Linear gives every loaded mob its AI back before the world is saved, so
  removing the jar is safe. Mobs in chunks that were not loaded at shutdown keep their tag. Run
  `/linear restore` and visit those areas (or just reinstall Linear) to undo it.
- **Folia:** every mob is evaluated by its own entity scheduler on the thread that owns it. Player
  positions are shared through a thread-safe snapshot, and lag machine statistics are evaluated
  asynchronously. Linear never touches a world from the wrong thread.

## Building

```bash
mvn package
```

The jar is written to `target/Linear-<version>.jar`. It is compiled against the 1.21 API with Java 21
bytecode, so the same jar runs on every supported version.
