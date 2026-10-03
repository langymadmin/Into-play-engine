import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.PhantomEntity;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;

import java.util.List;

/**
 * The question this fork exists to answer: will Forge let a spell be aimed at
 * something it cannot see?
 *
 * Builds a Lightning Bolt from script text, puts a phantom on the other side of
 * the table, and asks the engine — not by reading canTarget() and reasoning
 * about it, but by calling it. Reading and running have disagreed three times
 * already on this project.
 *
 * Checks, in order of how much they matter:
 *
 *   1. canTarget()      — does the engine consider a phantom a legal target?
 *   2. a real Card      — does a normal creature still work, i.e. have we
 *                         broken targeting for everything else?
 *   3. a wrong target   — does an illegal target still get refused, i.e. is
 *                         the phantom a hole for itself only?
 *   4. resolution       — does putting a phantom in the targets and resolving
 *                         the spell leave the game intact?
 */
public final class PhantomSpike {

    static int pass = 0, fail = 0;

    static void check(String what, boolean got, boolean want) {
        boolean ok = got == want;
        if (ok) pass++; else fail++;
        System.out.printf("  %s  %-58s got %-5s want %s%n",
                ok ? "PASS" : "FAIL", what, got, want);
    }

    static Card make(Game game, Player owner, String name, String... lines) {
        List<String> script = new java.util.ArrayList<>(List.of("Name:" + name));
        script.addAll(List.of(lines));
        PaperCard pc = new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
        return CardFactory.getCard(pc, owner, game);
    }

    public static void main(String[] args) {
        String res = args.length > 0 ? args[0] : "forge-gui/res";
        forge.gui.GuiBase.setInterface(Spike.headlessGuiBase(res));
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "/languages/");

        List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(Spike.lobbyPlayer("Into Play")),
                new RegisteredPlayer(new Deck()).setPlayer(Spike.lobbyPlayer("Across the table")));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "PhantomSpike"));
        Player me = game.getPlayers().get(0);

        // A real Lightning Bolt, from the same script syntax Forge's own 34,000
        // card files use: three damage to any target.
        Card bolt = make(game, me, "Lightning Bolt",
                "ManaCost:R",
                "Types:Instant",
                "A:SP$ DealDamage | ValidTgts$ Any | TgtPrompt$ Select any target | NumDmg$ 3",
                "Oracle:Lightning Bolt deals 3 damage to any target.");
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(me);

        // A real creature, as the control: if this stops being targetable we
        // have broken the engine rather than extended it.
        Card bear = make(game, me, "Grizzly Bears",
                "ManaCost:1 G", "Types:Creature Bear", "PT:2/2", "Oracle:");
        game.getAction().moveTo(ZoneType.Battlefield, bear, null, null);

        GameEntity phantom = new PhantomEntity(game, 9001, "their 2/2 on the table");

        System.out.println("=== can a spell be aimed at a phantom? ===");
        check("Lightning Bolt can target a phantom",        sa.canTarget(phantom), true);
        check("...and still target a real creature",        sa.canTarget(bear),    true);
        check("...and still target a player",               sa.canTarget(me),      true);

        // The phantom must not make the engine permissive in general. A spell
        // that may only hit creatures should still refuse a player.
        Card shock = make(game, me, "Creature Only",
                "ManaCost:R", "Types:Instant",
                "A:SP$ DealDamage | ValidTgts$ Creature | TgtPrompt$ Select a creature | NumDmg$ 2",
                "Oracle:");
        SpellAbility creatureOnly = shock.getFirstSpellAbility();
        creatureOnly.setActivatingPlayer(me);
        System.out.println();
        System.out.println("=== is the hole only the phantom's? ===");
        check("creature-only spell refuses a player",       creatureOnly.canTarget(me),      false);
        check("creature-only spell accepts a creature",     creatureOnly.canTarget(bear),    true);
        check("creature-only spell accepts a phantom",      creatureOnly.canTarget(phantom), true);

        System.out.println();
        System.out.println("=== does resolving onto a phantom leave the game intact? ===");
        int lifeBefore = me.getLife();
        boolean resolved;
        String how = "";
        try {
            sa.getTargets().add(phantom);
            check("phantom is in the spell's targets",
                    sa.getTargets().contains(phantom), true);
            // getTargetCards() filters to Card.class, so a phantom is dropped
            // here. That is the interception point the bridge will hook; for
            // now the point is only that nothing explodes.
            check("getTargetCards() drops it (hence the hook)",
                    sa.getTargets().getTargetCards().isEmpty(), true);
            sa.resolve();
            resolved = true;
        } catch (Throwable t) {
            resolved = false;
            how = t.toString();
        }
        check("resolving the spell does not throw", resolved, true);
        if (!how.isEmpty()) System.out.println("       threw: " + how);
        check("nobody's life changed", me.getLife() == lifeBefore, true);
        check("the real creature is untouched",
                bear.isInZone(ZoneType.Battlefield) && bear.getNetToughness() == 2, true);
        check("the game is not over", game.isGameOver(), false);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
