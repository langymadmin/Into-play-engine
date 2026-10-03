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
        if (sink != null) {
            sink.send(o.toString());
        }
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
        if (getGameView() == null || getGameView().getPlayers() == null) {
            return null;
        }
        for (PlayerView p : getGameView().getPlayers()) {
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
        if (name == null || getGameView() == null || getGameView().getPlayers() == null) {
            return null;
        }
        for (PlayerView p : getGameView().getPlayers()) {
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
        if (getGameView() == null || getGameView().getPlayers() == null) {
            return;
        }
        JsonObject o = msg("board");
        JsonArray seats = new JsonArray();
        for (PlayerView p : getGameView().getPlayers()) {
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
                    JsonObject j = new JsonObject();
                    j.addProperty("id", c.getId());
                    j.addProperty("name", c.getName());
                    arr.add(j);
                }
                if (arr.size() > 0) {
                    s.add(z.name().toLowerCase(), arr);
                }
            }
            seats.add(s);
        }
        o.add("seats", seats);
        PlayerView turn = getGameView().getPlayerTurn();
        o.addProperty("turnPlayer", turn == null ? null : turn.getName());
        o.addProperty("turn", getGameView().getTurn());
        send(o);
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
        o.addProperty("text", message);
        if (card != null) {
            o.addProperty("card", card.getName());
            o.addProperty("cardId", card.getId());
        }
        send(o);
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
        send(o);
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        send(msg("targetingDone"));
    }

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

    /** No avatars over the wire; the client draws its own seats. */
    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
    }

    /**
     * False always: stop at every phase.
     *
     * <p>Forge's desktop UI answers this from the phase indicator's enabled
     * labels — the player's own "don't stop in my upkeep" preferences. There is
     * no such widget here, and the wrong answer is not symmetrical: skipping a
     * phase the player wanted silently removes their window to act, while
     * stopping at one they did not want costs a tap. Into Play's phase dial
     * already decides what the player is shown, so this stays open and the
     * client filters.
     */
    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return false;
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
        // Not asked over the wire yet — combat is a later step. Until then all
        // damage goes to the first blocker, so a game runs to completion instead
        // of stopping here. Wrong, but deliberately and visibly wrong.
        Map<CardView, Integer> out = new java.util.HashMap<>();
        if (blockers != null && !blockers.isEmpty()) {
            out.put(blockers.get(0), damage);
        }
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
                                                    final int amount, final boolean atLeastOne,
                                                    final String amountLabel) {
        Map<Object, Integer> out = new java.util.HashMap<>();
        if (target != null && !target.isEmpty()) {
            out.put(target.keySet().iterator().next(), amount);
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

        List<T> ordered = pick(ask(o), all);
        if (ordered.isEmpty()) {
            ordered = destChoices == null ? new ArrayList<>(all) : new ArrayList<>(destChoices);
            if (destChoices != null && sourceChoices != null) {
                ordered.addAll(sourceChoices);
            }
        }
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
        send(o);
    }

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
