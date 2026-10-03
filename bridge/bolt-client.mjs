#!/usr/bin/env node
// Cast Lightning Bolt twice: once at something the engine cannot see, once at
// the opponent as a real player.
//
// The two casts prove the two halves of the hybrid. The opponent's *cards* are
// cardboard, so a spell aimed at one needs a phantom and the engine does
// nothing but let it resolve. The opponent *as a player* is a genuine engine
// Player, so a spell aimed at them changes a number the engine owns — which is
// what makes their life counter real rather than a thing a human nudges.
//
// One game, two turns, because a second Bolt needs a second untapped land and
// lands come one per turn.
//
//   node bridge/bolt-client.mjs [ws://localhost:8099/play] [seconds]

const url = process.argv[2] || "ws://localhost:8099/play";
const budget = Number(process.argv[3] || 60) * 1000;
const DESCRIBED = "their 2/2 on the table";
const DAMAGE = 3;

const log = (...a) => console.log(...a);
const short = (s, n = 64) =>
  s == null ? "" : String(s).length > n ? String(s).slice(0, n - 3) + "..." : String(s);

// What the engine itself has to confirm. Nothing here is set by this script
// deciding something must have worked.
const got = {
  land: false,         // a Mountain reached the battlefield
  cast: false,         // tapping Bolt made the engine ask for a target
  aimed: false,        // it accepted something off-table as that target
  resolved: false,     // that spell finished resolving
  tellThem: false,     // ...and the engine said the table has to be told
  playerCast: false,   // a second Bolt was aimed at the opponent as a player
  lifeDropped: false,  // ...and the engine took the life off itself
};
let noLegalTargets = false;  // recorded, not asserted — see the summary
let effectText = "";
let lifeBefore = null;
let lifeAfter = null;

let seat = null;       // the seat we play
let opponent = null;   // the seat across the table
let board = null;
let land = null;       // an untapped Mountain of ours, if there is one
let step = "opening";  // opening → land → landSent → bolt → target → okTarget → pay → settle
let lastPrompt = "";
let tappedForMana = false;
// The turn we last played a land on. One land per turn, and the engine simply
// refuses a second — so without this the client re-reads the board forever,
// tapping a card the engine will never accept.
let lastLandTurn = -1;

// "offtable" first, then "player".
const plan = ["offtable", "player"];
let aim = plan.shift();

const ws = new WebSocket(url);
const send = (o) => ws.send(JSON.stringify(o));

/**
 * Give up priority and let the game move on.
 *
 * Every "wait for a later turn" path has to do this. The buttons message that
 * brought us here is the engine asking for a decision, and answering it with a
 * board request is not an answer: the engine stays parked, we re-read the same
 * board, and the turn never ends. That loop held the game on turn 1 forever.
 */
function pass() {
  if (seat) send({ t: "ok", player: seat });
}

ws.addEventListener("open", () => log("connected to", url));
ws.addEventListener("error", (e) => log("socket error:", e.message || e));
ws.addEventListener("close", () => log("closed"));

/** Start the next cast, or stop if both are done. */
function nextCast() {
  aim = plan.shift();
  if (!aim) {
    step = "done";
    return;
  }
  log(`  --- next: Bolt at ${aim === "player" ? "the opponent" : "something off the table"}`);
  tappedForMana = false;
  step = "opening";
}

ws.addEventListener("message", (ev) => {
  let m;
  try {
    m = JSON.parse(ev.data);
  } catch {
    return;
  }

  if (process.env.TRACE_ALL && m.t !== "event") {
    log(`  << ${JSON.stringify(m).slice(0, 130)}`);
  }
  switch (m.t) {
    case "prompt":
      lastPrompt = m.text || "";
      // Payment is handled here, not on the buttons message, because
      // updateButtons fires BEFORE showPromptMessage. A client that reads the
      // prompt to decide what the buttons mean is always one message behind,
      // and during payment that costs the cast: the buttons are [Auto]
      // [Cancel] with Auto disabled, so "press whichever is enabled" presses
      // Cancel and the spell vanishes with no error anywhere.
      if (/Pay Mana Cost/i.test(lastPrompt) && land && !tappedForMana) {
        log(`  pay     tap ${land.name} (${land.id})`);
        send({ t: "card", cardId: land.id, player: seat });
        tappedForMana = true;
      }
      break;

    case "open":
      // The bridge names the proxy seat, so we take the other one. Inferring it
      // from whoever got prompted first made the test depend on a coin toss.
      if (m.offTable) {
        opponent = m.offTable;
        seat = (m.seats || []).find((n) => n !== m.offTable) || seat;
        log(`  seats   we are ${seat}; ${opponent} is across the table`);
      }
      break;

    case "board": {
      board = m;
      if (m.offTable) {
        opponent = m.offTable;
        if (!seat) seat = (m.seats || []).map((x) => x.name).find((n) => n !== m.offTable) || null;
      }
      const me = (m.seats || []).find((x) => x.name === seat);
      if (!me) break;

      const field = me.battlefield || [];
      const hand = me.hand || [];
      land = field.find((c) => c.name === "Mountain" && !c.tapped) || null;

      // Waiting for the engine to take the life off the opponent.
      if (step === "settle") {
        const now = (m.seats || []).find((x) => x.name === opponent)?.life ?? null;
        if (now != null && lifeBefore != null && now <= lifeBefore - DAMAGE) {
          lifeAfter = now;
          got.lifeDropped = true;
          log(`  life    ${opponent} ${lifeBefore} -> ${now}  (engine applied ${DAMAGE})`);
          nextCast();
        }
        break;
      }

      // Lands and sorcery-speed casting only happen on our own turn. We DO get
      // priority during the opponent's turn — that is where instants live — so
      // "is it my priority" is not the same question as "is it my turn", and
      // acting on the wrong one gets everything refused.
      if ((step === "land" || step === "landSent" || step === "bolt")
          && m.turnPlayer !== seat) {
        log(`  turn ${m.turn} belongs to ${m.turnPlayer}, waiting for ours`);
        step = "opening";
        pass();
        break;
      }

      if (step === "land") {
        if (land) {
          got.land = true;
          step = "bolt";
        } else if (m.turn === lastLandTurn) {
          // Already played a land this turn, and nothing untapped to pay with.
          // Nothing to do but let the turn end: lands untap in the next
          // untap step, so waiting is the whole fix — a second Bolt does not
          // need a second land, only an untapped one.
          log(`  turn ${m.turn}: land already played, waiting to untap`);
          step = "opening";
          pass();
          break;
        } else {
          const mtn = hand.find((c) => c.name === "Mountain");
          if (!mtn) {
            log(`  no untapped Mountain and none in hand — waiting for a draw`);
            step = "opening";
            pass();
            break;
          }
          log(`  play    Mountain (${mtn.id}) on turn ${m.turn}`);
          send({ t: "card", cardId: mtn.id, player: seat });
          lastLandTurn = m.turn;
          step = "landSent";
          break;
        }
      }

      if (step === "landSent") {
        if (land) {
          got.land = true;
          log(`  land    Mountain is on the battlefield, untapped (${land.id})`);
          step = "bolt";
        } else {
          // The engine refused it. Go back and re-decide rather than retrying
          // the same tap, which it will refuse just as fast.
          step = "land";
          break;
        }
      }

      if (step === "bolt") {
        const bolt = hand.find((c) => c.name === "Lightning Bolt");
        if (!bolt) {
          log(`  no Bolt in hand (${hand.map((c) => c.name).join(", ") || "empty"}) — waiting`);
          step = "opening";
          pass();
          break;
        }
        log(`  cast    Lightning Bolt (${bolt.id})`);
        send({ t: "card", cardId: bolt.id, player: seat });
        step = "target";
      }
      break;
    }

    // The engine is waiting to be pointed at something, and says off-table is
    // allowed. This is the message the fork exists to produce.
    case "targeting": {
      if (step !== "target") break;
      const n = (m.cards || []).length;
      log(`  target  engine wants ${m.min}-${m.max} of ${n} card(s)` +
          `${m.offTable ? ", or something off the table" : ""}`);
      if (aim === "offtable") {
        got.cast = true;
        noLegalTargets = n === 0;
        log(`          -> off the table: "${DESCRIBED}"`);
        send({ t: "offTable", describe: DESCRIBED, player: seat });
      } else {
        // No phantom involved. Forge's damage loop has an explicit
        // `else if (o instanceof Player)` branch, which is why this one
        // changes a number the engine owns.
        got.playerCast = true;
        lifeBefore = (board?.seats || []).find((x) => x.name === opponent)?.life ?? null;
        log(`          -> ${opponent}, a real player on ${lifeBefore} life`);
        send({ t: "seat", name: opponent, player: seat });
      }
      // Declaring a target does not finish targeting: Forge re-renders the
      // same prompt with OK now enabled and waits for it.
      step = "okTarget";
      break;
    }

    case "targetingDone":
      if (step === "okTarget" || step === "pay") step = "pay";
      break;

    case "offTable":
      if (m.phase === "aimed") {
        got.aimed = true;
        effectText = m.effect || "";
        log(`  aimed   ${m.line}`);
      } else if (m.phase === "resolved") {
        got.resolved = true;
        got.tellThem = Boolean(m.tellThem);
        log(`  RESOLVED${m.fizzled ? " (fizzled)" : ""}`);
        // m.line, not m.effect: Forge's own sentence has a hole where the
        // phantom should be, because it renders targets by narrowing them to
        // Cards and Players.
        if (m.tellThem) log(`  >>> TELL THEM: ${m.line}`);
        nextCast();
      }
      break;

    case "rejected":
      log("  rejected — the engine refused that");
      break;

    case "buttons": {
      const who = m.player ? { player: m.player } : {};
      if (!seat && m.player) seat = m.player;
      const mine = m.player === seat;

      // Confirm the targets. One press, then on to paying.
      if (mine && step === "okTarget" && m.okEnabled !== false) {
        log("  confirm targets");
        send({ t: "ok", ...who });
        step = "pay";
        break;
      }

      // Waiting to pay: press nothing. The payment prompt has not arrived yet
      // (buttons come first) and the only enabled button here is Cancel.
      if (step === "pay" && !tappedForMana) break;

      if (step === "pay" && tappedForMana) {
        // Paid. For a phantom the report arrives on its own; for a real player
        // nothing announces it, so watch the life total instead.
        step = aim === "player" ? "settle" : "waiting";
      }

      if (mine && step === "settle") {
        send({ t: "board" });
        send({ t: "ok", ...who });
        break;
      }

      // Any main phase of ours with nothing in flight: go and do the thing.
      if (mine && step === "opening" && /Main phase/i.test(lastPrompt)) {
        step = "land";
        send({ t: "board" });
        break;
      }

      // Mid-sequence: ask for the board rather than passing priority, which
      // would end the phase we need.
      if (mine && (step === "land" || step === "landSent" || step === "bolt")) {
        send({ t: "board" });
        break;
      }

      if (m.okEnabled !== false) send({ t: "ok", ...who });
      else if (m.cancelEnabled !== false) send({ t: "cancel", ...who });
      break;
    }

    default:
      break;
  }
});

setTimeout(() => {
  log("");
  log("=== two casts, end to end ===");
  const rows = [
    ["a Mountain reached the battlefield", got.land],
    ["tapping Bolt made the engine ask for a target", got.cast],
    ["the engine accepted something off-table as that target", got.aimed],
    ["that spell finished resolving", got.resolved],
    ["and the engine said the table has to be told", got.tellThem],
    ["a second Bolt was aimed at the opponent as a real player", got.playerCast],
    [`and the engine took ${DAMAGE} life off them itself`, got.lifeDropped],
  ];
  for (const [what, ok] of rows) log(`  ${ok ? "PASS" : "FAIL"}  ${what}`);

  if (noLegalTargets) {
    log("  note: the engine had 0 legal card targets for the first Bolt —");
    log("        stock Forge would have refused that cast outright");
  }
  if (lifeBefore != null) log(`  life: ${opponent} ${lifeBefore} -> ${lifeAfter ?? "?"}`);
  if (effectText) {
    log(`  forge's text: ${effectText}`);
    log("  (the gap before the full stop is where the phantom would render)");
  }

  const ok = rows.every(([, v]) => v);
  log(ok
    ? "OK: a phantom and a real opponent, both hit by the same card"
    : "FAIL: the sequence did not complete");
  process.exit(ok ? 0 : 1);
}, budget);
