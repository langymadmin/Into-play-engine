package forge.intoplay;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocks declared with cardboard.
 *
 * <p>The off-table seat is a real engine player with no creatures: theirs are on
 * the table. So when we attack, Forge sees nobody who could block and goes
 * straight to damage — every attack unblocked, which is not the game being
 * played in the room.
 *
 * <p>UI-3.0.md's rule for this: <b>just-in-time, never up-front</b>. Nobody sets
 * up the opponent's board. At the moment blocks are declared, the tablet asks
 * "did they block, and with what?" — power, toughness, and the few keywords that
 * change combat arithmetic. For each block a stand-in creature appears on their
 * side with exactly those numbers, Forge declares the block, and Forge does the
 * part people get wrong: first strike, deathtouch, trample, lifelink, damage
 * order. When combat ends the stand-ins go, and what happened to them is said
 * out loud — "their 2/2 blocking Grizzly Bears dies" — because the real card is
 * cardboard and somebody has to put it in a graveyard.
 *
 * <p>The stand-ins are placed into the zone directly rather than through
 * moveTo: the real creature was already on the table, so nothing "entered", and
 * an enters-the-battlefield trigger here would be the engine inventing an event.
 * Dying is different — the real creature did die — so death in combat goes
 * through Forge normally and anything watching for it fires.
 */
final class CardboardBlocks {

    private final Game game;
    private final BridgeGui gui;
    private final String offTable;
    /** Stand-in -> the attacker it blocked, for the report after combat. */
    private final Map<Card, Card> standIns = new LinkedHashMap<>();
    private final Map<Card, String> described = new LinkedHashMap<>();

    CardboardBlocks(final Game game0, final BridgeGui gui0, final String offTable0) {
        game = game0;
        gui = gui0;
        offTable = offTable0;
    }

    @Subscribe
    public void receiveGameEvent(final GameEvent e) {
        // Synchronous, on the game thread: the step-change event fires before
        // PhaseHandler runs the step, so blockers placed here are on the
        // battlefield when declareBlockersTurnBasedAction looks for them.
        if (e instanceof GameEventTurnPhase tp && tp.phase() == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            askAndPlace();
        } else if (e instanceof GameEventCombatEnded) {
            report();
        }
    }

    private Player seat() {
        for (Player p : game.getPlayers()) {
            if (offTable.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private void askAndPlace() {
        Combat combat = game.getCombat();
        Player them = seat();
        if (combat == null || them == null || !combat.isPlayerAttacked(them)) {
            return;
        }
        List<Card> attackers = new ArrayList<>(combat.getAttackersOf(them));
        if (attackers.isEmpty()) {
            return;
        }

        JsonArray answer = gui.askBlocks(attackers);

        for (JsonElement el : answer) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject b = el.getAsJsonObject();
            Card attacker = byId(attackers, b.has("attacker") ? b.get("attacker").getAsInt() : -1);
            if (attacker == null) {
                continue;
            }
            int power = b.has("power") ? b.get("power").getAsInt() : 0;
            int toughness = b.has("toughness") ? b.get("toughness").getAsInt() : 1;
            List<String> keywords = new ArrayList<>();
            if (b.has("keywords")) {
                for (JsonElement k : b.getAsJsonArray("keywords")) {
                    keywords.add(k.getAsString());
                }
            }
            Card standIn = standIn(them, power, toughness, keywords);
            combat.addBlocker(attacker, standIn);
            standIns.put(standIn, attacker);
            described.put(standIn, "their " + power + "/" + toughness
                    + (keywords.isEmpty() ? "" : " (" + String.join(", ", keywords).toLowerCase() + ")"));
        }
        if (!standIns.isEmpty()) {
            game.updateCombatForView();
        }
    }

    private Card standIn(final Player them, final int power, final int toughness, final List<String> keywords) {
        List<String> script = new ArrayList<>();
        script.add("Name:Their creature");
        script.add("Types:Creature");
        script.add("PT:" + power + "/" + toughness);
        // Reach, so the block is legal whatever it is blocking: at the table
        // they already decided it could block, and that is not the engine's
        // call to overrule with a creature it cannot see.
        script.add("K:Reach");
        for (String k : keywords) {
            script.add("K:" + k);
        }
        script.add("Oracle:A creature on the table, standing in for this combat.");
        PaperCard pc = new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
        Card c = CardFactory.getCard(pc, them, game);
        c.setSickness(false);
        them.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    private void report() {
        if (standIns.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Card, Card> e : standIns.entrySet()) {
            Card standIn = e.getKey();
            Card attacker = e.getValue();
            String them = described.get(standIn);
            boolean theyDied = !standIn.isInZone(ZoneType.Battlefield);
            boolean oursDied = !attacker.isInZone(ZoneType.Battlefield);
            String line = them + " blocking " + attacker.getName()
                    + (theyDied ? " dies" : " survives"
                        + (standIn.getDamage() > 0 ? " with " + standIn.getDamage() + " damage" : ""))
                    + (oursDied ? "; your " + attacker.getName() + " dies" : "");
            lines.add(line);
            // The stand-in goes, wherever combat left it. A survivor's real card
            // is still on the table; a dead one's goes to a graveyard made of
            // cardboard. Either way the engine has no use for it after this.
            if (standIn.getZone() != null) {
                standIn.getZone().remove(standIn);
            }
        }
        standIns.clear();
        described.clear();
        gui.tell(lines);
        System.out.println("cardboard blocks: " + lines);
    }

    private static Card byId(final List<Card> cards, final int id) {
        for (Card c : cards) {
            if (c.getId() == id) {
                return c;
            }
        }
        return null;
    }
}
