// Agatha's Soul Cauldron: an activated ability, and then the thing no
// hand-written menu could ever produce.
//
//   node bridge/cauldron-client.mjs ws://localhost:8140/play [seconds]
//
//   {T}: Exile target card from a graveyard. When a creature card is exiled
//        this way, put a +1/+1 counter on target creature you control.
//   (static) Creatures you control with +1/+1 counters on them have all
//        activated abilities of all creature cards exiled with this.
//
// Three things are being measured, in increasing order of how much they matter.
//
// 1. Does an activated ability reach the UI as a LIST? getAbilityToPlay hands
//    the gui a card plus its legal SpellAbilityViews. That list is what the
//    ring menu should be drawn from in UI 3.0 instead of a hand-written array —
//    so the question is whether it arrives, and what the labels look like.
//
// 2. Can a target be picked that is not on the battlefield? ValidTgts$ Card
//    with Origin$ Graveyard means the arrow has to be able to land inside a
//    pile. Then a SECOND targeting round for the counter, which only happens
//    if the first target turned out to be a creature.
//
// 3. THE ONE THAT MATTERS. After the ability resolves, Llanowar Elves is in
//    exile under the Cauldron and the Bears have a +1/+1 counter — so the Bears
//    now have "{T}: Add {G}". If the engine offers that ability when we ask the
//    Bears what they can do, then Into Play has drawn a menu item for a card it
//    has never heard of, sitting in a zone it is not looking at, granted by a
//    static on a third card. That is the whole argument for UI 3.0, and it is
//    unreachable by any amount of menu code.

const url = process.argv[2] || "ws://localhost:8140/play";
const budget = Number(process.argv[3] || 30);

const ws = new WebSocket(url);
const t0 = Date.now();
const at = () => String(Date.now() - t0).padStart(6) + "ms";
const log = (s) => console.log(`${at()}  ${s}`);

let seat = null;
let offTable = null;
let buttons = null;
let board = null;
let step = "setup";   // setup -> activate -> pickTarget -> pickCreature -> askBears -> done
const abilityLists = [];
const selectRounds = [];
const got = {};
const tried = new Set();

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

    case "buttons": buttons = m; break;

    case "prompt": {
      const text = String(m.text || "").replace(/\n/g, " / ");
      log(`prompt  [${m.player}] ${text.slice(0, 100)}`);

      // Ignored. The yield belongs to whichever seat the `yield` message names,
      // and the prose here is labelled with a stale seat — guessing from it is
      // what deadlocked two earlier runs.
      if (/Yielding until end of turn/.test(text)) break;

      if (/^Priority:/.test(text)) {
        if (step === "setup" && m.player === seat && /Main phase, precombat/.test(text)) {
          step = "activate";
          send({ t: "board" });
          break;
        }
        // Keep passing priority — the ImmediateTrigger that puts the counter on
        // is itself an object on the stack and will not resolve otherwise —
        // while checking after each pass whether the counter has landed.
        if (buttons && buttons.okEnabled !== false) send({ t: "ok", player: m.player });
        if (step === "askBears" && m.player === seat) send({ t: "board" });
        break;
      }

      // Pre-game: the coin toss and both opening hands, none of which are
      // "Priority:" prompts. Answered on `prompt` rather than on `buttons`,
      // because updateButtons fires first and a client reading the buttons
      // alone is one message behind.
      if (step === "setup" && buttons && buttons.okEnabled !== false) {
        send({ t: "ok", player: buttons.player });
        break;
      }

      if (m.player === seat && (step === "pay" || step === "activate")) {
        send({ t: "board" });
      }
      break;
    }

    case "board": {
      board = m;
      const me = (m.seats || []).find((s) => s.name === seat);
      if (!me) break;

      if (step === "activate") {
        const cauldron = (me.battlefield || []).find((c) => /Cauldron/.test(c.name));
        if (!cauldron) { log(`no Cauldron: ${(me.battlefield || []).map((c) => c.name)}`); break; }
        log(`activate Cauldron (${cauldron.id}); graveyard = `
          + `${(me.graveyard || []).map((c) => `${c.name}(${c.id})`).join(", ")}`);
        got.activated = true;
        step = "pickTarget";
        send({ t: "card", cardId: cauldron.id, player: seat });
        break;
      }

      if (step === "askBears") {
        const bears = (me.battlefield || []).find((c) => /Grizzly/.test(c.name));
        if (!bears) break;
        // Wait until the counter has actually landed, otherwise we ask before
        // the static has anything to grant and get a true but useless answer.
        const ctr = bears.counters && bears.counters.P1P1;
        if (!ctr) { log(`wait    Bears have no +1/+1 counter yet`); break; }
        got.counter = true;
        log(`ask     ${bears.name} (${bears.id}) is ${bears.power}/${bears.toughness}`
          + ` with ${ctr} +1/+1 counter(s) — what can it do?`);
        log(`        exiled with the Cauldron: `
          + `${(me.exile || []).map((c) => c.name).join(", ") || "(empty)"}`);
        step = "done";
        send({ t: "card", cardId: bears.id, player: seat });
      }
      break;
    }

    // Both "pick a target" and "pick cards from a zone" arrive here — see the
    // long note in intuition-client.mjs. Which one it is has to be inferred.
    case "targeting": {
      const cards = m.cards || [];
      selectRounds.push({ min: m.min, max: m.max, cards: cards.map((c) => `${c.name}(${c.id})`) });
      log(`SELECT  round ${selectRounds.length}: min=${m.min} max=${m.max}, ${cards.length} offered`);
      for (const c of cards) log(`          ${c.id} ${c.name}`);
      if (!cards.length) break;

      // Round 1 is the graveyard card to exile; prefer a creature so the
      // conditional trigger actually fires. Round 2 is the creature to put the
      // counter on.
      const creature = cards.find((c) => /Llanowar|Grizzly/.test(c.name));
      const pick = creature || cards[0];
      if (tried.has(pick.id)) break;
      tried.add(pick.id);
      log(`        -> ${pick.name} (${pick.id})`);
      if (selectRounds.length === 1) got.targetedInGraveyard = true;
      else got.secondRound = true;
      send({ t: "card", cardId: pick.id, player: seat });
      setTimeout(() => send({ t: "ok", player: seat }), 120);
      // One round is enough to move on. The counter's own target round never
      // arrives: there is exactly one creature we control, and the engine
      // auto-targets rather than asking — the same shortcut Intuition took
      // with its single opponent. So waiting for a second round waits forever.
      step = "askBears";
      break;
    }

    case "choose": {
      abilityLists.push({ message: m.message, min: m.min, max: m.max,
                          options: (m.options || []).map((o) => o.label) });
      log(`CHOOSE  player=${m.player ?? "(none sent)"} min=${m.min} max=${m.max} "${m.message}"`);
      for (const o of m.options || []) log(`          [${o.i}] ${o.label}`);
      if (m.revealOnly) break;
      got.abilityList = true;
      // THE ONE THAT MATTERS. If this list, asked of Grizzly Bears, contains a
      // mana ability, it came from Llanowar Elves — a card in exile that Into
      // Play has never been told about, granted by a static on a third card.
      if (step === "done" && (m.options || []).some((o) => /Add|\{G\}|mana/i.test(o.label))) {
        got.grantedAbility = true;
        console.log(`${at()}  *** the Bears are offering an ability that belongs to a card in exile`);
      }
      // Prefer the Cauldron's own exile ability if this is the ability list.
      let idx = 0;
      const exileIdx = (m.options || []).findIndex((o) => /[Ee]xile/.test(o.label));
      if (exileIdx >= 0) idx = exileIdx;
      log(`        answering [${idx}]`);
      send({ t: "choose", id: m.id, picked: [idx], player: m.player ?? seat });
      break;
    }

    // The bridge naming the seat that is actually auto-passing, because Forge's
    // own prompt does not. Only noted — the off-table seat's yield is wanted.
    case "yield":
      log(`yield   ${m.player} is auto-passing${m.offTable ? " (off-table seat)" : ""}`);
      break;

    // What a card can do, straight from the engine. This is the ring's data.
    case "abilities": {
      abilityLists.push({ message: `${m.card} (${m.cardId})`, min: 1, max: 1,
                          options: (m.options || []).map((o) => o.label) });
      log(`ABILITIES ${m.card} (${m.cardId})${m.asked ? "" : " [not asked — one option]"}`);
      for (const o of m.options || []) log(`          [${o.i}] ${o.label}`);
      if (step === "done" && (m.options || []).some((o) => /Add \{|mana/i.test(o.label))) {
        got.grantedAbility = true;
        console.log(`${at()}  *** the Bears are offering an ability that belongs to a card in exile`);
      }
      break;
    }

    case "targetingDone": break;

    // Counter and trigger events only. Printed because "the Bears have no
    // counter" has two very different causes — the engine did not put one on,
    // or it did and the board serialiser is not reporting it — and the event
    // stream is the engine's own account, independent of my projection of it.
    default:
      if (m.t === "event") {
        const s = `${m.kind || ""} ${m.text || m.line || ""}`;
        // "Add {G}" is the whole point: Grizzly Bears has no mana ability of
        // its own. If the engine reports the Bears activating one, it came
        // from Llanowar Elves in exile, and Into Play was never told that
        // Llanowar Elves exists.
        //
        // Looked for as an EVENT rather than as a choice list, because
        // BridgeGui.getAbilityToPlay returns immediately when a card has
        // exactly one legal ability instead of asking. That shortcut is right
        // for desktop Forge and wrong for UI 3.0 — the ring has to show the
        // player that the Bears can make mana, not silently do it — so it is
        // on the list of things to change.
        if (/Add \{|[Cc]ounter|[Tt]rigger|SpellResolved|Exile/.test(s)) {
          log(`event   ${s.slice(0, 120)}`);
        }
        if (/Grizzly Bears/.test(s) && /Add \{|mana/i.test(s)) {
          got.grantedAbility = true;
          console.log(`${at()}  *** the Bears used an ability that belongs to a card in exile`);
        }
      }
      break;
  }
});

setTimeout(() => {
  send({ t: "board" });
  setTimeout(() => {
    const me = (board?.seats || []).find((s) => s.name === seat);
    console.log("\n=== the board afterwards ===");
    if (me) {
      console.log(`  battlefield ${(me.battlefield || []).map((c) => c.name).join(", ") || "(empty)"}`);
      console.log(`  graveyard   ${(me.graveyard || []).map((c) => c.name).join(", ") || "(empty)"}`);
      console.log(`  exile       ${(me.exile || []).map((c) => c.name).join(", ") || "(empty)"}`);
    }
    console.log("\n=== every list of options the engine offered ===");
    if (!abilityLists.length) console.log("  (none — see the note on single-ability shortcutting)");
    for (const a of abilityLists) {
      console.log(`  "${a.message}" ${a.min}-${a.max}: ${a.options.join(" | ")}`);
    }
    console.log("\n=== every selectable round ===");
    for (const r of selectRounds) {
      console.log(`  min=${r.min} max=${r.max}: ${r.cards.join(", ") || "(none — a player target)"}`);
    }
    const checks = [
      ["the Cauldron's activated ability could be started", !!got.activated],
      ["a card in a GRAVEYARD was offered as a target", !!got.targetedInGraveyard],
      ["the trigger put a +1/+1 counter on the Bears", !!got.counter],
      // OPEN. Tapping the Bears produced no `abilities` message at all, so the
      // engine offered them nothing — which is either the static not granting
      // (an ExiledWith tracking question in the card script) or selectCard not
      // reaching getAbilityToPlay for a creature with only a mana ability.
      // Distinguishing those two is a rules/scripting question, not a UI one,
      // and it is the one thing in this scenario still unanswered.
      ["asking the Bears offered an ability from EXILE", !!got.grantedAbility],
    ];
    console.log("\n=== checks ===");
    let ok = true;
    for (const [what, pass] of checks) { if (!pass) ok = false; console.log(`  ${pass ? "PASS" : "FAIL"}  ${what}`); }
    process.exit(ok ? 0 : 1);
  }, 700);
}, budget * 1000);
