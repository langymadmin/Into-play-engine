# into-play-engine

A fork of [Forge](https://github.com/Card-Forge/forge), the open-source Magic
rules engine, modified to run a game where only one seat is known.

Forge assumes it owns every player. At a kitchen table it owns one: the other
players are cardboard and human, and the engine cannot see them. This fork adds
the two things that close that gap, and a bridge so a web client can drive it.

**GPL-3.0**, inherited from Forge. That is why this is a separate repository
from the Into Play app: the fork is a derivative work and its source must be
published, and that obligation stops at a repository boundary.

## Set it up as a GitHub fork, not a copy

Fork `Card-Forge/forge` through GitHub rather than pushing a copy. It keeps the
full history (which GPL attribution wants) and the upstream link, so staying
current is `git fetch upstream && git merge` instead of re-applying patches by
hand. Upstream absorbs a few thousand new card scripts a year and churns hardest
in the targeting code this fork patches, so that link is the difference between
a maintainable fork and an abandoned one.

Then put the work on a branch and keep `master` clean for merging upstream.

## What is here

Everything under `into-play/` is additive. Only one file outside it is patched
(`TargetChoices.add`, one clause), which is what keeps upstream merges cheap.

The real code is the package; the flat files beside it are the throwaway spikes
that established it was worth writing, kept because they are the cheapest way to
re-check that the engine still starts.

| | |
| --- | --- |
| `src/forge/intoplay/BridgeGui.java` | `IGuiGame` marshalled to JSON — the engine's side of the wire |
| `src/forge/intoplay/BridgeServer.java` | Netty WebSocket on `/play`, routing client messages to `IGameController` |
| `src/forge/intoplay/BridgeMain.java` | Loads the card pool, starts a game behind the bridge |
| `bridge-client.mjs` | A client that plays a game by pressing OK — the bridge's test |
| `bolt-client.mjs` | Casts Bolt twice: at a phantom, then at the real opponent |
| `run-bridge.sh` | Starts the bridge, drives a game through it, fails if it stalls |
| `run-bolt.sh` | Starts the bridge and casts both Bolts, fails unless the engine confirms all seven steps |
| `Probe.java` | Loads the card pool with no GUI and reports size and cost |
| `Spike.java` | Starts a game with no GUI and logs every call the engine makes to the UI |
| `PhantomSpike.java` | 12 checks that a spell can still be aimed off-table |
| `run-spike.sh` | Builds the engine modules and runs any of the above |

```sh
./into-play/run-spike.sh Probe          # card pool
./into-play/run-spike.sh Spike          # a game, prompt traffic on stdout
./into-play/run-spike.sh PhantomSpike   # the phantom-target checks
./into-play/run-bridge.sh               # a whole game over a WebSocket
./into-play/run-bolt.sh                 # Bolt at a phantom, and Bolt at the opponent
```

Run from the root of the Forge checkout.

## The bridge

A WebSocket on `ws://localhost:8099/play`. Every message is a JSON object with a
`t` field; the full list is in the javadoc at the top of `BridgeServer.java`.

**The protocol is asymmetric, and that is the one thing to understand.** Buttons
are fire-and-forget — `{"t":"ok"}` with no id — because the engine is blocked on
a latch and `IGameController` releases it. Questions carry an id —
`{"t":"choose","id":7,"picked":[0]}` — because the engine thread is parked inside
`getChoices()` waiting for that specific answer to come back. Taps carry neither,
because the engine never asked: `{"t":"card","cardId":42}` tells it what the
player touched, and it decides for itself whether that was legal.

Three threads, and they must stay three: Forge throws outright if its input latch
is awaited from the thread it considers the UI. The engine runs on
`into-play-game`, Netty on its event loop, and a single-thread executor named
`into-play-edt` stands in for the UI thread so `isGuiThread()` has a truthful
answer.

`BridgeGui` extends Forge's own `AbstractGuiGame`, which already derives `one()`,
`oneOrNone()`, `reveal()` and `getInteger()` from `getChoices()`. So answering
`getChoices` answers most of a 104-method interface, and only genuinely new
behaviour is written — about fifteen methods, not a hundred.

### What it does, measured

`run-bridge.sh` plays a complete game: 108 turns, both libraries emptied, game
over by decking. The client only presses OK and discards to hand size, so nobody
ever plays a land — which is why the game ends that way, and why "a game ran to
completion" is the assertion rather than anything about the result.

The check earns its keep. Breaking the card lookup on purpose makes it fail with
`no card selection ever reached the engine`. An earlier, weaker version of the
assertion counted turn changes and passed anyway, because `turn` fires on every
priority pass and cleared its own threshold long before the stall.

## The Bolt

`run-bolt.sh` does the thing the fork was built for, through the whole stack:
play a Mountain, cast Lightning Bolt, aim it at a card that is not in the game,
pay for it, let it resolve, and read back what the room has to be told.

```
  play  Mountain (10)
  cast  Lightning Bolt (55)
  target  engine wants 1-1 of 0 card(s), or something off the table
          -> off the table: "their 2/2 on the table"
  targets locked in
  pay     tap Mountain (10)
  RESOLVED
  >>> TELL THEM: Lightning Bolt → their 2/2 on the table
```

**`1-1 of 0 card(s)` is the whole point.** The engine needed exactly one target
and had none to offer: no creature anywhere in its state, so stock Forge would
have refused the cast outright. The phantom is the only reason there is a legal
spell here at all.

### How a client declares one

`{"t":"offTable","describe":"their 2/2 on the table"}`, while a `targeting`
message is open. The engine's side is one method,
`InputSelectTargets.selectOffTable(String)` — the second and last patch to
Forge's own source, written in the style of the `selectCardForMacro` and
`selectPlayerForMacro` methods already beside it, which exist for the same
reason: something outside the UI driving a selection a human would click.

Reaching into the live input is deliberate rather than lazy. Forge's targeting
state lives inside that object — its own target set, its min/max accounting,
whether OK is enabled — so adding a target through `sa.getTargets()` instead
would leave the input believing nothing had been chosen.

### One seat is a proxy, and it is still a real Player

The opponent is a genuine engine `Player` — that is the point, and it is what
makes their life total real rather than a number a human nudges. Only their
*cards* are cardboard. So the same Bolt proves both halves:

```
  >>> TELL THEM: Lightning Bolt → their 2/2 on the table     (phantom: engine does nothing)
  life    Phantom 20 -> 17  (engine applied 3)               (player: engine does it itself)
```

Two things had to be true for that to work, and both are Forge's own machinery:

**It must not draw.** Its sixty cards are on the table, not in the engine, and a
real player who draws from an empty library loses — rule 704.5b. The proxy would
hand over the game within a few turns for no reason visible at the table. The
fix is a `CantDraw` static on a card in its command zone, which stops the draw
*before* `drawCards` touches the library, so `triedToDrawFromEmptyLibrary` is
never set. Blocking the loss instead would leave a failed draw happening every
turn, firing whatever watches for one.

`EffectZone$ Command` is load-bearing. A static ability is only active on the
battlefield unless it says otherwise, so without it the card sits in the command
zone with its ability parsed, attached, and doing nothing — `canDraw()` stayed
true and the proxy decked itself out exactly as before. `BridgeMain` now prints
the seal on startup (`canDraw=false`) rather than assuming it took.

**It must not be asked anything.** Nobody is holding that screen.
`autoPassUntilEndOfTurn()` is Forge's own yield, re-armed on every
`GameEventTurnBegan` because `autoPassCancel` clears it at each cleanup. Only
that seat: our own priority during their turn still comes through, because that
is where instants live.

The bridge also now tells the client which seat is the proxy, in `open` and
`board`. With one socket holding both seats, "which seat am I" is genuinely
ambiguous — inferring it from whoever was prompted first made the Bolt test
depend on the coin toss.

### Two messages, because a spell can be countered

`{"t":"offTable","phase":"aimed",...}` when the arrow is drawn, and
`{"t":"offTable","phase":"resolved","tellThem":true,...}` when the engine
actually finishes with it. Telling the room "3 damage" the moment the arrow
appears would be a lie often enough to matter. They are matched on Forge's own
stack description, which both ends read from the same `sa.getStackDescription()`.

### The Card-or-Player assumption is in three places, not one

This is the fork's real shape, and it took a running game to see:

| | What it does to a phantom |
| --- | --- |
| `TargetChoices.add()` | Refused it silently — **patched**, one clause |
| `DamageDealEffect`'s apply loop | `instanceof Card` / `else instanceof Player`, so the phantom matches neither and the damage is skipped |
| `DamageDealEffect`'s stack description | Narrows targets to Cards and Players, so Bolt reads `deals 3 damage to .` |

Only the first is patched, and the other two are left alone on purpose: each
lives in one of roughly two hundred effect classes, and chasing them would
produce a fork nobody could merge upstream into.

So `PhantomEntity`'s "absorbs whatever lands on it" is true only in the sense
that nothing lands on it — `addDamageAfterPrevention` is never called. Nothing
is corrupted; nothing happens either. The `offTable` message is the effect, as
far as the room is concerned, and `line` carries the sentence while `effect`
passes Forge's gapped version through unchanged. The number comes from the
card's own text, which the client already has for every card it draws.

The engine's job was to let the spell happen legally. The sentence belongs to
the table.

### What the bridge does not do yet

- **One socket, one seat.** The tablet and the phone will be two, with prompts
  routed by which device owns the privacy of the information. The seat is already
  on the wire (`{"t":"buttons","player":"…"}`) and a press may name it back, so
  the routing exists; what is missing is a second connection to route to.
- **Combat damage assignment** is stubbed: all damage to the first blocker. Wrong
  and deliberately visible, so a game finishes rather than stopping there.
- **The proxy's opening seven is fictional.** The opening hand is dealt before
  the game starts, which skips the draw check, so the engine thinks it holds
  seven cards. Harmless, and the count starts out roughly honest — but it never
  changes after that.
- **Its mulligan is auto-kept.** At a real table that decision happens in the
  room; the engine still has to be answered, and keeping is the answer.
- **Sideboarding** returns the deck unchanged.
- **`{"t":"board"}` sends every seat's library, in order.** Fine for a test
  client, not for two people at a table. Who may see what is the routing
  question above.
- **The board projection is thinner than `CardView`.** It carries id, name and
  tapped. Everything else a client needs — power, toughness, counters,
  attacking, summoning sickness — already exists on `CardView` and simply is not
  serialised yet. Worth doing in one pass rather than a field at a time: the
  `tapped` flag only got added because a test tripped over its absence, and the
  client had grown logic to work around a gap that was never Forge's.

## Measured

```
cards loaded   : 33,185
editions       : 683
load time      : 3,564 ms
heap for pool  : 139 MB
```

139 MB is the fixed cost; per-game state is small beside it, and one JVM can
host many games sharing one pool. A 1 GB box is enough to start.

## The protocol, as observed

`Spike` starts a two-player game and logs what the engine asks. The opening:

```
notify  awaitNextInput
notify  setCurrentPlayer      | Into Play
notify  cancelAwaitNextInput
notify  updateButtons         | Into Play | Play | Draw | true | true | true
notify  awaitInput            | java.util.concurrent.CountDownLatch@234e73c3
notify  showPromptMessage     | Into Play | Into Play, you have won the coin toss. Would…
```

That is a real coin toss, with the engine blocked on a latch waiting for an
answer.

**It is two interfaces, not one.** This matters for the bridge design:

- `IGuiGame` — engine to UI. `updateButtons`, `showPromptMessage`,
  `awaitInput(latch)`. Mostly notifications; note every line above is a `notify`,
  not a return-value prompt.
- `IGameController` — UI to engine. `selectButtonOk`, `selectButtonCancel`,
  `selectCard`, `selectAbility`, `selectPlayer`, `undoLastAction`, `concede`,
  and `cheat()` returning `IDevModeCheats`.

The engine hands the UI a `CountDownLatch`, says what the buttons mean, and
blocks. The UI calls back through `IGameController` and releases it. So the
WebSocket protocol is bidirectional and latch-driven, not request/response.

`IGameController.cheat()` is also how the UI already reaches dev mode, which is
where the self-declared effects ("the opponent made me mill two") will come from.

## Bootstrap order, which is not documented anywhere

Get these wrong and the engine dies deep inside card parsing with an unhelpful
NPE:

1. `GuiBase.setInterface(...)` — an `IGuiBase`, the *platform* seam (~45 methods:
   images, audio, dialogs, thread dispatch). Headless only needs the threading.
2. `Lang.createInstance("en-US")`
3. `Localizer.getInstance().initialize("en-US", res + "/languages/")`
4. `ImageKeys.initializeDirs(...)` and `getAssetsDir()` — the assets dir must be
   the **parent** of `res`, with a trailing separator, because
   `ForgeConstants.RES_DIR` appends `res/` to it. Those constants are
   `static final`, resolved the first time the class is touched, so
   `GuiBase.setInterface` has to come before anything that loads cards.
   `initializeDirs` looks like dead configuration for a headless engine and is
   not. `CardDb` picks which printing you get partly by whether the
   art is on disk, so looking up `"Mountain"` reaches `ImageKeys.hasImage()` and
   NPEs on static fields nobody set. Empty paths are the truthful answer.
5. `FModel.loadDynamicGamedata()` — **the most expensive thing to forget in the
   whole bootstrap, because it fails silently and the symptom looks like
   something else entirely.** It parses `res/lists/TypeLists.txt` into
   `CardType.Constant.LAND_TYPES`, `CREATURE_TYPES` and the rest. Without it
   those sets are empty, so *every subtype on every card is dropped at parse
   time*: a Mountain comes out as `Basic Land` with no `Mountain` subtype.
   Forge grants a basic land's `{T}: Add {R}` from that subtype
   (`CardState.LandTraitChanges`), so the land produces no mana, Lightning Bolt
   can never be paid for, and the engine waits forever inside
   `applyManaToCost` — no exception, no log line, nothing in the prompt traffic
   to suggest cards are the problem. Every creature type is gone too.

   It also depends on 4 being right: `ForgeConstants.RES_DIR` is
   `getAssetsDir() + "res/"`, and `FileUtil.readFile` returns an empty list for
   a missing file rather than complaining, so a wrong assets dir produces
   exactly the same silence.
6. `CardStorageReader` → `StaticData` — **not optional even for an empty deck.**
   `GameAction.startGame` reaches for `StaticData.instance()` while dealing
   opening hands. Skip it and the game gets all the way through the coin toss
   first, then NPEs several frames from anything that mentions cards, which looks
   exactly like a broken bridge and is not one.

### It needs a real second thread

`IGuiBase.isGuiThread()` must not lie. Returning `true` and running callbacks
inline gets you:

```
IllegalStateException: InputSyncronizedBase.awaitLatchRelease
                       may not be accessed from the event dispatch thread
```

Forge is asserting its desktop threading model: the game thread blocks on a
latch and a *different* thread releases it. `Spike.java` creates a
single-thread executor as a headless EDT. In the real bridge, that executor is
where socket replies land — so the constraint is describing the architecture.

### Classpath

`mvn dependency:build-classpath` does not work outside the reactor. Forge's poms
use `<version>${revision}</version>`, so resolving `forge-gui` standalone fails
with `Could not find artifact forge:forge:pom:${revision}`. `run-spike.sh`
assembles from `~/.m2` instead.

**`google-collections-1.0` must be excluded.** It is the pre-Guava library with
the same `com.google.common.collect` package, and if it wins classpath order you
get `NoSuchMethodError` on `ImmutableSet.of(...)` from somewhere unrelated.

## The two changes this fork exists for

**Phantom targets — done, and proven in a real cast.** `PhantomEntity` is a `GameEntity` that is a legal
target for anything, so a spell aimed at a card the engine cannot see still
resolves. Legality funnels through one method, `SpellAbility.canTarget()`, where
every card-specific check is guarded by `instanceof Card` and the rest are
virtual calls on the `GameObject` interface — so it needed no change to Forge at
all. *Storing* the choice did: `TargetChoices.add()` whitelists
`Player|Card|SpellAbility` and silently returned false, which let the spell
resolve having chosen nothing. That one clause is the whole patch.

It also made three tests pass vacuously before it was found — "resolving does not
throw" is trivially true when nothing is in targets. `PhantomSpike` now checks
that the hole is the phantom's alone, not a hole in general.

A phantom answers every question with "yes" or "nothing", so effects that push
outward work and effects that read back do not. "Gain life equal to its power"
has no power to read. That is not a bug to fix — the information genuinely is not
in the room — it is a prompt the UI has to ask.

See **The Bolt** above for what actually happens when a spell resolves onto one,
and for the two further places Forge assumes a target is a Card or a Player.

**Self-declared effects — not started.** Non-prompting variants of
`IDevModeCheats` so the player can enact what an unseen opponent did, going
through the same engine APIs so triggers still fire. `IGameController.cheat()` is
already how the UI reaches dev mode, so the seam exists.

The client design, and how these prompts map onto panels that already exist,
is in `docs/UI-3.0.md` in the Into Play repo.
