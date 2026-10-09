package forge.intoplay;

import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * "Take that back" — the kitchen-table undo, for casual games.
 *
 * <p>Forge's own undo only reaches back as far as mana taps, which is the
 * right rule for a tournament and the wrong one for testing a deck at a table,
 * where "wait, I meant the other land" is normal. This keeps a short history
 * of the game, one entry per thing that happened, and puts the game back to
 * the entry before the last.
 *
 * <p>Taken with Forge's own {@link GameSnapshot} (the full copy its
 * experimental restore uses), at the one safe moment there is: when our seat is
 * about to be asked what to do with priority — nothing half-cast, nothing
 * half-resolved. Consecutive entries with the same game in them (passing from
 * one step to the next) are merged, keeping the later step, so one take-back
 * undoes one action rather than one priority pass.
 *
 * <p>What it does not do: hidden information stays as it was — the library
 * keeps its order, so taking back a draw and drawing again draws the same
 * card. Their cardboard is theirs to take back at the table.
 */
final class Rewind {

    private static final int KEEP = 30;

    private static final class Entry {
        final GameSnapshot snapshot;
        final String signature;
        /** The stack instances in it — see {@link #back}. */
        final java.util.Set<Integer> stackIds;
        /** Who was about to get priority — see {@link #recover}. */
        final String priority;

        Entry(final GameSnapshot snapshot0, final String signature0, final java.util.Set<Integer> stackIds0, final String priority0) {
            snapshot = snapshot0;
            signature = signature0;
            stackIds = stackIds0;
            priority = priority0;
        }
    }

    private static final Deque<Entry> history = new ArrayDeque<>();
    private static Game forGame;

    private Rewind() { }

    /** On the game thread, as our seat is given priority. */
    static synchronized void remember(final Game game) {
        if (game != forGame) {
            history.clear();
            forGame = game;
        }
        try {
            String sig = signature(game);
            GameSnapshot snap = new GameSnapshot(game);
            snap.makeCopy();
            Entry top = history.peekLast();
            if (top != null && top.signature.equals(sig)) {
                history.pollLast(); // same game, later step: keep the later one
            }
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (SpellAbilityStackInstance si : game.getStack()) {
                ids.add(si.getId());
            }
            Player pr = game.getPhaseHandler().getPriorityPlayer();
            history.addLast(new Entry(snap, sig, ids, pr == null ? null : pr.getName()));
            while (history.size() > KEEP) {
                history.pollFirst();
            }
        } catch (RuntimeException e) {
            // Experimental in Forge; a snapshot that fails costs one undo step,
            // never the game.
            System.out.println("rewind: snapshot failed: " + e);
        }
    }

    /** How many steps can be taken back. */
    static synchronized int available(final Game game) {
        return game == forGame ? Math.max(0, history.size() - 1) : 0;
    }

    /**
     * Put the game back one entry. The newest entry is now; the one before it
     * is restored and becomes now.
     */
    static synchronized boolean back(final Game game) {
        if (game != forGame || history.size() < 2) {
            return false;
        }
        history.pollLast();
        Entry target = history.peekLast();
        target.snapshot.restoreGameState(game);
        // Forge's restore adds what the stack is missing but never removes
        // what it has extra — it was written to roll back a cast in progress.
        // Taking back a spell that had reached the stack needs it gone.
        java.util.List<SpellAbilityStackInstance> now = new java.util.ArrayList<>();
        game.getStack().forEach(now::add);
        for (SpellAbilityStackInstance si : now) {
            if (!target.stackIds.contains(si.getId())) {
                game.getStack().remove(si);
            }
        }
        fixCombat(game.getPhaseHandler());
        return true;
    }

    /**
     * The engine threw in the middle of the game loop (a rules corner Forge
     * gets wrong). Rather than end the game, put it back to the last moment
     * remembered — someone about to get priority, nothing half-done — and
     * hand priority back exactly there, so {@code mainGameLoop} can carry on.
     * Each call goes one entry further back, so a moment that throws again is
     * left behind. Returns the step it went back to, or null if there is none.
     */
    static synchronized String recover(final Game game) {
        if (game != forGame || history.isEmpty()) {
            return null;
        }
        Entry target = history.pollLast();
        try {
            target.snapshot.restoreGameState(game);
            java.util.List<SpellAbilityStackInstance> now = new java.util.ArrayList<>();
            game.getStack().forEach(now::add);
            for (SpellAbilityStackInstance si : now) {
                if (!target.stackIds.contains(si.getId())) {
                    game.getStack().remove(si);
                }
            }
            forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
            fixCombat(ph);
            Player who = null;
            for (Player p : game.getPlayers()) {
                if (p.getName().equals(target.priority)) {
                    who = p;
                }
            }
            ph.setPriority(who != null ? who : ph.getPlayerTurn());
            java.lang.reflect.Field f = forge.game.phase.PhaseHandler.class.getDeclaredField("givePriorityToPlayer");
            f.setAccessible(true);
            f.setBoolean(ph, true);
            game.getAction().checkStateEffects(true);
            return ph.getPhase() == null ? "the last step" : ph.getPhase().nameForUi;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("rewind: recovery failed: " + e);
            return null;
        }
    }

    /**
     * Forge's restore ends whatever combat there was and copies back only a
     * combat the snapshot had. Put back in a combat step without one, the next
     * step (declare attackers) reached for it and the game stopped. A combat
     * step always has one: an empty one is right.
     */
    private static void fixCombat(final forge.game.phase.PhaseHandler ph) {
        if (ph.getCombat() == null && ph.getPhase() != null && ph.getPlayerTurn() != null
                && java.util.EnumSet.of(forge.game.phase.PhaseType.COMBAT_BEGIN, forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS,
                        forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS, forge.game.phase.PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
                        forge.game.phase.PhaseType.COMBAT_DAMAGE, forge.game.phase.PhaseType.COMBAT_END).contains(ph.getPhase())) {
            ph.setCombat(new forge.game.combat.Combat(ph.getPlayerTurn()));
        }
    }

    /** What counts as "something happened": everything but the step. */
    private static String signature(final Game game) {
        StringBuilder b = new StringBuilder();
        b.append(game.getPhaseHandler().getTurn()).append('|');
        for (Player p : game.getPlayers()) {
            b.append(p.getName()).append(':').append(p.getLife()).append(':')
                    .append(p.getCounters()).append(':').append(p.getManaPool().totalMana()).append(':')
                    .append(p.getLandsPlayedThisTurn()).append('|');
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
                b.append(z.name().charAt(0));
                for (Card c : p.getCardsIn(z)) {
                    b.append(c.getId());
                    if (z == ZoneType.Battlefield) {
                        b.append(c.isTapped() ? 't' : 'u').append(c.getCounters()).append(c.getDamage());
                    }
                    b.append(',');
                }
            }
        }
        for (SpellAbilityStackInstance si : game.getStack()) {
            b.append('s').append(si.getSourceCard() == null ? 0 : si.getSourceCard().getId());
        }
        return b.toString();
    }
}
