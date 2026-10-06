package forge.intoplay;

import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.List;

/**
 * "They did something to me."
 *
 * <p>The opponent's spells are cardboard; their effects on our side are real.
 * "Target player mills two", "destroy target creature", "each opponent loses
 * 3 life" — the engine cannot see the spell, so the player declares what it
 * did, and the bridge enacts it here.
 *
 * <p>Through the engine's own actions, never by editing state: a declared
 * destroy is {@code GameAction.destroy}, so indestructible and regeneration
 * apply and "whenever a creature you control dies" fires; a declared mill is
 * {@code Player.mill}, so the cards go where the rules say. This is
 * UI-3.0.md's "self-declared effects" — Forge's dev-mode cheats prompt through
 * the GUI, these take their answer from the message instead.
 *
 * <p>Run on the game's own thread via {@code GameAction.invoke}, as the cheats
 * do, then state-based actions are checked so a declared lethal loss of life
 * ends the game at once rather than at the next priority pass.
 *
 * <pre>
 *   {"t":"declare","what":"life","delta":-3}
 *   {"t":"declare","what":"damage","amount":3}
 *   {"t":"declare","what":"draw","n":1}       {"what":"mill","n":2}
 *   {"t":"declare","what":"discard|destroy|sacrifice|exile|bounce|graveyard","cardId":42}
 *   {"t":"declare","what":"library","cardId":42,"top":true}
 *   {"t":"declare","what":"tap|untap","cardId":42}
 *   {"t":"declare","what":"counter","cardId":42,"type":"P1P1","n":-1}
 * </pre>
 */
final class Declarations {

    private Declarations() { }

    static void apply(final Game game, final BridgeGui gui, final String seatName, final JsonObject in) {
        if (game == null) {
            return;
        }
        final String what = in.has("what") ? in.get("what").getAsString() : "";
        game.getAction().invoke(() -> {
            try {
                enact(game, gui, seatName, in, what);
            } catch (RuntimeException e) {
                // The pool this runs on swallows exceptions, which made a failed
                // declaration indistinguishable from one never sent.
                System.out.println("declaration " + what + " failed: " + e);
                e.printStackTrace(System.out);
                gui.tell(List.of("The engine could not apply that (" + what + "): " + e.getMessage()));
            }
        });
    }

    private static void enact(final Game game, final BridgeGui gui, final String seatName,
                              final JsonObject in, final String what) {
        {
            Player me = null;
            for (Player p : game.getPlayers()) {
                if (seatName.equals(p.getName())) {
                    me = p;
                }
            }
            if (me == null) {
                return;
            }
            Card card = in.has("cardId") ? find(game, in.get("cardId").getAsInt()) : null;
            int n = in.has("n") ? in.get("n").getAsInt() : 1;
            String said;
            switch (what) {
                case "life": {
                    int delta = in.has("delta") ? in.get("delta").getAsInt() : 0;
                    if (delta < 0) {
                        me.loseLife(-delta, false, false, null);
                    } else if (delta > 0) {
                        me.gainLife(delta, null, null);
                    }
                    said = (delta < 0 ? "lost " + (-delta) : "gained " + delta) + " life";
                    break;
                }
                case "damage": {
                    int amount = in.has("amount") ? in.get("amount").getAsInt() : 0;
                    // Counted as damage for what reads life lost to damage.
                    me.loseLife(amount, true, false, null);
                    said = "took " + amount + " damage";
                    break;
                }
                case "draw":
                    me.drawCards(n, null, AbilityKey.newMap());
                    said = "drew " + n;
                    break;
                case "mill": {
                    // Player.mill needs the spell that caused it (it reads the
                    // root ability for simultaneous-ETB replacement), and a
                    // cardboard spell has none. The top n move to the graveyard
                    // through the engine instead: zone-change and graveyard
                    // triggers still fire; only "whenever a card is milled"
                    // wording does not.
                    List<Card> top = new java.util.ArrayList<>();
                    for (Card c : me.getCardsIn(ZoneType.Library)) {
                        if (top.size() >= n) {
                            break;
                        }
                        top.add(c);
                    }
                    for (Card c : top) {
                        game.getAction().moveToGraveyard(c, null, AbilityKey.newMap());
                    }
                    said = "milled " + top.size();
                    break;
                }
                case "discard":
                    if (card == null) { return; }
                    me.discard(card, null, true, AbilityKey.newMap());
                    said = "discarded " + card.getName();
                    break;
                case "destroy":
                    if (card == null) { return; }
                    game.getAction().destroy(card, null, true, AbilityKey.newMap());
                    said = card.getName() + " was destroyed";
                    break;
                case "sacrifice":
                    if (card == null) { return; }
                    game.getAction().sacrifice(List.of(card), null, true, AbilityKey.newMap());
                    said = "sacrificed " + card.getName();
                    break;
                case "exile":
                    if (card == null) { return; }
                    game.getAction().exile(card, null, AbilityKey.newMap());
                    said = card.getName() + " was exiled";
                    break;
                case "bounce":
                    if (card == null) { return; }
                    game.getAction().moveToHand(card, null, AbilityKey.newMap());
                    said = card.getName() + " returned to hand";
                    break;
                case "graveyard":
                    if (card == null) { return; }
                    game.getAction().moveToGraveyard(card, null, AbilityKey.newMap());
                    said = card.getName() + " was put into the graveyard";
                    break;
                case "library": {
                    if (card == null) { return; }
                    boolean top = !in.has("top") || in.get("top").getAsBoolean();
                    game.getAction().moveToLibrary(card, top ? 0 : -1, null, AbilityKey.newMap());
                    said = card.getName() + " was put on the " + (top ? "top" : "bottom") + " of the library";
                    break;
                }
                case "tap":
                    if (card == null) { return; }
                    card.tap(true, null, null);
                    said = card.getName() + " was tapped";
                    break;
                case "untap":
                    if (card == null) { return; }
                    card.untap();
                    said = card.getName() + " was untapped";
                    break;
                case "counter": {
                    if (card == null) { return; }
                    CounterType type = CounterType.getType(in.has("type") ? in.get("type").getAsString() : "P1P1");
                    if (n > 0) {
                        // As Forge's own counter cheat does: without the event
                        // path, which needs a parameter map a cardboard source
                        // cannot supply.
                        card.addCounterInternal(type, n, me, false, null, null);
                    } else if (n < 0) {
                        card.subtractCounter(type, -n, me);
                    }
                    said = card.getName() + (n > 0 ? " got " : " lost ") + Math.abs(n) + " " + type.getName() + " counter(s)";
                    break;
                }
                default:
                    System.out.println("unknown declaration: " + in);
                    return;
            }
            game.getAction().checkStateEffects(true);
            System.out.println("declared: " + said);
            // The engine's own events already narrate the result in the log;
            // the board is sent at once so the screen does not wait for the
            // next prompt to show it.
            gui.sendBoard();
        }
    }

    private static Card find(final Game game, final int id) {
        for (Card c : game.getCardsInGame()) {
            if (c.getId() == id) {
                return c;
            }
        }
        return null;
    }
}
