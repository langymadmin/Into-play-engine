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
 * <p>UI-3.0.md's rule for this: <b>just-in-time, never up-front</b>, and the
 * player's own rule on top of it: <b>nothing typed</b>. At the moment blocks
 * are declared the tablet asks one number per attacker — how much damage gets
 * through to them — starting at the attacker's full power, taken down to what
 * happened at the table. An attacker held back gets a stand-in blocker that
 * only soaks the difference (no power, indestructible), so the engine deals
 * exactly that much to their life and lifelink and "deals combat damage"
 * still count. Whether their creature died is said at the table; whether ours
 * did is declared, as any other effect of their cardboard is.
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

    private void askAndPlace() {
        Combat combat = game.getCombat();
        if (combat == null) {
            return;
        }
        // Every off-table seat that was attacked (several, in multiplayer):
        // one question for all of them, each attacker's stand-in belonging to
        // the player it attacked.
        List<Card> attackers = new ArrayList<>();
        Map<Card, Player> defenderOf = new LinkedHashMap<>();
        for (Player p : game.getPlayers()) {
            if (!BridgeMain.isOffTable(p.getName()) || !combat.isPlayerAttacked(p)) {
                continue;
            }
            for (Card a : combat.getAttackersOf(p)) {
                attackers.add(a);
                defenderOf.put(a, p);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }

        // One number per attacker: how much damage gets through to them. The
        // player starts from the attacker's full power and takes it down to
        // what really happened at the table — 0 for a blocked attacker. Nothing
        // about their creatures is asked; they are cardboard.
        JsonArray answer = gui.askThrough(attackers);

        for (JsonElement el : answer) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject b = el.getAsJsonObject();
            Card attacker = byId(attackers, b.has("attacker") ? b.get("attacker").getAsInt() : -1);
            if (attacker == null) {
                continue;
            }
            int power = Math.max(0, attacker.getNetCombatDamage());
            int through = b.has("n") ? Math.max(0, Math.min(power, b.get("n").getAsInt())) : power;
            if (through >= power) {
                continue; // unblocked: the engine deals it all, as it already would
            }
            // Held back: a blocker absorbs the rest. With trample the engine asks
            // how to split the damage, and the answer is already known (see
            // wantedThrough); without it a blocked attacker deals the player
            // nothing, which is the rule.
            Card standIn = standIn(defenderOf.get(attacker), power - through);
            combat.addBlocker(attacker, standIn);
            standIns.put(standIn, attacker);
            wanted.put(attacker.getId(), through);
        }
        if (!standIns.isEmpty()) {
            game.updateCombatForView();
        }
    }

    /** Attacker id -> damage the player said gets through, while combat lasts. */
    private static final Map<Integer, Integer> wanted = new java.util.concurrent.ConcurrentHashMap<>();
    /** Stand-in ids, so a damage split that only involves them can be answered for the player. */
    private static final java.util.Set<Integer> standInIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The damage the player already said this attacker deals to them, when its
     * only blockers are our stand-ins — so the engine's "assign damage" question
     * is answered without asking twice. Null when the player has to decide.
     */
    static Integer wantedThrough(final int attackerId, final java.util.List<Integer> blockerIds) {
        Integer n = wanted.get(attackerId);
        if (n == null) {
            return null;
        }
        for (Integer id : blockerIds) {
            if (!standInIds.contains(id)) {
                return null;
            }
        }
        return n;
    }

    /**
     * A blocker that is only there to absorb damage: no power, so our attacker
     * takes nothing from it (whether it did at the table is the player's to
     * declare), indestructible, so the engine never decides that their
     * creature died — that happened, or did not, on the table — and reach, so
     * the block is legal whatever it is blocking. Its toughness is the damage
     * it has to soak up.
     */
    private Card standIn(final Player them, final int soak) {
        List<String> script = new ArrayList<>();
        script.add("Name:Their blocker");
        script.add("Types:Creature");
        script.add("PT:0/" + Math.max(1, soak));
        script.add("K:Reach");
        script.add("K:Indestructible");
        script.add("Oracle:A creature on the table, standing in for this combat.");
        PaperCard pc = new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
        Card c = CardFactory.getCard(pc, them, game);
        c.setSickness(false);
        them.getZone(ZoneType.Battlefield).add(c);
        standInIds.add(c.getId());
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
            // What the engine knows is the damage dealt; whether their
            // creature survived it is theirs to say, at the table.
            if (standIn.getDamage() > 0) {
                lines.add(attacker.getName() + " dealt " + standIn.getDamage() + " to their blocker.");
            }
            // The stand-in goes, wherever combat left it. A survivor's real card
            // is still on the table; a dead one's goes to a graveyard made of
            // cardboard. Either way the engine has no use for it after this.
            if (standIn.getZone() != null) {
                standIn.getZone().remove(standIn);
            }
        }
        standIns.clear();
        described.clear();
        wanted.clear();
        standInIds.clear();
        if (!lines.isEmpty()) {
            gui.tell(lines);
        }
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
