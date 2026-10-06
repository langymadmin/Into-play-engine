package forge.intoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.gamemodes.match.AbstractGuiGame;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine's side of the wire.
 *
 * <p>Forge talks to a UI through two interfaces, not one, and the direction
 * matters. {@code IGuiGame} is engine to UI and is almost entirely
 * notifications — "the buttons now say Play and Draw", "here is a message",
 * "I am waiting". {@code IGameController} is UI to engine, and that is where
 * decisions actually arrive: selectButtonOk, selectCard, selectAbility.
 *
 * <p>This extends Forge's own {@link AbstractGuiGame}, which already implements
 * the derived prompts — one(), oneOrNone(), reveal(), getInteger() — in terms of
 * getChoices(). So answering getChoices() correctly answers most of the
 * interface, and only genuinely new behaviour is written here.
 *
 * <h2>Two kinds of waiting</h2>
 *
 * <b>Buttons.</b> The engine calls updateButtons() to say what they mean, then
 * awaitInput() with a latch, and blocks. Nothing is returned. The answer arrives
 * later, from the socket thread, by calling the stored {@link IGameController},
 * which releases the latch inside the engine.
 *
 * <p><b>Choices.</b> getChoices() is a blocking call on the engine thread that
 * must return a value. It sends a message carrying a fresh id and parks on a
 * queue until the socket thread hands back the reply with that id.
 *
 * <p>Both depend on the engine thread and the socket thread being genuinely
 * different threads. Forge asserts this — blocking on its input latch from the
 * thread it considers the UI thread throws outright — so the constraint is
 * describing this architecture rather than obstructing it.
 *
 * <h2>Not yet</h2>
 *
 * One seat, one socket, no device routing. The tablet and the phone are the same
 * seat and will share this object, with prompts routed by which device owns the
 * privacy of the information (see docs/UI-3.0.md in the into-play repo). That is
 * a refinement of a working bridge rather than a precondition for one, so it is
 * deliberately absent.
 */
public class BridgeGui extends AbstractGuiGame {

    /** Where messages go. Supplied by the server; null means nobody is listening. */
    public interface Sink {
        void send(String json);
    }

    private final Sink sink;
    private final AtomicInteger nextAskId = new AtomicInteger(1);

    /** Blocking prompts parked by id, waiting for the socket to answer. */
    private final Map<Integer, SynchronousQueue<JsonArray>> pending = new ConcurrentHashMap<>();

    private PlayerView currentPlayer;
    private volatile String focus;

    /**
     * The seat whose cards are on the table rather than in the engine.
     *
     * <p>A real {@code Player} — that is the point, and it is what makes the
     * life counter real — but one whose decisions happen in the room. The
     * engine must never stop and ask it anything, because nobody is holding
     * that screen.
     */
    private volatile String offTableSeat;

    public void setOffTableSeat(final String name) {
        offTableSeat = name;
    }

    /**
     * Which seat the engine is currently asking — set around every call made
     * through the off-table seat's own gui. See {@code BridgeMain.proxyGuiFor}.
     *
     * <p>This is the answer to the question the whole fork turns on. The seat
     * across the table is a real engine {@link forge.game.player.Player}: the
     * engine asks it things, and it can never answer, because the person it
     * stands for is holding cardboard and not a device. So every question aimed
     * at it has to arrive on OUR screen, labelled as theirs, and be answered by
     * the person holding the tablet on their behalf.
     *
     * <p>Forge already allows this without being changed: {@code setGui} is per
     * controller, not per game, so that seat can be handed a gui of its own.
     * What that gui does is set this field, forward to this one, and clear it.
     * The socket stays single, the client gets one extra field, and no engine
     * code has an opinion about any of it.
     *
     * <p>Volatile rather than ThreadLocal on purpose: Forge runs one game thread,
     * and a question asked on it is answered before the next one starts. A
     * ThreadLocal here would be stricter and would also be wrong — the socket
     * thread reads this when composing the reply.
     */
    private volatile String askingFor;

    public void setAskingFor(final String seat) {
        askingFor = seat;
    }

    /** The seat a question belongs to, or null when it is simply ours. */
    private String asking() {
        return askingFor;
    }

    /**
     * Who the last pair of buttons belonged to.
     *
     * <p>With one socket this is redundant, and that was checked rather than
     * assumed: Forge calls setCurrentPlayer immediately before prompting a
     * seat, so routing a press by "current player" works, and a full game still
     * runs with this field ignored.
     *
     * <p>It is here because the redundancy ends at one socket. With the phone
     * and the tablet both pressing, "current player" is whoever the engine spoke
     * to last, which is not the same as who a given press came from. The buttons
     * already carry their owner, so following it costs nothing now and is the
     * only thing that works later.
     */
    private PlayerView buttonOwner;

    public BridgeGui(final Sink sink0) {
        sink = sink0;
    }

    // ---------------------------------------------------------------------
    // Outbound
    // ---------------------------------------------------------------------

    private void send(final JsonObject o) {
        String s = o.toString();
        // What a screen joining mid-game needs to catch up: where the game is
        // and what it is waiting for, per seat. The board it asks for itself.
        String t = o.has("t") ? o.get("t").getAsString() : "";
        if ("phase".equals(t) || "turn".equals(t)) {
            latest.put(t, s);
        } else if ("prompt".equals(t) || "buttons".equals(t)) {
            String who = o.has("player") && !o.get("player").isJsonNull() ? o.get("player").getAsString() : "";
            latest.put(t + ":" + who, s);
        }
        if (sink != null) {
            sink.send(s);
        }
    }

    /** The last phase, turn, and each seat's prompt and buttons — see {@link #catchUp}. */
    private final java.util.Map<String, String> latest = new java.util.concurrent.ConcurrentHashMap<>();

    /** Messages that bring a screen joining mid-game up to date, oldest kind first. */
    public java.util.List<String> catchUp() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (lastOpen != null) {
            out.add(lastOpen);
        }
        for (String k : java.util.List.of("turn", "phase")) {
            if (latest.containsKey(k)) {
                out.add(latest.get(k));
            }
        }
        latest.forEach((k, v) -> { if (k.startsWith("buttons:")) out.add(v); });
        latest.forEach((k, v) -> { if (k.startsWith("prompt:")) out.add(v); });
        return out;
    }

    /**
     * What became of the deck the client sent: how many cards made it into
     * the library, and every name Forge could not find. Sent before the game
     * starts, so a typo is reported where the player can fix it rather than
     * discovered as a missing card on turn six.
     */
    public void sendDeckReport(final String deckName, final int cards, final java.util.List<String> missing) {
        JsonObject o = msg("deck");
        o.addProperty("name", deckName);
        o.addProperty("cards", cards);
        JsonArray m = new JsonArray();
        for (String s : missing) {
            m.add(s);
        }
        o.add("missing", m);
        send(o);
    }

    /**
     * "Did they block?" — asked when blockers are declared against the
     * off-table seat. One entry per attacker; the answer is a list of
     * {@code {"attacker":id,"power":p,"toughness":t,"keywords":[…]}}, one per
     * block, and an empty list means nothing blocked. See CardboardBlocks.
     */
    /**
     * "How much gets through?" — one number per attacker, for the player to
     * take down from its full combat damage. Answered [{attacker, n}].
     */
    public JsonArray askThrough(final java.util.List<forge.game.card.Card> attackers) {
        JsonObject o = msg("through");
        JsonArray list = new JsonArray();
        for (forge.game.card.Card a : attackers) {
            JsonObject j = new JsonObject();
            j.addProperty("id", a.getId());
            j.addProperty("name", a.getName());
            j.addProperty("damage", Math.max(0, a.getNetCombatDamage()));
            j.addProperty("trample", a.hasKeyword(forge.game.keyword.Keyword.TRAMPLE));
            list.add(j);
        }
        o.add("attackers", list);
        return ask(o);
    }

    public JsonArray askBlocks(final java.util.List<forge.game.card.Card> attackers) {
        JsonObject o = msg("blocks");
        JsonArray list = new JsonArray();
        for (forge.game.card.Card a : attackers) {
            JsonObject j = new JsonObject();
            j.addProperty("id", a.getId());
            j.addProperty("name", a.getName());
            j.addProperty("power", a.getNetPower());
            j.addProperty("toughness", a.getNetToughness());
            list.add(j);
        }
        o.add("attackers", list);
        return ask(o);
    }

    /**
     * Something the engine worked out that the room has to act on: their
     * creature died, so the real card goes to their graveyard. Not a question,
     * and nothing waits on it.
     */
    public void tell(final java.util.List<String> lines) {
        JsonObject o = msg("tell");
        JsonArray a = new JsonArray();
        for (String s : lines) {
            a.add(s);
        }
        o.add("lines", a);
        send(o);
    }

    /**
     * "Their table" — asked when one of our spells counts the other side
     * (Tarmogoyf, Beast of Burden). {@code needs}: "graveyard", "creatures".
     * Answered [{graveyardTypes:[…], creatures:n}].
     */
    public JsonArray askTable(final String card, final java.util.Set<String> needs, final JsonObject current) {
        JsonObject o = msg("defineTable");
        o.addProperty("card", card);
        JsonArray n = new JsonArray();
        needs.forEach(n::add);
        o.add("needs", n);
        o.add("current", current);
        JsonArray types = new JsonArray();
        TheirSide.TYPES.forEach(types::add);
        o.add("types", types);
        return ask(o);
    }

    /**
     * Two piles (Fact or Fiction, Sauron's Ransom, Gifts Ungiven…): one panel,
     * asked twice. {@code step} "split": the separator moves cards into pile 1;
     * answered with the card ids of pile 1. "choose": answered [1] or [2].
     * {@code faceDown} is Forge's FaceDown$ — "One" hides pile 1 from the
     * chooser, which is the whole of Sauron's Ransom.
     */
    public JsonArray askPiles(final String step, final String seat, final forge.game.spellability.SpellAbility sa,
                              final Iterable<forge.game.card.Card> pile1, final Iterable<forge.game.card.Card> pile2,
                              final String faceDown) {
        JsonObject o = msg("piles");
        o.addProperty("step", step);
        o.addProperty("seat", seat);
        o.addProperty("askingFor", seat);
        o.addProperty("faceDown", faceDown);
        if (sa != null && sa.getHostCard() != null) {
            o.addProperty("card", sa.getHostCard().getName());
            o.addProperty("cardId", sa.getHostCard().getId());
        }
        o.add("pile1", pileJson(pile1));
        o.add("pile2", pileJson(pile2));
        return ask(o);
    }

    private static JsonArray pileJson(final Iterable<forge.game.card.Card> cards) {
        JsonArray a = new JsonArray();
        if (cards != null) {
            for (forge.game.card.Card c : cards) {
                JsonObject j = new JsonObject();
                j.addProperty("id", c.getId());
                j.addProperty("name", c.getName());
                a.add(j);
            }
        }
        return a;
    }

    /** A card's triggered abilities, for the player to fire one by hand. */
    public void sendTriggerList(final int cardId, final String name, final java.util.List<String> labels) {
        JsonObject o = msg("triggerList");
        o.addProperty("cardId", cardId);
        o.addProperty("card", name);
        JsonArray a = new JsonArray();
        for (int i = 0; i < labels.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            j.addProperty("label", labels.get(i));
            a.add(j);
        }
        o.add("options", a);
        send(o);
    }

    /** The game thread died. Nothing more will be asked; the client should say so. */
    public void sendFatal(final String message) {
        JsonObject o = msg("fatal");
        o.addProperty("message", message);
        send(o);
    }

    private static JsonObject msg(final String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    // ---------------------------------------------------------------------
    // Inbound — called from the socket thread, never the engine thread
    // ---------------------------------------------------------------------

    /**
     * Hand a reply to whichever prompt is waiting for it. False means no prompt
     * by that id is open, which is usually a double click or a reply that
     * arrived after the game had moved on.
     */
    public boolean answer(final int askId, final JsonArray picked) {
        SynchronousQueue<JsonArray> q = pending.get(askId);
        return q != null && q.offer(picked);
    }

    /**
     * The controller a button press belongs to.
     *
     * <p>The seat that was last given buttons, falling back to the current
     * player. A client that knows which seat it is pressing for should say so
     * and use {@link #controllerFor(String)} instead — which is what the phone
     * and the tablet will do once they are two sockets rather than one.
     */
    public IGameController controller() {
        PlayerView p = buttonOwner != null ? buttonOwner : currentPlayer;
        return p == null ? null : getGameController(p);
    }

    /**
     * Find a card the client named by id.
     *
     * <p>Walks the game view rather than keeping a registry of every CardView
     * that has crossed the wire: the view is authoritative, a card that has
     * moved zones is still found, and one that has genuinely left the game is
     * correctly not found. The cost is a scan of a tabletop, which is nothing.
     */
    public CardView card(final int id) {
        final forge.game.GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) {
            return null;
        }
        for (PlayerView p : gv.getPlayers()) {
            for (ZoneType z : LOOKUP_ZONES) {
                Iterable<CardView> in = p.getCards(z);
                if (in == null) {
                    continue;
                }
                for (CardView c : in) {
                    if (c.getId() == id) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /** The seat the client named, by name, or null. */
    public PlayerView player(final String name) {
        final forge.game.GameView gv = getGameView();
        if (name == null || gv == null || gv.getPlayers() == null) {
            return null;
        }
        for (PlayerView p : gv.getPlayers()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private static final ZoneType[] LOOKUP_ZONES = {
        ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
        ZoneType.Command, ZoneType.Stack, ZoneType.Library, ZoneType.Sideboard,
    };

    /**
     * The whole table as the engine sees it.
     *
     * <p>Sent on request rather than on every change: Into Play's client already
     * knows how to render a board, and what it has lacked is a board to render
     * that the engine agrees with. Under UI 3.0 this is what Firebase becomes a
     * projection of.
     *
     * <p>Note what is in here that no amount of tapping could produce honestly:
     * each seat's library contents, in order. Which seat may see which part of
     * this is the routing question in docs/UI-3.0.md, and it is not answered
     * here — one socket, one seat, everything visible.
     */
    public void sendBoard() {
        final forge.game.GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) {
            return;
        }
        JsonObject o = msg("board");
        JsonArray seats = new JsonArray();
        for (PlayerView p : gv.getPlayers()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", p.getName());
            s.addProperty("life", p.getLife());
            for (ZoneType z : LOOKUP_ZONES) {
                Iterable<CardView> in = p.getCards(z);
                if (in == null) {
                    continue;
                }
                JsonArray arr = new JsonArray();
                for (CardView c : in) {
                    JsonObject cj = card(c);
                    // What of theirs is held under this card (see noteExileWith).
                    java.util.List<String> under = exiledUnder.get(c.getId());
                    if (under != null && !under.isEmpty()) {
                        JsonArray ua = new JsonArray();
                        under.forEach(ua::add);
                        cj.add("theirsUnder", ua);
                    }
                    arr.add(cj);
                }
                if (arr.size() > 0) {
                    s.add(z.name().toLowerCase(), arr);
                }
            }
            // Commander games: which cards are this seat's commanders, where
            // they are, and how often they have been cast — the tax is the
            // engine's count, not the app's.
            java.util.List<CardView> cmd = p.getCommanders();
            if (cmd != null && !cmd.isEmpty()) {
                JsonArray ca = new JsonArray();
                for (CardView c : cmd) {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", c.getId());
                    j.addProperty("name", c.getName());
                    j.addProperty("cast", p.getCommanderCast(c));
                    j.addProperty("zone", c.getZone() == null ? null : c.getZone().name());
                    ca.add(j);
                }
                s.add("commanders", ca);
            }
            // Commander damage this seat has taken, by commander. Twenty-one
            // from one commander loses the game, and the engine applies that.
            JsonArray dmg = new JsonArray();
            for (PlayerView other : gv.getPlayers()) {
                java.util.List<CardView> theirs = other.getCommanders();
                if (other == p || theirs == null) {
                    continue;
                }
                for (CardView c : theirs) {
                    int n = p.getCommanderDamage(c);
                    if (n > 0) {
                        JsonObject j = new JsonObject();
                        j.addProperty("from", c.getName());
                        j.addProperty("damage", n);
                        dmg.add(j);
                    }
                }
            }
            if (dmg.size() > 0) {
                s.add("commanderDamage", dmg);
            }
            playerState(s, p.getName());
            seats.add(s);
        }
        o.add("seats", seats);
        // The stack, top first. It belongs to the game, not to a seat — a
        // player's own Stack zone is always empty in Forge — so it is sent once
        // here. Each item is a spell or an ability; a triggered ability has a
        // source card but is not that card, which is why "text" is sent too.
        JsonArray stack = new JsonArray();
        if (gv.getStack() != null) {
            for (forge.game.spellability.StackItemView si : gv.getStack()) {
                CardView src = si.getSourceCard();
                JsonObject j = new JsonObject();
                j.addProperty("id", src == null ? 0 : src.getId());
                j.addProperty("name", src == null ? "" : src.getName());
                if (src != null && src.getCurrentState() != null) {
                    j.addProperty("types", src.getCurrentState().getType().toString());
                }
                j.addProperty("text", si.getText());
                j.addProperty("ability", si.isAbility());
                j.addProperty("trigger", si.isTrigger());
                j.addProperty("controller", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
                stack.add(j);
            }
        }
        o.add("stack", stack);
        if (TheirSide.current != null) {
            o.add("table", TheirSide.current.describe());
        }
        forge.game.Game game = BridgeMain.currentGame;
        if (game != null && game.getDayTime() != null) {
            o.addProperty("dayTime", game.isDay() ? "day" : "night");
        }
        o.addProperty("offTable", offTableSeat);
        o.add("opponents", opponentsJson());
        PlayerView turn = gv.getPlayerTurn();
        o.addProperty("turnPlayer", turn == null ? null : turn.getName());
        o.addProperty("turn", gv.getTurn());
        send(o);
    }

    /**
     * What a player has that is not a card: floating mana, poison and other
     * player counters (energy, experience, rad), the monarch, the initiative,
     * the city's blessing. Read from the live game — PlayerView does not carry
     * them — and drawn by the panels the app already has for each.
     */
    private void playerState(final JsonObject s, final String name) {
        forge.game.Game game = BridgeMain.currentGame;
        if (game == null) {
            return;
        }
        forge.game.player.Player p = null;
        for (forge.game.player.Player x : game.getPlayers()) {
            if (name.equals(x.getName())) {
                p = x;
            }
        }
        if (p == null) {
            return;
        }
        forge.game.mana.ManaPool pool = p.getManaPool();
        if (pool != null && pool.totalMana() > 0) {
            JsonObject m = new JsonObject();
            m.addProperty("W", pool.getAmountOfColor(forge.card.MagicColor.WHITE));
            m.addProperty("U", pool.getAmountOfColor(forge.card.MagicColor.BLUE));
            m.addProperty("B", pool.getAmountOfColor(forge.card.MagicColor.BLACK));
            m.addProperty("R", pool.getAmountOfColor(forge.card.MagicColor.RED));
            m.addProperty("G", pool.getAmountOfColor(forge.card.MagicColor.GREEN));
            m.addProperty("C", pool.getAmountOfColor(forge.card.MagicColor.COLORLESS));
            s.add("mana", m);
        }
        com.google.common.collect.Multiset<forge.game.card.CounterType> ctrs = p.getCounters();
        if (ctrs != null && !ctrs.isEmpty()) {
            JsonObject c = new JsonObject();
            for (forge.game.card.CounterType t : ctrs.elementSet()) {
                c.addProperty(t.getName(), ctrs.count(t));
            }
            s.add("playerCounters", c);
        }
        if (game.getMonarch() == p) {
            s.addProperty("monarch", true);
        }
        if (game.getHasInitiative() == p) {
            s.addProperty("initiative", true);
        }
        if (p.hasBlessing()) {
            s.addProperty("blessing", true);
        }
    }

    /**
     * One card, as much of it as the client can use.
     *
     * <p>This used to be id, name and tapped — and that was the wrong kind of
     * mistake. {@link CardView} has always carried power, toughness, counters,
     * attacking, summoning sickness and the rest; writing three fields and then
     * building client logic around the gap meant reimplementing, badly, things
     * the engine already knew. Everything here is a plain read of a CardView
     * accessor. Nothing is computed, because anything computed here would be a
     * second opinion about a game the engine is already running.
     *
     * <p>Zone-dependent on purpose: a card in a hidden zone gets its identity
     * and nothing else, because power and counters are battlefield facts and
     * sending them for a card in a library is both meaningless and a leak.
     */
    private static JsonObject card(final CardView c) {
        JsonObject j = new JsonObject();
        j.addProperty("id", c.getId());
        j.addProperty("name", c.getName());
        j.addProperty("tapped", c.isTapped());
        j.addProperty("faceDown", c.isFaceDown());
        j.addProperty("token", c.isToken());

        final CardView.CardStateView st = c.getCurrentState();
        if (st != null) {
            // The type line as the engine sees it, so a client never has to
            // guess from the name whether something is a land.
            j.addProperty("types", String.valueOf(st.getType()));
            if (st.isCreature()) {
                j.addProperty("power", st.getPower());
                j.addProperty("toughness", st.getToughness());
            }
        }

        if (c.getZone() == forge.game.zone.ZoneType.Battlefield) {
            j.addProperty("attacking", c.isAttacking());
            j.addProperty("blocking", c.isBlocking());
            // isSick() is the one that matters to a player: it already accounts
            // for being a creature, being on the battlefield, and haste.
            // hasSickness() alone is true for cards that could never attack.
            j.addProperty("sick", c.isSick());
            j.addProperty("damage", c.getDamage());
            // Auras and equipment: what they are on, so the board can draw them
            // behind it, as Forge's own PlayArea does.
            if (c.getAttachedTo() != null) {
                j.addProperty("attachedTo", c.getAttachedTo().getId());
            }
        }

        com.google.common.collect.Multiset<forge.game.card.CounterType> ctrs = c.getCounters();
        if (ctrs != null && !ctrs.isEmpty()) {
            JsonObject counters = new JsonObject();
            for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> e
                    : ctrs.entrySet()) {
                counters.addProperty(e.getElement().toString(), e.getCount());
            }
            j.add("counters", counters);
        }
        return j;
    }

    /** The controller for a named seat, or null if this gui does not hold it. */
    public IGameController controllerFor(final String playerName) {
        if (playerName == null) {
            return controller();
        }
        for (PlayerView p : getLocalPlayers()) {
            if (playerName.equals(p.getName())) {
                return getGameController(p);
            }
        }
        return null;
    }

    public PlayerView current() {
        return currentPlayer;
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        currentPlayer = player;
        JsonObject o = msg("turn");
        o.addProperty("player", player == null ? null : player.getName());
        send(o);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        JsonObject o = msg("prompt");
        o.addProperty("player", playerView == null ? null : playerView.getName());
        // The seat the question is FOR, as opposed to `player`, which is
        // getCurrentPlayer() and is stale for anything not driven by an input.
        // When this is set, the band says "they choose — you decide for them".
        o.addProperty("askingFor", asking());
        o.addProperty("text", message);
        if (card != null) {
            o.addProperty("card", card.getName());
            o.addProperty("cardId", card.getId());
        }
        send(o);

        // Now that the engine is parked, it is safe to say what it is waiting
        // to be pointed at.
        JsonObject targeting = pendingTargeting;
        if (targeting != null) {
            pendingTargeting = null;
            addValidTargets(targeting, playerView);
            send(targeting);
        }
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2,
                              final boolean enable1, final boolean enable2, final boolean focus1) {
        buttonOwner = owner;
        JsonObject o = msg("buttons");
        o.addProperty("player", owner == null ? null : owner.getName());
        o.addProperty("ok", label1);
        o.addProperty("cancel", label2);
        o.addProperty("okEnabled", enable1);
        o.addProperty("cancelEnabled", enable2);
        send(o);
    }

    @Override
    public void flashIncorrectAction() {
        send(msg("rejected"));
    }

    @Override
    public void alertUser() {
        send(msg("alert"));
    }

    @Override
    public void message(final String message, final String title) {
        JsonObject o = msg("message");
        o.addProperty("title", title);
        o.addProperty("text", message);
        send(o);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        JsonObject o = msg("error");
        o.addProperty("title", title);
        o.addProperty("text", message);
        send(o);
    }

    @Override
    public void updateCards(final Iterable<CardView> cards) {
        if (cards == null) {
            return;
        }
        JsonArray arr = new JsonArray();
        for (CardView c : cards) {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.getId());
            j.addProperty("name", c.getName());
            arr.add(j);
        }
        if (arr.size() > 0) {
            JsonObject o = msg("cards");
            o.add("cards", arr);
            send(o);
        }
    }

    @Override
    public void updateRevealedCards(final TrackableCollection<CardView> collection) {
        updateCards(collection);
    }

    @Override
    public void showCombat() {
        send(msg("combat"));
    }

    /**
     * The engine is waiting to be pointed at something.
     *
     * <p>This is the message the whole fork exists to make possible. Forge sends
     * the cards it will accept; the bridge adds {@code offTable}, because what
     * the player actually wants to aim at is usually not in that list — it is on
     * the table, in front of someone else, and the engine has never heard of it.
     */
    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        JsonObject o = msg("targeting");
        // Held, not sent. Forge calls this from the InputSelectTargets
        // CONSTRUCTOR, which runs before showAndWait() puts the input on the
        // queue — so a client fast enough to answer the announcement finds
        // nothing listening and the declaration is dropped. It only showed up
        // once the opponent stopped taking turns and the reply got quicker,
        // which is the worst kind of race: latent, and timing-dependent.
        //
        // showPromptMessage is the honest moment. setInput() pushes the input
        // and then calls showMessage(), so by the time a prompt goes out the
        // engine really is parked and ready to be answered.
        o.addProperty("min", min);
        o.addProperty("max", max);
        o.addProperty("offTable", true);
        JsonArray arr = new JsonArray();
        if (cards != null) {
            for (CardView c : cards) {
                JsonObject j = new JsonObject();
                j.addProperty("id", c.getId());
                j.addProperty("name", c.getName());
                arr.add(j);
            }
        }
        o.add("cards", arr);
        pendingTargeting = o;
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        pendingTargeting = null;
        send(msg("targetingDone"));
    }

    /**
     * What the ability accepts, as Forge writes it ("Permanent.nonLand",
     * "Creature"), so the client can narrow their deck list to cards the
     * spell could really be aimed at. Read off the live input, whose spell
     * ability is private — read, not patched, as in BridgeServer's offTable.
     */
    private void addValidTargets(final JsonObject o, final PlayerView playerView) {
        IGameController c = controllerFor(playerView == null ? null : playerView.getName());
        if (!(c instanceof forge.player.PlayerControllerHuman human)) {
            return;
        }
        if (!(human.getInputQueue().getInput() instanceof forge.gamemodes.match.input.InputSelectTargets targeting)) {
            return;
        }
        try {
            java.lang.reflect.Field f = forge.gamemodes.match.input.InputSelectTargets.class.getDeclaredField("sa");
            f.setAccessible(true);
            forge.game.spellability.SpellAbility sa = (forge.game.spellability.SpellAbility) f.get(targeting);
            if (sa == null || sa.getTargetRestrictions() == null) {
                return;
            }
            JsonArray valid = new JsonArray();
            for (String v : sa.getTargetRestrictions().getValidTgts()) {
                valid.add(v);
            }
            o.add("valid", valid);
            o.addProperty("card", sa.getHostCard() == null ? null : sa.getHostCard().getName());
        } catch (ReflectiveOperationException e) {
            System.out.println("could not read the targeting ability: " + e);
        }
    }

    /** A targeting announcement waiting for the input to actually be live. */
    private volatile JsonObject pendingTargeting;

    /**
     * Something has been aimed off the table — but has not happened yet.
     *
     * <p>Two messages, not one, and the split is the point. A spell that is
     * merely aimed can still be countered, and telling the room "3 damage" the
     * moment the arrow is drawn would be a lie often enough to matter. So this
     * is `aimed`, for the arrow; {@link #handleGameEvent} sends `resolved` when
     * the engine actually finishes with it, and that is the one carrying
     * <i>tell them</i>.
     *
     * <p>Keyed by Forge's stack description, which both ends get from the same
     * {@code sa.getStackDescription()} — so the match is an identity check on
     * one string rather than a guess about ordering.
     *
     * <p><b>{@code effect} has a hole in it, deliberately left there.</b> Forge
     * renders a spell's targets by narrowing them to Cards and Players — the
     * same assumption as the damage loop and the target whitelist, a third
     * place — so a phantom renders as nothing and Bolt reads "deals 3 damage
     * to ." Patching that would mean patching it in two hundred effect
     * classes, one per card type, which is not a fork anyone could maintain.
     *
     * <p>So the gapped string is passed through as-is, and {@code line} carries
     * what the room actually needs: the spell and what it was aimed at. The
     * number comes from the card's own text, which the client already has for
     * every card it draws. The engine's job was to let the spell happen
     * legally; the sentence belongs to the table.
     */
    public void offTableAimed(final String spell, final String described, final String effect) {
        pendingOffTable.put(effect, new String[] {spell, described});
        JsonObject o = msg("offTable");
        o.addProperty("phase", "aimed");
        o.addProperty("spell", spell);
        o.addProperty("target", described);
        o.addProperty("effect", effect);
        o.addProperty("line", line(spell, described));
        send(o);
    }

    private static String line(final String spell, final String described) {
        return (spell == null ? "That spell" : spell)
                + " \u2192 " + (described == null ? "something off the table" : described);
    }

    /** Off-table declarations waiting for their spell to finish resolving. */
    private final Map<String, String[]> pendingOffTable = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------
    // Exiled with: their cards held under one of ours
    //
    // Parallax Wave, Oblivion Ring, Banishing Light: "exile target creature …
    // until this leaves the battlefield". Aimed at their creature, the phantom
    // is exiled with nothing — so when the Wave leaves, the engine has nothing
    // to give back and the room would have to remember on its own. The bridge
    // remembers instead: what of theirs went under which of our cards, shown
    // on that card, and when the card leaves the battlefield, "it comes back —
    // tell them". The real card's journey (battlefield, or a commander's choice
    // to go to the command zone) happens at the table.
    // -----------------------------------------------------------------

    /** Effect string -> {hostId, hostName, described}, until the ability resolves. */
    private final Map<String, Object[]> pendingExileWith = new ConcurrentHashMap<>();
    /** Our card id -> what of theirs is exiled with it. */
    private final Map<Integer, java.util.List<String>> exiledUnder = new ConcurrentHashMap<>();

    /**
     * Called when something is aimed off the table. Remembered only for an
     * exile from a permanent whose text gives cards back — "exiled with" or
     * "until … leaves" — because a plain exile (Swords to Plowshares) returns
     * nothing and there is nothing to remember.
     */
    public void noteExileWith(final forge.game.spellability.SpellAbility sa, final String described, final String effect) {
        if (sa == null || sa.getApi() != forge.game.ability.ApiType.ChangeZone
                || !"Exile".equals(sa.getParam("Destination"))) {
            return;
        }
        forge.game.card.Card host = sa.getHostCard();
        if (host == null || !host.isInZone(ZoneType.Battlefield)) {
            return;
        }
        // Three wordings give the card back: "exiled with" (Parallax Wave),
        // "until … leaves" (Banishing Light), and "when … leaves the
        // battlefield, return the exiled card" (Oblivion Ring, Journey to
        // Nowhere). A plain exile says none of them.
        String text = String.valueOf(host.getOracleText()).toLowerCase();
        boolean givesBack = text.contains("exiled with") || text.contains("until")
                || (text.contains("leaves the battlefield") && text.contains("return"));
        if (!givesBack) {
            return;
        }
        pendingExileWith.put(effect, new Object[] {host.getId(), host.getName(),
                described == null ? "their card" : described});
    }

    /** No avatars over the wire; the client draws its own seats. */
    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
    }

    /**
     * Which steps the player wants the game to stop at.
     *
     * <p>This is the phase dial, and it is the only thing it can be under 3.0.
     * In 2.0 the dial <i>drove</i> the phase — tap a step and the app moved
     * there. The engine owns the phase now, so the dial stops being a control
     * and becomes a filter: thirteen steps, and for each one, "wake me here or
     * don't". Forge's desktop UI answers this exact method by reading which
     * labels are lit in its phase indicator, which is also what endstep.cc's
     * lit-versus-dim phase buttons are.
     *
     * <p>Empty means stop everywhere, which is the safe default: skipping a
     * step the player wanted silently removes their window to act, while
     * stopping at one they did not want costs a tap.
     */
    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return !stops.isEmpty() && !stops.contains(phase);
    }

    /** The steps the client has asked to be woken at; empty means all of them. */
    private final java.util.Set<PhaseType> stops =
            java.util.EnumSet.noneOf(PhaseType.class);

    /** Replace the stop list. Names are {@link PhaseType} constants. */
    public void setStops(final Iterable<String> names) {
        stops.clear();
        if (names == null) {
            return;
        }
        for (String n : names) {
            try {
                stops.add(PhaseType.valueOf(n));
            } catch (IllegalArgumentException e) {
                System.out.println("unknown phase in stop list: " + n);
            }
        }
    }

    /**
     * Where the engine is, as data rather than prose.
     *
     * <p>The client was reading "Phase: Main phase, precombat" out of a
     * human-readable prompt with a regular expression, which is fine for a test
     * harness and no basis for a dial. The step name is the enum constant, and
     * {@code group} is Forge's own {@code PHASE_GROUPS} index — the six
     * clusters (untap/upkeep/draw, main 1, the six combat steps, main 2, end,
     * cleanup) that every Magic client draws as separated bands. The grouping
     * is not invented here either.
     */
    private void sendPhase(final GameEventTurnPhase e) {
        JsonObject o = msg("phase");
        o.addProperty("step", e.phase() == null ? null : e.phase().name());
        o.addProperty("label", e.phase() == null ? null : e.phase().nameForScripts);
        o.addProperty("group", group(e.phase()));
        o.addProperty("turnPlayer", e.playerTurn() == null ? null : e.playerTurn().getName());
        o.addProperty("desc", e.phaseDesc());
        o.addProperty("stopsHere", e.phase() != null
                && !isUiSetToSkipPhase(e.playerTurn(), e.phase()));
        send(o);
    }

    private static int group(final PhaseType p) {
        if (p == null) {
            return -1;
        }
        for (int i = 0; i < PhaseType.PHASE_GROUPS.size(); i++) {
            if (PhaseType.PHASE_GROUPS.get(i).contains(p)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void finishGame() {
        send(msg("gameOver"));
    }

    @Override
    public void afterGameEnd() {
        send(msg("ended"));
        super.afterGameEnd();
    }

    // ---------------------------------------------------------------------
    // The blocking prompts
    // ---------------------------------------------------------------------

    /**
     * The one prompt everything else is built on.
     *
     * <p>min and max of -1 mean "nothing to choose, just look at this" — Forge
     * uses that for reveals — so it returns at once rather than parking.
     */
    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
                                  final List<T> choices, final List<T> selected,
                                  final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }

        JsonObject o = msg("choose");
        // Whose question this is. Null when it is simply ours; the off-table
        // seat's name when the engine is asking the side of the table that
        // cannot answer, and the person holding the tablet has to answer for
        // them. Intuition's "target opponent chooses one" is exactly this.
        o.addProperty("askingFor", asking());
        o.addProperty("message", message);
        o.addProperty("min", min);
        o.addProperty("max", max);
        o.add("options", options(choices, display));

        if (min < 0 && max < 0) { // a reveal; nothing to wait for
            o.addProperty("revealOnly", true);
            o.addProperty("id", nextAskId.getAndIncrement());
            send(o);
            return new ArrayList<>();
        }

        return pick(ask(o), choices);
    }

    /**
     * Send a question and park the engine thread until the client answers it.
     *
     * <p>Every blocking prompt goes through here, which is why there is one id
     * counter and one map rather than a mechanism per dialog type. The caller
     * supplies the message body; this adds the id and does the waiting.
     *
     * <p>Returns an empty array if the wait was interrupted — a conceded or
     * abandoned game, where returning something rather than hanging is the
     * point.
     */
    private JsonArray ask(final JsonObject body) {
        final int id = nextAskId.getAndIncrement();
        body.addProperty("id", id);
        SynchronousQueue<JsonArray> q = new SynchronousQueue<>();
        pending.put(id, q);
        send(body);
        try {
            return q.take(); // the engine thread parks here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new JsonArray();
        } finally {
            pending.remove(id);
        }
    }

    private static <T> JsonArray options(final List<T> choices, final FSerializableFunction<T, String> display) {
        JsonArray opts = new JsonArray();
        for (int i = 0; i < choices.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            j.addProperty("label", label(choices.get(i), display));
            // Which card this choice is, when it is one: the client draws the
            // card (its own library search, its own scry panel) rather than a
            // list of names, and knows where the card is.
            if (choices.get(i) instanceof CardView cv) {
                j.addProperty("cardId", cv.getId());
                if (cv.getCurrentState() != null) {
                    j.addProperty("types", String.valueOf(cv.getCurrentState().getType()));
                }
            }
            opts.add(j);
        }
        return opts;
    }

    /** Map answered indices back onto the objects they stood for, dropping junk. */
    private static <T> List<T> pick(final JsonArray picked, final List<T> from) {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            int idx = asIndex(picked.get(i));
            if (idx >= 0 && idx < from.size()) {
                out.add(from.get(idx));
            }
        }
        return out;
    }

    private static int asIndex(final com.google.gson.JsonElement e) {
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return -1; // a string where a number belonged; ignore the entry
        }
    }

    private static <T> String label(final T c, final FSerializableFunction<T, String> display) {
        if (display != null) {
            try {
                return display.apply(c);
            } catch (Exception e) {
                // A display function that cannot render its own item is not a
                // reason to lose the prompt.
            }
        }
        return String.valueOf(c);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
                                             final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        // Always announce the list, even when it is not a question.
        //
        // This is the message UI 3.0's ring is drawn from, and the ring has to
        // show what a card can do whether or not there is a choice to make.
        // Agatha's Soul Cauldron is the case that proves it: once Llanowar Elves
        // is exiled under the Cauldron and a creature has a +1/+1 counter, that
        // creature gains "{T}: Add {G}" — one ability, so Forge never asks, so
        // under the old shortcut the only way a player could find out was to tap
        // and watch it happen. The engine knew; the player could not see.
        //
        // Informational on purpose: the return path below is unchanged, so a
        // single ability is still auto-selected and existing clients are
        // unaffected. The ring reads this; the prompt still drives the game.
        JsonObject list = msg("abilities");
        list.addProperty("cardId", hostCard == null ? 0 : hostCard.getId());
        list.addProperty("card", hostCard == null ? null : hostCard.getName());
        JsonArray arr = new JsonArray();
        for (int i = 0; i < abilities.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            j.addProperty("label", String.valueOf(abilities.get(i)));
            arr.add(j);
        }
        list.add("options", arr);
        list.addProperty("asked", abilities.size() > 1);
        send(list);

        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        // Under UI 3.0 this is the ring: the engine says what is legal here and
        // now, and the client draws exactly those rather than guessing.
        List<SpellAbilityView> picked = getChoices(
                hostCard == null ? "Choose an ability" : hostCard.getName(),
                1, 1, abilities, null, null);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers,
                                                     final int damage, final GameEntityView defender,
                                                     final boolean overrideOrder, final boolean maySkip) {
        // Several blockers, or trample: how this attacker's damage is split is
        // the player's decision. Asked over the wire with, per blocker, what is
        // lethal (toughness less damage already marked), and the defender when
        // trample lets damage through. Answered as [{id, n}], id 0 for the
        // defender — Forge's own convention is a null key for the defender.
        // Already answered when the only blockers are our stand-ins: the player
        // said how much gets through when blocks were declared.
        if (attacker != null && blockers != null && !blockers.isEmpty()) {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (CardView b : blockers) {
                ids.add(b.getId());
            }
            Integer through = CardboardBlocks.wantedThrough(attacker.getId(), ids);
            if (through != null) {
                Map<CardView, Integer> auto = new java.util.HashMap<>();
                int rest = damage - (defender != null ? through : 0);
                auto.put(blockers.get(0), Math.max(0, rest));
                if (defender != null && through > 0) {
                    auto.put(null, through);
                }
                return auto;
            }
        }
        JsonObject o = msg("damage");
        o.addProperty("attacker", attacker == null ? "" : attacker.getName());
        o.addProperty("attackerId", attacker == null ? 0 : attacker.getId());
        o.addProperty("amount", damage);
        o.addProperty("defender", defender == null ? null : defender.getName());
        o.addProperty("maySkip", maySkip);
        JsonArray bl = new JsonArray();
        for (CardView b : blockers) {
            JsonObject j = new JsonObject();
            j.addProperty("id", b.getId());
            j.addProperty("name", b.getName());
            int toughness = b.getCurrentState() == null ? 0 : b.getCurrentState().getToughness();
            j.addProperty("toughness", toughness);
            j.addProperty("lethal", Math.max(0, toughness - b.getDamage()));
            bl.add(j);
        }
        o.add("blockers", bl);
        JsonArray a = ask(o);

        Map<CardView, Integer> out = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : a) {
            if (!e.isJsonObject()) {
                continue;
            }
            int id = e.getAsJsonObject().get("id").getAsInt();
            int n = e.getAsJsonObject().get("n").getAsInt();
            if (n <= 0) {
                continue;
            }
            if (id == 0) {
                out.put(null, n);
                continue;
            }
            for (CardView b : blockers) {
                if (b.getId() == id) {
                    out.put(b, n);
                }
            }
        }
        if (out.isEmpty() && !blockers.isEmpty()) {
            // No answer (the client went away): the old behaviour, all to the
            // first blocker, so the game can still finish.
            out.put(blockers.get(0), damage);
        }
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
                                                    final int amount, final boolean atLeastOne,
                                                    final String amountLabel) {
        // Divide an amount among targets — "4 damage divided as you choose",
        // a mana combination, a shield split. Asked as {"t":"divide"} with the
        // targets in order; answered as [{i, n}].
        java.util.List<Object> keys = new java.util.ArrayList<>(target == null ? java.util.List.of() : target.keySet());
        JsonObject o = msg("divide");
        o.addProperty("source", effectSource == null ? "" : effectSource.getName());
        o.addProperty("amount", amount);
        o.addProperty("atLeastOne", atLeastOne);
        o.addProperty("label", amountLabel);
        JsonArray ts = new JsonArray();
        for (int i = 0; i < keys.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            Object k = keys.get(i);
            j.addProperty("label", k instanceof GameEntityView gv ? gv.getName() : String.valueOf(k));
            if (k instanceof CardView cv) {
                j.addProperty("cardId", cv.getId());
            }
            ts.add(j);
        }
        o.add("targets", ts);
        JsonArray a = ask(o);

        Map<Object, Integer> out = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : a) {
            if (!e.isJsonObject()) {
                continue;
            }
            int i = e.getAsJsonObject().get("i").getAsInt();
            int n = e.getAsJsonObject().get("n").getAsInt();
            if (i >= 0 && i < keys.size() && n > 0) {
                out.put(keys.get(i), n);
            }
        }
        if (out.isEmpty() && !keys.isEmpty()) {
            out.put(keys.get(0), amount);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Dialogs
    //
    // Forge's own UIs render these as modal windows. Into Play does not have
    // modal windows, and the client is free to draw a confirm as two ring
    // segments or a sheet — which is exactly why these go over the wire as
    // questions with an id rather than as "show this dialog".
    // ---------------------------------------------------------------------

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
                                     final String noButtonText, final boolean defaultYes) {
        JsonObject o = msg("confirm");
        o.addProperty("title", title);
        o.addProperty("text", message);
        o.addProperty("yes", yesButtonText);
        o.addProperty("no", noButtonText);
        o.addProperty("defaultYes", defaultYes);
        return yes(ask(o), defaultYes);
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes,
                           final List<String> options) {
        JsonObject o = msg("confirm");
        o.addProperty("text", question);
        if (c != null) {
            o.addProperty("card", c.getName());
            o.addProperty("cardId", c.getId());
        }
        if (options != null && options.size() >= 2) {
            o.addProperty("yes", options.get(0));
            o.addProperty("no", options.get(1));
        }
        o.addProperty("defaultYes", defaultIsYes);
        return yes(ask(o), defaultIsYes);
    }

    /**
     * A confirm answers with one index: 0 for no, anything else for yes. An
     * empty answer means the client went away mid-question, and the default is
     * the only honest reading of that.
     */
    private static boolean yes(final JsonArray answer, final boolean dflt) {
        if (answer == null || answer.size() == 0) {
            return dflt;
        }
        return asIndex(answer.get(0)) != 0;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        if (options == null || options.isEmpty()) {
            return defaultOption;
        }
        JsonObject o = msg("options");
        o.addProperty("title", title);
        o.addProperty("text", message);
        o.add("options", options(options, null));
        JsonArray a = ask(o);
        if (a.size() == 0) {
            return defaultOption;
        }
        int idx = asIndex(a.get(0));
        return idx >= 0 && idx < options.size() ? idx : defaultOption;
    }

    /**
     * The one prompt whose answer is text rather than an index — so the reply
     * carries a string in the same "picked" array, which is why
     * {@link #pick} tolerates a non-number and {@link #asIndex} does not throw.
     */
    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions,
                                  final boolean isNumeric) {
        JsonObject o = msg("input");
        o.addProperty("title", title);
        o.addProperty("text", message);
        o.addProperty("initial", initialInput);
        o.addProperty("numeric", isNumeric);
        if (inputOptions != null && !inputOptions.isEmpty()) {
            o.add("options", options(inputOptions, null));
        }
        JsonArray a = ask(o);
        if (a.size() == 0) {
            return initialInput;
        }
        try {
            return a.get(0).getAsString();
        } catch (Exception e) {
            return initialInput;
        }
    }

    // ---------------------------------------------------------------------
    // Ordering
    // ---------------------------------------------------------------------

    /**
     * Put things in an order: the top of the library after a scry, the order
     * triggers resolve in, which blocker takes damage first.
     *
     * <p>The wire form is one flat option list — sourceChoices first, then
     * destChoices — and the answer is the destination read top to bottom as
     * indices into it. Two lists would be more faithful to Forge's dialog, but
     * the client already drags cards into one pile and sends back what the pile
     * looks like, which is the same information with no seam.
     *
     * <p>No answer leaves the order Forge proposed, which is the same as the
     * player accepting the default.
     */
    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
                                    final int remainingObjectsMax, final List<T> sourceChoices,
                                    final List<T> destChoices, final CardView referenceCard,
                                    final boolean sideboardingMode, final boolean showRememberCheckbox) {
        List<T> all = new ArrayList<>();
        if (sourceChoices != null) {
            all.addAll(sourceChoices);
        }
        final int split = all.size();
        if (destChoices != null) {
            all.addAll(destChoices);
        }
        if (all.isEmpty()) {
            return new OrderResult<>(new ArrayList<>(), false);
        }

        JsonObject o = msg("order");
        o.addProperty("title", title);
        o.addProperty("top", top);
        o.addProperty("minRemaining", remainingObjectsMin);
        o.addProperty("maxRemaining", remainingObjectsMax);
        o.addProperty("destFrom", split); // options below this index came from source
        if (referenceCard != null) {
            o.addProperty("card", referenceCard.getName());
            o.addProperty("cardId", referenceCard.getId());
        }
        o.add("options", options(all, null));

        // An empty answer is an answer: "none of them". many() is built on this
        // call — surveil's "which go to the graveyard", scry's "which go to the
        // bottom" — and treating empty as "use Forge's default" sent every
        // card there when the player chose none. The client never sends an
        // empty answer to a question that needs every card ordered.
        List<T> ordered = pick(ask(o), all);
        return new OrderResult<>(ordered, false);
    }

    /**
     * Scry and surveil end up here: some of these cards may move, the rest stay
     * put. The answer is the whole list in its new order, so an untouched list
     * is a valid answer meaning "leave it".
     */
    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
                                             final Iterable<CardView> manipulable, final boolean toTop,
                                             final boolean toBottom, final boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        if (cards != null) {
            for (CardView c : cards) {
                all.add(c);
            }
        }
        if (all.isEmpty()) {
            return all;
        }
        JsonObject o = msg("manipulate");
        o.addProperty("title", title);
        o.addProperty("toTop", toTop);
        o.addProperty("toBottom", toBottom);
        o.addProperty("toAnywhere", toAnywhere);
        o.add("options", options(all, CardView::getName));
        JsonArray movable = new JsonArray();
        if (manipulable != null) {
            for (CardView c : manipulable) {
                int i = all.indexOf(c);
                if (i >= 0) {
                    movable.add(i);
                }
            }
        }
        o.add("movable", movable);

        List<CardView> out = pick(ask(o), all);
        return out.isEmpty() ? all : out;
    }

    // ---------------------------------------------------------------------
    // Entity choices
    // ---------------------------------------------------------------------

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title,
                                                      final List<? extends GameEntityView> optionList,
                                                      final DelayedReveal delayedReveal, final boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        revealDelayed(delayedReveal);
        List<GameEntityView> opts = new ArrayList<>(optionList);
        List<GameEntityView> got = getChoices(title, isOptional ? 0 : 1, 1, opts, null, GameEntityView::getName);
        return got.isEmpty() ? null : got.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title,
                                                        final List<? extends GameEntityView> optionList,
                                                        final int min, final int max,
                                                        final DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) {
            return new ArrayList<>();
        }
        revealDelayed(delayedReveal);
        List<GameEntityView> opts = new ArrayList<>(optionList);
        return getChoices(title, min, max, opts, null, GameEntityView::getName);
    }

    /**
     * Forge's desktop UI folds this into the search dialog; here it is simply
     * sent ahead of the question, because the client decides for itself whether
     * to show the two together.
     */
    private void revealDelayed(final DelayedReveal dr) {
        if (dr == null || dr.getCards() == null || dr.getCards().isEmpty()) {
            return;
        }
        JsonObject o = msg("reveal");
        o.addProperty("prefix", dr.getMessagePrefix());
        o.add("cards", options(new ArrayList<>(dr.getCards()), CardView::getName));
        send(o);
    }

    /**
     * Between games. Into Play has no sideboard step yet — the deck a player
     * sat down with is the deck they keep — so the main deck is returned
     * unchanged rather than a dialog being invented for it.
     */
    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return main == null ? new ArrayList<>() : main.toFlatList();
    }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        JsonObject o = msg("open");
        JsonArray seats = new JsonArray();
        if (myPlayers != null) {
            for (PlayerView p : myPlayers) {
                seats.add(p.getName());
            }
        }
        o.add("seats", seats);
        // Which seat is a proxy for someone across the table. The client cannot
        // work this out for itself: with one socket holding both seats, "which
        // seat am I" is genuinely ambiguous, and inferring it from whoever was
        // prompted first means it changes with the coin toss.
        o.addProperty("offTable", offTableSeat);
        o.add("opponents", opponentsJson());
        lastOpen = o.toString();
        send(o);
    }

    /** The "open" message as sent, for a screen that joins later (the phone's hand). */
    private volatile String lastOpen;

    public String lastOpen() {
        return lastOpen;
    }

    /** Every off-table seat, first one first (several in multiplayer). */
    static JsonArray opponentsJson() {
        JsonArray a = new JsonArray();
        BridgeMain.OPPONENTS.forEach(a::add);
        return a;
    }

    /**
     * Stop arming the off-table seat's auto-pass, to find out what it costs.
     *
     * <p>The seat has to stop taking turns, and it does — that is the Skip$ True
     * replacement on the command-zone card, which is a different mechanism. This
     * flag is only about the per-turn yield, which exists to spare the player
     * pressing OK at every step of their own turn for a seat that will never
     * respond. The question it answers: does that yield also swallow the
     * QUESTIONS the engine asks that seat — Intuition's "target opponent chooses
     * one", which never arrived.
     */
    private static final boolean NO_AUTOPASS = "1".equals(System.getenv("NO_AUTOPASS"));

    /**
     * Every state change in the game passes through here, which is what Into
     * Play's event log has wanted all along: a record written by the thing that
     * knows, rather than by the client guessing after each tap.
     */
    @Override
    public void handleGameEvent(final GameEvent event) {
        if (event == null) {
            return;
        }
        JsonObject o = msg("event");
        o.addProperty("kind", event.getClass().getSimpleName());
        o.addProperty("text", String.valueOf(event));
        send(o);

        // The moment the fork exists for. The spell was legal, it has finished
        // resolving, and the engine did nothing to the phantom because a
        // phantom is neither a Card nor a Player and every effect tests for
        // exactly those two. Nothing is corrupted; nothing is reported either.
        // This is the report.
        // Re-arm the off-table seat's auto-pass every turn.
        //
        // Forge's own mechanism, not one invented here: autoPassUntilEndOfTurn
        // sets a yield the priority loop consults, and it is deliberately
        // per-turn — autoPassCancel runs at every cleanup — so this has to be
        // re-armed rather than set once.
        //
        // Only THAT seat. Our own priority during their turn still comes
        // through, because that is where instants live: a bridge that passed
        // for both seats would quietly remove the ability to respond.
        // Every off-table seat, when there are several opponents.
        if (event instanceof GameEventTurnBegan && offTableSeat != null && !NO_AUTOPASS) {
          for (String seatName : BridgeMain.OPPONENTS) {
            // The cast is necessary: autoPassCancel is on IGameController but
            // autoPassUntilEndOfTurn is not — only the human controller has it.
            if (controllerFor(seatName) instanceof forge.player.PlayerControllerHuman h) {
                h.autoPassUntilEndOfTurn();
                // Say WHOSE yield it is, because Forge's own report does not.
                //
                // AbstractGuiGame.updateAutoPassPrompt sends "Yielding until end
                // of turn" through showPromptMessage(getCurrentPlayer(), …), and
                // getCurrentPlayer() is only refreshed by InputProxy from the
                // owner of the current *input*. An auto-pass is not an input, so
                // that text arrives labelled with whichever seat happened to be
                // there last — watched live, the off-table seat's yield was
                // reported as ours.
                //
                // Not cosmetic. A client that reads the seat off the prose and
                // cancels "its" yield cancels the wrong one; both seats then sit
                // yielding and the stack never resolves. It hid for two runs of
                // the Cauldron scenario until the coin toss went the other way.
                //
                // updateAutoPassPrompt is final in AbstractGuiGame, so it cannot
                // be corrected where it is written. It can be stated here, which
                // is the honest place anyway: this is the line that armed it, so
                // this is the code that knows which seat it belongs to.
                JsonObject y = msg("yield");
                y.addProperty("player", seatName);
                y.addProperty("offTable", true);
                send(y);
            }
          }
        }

        if (event instanceof GameEventTurnPhase tp) {
            sendPhase(tp);
        }

        // Exiled with one of ours: remembered once the exile has resolved, and
        // handed back — out loud — when our card leaves the battlefield.
        if (event instanceof GameEventSpellResolved er && !er.hasFizzled()) {
            Object[] ex = pendingExileWith.remove(er.stackDescription());
            if (ex != null) {
                exiledUnder.computeIfAbsent((Integer) ex[0], k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add((String) ex[2]);
                sendBoard();
            }
        }
        if (event instanceof forge.game.event.GameEventCardChangeZone cz
                && cz.card() != null && cz.from() != null && cz.from().zoneType() == ZoneType.Battlefield) {
            java.util.List<String> under = exiledUnder.remove(cz.card().getId());
            if (under != null && !under.isEmpty()) {
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (String what : under) {
                    lines.add(Character.toUpperCase(what.charAt(0)) + what.substring(1) + " comes back — it was exiled with " + cz.card().getName()
                            + ". Return it to them.");
                }
                tell(lines);
            }
        }

        if (event instanceof GameEventSpellResolved r) {
            String[] aimed = pendingOffTable.remove(r.stackDescription());
            if (aimed != null) {
                JsonObject t = msg("offTable");
                t.addProperty("phase", "resolved");
                t.addProperty("spell", aimed[0]);
                t.addProperty("target", aimed[1]);
                t.addProperty("effect", r.stackDescription());
                t.addProperty("line", line(aimed[0], aimed[1]));
                t.addProperty("fizzled", r.hasFizzled());
                // The engine has done all it can. Everything after this
                // happens out loud, between people.
                t.addProperty("tellThem", !r.hasFizzled());
                send(t);
            }
        }
    }

    /**
     * What the player could do something with right now: the cards Forge
     * would light up on its own desktop — castable spells, playable lands,
     * activatable permanents, legal attackers or blockers. The client glows
     * them, so "what can I do?" is answered by the table, not by trying.
     */
    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        JsonObject o = msg("playable");
        JsonArray ids = new JsonArray();
        if (cards != null) {
            for (CardView c : cards) {
                ids.add(c.getId());
            }
        }
        o.add("ids", ids);
        send(o);
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        JsonObject o = msg("playable");
        o.add("ids", new JsonArray());
        send(o);
    }

    @Override
    public void setHighlighted(final Iterable<GameEntityView> entities, final boolean b) {
        JsonObject o = msg("highlight");
        o.addProperty("on", b);
        JsonArray ids = new JsonArray();
        if (entities != null) {
            for (GameEntityView e : entities) {
                ids.add(e.getId());
            }
        }
        o.add("ids", ids);
        send(o);
    }

    /**
     * The card the engine wants looked at.
     *
     * <p>Worth more than it appears: {@code InputSelectTargets.showMessage()}
     * calls this with the spell doing the targeting, so remembering it here is
     * how the bridge knows which card an off-table declaration belongs to —
     * without another accessor patched into Forge to ask.
     */
    @Override
    public void setCard(final CardView card) {
        if (card == null) {
            return;
        }
        focus = card.getName();
        JsonObject o = msg("focus");
        o.addProperty("cardId", card.getId());
        o.addProperty("name", card.getName());
        send(o);
    }

    /** The last card the engine put in focus; the spell, during targeting. */
    public String focus() {
        return focus;
    }

    /** Which card's abilities the desktop UI would be showing in its panel. */
    @Override
    public void setPanelSelection(final CardView hostCard) {
        setCard(hostCard);
    }

    /** Forge's save/restore debug hook. Nothing here has a state to hand back. */
    @Override
    public GameState getGamestate() {
        return null;
    }

    @Override
    public boolean isGamePaused() {
        return false;
    }
}
