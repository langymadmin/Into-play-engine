// Watch the wire. Answer nothing unless told to.
//
//   node bridge/probe-client.mjs ws://localhost:8111/play [seconds]
//
// Every other client here drives a scenario, which means each one decides what
// to ignore — and a client that ignores a message is indistinguishable from a
// bridge that never sent it. This one has no opinions: it connects, prints
// everything in the order it arrived, and leaves. It exists because three bugs
// on this project were "the bridge doesn't send X" when the bridge sent X and
// the client dropped it.
//
// Board requests are the one exception to answering nothing, because without a
// board the log says nothing about the position that was seeded.

const url = process.argv[2] || "ws://localhost:8099/play";
const budget = Number(process.argv[3] || 12);

const ws = new WebSocket(url);
const t0 = Date.now();
const at = () => String(Date.now() - t0).padStart(6) + "ms";

let seat = null;
let buttons = null;
// Pre-game — coin toss, then the opening hand — happens before the start hook
// that seeds the position, so a probe that answers nothing never sees the
// scenario at all. It presses OK through setup and then keeps pressing, which
// is enough to watch a position; anything that needs a real decision gets its
// own client. PARK stops at the first `choose` so the question can be read.
const PARK = process.env.PARK === "1";
let parked = false;

function pressOk() {
  if (parked || !buttons || buttons.okEnabled === false) return;
  ws.send(JSON.stringify({ t: "ok", player: buttons.player }));
}

ws.addEventListener("open", () => {
  console.log(`${at()}  open ${url}`);
  // One board read, once, so the dump includes the seeded position.
  setTimeout(() => ws.send(JSON.stringify({ t: "board" })), 400);
});

ws.addEventListener("message", (ev) => {
  let m;
  try { m = JSON.parse(ev.data); } catch { console.log(`${at()}  unparseable: ${ev.data}`); return; }

  // Events are the engine's own log and there are hundreds; keep them one-line.
  if (m.t === "event") {
    console.log(`${at()}  event   ${m.kind || ""} ${(m.text || m.line || "").slice(0, 110)}`);
    return;
  }

  if (m.t === "board") {
    if (!seat) seat = (m.seats || []).map((s) => s.name).find((n) => n !== m.offTable) || null;
    console.log(`${at()}  board   turn ${m.turn} ${m.turnPlayer} step=${m.step || "?"} offTable=${m.offTable}`);
    for (const s of m.seats || []) {
      const z = (name, arr) => `${name} ${Array.isArray(arr) ? arr.length : arr ?? 0}`;
      console.log(`           ${s.name === m.offTable ? "[off]" : "[me ]"} ${String(s.name).padEnd(10)}`
        + ` life ${String(s.life).padEnd(4)} ${z("hand", s.hand)} ${z("play", s.battlefield)}`
        + ` ${z("grave", s.graveyard)} ${z("lib", s.library)} ${z("exile", s.exile)}`);
      for (const c of s.battlefield || []) {
        console.log(`             play   ${c.id} ${c.name}${c.tapped ? " (tapped)" : ""}`);
      }
      for (const c of s.hand || []) console.log(`             hand   ${c.id} ${c.name}`);
      for (const c of s.graveyard || []) console.log(`             grave  ${c.id} ${c.name}`);
    }
    return;
  }

  // Everything else verbatim — the point of this client is not to summarise.
  console.log(`${at()}  ${String(m.t).padEnd(7)} ${JSON.stringify(m)}`);

  if (m.t === "buttons") { buttons = m; return; }

  // On `prompt`, not on `buttons`. updateButtons fires first, so a client that
  // acts on the buttons alone is reading the PREVIOUS prompt's labels — during
  // mana payment that is [Auto][Cancel] with Auto disabled, and "press whatever
  // is enabled" presses Cancel and loses the spell with no error anywhere.
  if (m.t === "prompt") { pressOk(); return; }

  if (m.t === "choose" && !m.revealOnly && PARK) {
    parked = true;
    console.log(`${at()}  PARKED at a choice — not answering, so it can be read above`);
  }
});

ws.addEventListener("error", (e) => console.log(`${at()}  error ${e.message || e}`));
ws.addEventListener("close", () => console.log(`${at()}  closed`));

setTimeout(() => { console.log(`${at()}  budget spent`); ws.close(); process.exit(0); }, budget * 1000);
