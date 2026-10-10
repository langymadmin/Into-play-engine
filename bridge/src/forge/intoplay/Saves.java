package forge.intoplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.Game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The game in progress, on disk — so a crash, a reboot or a dropped network
 * does not cost the match. "Resume the last game" deals it again from here.
 *
 * <p>Two files in bridge/saves: how the game was started (the decks, the names,
 * one player or two — the very messages the screens sent) and Forge's own
 * {@link forge.game.GameState} text, the format of the test scenarios, written
 * at every moment the game is remembered for a take-back (someone about to
 * get priority, nothing half-done). A game that ends normally deletes them.
 *
 * <p>What comes back is the board, the hands, libraries, graveyards, exile,
 * the command zone, life, counters, the turn and the step. What does not:
 * anything on the stack, "until end of turn" effects, and the take-back
 * history — it starts again from the resumed moment.
 */
final class Saves {

    private Saves() { }

    // One folder per port: a test engine beside the real one never touches
    // the real one's save.
    private static Path DIR = Paths.get("bridge", "saves", "8099");
    static Path STATE = DIR.resolve("last-state.txt");
    private static Path SETUP = DIR.resolve("last-setup.json");

    /** At startup: this engine's port. */
    static void forPort(final int port) {
        DIR = Paths.get("bridge", "saves", String.valueOf(port));
        STATE = DIR.resolve("last-state.txt");
        SETUP = DIR.resolve("last-setup.json");
    }

    /** Being dealt from a save: no coin toss, no mulligans (BridgeMain). */
    static volatile boolean resuming;

    private static Game savingFor;

    /** A game against the table begins: its start message. */
    static synchronized void begin(final Game game, final JsonObject setup) {
        JsonObject o = new JsonObject();
        o.addProperty("versus", false);
        o.add("setup", strip(setup));
        write(game, o);
    }

    /** A two-player game begins: each player's name and start message. */
    static synchronized void beginVersus(final Game game, final String nameA, final JsonObject a,
                                         final String nameB, final JsonObject b, final String first) {
        JsonObject o = new JsonObject();
        o.addProperty("versus", true);
        o.addProperty("nameA", nameA);
        o.addProperty("nameB", nameB);
        o.add("a", strip(a));
        o.add("b", strip(b));
        write(game, o);
    }

    private static JsonObject strip(final JsonObject setup) {
        JsonObject s = setup == null ? new JsonObject() : setup.deepCopy();
        s.remove("resume");
        s.remove("code");
        return s;
    }

    private static void write(final Game game, final JsonObject o) {
        // A resumed game keeps its save until it writes its own first state.
        savingFor = game;
        o.addProperty("at", System.currentTimeMillis());
        try {
            Files.createDirectories(DIR);
            Files.writeString(SETUP, o.toString(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            System.out.println("save: " + e);
        }
    }

    /** On the game thread, when Rewind remembers a new moment. */
    static synchronized void snapshot(final Game game) {
        if (game != savingFor || game.isGameOver() || game.getPhaseHandler().getPhase() == null) {
            return;
        }
        try {
            forge.game.GameState gs = new forge.game.GameState();
            gs.initFromGame(game);
            String text = gs.toString();
            Path tmp = DIR.resolve("last-state.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, STATE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // The save now says which turn it is, for the offer on the screen.
            JsonObject o = setup();
            if (o != null) {
                o.addProperty("turn", game.getPhaseHandler().getTurn());
                o.addProperty("at", System.currentTimeMillis());
                Files.writeString(SETUP, o.toString(), StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException | RuntimeException e) {
            // Saving is a convenience: never the game's problem.
            System.out.println("save: snapshot failed: " + e);
        }
    }

    /** The game being saved is about to be ended by the table, not finished: keep its save. */
    private static Game keepGame;

    static synchronized void keepNext() {
        keepGame = savingFor;
    }

    /** The game ended (someone won, or it was conceded): nothing to resume. */
    static synchronized void over(final Game game) {
        if (game != savingFor) {
            return;
        }
        if (game == keepGame) {
            keepGame = null;
            savingFor = null;
            return;
        }
        savingFor = null;
        try {
            Files.deleteIfExists(STATE);
            Files.deleteIfExists(SETUP);
        } catch (java.io.IOException e) {
            System.out.println("save: " + e);
        }
    }

    /** The saved start, or null when there is no game to resume. */
    static synchronized JsonObject setup() {
        try {
            if (!Files.exists(STATE) || !Files.exists(SETUP)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(SETUP, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** What the screen shows about it: {versus, names, turn, at}, or null. */
    static JsonObject offer() {
        JsonObject s = setup();
        if (s == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        boolean versus = s.has("versus") && s.get("versus").getAsBoolean();
        o.addProperty("versus", versus);
        if (versus) {
            o.addProperty("vs", s.get("nameA").getAsString() + " vs " + s.get("nameB").getAsString());
        } else {
            JsonObject st = s.getAsJsonObject("setup");
            if (st != null && st.has("deckName")) {
                o.addProperty("deck", st.get("deckName").getAsString());
            }
        }
        if (s.has("turn")) {
            o.addProperty("turn", s.get("turn").getAsInt());
        }
        if (s.has("at")) {
            o.addProperty("at", s.get("at").getAsLong());
        }
        return o;
    }
}
