// Cast Intuition, and watch who the engine asks.
//
//   node bridge/intuition-client.mjs ws://localhost:8130/play [seconds]
//
// Intuition: "Search your library for three cards and reveal them. Target
// opponent chooses one. Put that card into your hand and the rest into your
// graveyard."
//
// The interesting word is in the script, not the card text: Chooser$ Targeted.
// The second choice is not ours. Everything the bridge has proven so far has
// been the engine asking US something; this is the engine asking the seat
// across the table, which at a kitchen table is answered by a human reaching
// over to the tablet — or saying it out loud while we tap for them. Either way
// the question has to appear on this screen, labelled as not ours.
//
// So what this client is really measuring is whether the wire carries WHO is
// being asked. If it does not, the priority band cannot say it, and a player
// will answer their opponent's question thinking it was theirs.
//
// The position comes from bridge/states/intuition.txt rather than from playing
// to it: three untapped Islands, Intuition in hand, five known cards in the
// library.

const url = process.argv[2] || "ws://localhost:8130/play";
const budget = Number(process.argv[3] || 30);

const ws = new WebSocket(url);
const t0 = Date.now();
const at = () => String(Date.now() - t0).padStart(6) + "ms";
const log = (s) => console.log(`${at()}  ${s}`);

let seat = null;
let offTable = null;
let buttons = null;
let board = null;
let step = "setup";       // setup -> cast -> target -> pay -> watch
let askedSeats = [];      // every `choose` and who it was attributed to
const tapSent = new Set(); // lands we have already sent a tap for
let searched = false;      // the library search has been answered once
const got = {};

const send = (o) => ws.send(JSON.stringify(o));

ws.addEventListener("open", () => log(`open ${url}`));
ws.addEventListener("error", (e) => log(`error ${e.message || e}`));
ws.addEventListener("close", () => log("closed"));

ws.addEventListener("message", (ev) => {
  let m;
  try { m = JSON.parse(ev.data); } catch { return; }

  switch (m.t) {
    case "open":
      offTable = m.offTable;
      seat = (m.seats || []).find((n) => n !== m.offTable) || null;
      log(`seats   me=${seat} offTable=${offTable}`);
      break;

    case "buttons":
      buttons = m;
      break;

    case "prompt": {
      const text = String(m.text || "").replace(/\n/g, " / ");
      log(`prompt  [${m.player}] ${text.slice(0, 96)}`);

      // Ours and in a main phase with nothing in flight: go and cast.
      if (step === "setup" && m.player === seat && /Main phase/i.test(text)) {
        step = "cast";
        send({ t: "board" });
        break;
      }

      // Everything in setup (coin toss, both mulligans) is a plain OK. Acting
      // on `prompt` and not on `buttons`, because updateButtons fires FIRST and
      // a client that reads the buttons alone is one message behind — during
      // mana payment that means pressing Cancel and losing the spell silently.
      if (step === "setup" && buttons && buttons.okEnabled !== false) {
        send({ t: "ok", player: buttons.player });
        break;
      }

      // After the cast the engine goes straight to paying, with no targeting
      // message at all — see the note at the bottom of this file. So any prompt
      // for our seat once the spell is on the way means "still want something",
      // and the board tells us what we can tap.
      // A "Priority:" prompt means the engine is back at the top of the loop,
      // not asking for anything in particular — so the spell is paid for and
      // sitting on the stack, and what it wants is for priority to pass. A
      // payment prompt is the card's own stack description instead, which is
      // the only thing distinguishing the two. (See the note at the bottom:
      // that distinction ought to be a field, not a regex.)
      // "Yielding until end of turn" is the off-table seat's auto-pass being
      // reported — but reported against the WRONG SEAT. AbstractGuiGame's
      // updateAutoPassPrompt calls showPromptMessage(getCurrentPlayer(), ...),
      // and getCurrentPlayer() is only refreshed by InputProxy from the current
      // input's owner. Anything not driven by an Input therefore arrives
      // labelled with whoever happened to be there last, which is why this
      // shows up as Into Play yielding when it is Phantom that yielded.
      //
      // That is the answer to the question this client was written to ask: the
      // wire does NOT reliably carry who is being asked, and the priority band
      // cannot be built on the prompt's `player` field alone.
      //
      // Cancelling it here, because the message says so and because otherwise
      // the client sits waiting for a prompt that has already been shown.
      if (/Yielding until end of turn/.test(text)) {
        if (buttons && buttons.cancelEnabled !== false) {
          send({ t: "cancel", player: buttons.player });
        }
        break;
      }

      // Priority is passed for EITHER seat, including the off-table one.
      // That is not a shortcut: there is one tablet on the table, so when the
      // engine offers the other side a window to respond, the person holding
      // the tablet is the one who says "no response" on their behalf. It is
      // also a finding — the off-table seat really does get priority here, and
      // UI 3.0 has to show whose window is open, or a player will pass their
      // opponent's responses without noticing.
      if (/^Priority:/.test(text)) {
        if (step === "pay") { log("paid    — passing priority so it resolves"); step = "watch"; }
        if (buttons && buttons.okEnabled !== false) send({ t: "ok", player: m.player });
        break;
      }

      if ((step === "target" || step === "pay") && m.player === seat) {
        step = "pay";
        send({ t: "board" });
        break;
      }

      if (step === "watch" && buttons && buttons.okEnabled !== false && m.player === seat) {
        send({ t: "ok", player: seat });
      }
      break;
    }

    case "board": {
      board = m;
      const me = (m.seats || []).find((s) => s.name === seat);
      if (!me) break;
      if (step === "cast") {
        const card = (me.hand || []).find((c) => c.name === "Intuition");
        if (!card) { log(`no Intuition in hand: ${(me.hand || []).map((c) => c.name)}`); break; }
        log(`cast    Intuition (${card.id}) with ${(me.battlefield || []).filter((c) => !c.tapped).length} untapped`);
        got.cast = true;
        send({ t: "card", cardId: card.id, player: seat });
        step = "target";
      } else if (step === "pay") {
        // Pay by tapping an untapped Island. The engine asks again for each
        // remaining pip, so one tap per board read is enough — but only once
        // per card: the board snapshot can still show a land as untapped when
        // a tap for it is already in flight, and tapping twice makes the engine
        // refuse the second and look like it refused the first.
        const land = (me.battlefield || [])
          .find((c) => !c.tapped && c.name === "Island" && !tapSent.has(c.id));
        if (land) {
          tapSent.add(land.id);
          log(`tap     Island ${land.id}`);
          send({ t: "card", cardId: land.id, player: seat });
        }
      }
      break;
    }

    case "targeting": {
      const cards = m.cards || [];
      log(`SELECT  min=${m.min} max=${m.max}; ${cards.length} selectable card(s)`);
      for (const c of cards) log(`          ${c.id} ${c.name}`);

      // THE FINDING. This one message means two different things.
      //
      // Forge has a single mechanism — setSelectables — behind both "point this
      // spell at something" and "pick three cards out of your library". Casting
      // Intuition produced NO targeting message at all (one opponent, so the
      // engine auto-targeted and went straight to paying), and then RESOLVING
      // it produced this: min 3, max 3, every card in the library.
      //
      // So the arrow and the ask ring are the same call underneath, and UI 3.0
      // has to choose the presentation from the shape of the request rather
      // than from the kind of message:
      //
      //   few cards, all on the battlefield, min 1  -> an arrow to a permanent
      //   many cards, all in one hidden zone, min n -> a ring/sheet of n cards
      //
      // Getting that wrong means asking a player to draw an arrow at their own
      // library fifty-three times.
      if (cards.length === 0) {
        log(`        no cards offered — the target must be a player`);
        if (step === "target") {
          log(`        -> ${offTable}, as a real player`);
          got.targetedPlayer = true;
          send({ t: "seat", name: offTable, player: seat });
          step = "pay";
        }
        break;
      }

      if (searched) break;
      searched = true;
      got.searched = true;
      const want = Math.max(m.min ?? 1, 1);
      const picks = cards.slice(0, want);
      log(`        picking ${picks.map((c) => c.name).join(", ")}`);
      for (const c of picks) send({ t: "card", cardId: c.id, player: seat });
      // Selecting does not finish the input: Forge re-renders with OK enabled
      // and waits for the press. Same shape as declaring spell targets.
      setTimeout(() => send({ t: "ok", player: seat }), 120);
      step = "watch";
      break;
    }

    case "choose": {
      // The whole point of this client. Log it completely, including who the
      // bridge says is being asked.
      askedSeats.push({ player: m.player ?? null, message: m.message, min: m.min, max: m.max,
                        n: (m.options || []).length, revealOnly: !!m.revealOnly });
      log(`CHOOSE  player=${m.player ?? "(none sent)"} min=${m.min} max=${m.max}`
        + ` revealOnly=${!!m.revealOnly}`);
      log(`        "${m.message}"`);
      for (const o of m.options || []) log(`          [${o.i}] ${o.label}`);

      if (m.revealOnly) break;

      // Pick the first `min` options, whatever the question is. A smarter
      // choice would be a judgement about Magic; this is a protocol test.
      const want = Math.max(m.min ?? 1, 1);
      const picked = (m.options || []).slice(0, want).map((o) => o.i);
      log(`        answering with ${JSON.stringify(picked)}`);
      send({ t: "choose", id: m.id, picked, player: m.player ?? seat });
      if ((m.options || []).length >= 3 && want >= 3) got.searched = true;
      else got.opponentChose = true;
      step = "watch";
      break;
    }

    case "targetingDone":
      log("targeting done");
      break;

    default:
      if (m.t !== "event" && m.t !== "cards" && m.t !== "turn" && m.t !== "combat"
          && m.t !== "alert" && m.t !== "phase") {
        log(`${m.t}  ${JSON.stringify(m).slice(0, 200)}`);
      }
  }
});

setTimeout(() => {
  send({ t: "board" });
  setTimeout(() => {
    console.log("\n=== what happened ===");
    const me = (board?.seats || []).find((s) => s.name === seat);
    if (me) {
      console.log(`  hand      ${(me.hand || []).map((c) => c.name).join(", ") || "(empty)"}`);
      console.log(`  graveyard ${(me.graveyard || []).map((c) => c.name).join(", ") || "(empty)"}`);
      console.log(`  library   ${(me.library || []).length ?? me.library ?? "?"}`);
    }
    console.log("\n=== every choice the engine asked, and who it named ===");
    for (const a of askedSeats) {
      console.log(`  player=${String(a.player ?? "(none sent)").padEnd(12)} ${a.min}-${a.max} of ${a.n}`
        + `${a.revealOnly ? " (reveal)" : ""}  "${a.message}"`);
    }
    const checks = [
      ["Intuition was cast from the seeded position", !!got.cast],
      ["the engine asked for a target and took a player", !!got.targetedPlayer],
      ["the library search was asked as a choice", !!got.searched],
      ["a second choice arrived (the opponent's)", askedSeats.filter((a) => !a.revealOnly).length >= 2],
    ];
    console.log("\n=== checks ===");
    let ok = true;
    for (const [what, pass] of checks) {
      if (!pass) ok = false;
      console.log(`  ${pass ? "PASS" : "FAIL"}  ${what}`);
    }
    process.exit(ok ? 0 : 1);
  }, 700);
}, budget * 1000);
