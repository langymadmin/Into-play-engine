#!/usr/bin/env node
// Cast Lightning Bolt at something the engine cannot see.
//
// The one card, end to end: play a Mountain, cast Bolt, aim it off the table,
// pay for it, let it resolve, and read back what the room has to be told. This
// is the sequence a person performs in about four seconds and it is the whole
// product in miniature — the engine enforces the rules, the table holds the
// information the engine does not have.
//
// Unlike bridge-client.mjs, which presses OK at everything to prove a game can
// run at all, this one plays deliberately and asserts on the outcome.
//
//   node into-play/bolt-client.mjs [ws://localhost:8099/play] [seconds]

const url = process.argv[2] || "ws://localhost:8099/play";
const budget = Number(process.argv[3] || 40) * 1000;
const DESCRIBED = "their 2/2 on the table";

const log = (...a) => console.log(...a);
const short = (s, n = 64) =>
  s == null ? "" : String(s).length > n ? String(s).slice(0, n - 3) + "..." : String(s);

// What we are trying to establish, in order. Each is set by a message from the
// engine, never by this script deciding it must have worked.
const got = {
  land: false,       // a Mountain reached the battlefield
  cast: false,       // tapping Bolt made the engine ask for a target
  aimed: false,      // the engine accepted a phantom as that target
  resolved: false,   // the spell finished resolving
  tellThem: false,   // ...and the engine said the table has to be told
  // Recorded rather than asserted: whether the engine had any legal card
  // target at all. Zero is the interesting case, because it is the one where
  // stock Forge would simply refuse the cast.
  noLegalTargets: false,
};
let effectText = "";
let seat = null;        // the seat we are playing
let mountainOnField = null;
let step = "opening";   // opening -> land -> bolt -> target -> pay -> done
let lastPrompt = "";
let trace = Number(process.env.TRACE || 0);
let tappedForMana = false;

const ws = new WebSocket(url);
const send = (o) => ws.send(JSON.stringify(o));

ws.addEventListener("open", () => log("connected to", url));
ws.addEventListener("error", (e) => log("socket error:", e.message || e));

ws.addEventListener("message", (ev) => {
  let m;
  try {
    m = JSON.parse(ev.data);
  } catch {
    return;
  }

  if (process.env.TRACE_ALL && m.t !== "event") {
    log(`  << ${JSON.stringify(m).slice(0, 150)}`);
  }
  switch (m.t) {
    case "prompt":
      lastPrompt = m.text || "";
      if (step === "waiting" && trace-- > 0) log(`  . prompt ${short(m.text, 80)}`);
      // Paying for the spell, and it has to be handled here rather than on
      // the buttons message, because updateButtons fires BEFORE
      // showPromptMessage. A client that reads the prompt to decide what the
      // buttons mean is always one message behind, and during payment that
      // costs the whole cast: the buttons are [Auto] [Cancel] with Auto
      // disabled, so "press whichever is enabled" presses Cancel.
      if (/Pay Mana Cost/i.test(lastPrompt) && mountainOnField && !tappedForMana) {
        log(`  pay     tap Mountain (${mountainOnField.id})`);
        send({ t: "card", cardId: mountainOnField.id, player: seat });
        tappedForMana = true;
      }
      break;

    case "board": {
      const me = (m.seats || []).find((s) => s.name === seat) || (m.seats || [])[0];
      if (!me) break;

      const field = me.battlefield || [];
      const hand = me.hand || [];
      mountainOnField = field.find((c) => c.name === "Mountain") || mountainOnField;

      if (step === "land") {
        if (mountainOnField) {
          // Already have one; skip straight to the spell.
          got.land = true;
          step = "bolt";
        } else {
          const mtn = hand.find((c) => c.name === "Mountain");
          if (!mtn) {
            log("  no Mountain in hand — waiting for a draw");
            step = "opening";
            break;
          }
          log(`  play  Mountain (${mtn.id})`);
          send({ t: "card", cardId: mtn.id, player: seat });
          step = "landSent";
          break;
        }
      }

      if (step === "landSent") {
        if (mountainOnField) {
          got.land = true;
          log(`  land  Mountain is on the battlefield (${mountainOnField.id})`);
          step = "bolt";
        } else {
          step = "land"; // the tap did not take; try again next priority
          break;
        }
      }

      if (step === "bolt") {
        const bolt = hand.find((c) => c.name === "Lightning Bolt");
        if (!bolt) {
          log(`  no Bolt in hand (${hand.map((c) => c.name).join(", ") || "empty"}) — waiting for a draw`);
          step = "opening";
          break;
        }
        log(`  cast  Lightning Bolt (${bolt.id})`);
        send({ t: "card", cardId: bolt.id, player: seat });
        step = "target";
      }
      break;
    }

    // The engine is asking to be pointed at something, and has said off-table
    // is allowed. This is the message the fork exists to produce.
    case "targeting":
      if (step !== "target") break;
      got.cast = true;
      got.noLegalTargets = (m.cards || []).length === 0;
      log(`  target  engine wants ${m.min}-${m.max} of ${(m.cards || []).length} card(s)` +
          `${m.offTable ? ", or something off the table" : ""}`);
      log(`          -> off the table: "${DESCRIBED}"`);
      send({ t: "offTable", describe: DESCRIBED, player: seat });
      // Declaring a target does not finish targeting. Forge re-renders the
      // same prompt with OK now enabled, and waits for it — tapping a land to
      // pay before that just gets refused as an illegal target, forever.
      step = "okTarget";
      break;

    case "targetingDone":
      if (step === "okTarget" || step === "pay") {
        log("  targets locked in");
        step = "pay";
      }
      break;

    case "offTable":
      if (m.phase === "aimed") {
        got.aimed = true;
        effectText = m.effect || "";
        log(`  aimed   ${short(m.spell, 24)} -> ${m.target}`);
        log(`          ${short(m.effect, 90)}`);
      } else if (m.phase === "resolved") {
        got.resolved = true;
        got.tellThem = Boolean(m.tellThem);
        log(`  RESOLVED${m.fizzled ? " (fizzled)" : ""}`);
        // m.line, not m.effect. Forge's own sentence has a hole where the
        // phantom should be ("deals 3 damage to ."), because it renders
        // targets by narrowing them to Cards and Players. The number belongs
        // to the card's text, which the real client already has.
        if (m.tellThem) log(`  >>> TELL THEM: ${m.line}`);
      }
      break;

    case "rejected":
      log("  rejected — the engine refused that");
      break;

    case "buttons": {
      const who = m.player ? { player: m.player } : {};
      if (!seat && m.player) seat = m.player;
      const mine = m.player === seat;

      // Confirm the targets. One press, then move on.
      if (mine && step === "okTarget" && m.okEnabled !== false) {
        log("  confirm targets");
        send({ t: "ok", ...who });
        step = "pay";
        break;
      }

      // Waiting to pay: press nothing. The payment prompt has not arrived yet
      // (buttons come first), and the only enabled button here is Cancel.
      if (step === "pay" && !tappedForMana) {
        break;
      }
      if (step === "pay" && tappedForMana) {
        // Paid. From here, passing priority is what lets the stack resolve.
        step = "waiting";
      }

      // Any main phase of ours, nothing cast yet: go and do the thing. Any,
      // not just precombat — an opening hand without a Bolt means waiting for
      // a draw, and postcombat is a perfectly good moment to try again.
      if (mine && step === "opening" && /Main phase/i.test(lastPrompt)) {
        step = "land";
        send({ t: "board" });
        break;
      }

      // Mid-sequence: ask for the board again rather than passing priority,
      // which would end the phase we need.
      if (mine && (step === "land" || step === "landSent" || step === "bolt")) {
        send({ t: "board" });
        break;
      }

      if (step === "waiting" && trace > 0) {
        log(`  . buttons ${m.player} [${m.ok}] [${m.cancel}] ok=${m.okEnabled} cancel=${m.cancelEnabled}`);
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
  log("=== the one card, end to end ===");
  const rows = [
    ["a Mountain reached the battlefield", got.land],
    ["tapping Bolt made the engine ask for a target", got.cast],
    ["the engine accepted something off-table as that target", got.aimed],
    ["the spell finished resolving", got.resolved],
    ["and the engine said the table has to be told", got.tellThem],
  ];
  for (const [what, ok] of rows) log(`  ${ok ? "PASS" : "FAIL"}  ${what}`);
  if (effectText) {
    log(`  forge's text: ${effectText}`);
    log("  (the gap before the full stop is where the phantom would render —");
    log("   Forge narrows targets to Cards and Players, so it renders nothing)");
  }
  const ok = rows.every(([, v]) => v);
  log(ok
    ? "OK: Bolt was cast at a card the engine cannot see, resolved, and said so"
    : "FAIL: the sequence did not complete");
  process.exit(ok ? 0 : 1);
}, budget);
