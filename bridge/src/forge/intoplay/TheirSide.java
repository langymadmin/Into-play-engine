package forge.intoplay;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.event.GameEvent;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the engine is told about the other side of the table — only as much as
 * our own cards need, and only when they need it.
 *
 * <h2>Their graveyard, with real cards</h2>
 * The opponent's list (pasted, Moxfield, an archetype's average list) lives
 * on the client — never in an engine zone, not even their sideboard, which has
 * its own meaning in the rules. When something of theirs is in their
 * graveyard at the table and our cards care, the player puts that card into
 * their graveyard here, by name, before casting. Then it is an ordinary
 * graveyard card: Animate Dead targets it and returns the real card with its
 * real triggers; Unearth ("your graveyard") cannot, by the engine's own rule;
 * Tarmogoyf counts its real types.
 *
 * <h2>The table, defined just in time</h2>
 * Tarmogoyf counts card types in all graveyards; Beast of Burden counts every
 * creature on the battlefield. The engine sees half of each. When one of our
 * spells whose script counts the other side is cast, the player is asked once,
 * and the answer stays on the table until changed:
 * <ul>
 *   <li>graveyard: real cards as above, or — without a list — one blank card
 *       per card type, with shroud, so nothing takes it;</li>
 *   <li>creatures: blank 0/1s with shroud, indestructible, unable to block — so
 *       our removal cannot "kill" them and combat ignores them. A deliberate
 *       approximation: they count, and they do nothing else.</li>
 * </ul>
 */final class TheirSide {

    private final Game game;
    private final BridgeGui gui;
    private final String offTable;
    private final String seat;

    private final List<Card> graveyardMarks = new ArrayList<>();
    private final List<Card> creatureMarks = new ArrayList<>();
    private final Set<String> graveyardTypes = new LinkedHashSet<>();

    static volatile TheirSide current;

    TheirSide(final Game game0, final BridgeGui gui0, final String seat0, final String offTable0) {
        game = game0;
        gui = gui0;
        seat = seat0;
        offTable = offTable0;
        current = this;
    }

    private Player them() {
        for (Player p : game.getPlayers()) {
            if (offTable.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private Player me() {
        for (Player p : game.getPlayers()) {
            if (seat.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Their graveyard, with real cards
    // ------------------------------------------------------------------

    /** Real cards of theirs put into their graveyard by the player. */
    private final List<Card> graveyardCards = new ArrayList<>();

    /**
     * Put a real card of theirs into their graveyard, by name — quietly, since
     * it is already there at the table. The name comes from the opponent list
     * the client keeps (or a search, for a card not on it); the list itself
     * never enters the engine. From here the card is an ordinary graveyard
     * card: Animate Dead can target it and bring back the real thing with its
     * real triggers, and Tarmogoyf counts its real types.
     */
    Card putInGraveyard(final String name) {
        Player them = them();
        if (them == null || name == null) {
            return null;
        }
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name.trim());
        if (pc == null && name.contains("//")) {
            pc = StaticData.instance().getCommonCards().getCard(name.substring(0, name.indexOf("//")).trim());
        }
        if (pc == null) {
            return null;
        }
        Card c = CardFactory.getCard(pc, them, game);
        them.getZone(ZoneType.Graveyard).add(c);
        graveyardCards.add(c);
        return c;
    }

    /** Take one back out (it left their graveyard at the table). */
    void removeFromGraveyard(final int cardId) {
        Player them = them();
        if (them == null) {
            return;
        }
        for (Card c : new ArrayList<>(them.getCardsIn(ZoneType.Graveyard))) {
            if (c.getId() == cardId) {
                them.getZone(ZoneType.Graveyard).remove(c);
                graveyardCards.remove(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // Their cards that change the rules
    // ------------------------------------------------------------------

    /**
     * A real permanent of theirs on their battlefield: Blood Moon, Rest in
     * Peace, Chalice of the Void, Thalia, Elesh Norn. These change the rules
     * for our cards, so the engine has to have the card itself — a description
     * would not make Blood Moon turn our nonbasics into Mountains.
     *
     * <p>Put there quietly (it is already in play at the table), so its own
     * enters triggers do not run again. It is a real permanent from then on: our
     * Disenchant can take it, our Wrath kills their Elesh Norn. The off-table
     * seat's board is still never drawn; the client lists these in "Their
     * table" and on the "Their table…" button.
     */
    Card putOnBattlefield(final String name) {
        Player them = them();
        PaperCard pc = paper(name);
        if (them == null || pc == null) {
            return null;
        }
        Card c = CardFactory.getCard(pc, them, game);
        c.setSickness(false);
        c.setGameTimestamp(game.getNextTimestamp());
        them.getZone(ZoneType.Battlefield).add(c);
        game.getAction().checkStaticAbilities();
        return c;
    }

    /** It left play at the table. Quietly, as it came. */
    void removeFromBattlefield(final int cardId) {
        Player them = them();
        if (them == null) {
            return;
        }
        for (Card c : new ArrayList<>(them.getCardsIn(ZoneType.Battlefield))) {
            if (c.getId() == cardId && !creatureMarks.contains(c)) {
                them.getZone(ZoneType.Battlefield).remove(c);
            }
        }
        game.getAction().checkStaticAbilities();
    }

    private static PaperCard paper(final String name) {
        if (name == null) {
            return null;
        }
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name.trim());
        if (pc == null && name.contains("//")) {
            pc = StaticData.instance().getCommonCards().getCard(name.substring(0, name.indexOf("//")).trim());
        }
        return pc;
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    static final List<String> TYPES = List.of(
            "Creature", "Land", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Battle", "Kindred");

    void setGraveyardTypes(final Set<String> types) {
        Player them = them();
        if (them == null) {
            return;
        }
        for (Card c : graveyardMarks) {
            if (c.getZone() != null) {
                c.getZone().remove(c);
            }
        }
        graveyardMarks.clear();
        graveyardTypes.clear();
        for (String t : types) {
            if (!TYPES.contains(t)) {
                continue;
            }
            List<String> script = new ArrayList<>();
            script.add("Name:Their " + t.toLowerCase() + " card");
            script.add("Types:" + t);
            if ("Creature".equals(t)) {
                script.add("PT:0/0");
            } else if ("Planeswalker".equals(t)) {
                script.add("Loyalty:1");
            } else if ("Battle".equals(t)) {
                script.add("Defense:1");
            }
            script.add("K:Shroud");
            script.add("Oracle:A card in their graveyard, on the table.");
            Card c = blank(them, script);
            them.getZone(ZoneType.Graveyard).add(c);
            graveyardMarks.add(c);
            graveyardTypes.add(t);
        }
    }

    void setCreatures(final int n) {
        Player them = them();
        if (them == null) {
            return;
        }
        while (creatureMarks.size() > Math.max(0, n)) {
            Card c = creatureMarks.remove(creatureMarks.size() - 1);
            if (c.getZone() != null) {
                c.getZone().remove(c);
            }
        }
        while (creatureMarks.size() < n) {
            Card c = blank(them, List.of(
                    "Name:Their creature on the table",
                    "Types:Creature",
                    "PT:0/1",
                    "K:Shroud",
                    "K:Indestructible",
                    "K:CARDNAME can't block.",
                    "Oracle:A creature on the table, counted and nothing else."));
            c.setSickness(false);
            them.getZone(ZoneType.Battlefield).add(c);
            creatureMarks.add(c);
        }
    }

    private Card blank(final Player owner, final List<String> script) {
        PaperCard pc = new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
        return CardFactory.getCard(pc, owner, game);
    }

    /** The definitions in force, for the board message. */
    JsonObject describe() {
        JsonObject o = new JsonObject();
        JsonArray gt = new JsonArray();
        graveyardTypes.forEach(gt::add);
        o.add("graveyardTypes", gt);
        o.addProperty("creatures", creatureMarks.size());
        // Their graveyard as the engine has it: real cards put there by the
        // player, and whatever ended up there in play (a creature of theirs we
        // killed via a declaration, a spell resolved at them…). Placeholders
        // are left out — they are described by graveyardTypes.
        JsonArray gy = new JsonArray();
        Player them = them();
        if (them != null) {
            for (Card c : them.getCardsIn(ZoneType.Graveyard)) {
                if (graveyardMarks.contains(c)) {
                    continue;
                }
                JsonObject j = new JsonObject();
                j.addProperty("id", c.getId());
                j.addProperty("name", c.getName());
                gy.add(j);
            }
        }
        o.add("graveyard", gy);
        // Their real permanents (rule-changers put there by the player, a
        // creature stolen back…), without the counted placeholders.
        JsonArray bf = new JsonArray();
        if (them != null) {
            for (Card c : them.getCardsIn(ZoneType.Battlefield)) {
                if (creatureMarks.contains(c)) {
                    continue;
                }
                JsonObject j = new JsonObject();
                j.addProperty("id", c.getId());
                j.addProperty("name", c.getName());
                bf.add(j);
            }
        }
        o.add("battlefield", bf);
        return o;
    }

    /**
     * Apply {graveyardTypes:[…], creatures:n, addToGraveyard:[name…],
     * removeFromGraveyard:[id…]} — from the panel, or from the question at cast.
     */
    void apply(final JsonObject in) {
        if (in.has("removeFromGraveyard")) {
            for (JsonElement e : in.getAsJsonArray("removeFromGraveyard")) {
                removeFromGraveyard(e.getAsInt());
            }
        }
        if (in.has("addToGraveyard")) {
            for (JsonElement e : in.getAsJsonArray("addToGraveyard")) {
                putInGraveyard(e.getAsString());
            }
        }
        if (in.has("removeFromBattlefield")) {
            for (JsonElement e : in.getAsJsonArray("removeFromBattlefield")) {
                removeFromBattlefield(e.getAsInt());
            }
        }
        if (in.has("addToBattlefield")) {
            for (JsonElement e : in.getAsJsonArray("addToBattlefield")) {
                if (putOnBattlefield(e.getAsString()) == null) {
                    gui.tell(List.of("The engine doesn't know a card called \"" + e.getAsString() + "\"."));
                }
            }
        }
        if (in.has("graveyardTypes")) {
            Set<String> types = new LinkedHashSet<>();
            for (JsonElement e : in.getAsJsonArray("graveyardTypes")) {
                types.add(e.getAsString());
            }
            setGraveyardTypes(types);
        }
        if (in.has("creatures")) {
            setCreatures(in.get("creatures").getAsInt());
        }
    }

    // ------------------------------------------------------------------
    // Asking when it matters
    // ------------------------------------------------------------------

    /**
     * What a card's script counts on the other side of the table — read from
     * its own Count$ variables, so no list of cards is kept by hand:
     * "graveyard" for counts over all graveyards (Tarmogoyf), "creatures" for
     * counts over every creature or theirs (Beast of Burden). A count limited to
     * what we own or control is ours, and needs nothing.
     */
    static Set<String> needs(final Card c) {
        Set<String> out = new LinkedHashSet<>();
        for (java.util.Map.Entry<String, String> sv : c.getSVars().entrySet()) {
            String v = sv.getValue();
            if (v == null || !v.startsWith("Count$")) {
                continue;
            }
            boolean ours = v.contains("YouOwn") || v.contains("YouCtrl") || v.contains("Your") || v.contains("ValidYour");
            if (ours) {
                continue;
            }
            if (v.contains("Graveyard")) {
                out.add("graveyard");
            }
            if (v.startsWith("Count$Valid ") && v.contains("Creature")) {
                out.add("creatures");
            }
        }
        return out;
    }

    @Subscribe
    public void receiveGameEvent(final GameEvent e) {
        // Synchronous, on the game thread, while the spell is on the stack —
        // before it enters, so its first number is already right.
        if (!(e instanceof GameEventSpellAbilityCast cast) || cast.sa() == null || cast.sa().getHostCard() == null) {
            return;
        }
        int hostId = cast.sa().getHostCard().getId();
        Card host = null;
        for (Card c : game.getCardsIn(ZoneType.Stack)) {
            if (c.getId() == hostId) {
                host = c;
            }
        }
        Player me = me();
        // A spell of ours, on its way in — not an ability of a permanent.
        if (host == null || me == null || host.getController() != me) {
            return;
        }
        Set<String> needs = needs(host);
        if (needs.isEmpty()) {
            return;
        }
        JsonArray answer = gui.askTable(host.getName(), needs, describe());
        if (answer.size() > 0 && answer.get(0).isJsonObject()) {
            apply(answer.get(0).getAsJsonObject());
            gui.sendBoard();
        }
    }
}
