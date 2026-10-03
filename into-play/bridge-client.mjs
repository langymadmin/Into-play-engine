#!/usr/bin/env node
// A client for the bridge, standing in for Into Play's UI.
//
// It answers every question with the first legal option and presses OK on every
// button prompt, then prints what came back. That is not how a person plays —
// it is how you find out whether the wire actually carries a game, which is the
// only question this is here to settle.
//
//   node into-play/bridge-client.mjs [ws://localhost:8099/play] [seconds]

const url = process.argv[2] || "ws://localhost:8099/play";
const budget = Number(process.argv[3] || 25) * 1000;

const seen = new Map(); // message type -> count
let answered = 0;
let tapped = 0;
let lastPrompt = "";
let board = null;
let turns = 0;

let pendingTapFor = null; // the seat a board snapshot is being fetched for

const ws = new WebSocket(url);

const log = (...a) => console.log(...a);
const short = (s, n = 70) =>
  s == null ? "" : String(s).length > n ? String(s).slice(0, n - 3) + "..." : String(s);

ws.addEventListener("open", () => log("connected to", url));

ws.addEventListener("message", (ev) => {
  let m;
  try {
    m = JSON.parse(ev.data);
  } catch {
    return log("  unparseable:", short(ev.data));
  }
  seen.set(m.t, (seen.get(m.t) || 0) + 1);

  switch (m.t) {
    case "prompt":
      lastPrompt = m.text || "";
      log(`  prompt   ${short(m.player)} :: ${short(m.text)}`);
      break;

    case "buttons": {
      // Some waits are not button waits at all. "Select 1 card(s) to discard"
      // puts OK on screen but the engine is holding out for a tap, so pressing
      // OK is refused forever and the game stops. Ask for the board, pick a
      // card, send it — this is the leg that a prompts-only bridge is missing.
      if (/select .* card/i.test(lastPrompt) && m.player) {
        log(`  buttons  ${short(m.player, 12)} [${short(m.ok, 20)}] -> needs a card, asking for the board`);
        pendingTapFor = m.player;
        ws.send(JSON.stringify({ t: "board" }));
        break;
      }
      log(`  buttons  ${short(m.player, 12)} [${short(m.ok, 24)}] [${short(m.cancel, 24)}]  -> ok`);
      // The engine is blocked on a latch; this releases it. Fire and forget,
      // with no id, which is the whole asymmetry of the protocol. The seat is
      // echoed back so the press lands on the latch that is actually waiting.
      const who = m.player ? { player: m.player } : {};
      if (m.okEnabled !== false) ws.send(JSON.stringify({ t: "ok", ...who }));
      else if (m.cancelEnabled !== false) ws.send(JSON.stringify({ t: "cancel", ...who }));
      break;
    }

    case "choose": {
      const n = (m.options || []).length;
      log(`  choose#${m.id} ${short(m.message, 40)} (${n} options, ${m.min}-${m.max}) -> ${
        m.revealOnly ? "reveal, no answer" : "[0]"
      }`);
      if (m.revealOnly) break;
      const picked = m.min > 0 ? [0] : [];
      ws.send(JSON.stringify({ t: "choose", id: m.id, picked }));
      answered++;
      break;
    }

    case "confirm":
      log(`  confirm#${m.id} ${short(m.text, 50)} -> ${m.defaultYes ? "yes" : "yes"}`);
      ws.send(JSON.stringify({ t: "choose", id: m.id, picked: [1] }));
      answered++;
      break;

    case "options":
    case "order":
    case "manipulate": {
      const idx = (m.options || []).map((_, i) => i);
      log(`  ${m.t}#${m.id} ${short(m.title || m.text, 40)} (${idx.length}) -> in order`);
      ws.send(JSON.stringify({ t: "choose", id: m.id, picked: m.t === "options" ? [0] : idx }));
      answered++;
      break;
    }

    case "input":
      log(`  input#${m.id} ${short(m.text, 40)} -> "${m.initial ?? ""}"`);
      ws.send(JSON.stringify({ t: "choose", id: m.id, picked: [String(m.initial ?? "0")] }));
      answered++;
      break;

    case "event":
      log(`  event    ${m.kind}: ${short(m.text, 60)}`);
      break;

    case "turn":
      turns++;
      log(`  turn     ${short(m.player)}`);
      break;

    case "board": {
      board = m;
      const line = (m.seats || [])
        .map((s) => `${s.name} ${s.life} (hand ${(s.hand || []).length}, bf ${(s.battlefield || []).length})`)
        .join(" | ");
      log(`  board    turn ${m.turn} ${short(m.turnPlayer, 12)} :: ${line}`);
      if (pendingTapFor) {
        const seat = (m.seats || []).find((s) => s.name === pendingTapFor);
        const card = seat && (seat.hand || [])[0];
        if (card) {
          log(`           -> tap ${card.name} (${card.id})`);
          ws.send(JSON.stringify({ t: "card", cardId: card.id, player: pendingTapFor }));
          tapped++;
        }
        pendingTapFor = null;
      }
      break;
    }

    case "gameOver":
    case "ended":
      log(`  ${m.t}`);
      break;

    default:
      log(`  ${m.t}       ${short(JSON.stringify(m), 80)}`);
  }
});

ws.addEventListener("error", (e) => log("socket error:", e.message || e));
ws.addEventListener("close", () => log("closed"));

setTimeout(() => {
  log("");
  log("=== traffic ===");
  for (const [t, n] of [...seen].sort((a, b) => b[1] - a[1])) log(`  ${String(n).padStart(4)}  ${t}`);
  log(`  answered ${answered} question(s), tapped ${tapped} card(s), saw ${turns} turn change(s)`);
  log(`  last prompt: ${short(lastPrompt, 90) || "(none)"}`);
  if (board) {
    for (const s of board.seats || []) {
      log(`  ${s.name.padEnd(12)} life ${String(s.life).padEnd(4)} hand ${String((s.hand || []).length).padEnd(3)} library ${String((s.library || []).length).padEnd(3)} battlefield ${(s.battlefield || []).length}`);
    }
  }
  // What is actually being tested, and the weak version of this was worth
  // discarding: counting turn changes proves nothing, because "turn" fires on
  // every priority pass and so passes its own threshold long before the first
  // wait that could stall. Reaching the end of a game cannot be faked —
  // getting there needs buttons answered, cards tapped, and no deadlock.
  const prompted = Boolean(seen.get("buttons") || seen.get("choose"));
  const ok = prompted && tapped > 0 && Boolean(seen.get("gameOver"));
  log(ok
    ? "OK: the engine played a game to the end over the socket"
    : `FAIL: ${
        !prompted ? "no prompts crossed the wire"
        : tapped === 0 ? "no card selection ever reached the engine"
        : "the game never finished — something is waiting on something"
      }`);
  process.exit(ok ? 0 : 1);
}, budget);
