package forge.game;

import java.util.Map;

import com.google.common.collect.Multiset;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.trackable.Tracker;

/**
 * Something the engine cannot see, that a spell may still be aimed at.
 *
 * <p>Forge assumes it owns every seat. Into Play runs at a kitchen table where
 * it owns one: the other players' cards are cardboard on the table, and no
 * amount of modelling will put them in the engine's state. A spell aimed at one
 * of them has no legal target, so the engine refuses the cast — which is the
 * rule it exists to enforce, and exactly the rule that has to bend.
 *
 * <p>A phantom is the bend. It is a {@link GameEntity} that is a legal target
 * for anything, holds no characteristics, and absorbs whatever lands on it
 * without touching the game state. "Bolt that creature" becomes: the spell is
 * cast legally, it resolves, three damage goes nowhere the engine knows about,
 * and the player tells their opponent out loud. The engine's job was to let the
 * spell happen; the table's job is the rest.
 *
 * <h2>Why this needs no changes to Forge's own code</h2>
 *
 * Target legality funnels through {@code SpellAbility.canTarget()}, and every
 * check in it is one of two kinds. The card-specific ones — shared types,
 * controller restrictions, the zone check — are each guarded by
 * {@code instanceof Card}, so a phantom skips them by not being a Card. What
 * remains are two virtual calls, {@link #isValid} and {@link #canBeTargetedBy},
 * both {@code default} methods on the {@link GameObject} interface and so
 * overridable here.
 *
 * <p>That is the whole mechanism. This file is additive, and the fork carries
 * no patch to a file upstream also edits — which matters, because upstream
 * churns hardest in exactly that targeting code.
 *
 * <h2>What a phantom cannot do</h2>
 *
 * It answers every question with "yes" or "nothing", so effects that push
 * outward work and effects that read back do not. Destroy it, tap it, damage
 * it, counter it: fine, the effect resolves and the phantom swallows it. But
 * "gain life equal to its power" has no power to read, and "exile it and return
 * it at end of turn" has nothing to give back. Those are not bugs to fix here —
 * the information genuinely is not in the room. They are prompts: the UI has to
 * ask the player what the card was.
 *
 * <p>There is one more consequence worth stating plainly. Effects reaching a
 * phantom through {@code TargetChoices.getTargetCards()}, which is
 * {@code filter(targets, Card.class)}, are silently dropped rather than run.
 * That is the right behaviour — nothing is corrupted — but silence is the wrong
 * report, so the bridge hooks that filter to surface "this was aimed off-table"
 * instead of letting it vanish.
 */
public class PhantomEntity extends GameEntity {

    /** What the player said it was, for the log and the prompt. Never parsed. */
    private final String described;
    private final Game game;
    private final PhantomEntityView view;

    public PhantomEntity(final Game game0, final int id0, final String described0) {
        super(id0);
        game = game0;
        described = described0 == null ? "something on the other side" : described0;
        // The view has to exist first: GameEntity.setName() writes through to
        // getView().updateName(), so naming before the view is assigned is an
        // NPE inside the superclass.
        view = new PhantomEntityView(id0, game0.getTracker());
        setName(described);
    }

    public String getDescribed() {
        return described;
    }

    // ---------------------------------------------------------------------
    // The two that make it targetable. Everything else below is inertia.
    // ---------------------------------------------------------------------

    /**
     * Legal for every restriction. A phantom stands in for a card nobody here
     * can see, so there is no honest way to answer "is it a red creature?" —
     * and answering "no" would make the spell uncastable, which is the problem
     * this class exists to solve. Yes is the only answer that keeps the game
     * moving, and the player across the table is the one who actually checks.
     */
    @Override
    public boolean isValid(final String restriction, final Player sourceController,
                           final Card source, final CardTraitBase spellAbility) {
        return true;
    }

    @Override
    public boolean hasProperty(final String property, final Player sourceController,
                               final Card source, final CardTraitBase spellAbility) {
        return true;
    }

    @Override
    public boolean canBeTargetedBy(final SpellAbility sa) {
        return true;
    }

    // ---------------------------------------------------------------------
    // GameEntity's abstract surface, answered inertly: a phantom absorbs and
    // reports nothing. Damage is taken and discarded; counters are accepted
    // and forgotten. None of it may touch real game state, because the object
    // it stands for is not in the game.
    // ---------------------------------------------------------------------

    @Override
    public int addDamageAfterPrevention(final int damage, final Card source, final SpellAbility cause,
                                        final boolean isCombat, final GameEntityCounterTable counterTable) {
        return 0; // dealt, as far as the engine is concerned; the table does the rest
    }

    @Override
    public int staticReplaceDamage(final int damage, final Card source, final boolean isCombat) {
        return damage; // nothing here replaces anything
    }

    @Override
    public boolean hasKeyword(final String keyword) {
        return false;
    }

    @Override
    public boolean hasKeyword(final Keyword keyword) {
        return false;
    }

    @Override
    public void setCounters(final Multiset<CounterType> allCounters) {
    }

    @Override
    public boolean canRemoveCounters(final CounterType type) {
        return false;
    }

    @Override
    public boolean canReceiveCounters(final CounterType type) {
        return true; // accepted, then forgotten — refusing would fizzle the spell
    }

    @Override
    public int subtractCounter(final CounterType counterName, final int n, final Player remover) {
        return 0;
    }

    @Override
    public void clearCounters() {
    }

    @Override
    public void addCounterInternal(final CounterType counterType, final int n, final Player source,
                                   final boolean fireEvents, final GameEntityCounterTable table,
                                   final Map<AbilityKey, Object> params) {
    }

    @Override
    public Game getGame() {
        return game;
    }

    @Override
    public GameEntityView getView() {
        return view;
    }

    @Override
    public String toString() {
        return described + " (off-table)";
    }

    /** Minimal view so anything asking for one gets a name rather than a null. */
    public static class PhantomEntityView extends GameEntityView {
        private static final long serialVersionUID = 1L;

        public PhantomEntityView(final int id0, final Tracker tracker) {
            super(id0, tracker);
        }
    }
}
