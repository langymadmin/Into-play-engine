# Running the engine on Windows

No Maven, no bash, no fifteen-minute build. About five minutes start to finish,
most of it downloading.

## What you need

| | |
| --- | --- |
| **Java 21 runtime** | `winget install EclipseAdoptium.Temurin.21.JRE` — a JRE is enough to *run* the engine. You only need the JDK if you want to change Forge's own Java. |
| **Node 18+** | You already have it; it is what builds the Into Play app. |
| **This repository, cloned** | Not optional, and not for the code — for `forge-gui\res`, the 34,000 card scripts. Those are **not** in the build artifact. |

## 1. Clone the fork

```powershell
git clone --branch into-play --depth 50 https://github.com/langymadmin/into-play-engine.git
cd into-play-engine
```

`--depth 50` keeps it to recent history. It is still a large checkout, because
every card in Magic is a file in there.

## 2. Get the built engine

GitHub → the repo → **Actions** → **Engine build** → the most recent run with a
green tick → **Artifacts** → `into-play-engine`. Unzip it so the folder sits at
the root of the clone:

```
into-play-engine\
  engine\              <- the jars you just unzipped
  forge-gui\res\       <- the card scripts, from the clone
  into-play\
```

A complete `engine\` folder has a few hundred jars and comes to about 54 MB. If
it has four, you grabbed the wrong thing.

> **Why an artifact rather than a build?** Compiling Forge takes roughly fifteen
> minutes. CI already did it, on every push, and kept the result. Downloading it
> is the same output without the wait. The only thing you give up is changing
> Forge's own Java — for that you need the JDK and one `mvn install`, and every
> rebuild after the first is fast.

## 3. Start the bridge

```powershell
powershell -ExecutionPolicy Bypass -File into-play\windows\Start-Bridge.ps1
```

It checks java, the jars and the card scripts before starting, and tells you
which one is missing rather than failing with a stack trace. When it is up:

```
  starting the bridge on ws://localhost:8099/play
  cards loaded: 33185 in 3743 ms
  bridge listening on ws://localhost:8099/play
```

## 4. Drive a game

In a second terminal, from the same folder:

```powershell
node into-play\bridge-client.mjs ws://localhost:8099/play 100
```

That plays a whole game by pressing OK at everything, and prints a traffic
summary. Or the one that matters:

```powershell
node into-play\bolt-client.mjs ws://localhost:8099/play 80
```

Lightning Bolt twice — once at something the engine cannot see, once at the
opponent as a real player, with their life going 20 → 17.

## The Windows-specific trap

**The classpath separator is a semicolon here and a colon everywhere else.** A
command copied out of `run-bridge.sh` will fail with `Could not find or load
main class forge.intoplay.BridgeMain` and nothing else to go on. The PowerShell
script already uses `;`. If you ever run java by hand:

```powershell
java -Xmx2g -cp "engine\*;engine" forge.intoplay.BridgeMain forge-gui\res 8099
```

The `engine\*` is Java's own jar-directory syntax, not a shell glob, so it has
to stay inside the quotes.

## If you prefer bash

Git for Windows ships Git Bash, and WSL works too. Both run the existing
scripts unchanged:

```bash
./into-play/run-bridge.sh      # a whole game over the socket
./into-play/run-bolt.sh        # the two Bolts
```

They will try to build with Maven unless you pass `SKIP_BUILD=1` and already
have a classpath file, so on a download-only setup prefer the PowerShell script
or run java directly.
