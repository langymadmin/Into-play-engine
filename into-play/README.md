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

## What is here so far

Two throwaway programs that answer "does this work at all". They are not the
bridge; they are the evidence that the bridge is worth writing.

| | |
| --- | --- |
| `Probe.java` | Loads the card pool with no GUI and reports size and cost |
| `Spike.java` | Starts a real game with no GUI and logs every call the engine makes to the UI |
| `run-spike.sh` | Builds the engine modules and runs either of them |

```sh
./run-spike.sh Probe     # card pool
./run-spike.sh Spike     # a real game, prompt traffic on stdout
```

Run from the root of the Forge checkout.

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
4. `CardStorageReader` → `StaticData`

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

## What this fork will add

**Phantom targets** — a `GameEntity` subclass that is a legal target for
anything, so a spell aimed at a card the engine cannot see still resolves.
Legality funnels through one method, `SpellAbility.canTarget()`, where every
card-specific check is guarded by `instanceof Card` and the rest are virtual
calls on the `GameObject` interface. Effects landing on a phantom do not crash:
`TargetChoices.getTargetCards()` is `filter(targets, Card.class)`, so the
phantom is dropped and the effect no-ops. That filter is the interception point —
one hook turns a dropped phantom into "this effect was aimed off-table".

**Self-declared effects** — non-prompting variants of `IDevModeCheats` so the
player can enact what an unseen opponent did, going through the same engine APIs
so triggers still fire.

**The bridge** — `IGuiGame` + `IGameController` marshalled to JSON over a
WebSocket. Forge already ships a remote implementation of this pair in
`forge.gamemodes.net` (`ProtocolGuiGame`, `GameProtocolHandler`) over a binary
wire; the seam is proven and only the encoding changes.

The client design, and how these prompts map onto panels that already exist,
is in `docs/UI-3.0.md` in the Into Play repo.
