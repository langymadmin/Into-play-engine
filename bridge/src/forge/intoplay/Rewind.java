package forge.intoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * "Take that back" — the kitchen-table undo, for casual games.
 *
 * <p>Forge's own undo only reaches back as far as mana taps, which is the
 * right rule for a tournament and the wrong one for testing a deck at a table,
 * where "wait, I meant the other land" is normal. This keeps a short history
 * of the game — one moment per step in which something could be done — and
 * puts the game back to the moment the player picks.
 *
 * <p>Taken with Forge's own {@link GameSnapshot} (the full copy its
 * experimental restore uses), at the one safe moment there is: when a seat is
 * about to be asked what to do with priority — nothing half-cast, nothing
 * half-resolved. Being asked again in the same step with nothing changed
 * (priority going round) is the same moment; a new step, or anything done,
 * is a new one. So "pass the turn" can be taken back too.
 *
 * <p>Going back is not done underneath the game while it waits for someone
 * (in a two-player game that was the other player, and nothing visibly moved).
 * The waiting is ended instead ({@link Signal}), and {@code BridgeMain.playOn}
 * restores the moment and restarts the game loop there, with priority to
 * whoever had it then — the way a crash is recovered from.
 *
 * <p>What it does not do: hidden information stays as it was — the library
 * keeps its order, so taking back a draw and drawing again draws the same
 * card. Their cardboard is theirs to take back at the table.
 */
final class Rewind {

    private static final int KEEP = 40;

    private static final class Entry {
        final GameSnapshot snapshot;
        final String key;
        /** The stack instances in it — removed from the game on restore if not here. */
        final java.util.Set<Integer> stackIds;
        /** Who was about to get priority. */
        final String priority;
        final int turn;
        final String step;
        final String whose;
        /** What had just happened, as the game log says it. */
        final String after;

        Entry(final GameSnapshot snapshot0, final String key0, final java.util.Set<Integer> stackIds0, final String priority0,
              final int turn0, final String step0, final String whose0, final String after0) {
            snapshot = snapshot0;
            key = key0;
            stackIds = stackIds0;
            priority = priority0;
            turn = turn0;
            step = step0;
            whose = whose0;
            after = after0;
        }

        String label() {
            return "Turn " + turn + " · " + step + (whose == null ? "" : " (" + whose + "'s turn)");
        }
    }

    private static final List<Entry> history = new ArrayList<>();
    private static Game forGame;

    /** Thrown out of a seat's wait for priority to go back (see playOn). */
    static final class Signal extends RuntimeException {
        Signal() {
            super("going back", null, false, false);
        }
    }

    /** The moment asked for (an index into the history), and what to tell everyone. */
    private static Integer pending;
    private static String pendingSaid;
    /** Someone is waiting for priority right now — the only time going back can start. */
    static volatile boolean inPriority;
    /** Who that is. */
    static volatile String waitingSeat;

    private Rewind() { }

    /** On the game thread, as a seat is given priority. */
    static synchronized void remember(final Game game) {
        if (game != forGame) {
            history.clear();
            forGame = game;
            pending = null;
        }
        try {
            forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
            String step = ph.getPhase() == null ? "the start" : ph.getPhase().nameForUi;
            String key = signature(game) + "#" + ph.getPhase();
            Entry top = history.isEmpty() ? null : history.get(history.size() - 1);
            // The same moment, asked again (priority going round between the
            // players): nothing new to copy or save. A full copy of the game
            // at every pass was most of the engine's work in a two-player game.
            if (top != null && top.key.equals(key)) {
                return;
            }
            GameSnapshot snap = new GameSnapshot(game);
            snap.makeCopy();
            // On disk too, for "Resume the last game".
            Saves.snapshot(game);
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (SpellAbilityStackInstance si : game.getStack()) {
                ids.add(si.getId());
            }
            Player pr = ph.getPriorityPlayer();
            String after = "";
            try {
                List<forge.game.GameLogEntry> log = game.getGameLog().getLogEntries(null);
                // The newest thing DONE — not a step changing or mana floating.
                if (log != null) {
                    for (forge.game.GameLogEntry le : log) {
                        forge.game.GameLogEntryType ty = le.type();
                        if (ty != forge.game.GameLogEntryType.PHASE && ty != forge.game.GameLogEntryType.TURN
                                && ty != forge.game.GameLogEntryType.MANA && ty != forge.game.GameLogEntryType.INFORMATION) {
                            after = le.message();
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                after = "";
            }
            history.add(new Entry(snap, key, ids, pr == null ? null : pr.getName(), ph.getTurn(), step,
                    ph.getPlayerTurn() == null ? null : ph.getPlayerTurn().getName(), after));
            while (history.size() > KEEP) {
                history.remove(0);
            }
        } catch (RuntimeException e) {
            // Experimental in Forge; a snapshot that fails costs one moment,
            // never the game.
            System.out.println("rewind: snapshot failed: " + e);
        }
    }

    /** How many moments can be gone back to. */
    static synchronized int available(final Game game) {
        return game == forGame ? Math.max(0, history.size() - 1) : 0;
    }

    /** The moments to go back to, newest first (not the present one). */
    static synchronized JsonArray list(final Game game) {
        JsonArray a = new JsonArray();
        if (game != forGame) {
            return a;
        }
        for (int i = history.size() - 2; i >= 0; i--) {
            Entry e = history.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("i", i);
            o.addProperty("turn", e.turn);
            o.addProperty("step", e.step);
            if (e.whose != null) {
                o.addProperty("whose", e.whose);
            }
            // What was done since: the moment after this one says it.
            Entry next = history.get(i + 1);
            if (next.after != null && !next.after.isEmpty() && !next.after.equals(e.after)) {
                o.addProperty("undoes", next.after);
            }
            o.addProperty("label", e.label());
            a.add(o);
        }
        return a;
    }

    /** The moment just before the present one (plain "take that back"), or -1. */
    static synchronized int previous(final Game game) {
        return game == forGame && history.size() >= 2 ? history.size() - 2 : -1;
    }

    static synchronized String labelOf(final int i) {
        return i >= 0 && i < history.size() ? history.get(i).label() : null;
    }

    /** Ask to go back to moment i; the seat waiting for priority is released next. */
    static synchronized boolean request(final Game game, final int i, final String said) {
        if (game != forGame || i < 0 || i >= history.size() - 1) {
            return false;
        }
        pending = i;
        pendingSaid = said;
        return true;
    }

    static synchronized boolean isPending() {
        return pending != null;
    }

    /**
     * On the game thread, out of the game loop (playOn): put the game back to
     * the moment asked for and make it the present one. Returns what to tell
     * the players, or null if it could not be done.
     */
    static synchronized String goToPending(final Game game) {
        Integer i = pending;
        String said = pendingSaid;
        pending = null;
        pendingSaid = null;
        if (i == null || game != forGame || i >= history.size()) {
            return null;
        }
        Entry target = history.get(i);
        while (history.size() > i + 1) {
            history.remove(history.size() - 1);
        }
        return restore(game, target) ? (said != null ? said : "Back to " + target.label() + ".") : null;
    }

    /**
     * The engine threw in the middle of the game loop (a rules corner Forge
     * gets wrong). Rather than end the game, put it back to the last moment
     * remembered and carry on. Each call goes one moment further back, so a
     * moment that throws again is left behind. Returns the step it went back
     * to, or null if there is none.
     */
    static synchronized String recover(final Game game) {
        if (game != forGame || history.isEmpty()) {
            return null;
        }
        Entry target = history.remove(history.size() - 1);
        return restore(game, target) ? target.step : null;
    }

    /** Restore a moment and hand priority back exactly there, for mainGameLoop. */
    private static boolean restore(final Game game, final Entry target) {
        try {
            target.snapshot.restoreGameState(game);
            // Forge's restore adds what the stack is missing but never removes
            // what it has extra — it was written to roll back a cast in progress.
            List<SpellAbilityStackInstance> now = new ArrayList<>();
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
            game.updateStackForView();
            game.updateCombatForView();
            game.updatePhaseForView();
            game.updateTurnForView();
            game.updatePlayerTurnForView();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("rewind: restore failed: " + e);
            e.printStackTrace(System.out);
            return false;
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
