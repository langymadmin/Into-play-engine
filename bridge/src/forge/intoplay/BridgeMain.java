package forge.intoplay;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardDb;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.control.FControlGameEventHandler;
import forge.gui.interfaces.IGuiBase;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.util.Lang;
import forge.util.Localizer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts a real Forge game behind the bridge and waits for a client.
 *
 * <p>Nothing here is Into Play's product; it is the smallest host that proves a
 * game can be driven entirely over a socket. The wiring is copied from Forge's
 * own {@code HostedMatch} — setGui, setGameView twice, setOriginalGameController,
 * subscribe an event handler, openView — because each of those is load-bearing
 * and discovering that by omission costs an afternoon each time.
 *
 * <pre>
 *   java -cp … forge.intoplay.BridgeMain forge-gui/res [port]
 * </pre>
 */
public final class BridgeMain {

    /**
     * Forge's notion of a UI thread. It must be a real thread, and it must not
     * be the one the game runs on: Forge's input system blocks the game thread
     * on a latch and throws outright if that wait happens on the thread it
     * considers the UI. The socket is a third thread again, which is why
     * answers can arrive while the engine is parked.
     */
    static final String EDT_NAME = "into-play-edt";
    static final ExecutorService EDT = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, EDT_NAME);
        t.setDaemon(true);
        return t;
    });

    /**
     * The platform seam — images, audio, asset paths, thread dispatch. A
     * headless engine has no opinion about most of it, so this answers the four
     * that matter and defaults the rest.
     */
    /**
     * The gui the platform seam hands out.
     *
     * <p>A field rather than a constructor argument because GuiBase has to be
     * installed before anything touches ForgeConstants — whose paths are
     * {@code static final} and resolved from getAssetsDir() at class-init — and
     * that is long before a client connects and there is a gui to hand out.
     */
    static volatile BridgeGui current;

    static IGuiBase headlessGuiBase(final String assetsDir) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "invokeInEdtNow":
                case "invokeInEdtLater":
                    EDT.submit((Runnable) args[0]);
                    return null;
                case "invokeInEdtAndWait":
                    EDT.submit((Runnable) args[0]).get();
                    return null;
                case "runBackgroundTask":
                    ((Runnable) args[1]).run();
                    return null;
                case "isGuiThread":        return Thread.currentThread().getName().equals(EDT_NAME);
                case "isRunningOnDesktop": return Boolean.TRUE;
                case "isLibgdxPort":       return Boolean.FALSE;
                case "hasNetGame":         return Boolean.FALSE;
                case "getCurrentVersion":  return "into-play-bridge";
                // The PARENT of res, with a trailing separator. ForgeConstants
                // builds RES_DIR as getAssetsDir() + "res/", so returning the
                // res directory itself yields ".../res/res/lists/TypeLists.txt",
                // FileUtil.readFile returns an empty list for the missing file,
                // and nothing complains. See loadCards for what that costs.
                case "getAssetsDir":       return assetsDir;
                case "getNewGuiGame":      return current;
                default: break;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Class<?> r = method.getReturnType();
            if (r == void.class) return null;
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == float.class) return 1f;
            if (r == List.class) return new ArrayList<>();
            return null;
        };
        return (IGuiBase) Proxy.newProxyInstance(
                BridgeMain.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, h);
    }

    /**
     * {@code LobbyPlayerHuman.createIngamePlayer()} personalises the name from
     * FModel preferences, which drags in the whole model for no benefit here.
     * Same controller wiring without that lookup.
     */
    static LobbyPlayerHuman lobbyPlayer(final String name) {
        return lobbyPlayer(name, null);
    }

    /**
     * The same, with the two decisions a kitchen table makes for itself.
     *
     * <p><b>Who goes first.</b> Forge flips a coin and asks the winner to play
     * or draw. At a real table people decide that a dozen ways — a die roll,
     * whoever lost last, "you go" — and the app has no business insisting on
     * its own. So when the player has already said, {@code chooseStartingPlayer}
     * answers with that seat and nobody is asked. Forge's own hook, overridden
     * on the bridge's own controller; nothing in Forge is patched.
     *
     * <p><b>The off-table seat's mulligan.</b> Its hand exists only in the
     * engine — the real one is cardboard across the table, and its mulligans
     * happen there. Asking the tablet to keep or mulligan a hand nobody can see
     * is a question with no meaning, so that seat always keeps.
     */
    static LobbyPlayerHuman lobbyPlayer(final String name, final String first) {
        return new LobbyPlayerHuman(name) {
            @Override
            public Player createIngamePlayer(final Game game, final int id) {
                Player p = new Player(getName(), game, id);
                p.setFirstController(new PlayerControllerHuman(game, p, this) {
                    @Override
                    public Player chooseStartingPlayer(final boolean isFirstGame) {
                        String seat = "me".equals(first) ? SEAT
                                : "them".equals(first) ? OFF_TABLE
                                : first != null && first.startsWith("seat:") ? first.substring(5) : null;
                        if (seat != null) {
                            for (Player candidate : game.getPlayers()) {
                                if (seat.equals(candidate.getName())) {
                                    return candidate;
                                }
                            }
                        }
                        return super.chooseStartingPlayer(isFirstGame);
                    }

                    // Two piles: whoever separates, it is one panel on the
                    // tablet (PilesSheet), not Forge's list of names. When the
                    // separator is the off-table seat, the person across the
                    // table does it with the tablet in their hands.
                    @Override
                    public forge.game.card.CardCollectionView chooseCardsForEffect(
                            final forge.game.card.CardCollectionView sourceList, final forge.game.spellability.SpellAbility sa,
                            final String title, final int min, final int max, final boolean isOptional,
                            final java.util.Map<String, Object> params) {
                        // This seat's own screen (two in a versus game).
                        BridgeGui gui = getGui() instanceof BridgeGui own ? own : current;
                        if (gui == null || sa == null || sa.getApi() != forge.game.ability.ApiType.TwoPiles) {
                            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
                        }
                        com.google.gson.JsonArray ids = gui.askPiles("split", p.getName(), sa, sourceList, null,
                                sa.getParamOrDefault("FaceDown", "False"));
                        java.util.Set<Integer> want = new java.util.HashSet<>();
                        ids.forEach(e -> want.add(e.getAsInt()));
                        forge.game.card.CardCollection pile = new forge.game.card.CardCollection();
                        for (forge.game.card.Card c : sourceList) {
                            if (want.contains(c.getId())) {
                                pile.add(c);
                            }
                        }
                        return pile;
                    }

                    @Override
                    public boolean chooseCardsPile(final forge.game.spellability.SpellAbility sa,
                                                   final forge.game.card.CardCollectionView pile1,
                                                   final forge.game.card.CardCollectionView pile2, final String faceUp) {
                        // This seat's own screen (two in a versus game).
                        BridgeGui gui = getGui() instanceof BridgeGui own ? own : current;
                        if (gui == null) {
                            return super.chooseCardsPile(sa, pile1, pile2, faceUp);
                        }
                        com.google.gson.JsonArray a = gui.askPiles("choose", p.getName(), sa, pile1, pile2, faceUp);
                        return a.size() == 0 || a.get(0).getAsInt() != 2;
                    }

                    // Our seat is about to be asked what to do with priority:
                    // the one safe moment to remember the game for "take that
                    // back" (see Rewind).
                    @Override
                    public java.util.List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                        if (!isOffTable(p.getName()) && !VERSUS) {
                            Rewind.remember(game);
                        }
                        return super.chooseSpellAbilityToPlay();
                    }

                    // The off-table seat's blocks are cardboard, declared on the
                    // tablet just before this runs (CardboardBlocks). Forge's own
                    // InputBlock would ask that seat again, about stand-ins it
                    // already has, on a screen nobody on that side is holding.
                    // Their attacks happen at the table, with cardboard; what
                    // reaches us is declared ("Their effect…" → take damage).
                    // In the engine their turn has no attackers.
                    @Override
                    public void declareAttackers(final Player attackingPlayer, final forge.game.combat.Combat combat) {
                        if (isOffTable(p.getName())) {
                            return;
                        }
                        super.declareAttackers(attackingPlayer, combat);
                    }

                    @Override
                    public void declareBlockers(final Player defender, final forge.game.combat.Combat combat) {
                        if (isOffTable(p.getName())) {
                            return;
                        }
                        super.declareBlockers(defender, combat);
                    }

                    @Override
                    public boolean mulliganKeepHand(final Player startsGame, final int cardsToReturn) {
                        // The argument is the player who goes FIRST, not the
                        // one deciding (MulliganService passes firstPlayer).
                        // Whose hand this is comes from the controller's own
                        // seat, p.
                        // Phantom's hand is placeholders: it always keeps,
                        // test positions included.
                        if (isOffTable(p.getName())) {
                            return true;
                        }
                        return super.mulliganKeepHand(startsGame, cardsToReturn);
                    }
                });
                return p;
            }
        };
    }

    /** Our seat. */
    static final String SEAT = "Into Play";

    /** The game being played, for declarations that arrive over the socket. */
    static volatile Game currentGame;

    /**
     * Its event forwarder, which batches events until the engine next waits for
     * input. A declaration changes the game without changing the input, so its
     * events — the log lines, a card leaving the battlefield — sat in the buffer
     * until something else happened. Declarations flush it themselves.
     */
    static volatile forge.gui.control.GameEventForwarder currentForwarder;

    /**
     * A Forge deck from the list the client sent: {@code [{"name","count"}]}.
     *
     * <p>Names come from Moxfield, Archidekt or a pasted list, so they are
     * printed names, and a double-faced card may arrive as "Front // Back".
     * Forge files those under the front face, so that is tried second.
     * Anything still not found is reported rather than dropped in silence.
     */
    static Deck deckFrom(final com.google.gson.JsonObject setup, final BridgeGui gui) {
        CardDb db = StaticData.instance().getCommonCards();
        String name = setup.has("deckName") ? setup.get("deckName").getAsString() : SEAT;
        Deck d = new Deck(name);
        List<String> missing = new ArrayList<>();
        int total = 0;
        for (com.google.gson.JsonElement e : setup.getAsJsonArray("deck")) {
            com.google.gson.JsonObject entry = e.getAsJsonObject();
            String card = entry.get("name").getAsString().trim();
            int n = entry.has("count") ? entry.get("count").getAsInt() : 1;
            PaperCard pc = db.getCard(card);
            if (pc == null && card.contains("//")) {
                pc = db.getCard(card.substring(0, card.indexOf("//")).trim());
            }
            if (pc == null) {
                missing.add(card);
                continue;
            }
            d.getMain().add(pc, n);
            total += n;
        }
        // Commanders go in their own section, which is what makes the game put
        // them in the command zone rather than shuffle them into the library.
        if (setup.has("commanders") && setup.get("commanders").isJsonArray()) {
            for (com.google.gson.JsonElement e : setup.getAsJsonArray("commanders")) {
                String card = e.getAsString().trim();
                PaperCard pc = db.getCard(card);
                if (pc == null && card.contains("//")) {
                    pc = db.getCard(card.substring(0, card.indexOf("//")).trim());
                }
                if (pc == null) {
                    missing.add(card);
                    continue;
                }
                d.getOrCreate(forge.deck.DeckSection.Commander).add(pc, 1);
                total += 1;
            }
        }
        gui.sendDeckReport(name, total, missing);
        System.out.println("deck: " + name + ", " + total + " cards"
                + (missing.isEmpty() ? "" : ", not found: " + missing));
        return d;
    }

    /**
     * The off-table seat's library, which nobody will ever see.
     *
     * <p>It has to exist: the opening hand is dealt from it before the seal can
     * stop draws, and a seat that drew from an empty library on the way in
     * would be a loss the room never saw coming. Basic lands, because they do
     * nothing, and the client shows that seat's hand only as a count.
     */
    static Deck placeholderDeck() {
        Deck d = new Deck(OFF_TABLE);
        PaperCard wastes = StaticData.instance().getCommonCards().getCard("Wastes");
        if (wastes != null) {
            d.getMain().add(wastes, 60);
        }
        return d;
    }

    public static void main(final String[] args) throws Exception {
        final String res = args.length > 0 ? args[0] : "forge-gui/res";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        // A scenario file, if one was named. See loadState().
        final String state = args.length > 2 ? args[2] : System.getenv("STATE");

        // Order matters. GuiBase first: ForgeConstants' paths are static final
        // and computed from getAssetsDir() the first time the class is touched,
        // which card loading does.
        java.nio.file.Path resPath = java.nio.file.Paths.get(res).toAbsolutePath().normalize();
        GuiBase.setInterface(headlessGuiBase(resPath.getParent().toString() + java.io.File.separator));
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "/languages/");
        loadCards(res);
        SoundFiles.init(res);
        // The opponent's cards are cardboard: an ability with nothing legal to
        // target on the table may still be pointed at one of theirs.
        // Reflective, so a bridge running on an engine built before this switch
        // existed still starts — it just keeps stock Forge's behaviour.
        try {
            forge.player.TargetSelection.class.getMethod("setOffTableTargets", boolean.class).invoke(null, true);
        } catch (ReflectiveOperationException e) {
            System.out.println("this engine predates off-table targets for abilities with no target on the table");
        }

        // With a scenario file the game starts the moment a client connects, as
        // it always has — that is what the test clients expect. Without one it
        // waits for {"t":"start"}, which carries the player's own deck.
        final boolean startOnConnect = state != null && !state.isBlank();
        BridgeServer server = new BridgeServer(port, (gui, setup) -> {
            try {
                runGame(res, gui, state, setup);
            } catch (Throwable t) {
                System.out.println("[game ended] " + t);
                t.printStackTrace(System.out);
                // Say so. A game thread that dies leaves the last prompt on
                // the client's screen, which reads as "frozen" rather than
                // "the engine stopped", and the player can only guess.
                gui.sendFatal(String.valueOf(t));
            }
        }, startOnConnect);
        server.start();

        // The process stays up for the socket; the game lives on its own thread.
        Thread.currentThread().join();
    }

    /**
     * All 34k card scripts, once for the life of the process.
     *
     * <p>Not optional, and the failure is unhelpful if you skip it:
     * {@code GameAction.startGame} reaches for {@code StaticData.instance()}
     * while dealing opening hands and throws an NPE on a static accessor,
     * several frames away from anything that mentions cards. A game will happily
     * get through the coin toss first, which makes it look like the bridge broke
     * rather than the database being absent.
     *
     * <p>Measured: ~3.5s and ~139MB. That is the cost that has to be paid once
     * per server rather than once per game, which is the reason it lives in
     * main() and not in runGame().
     */
    static void loadCards(final String res) throws Exception {
        long t0 = System.currentTimeMillis();
        // A headless engine renders nothing, so this looks like dead
        // configuration — it is not. CardDb picks which printing of a card you
        // get partly by whether the art is on disk, so looking up "Mountain"
        // reaches ImageKeys.hasImage() and NPEs on these static fields if they
        // were never set. Empty paths are a truthful answer: no art is cached.
        java.nio.file.Path pics = java.nio.file.Files.createTempDirectory("into-play-nopics");
        forge.ImageKeys.initializeDirs(pics.toString() + "/", new java.util.HashMap<>(),
                pics.toString() + "/", pics.toString() + "/", pics.toString() + "/",
                pics.toString() + "/", pics.toString() + "/", pics.toString() + "/",
                pics.toString() + "/");
        CardStorageReader reader = new CardStorageReader(
                res + "/cardsfolder", CardStorageReader.ProgressObserver.emptyObserver, false);
        // StaticData wants a custom-cards directory and will not accept one that
        // is missing, so it gets an empty one rather than a path that happens to
        // exist on somebody's laptop.
        java.nio.file.Path noCustom = java.nio.file.Files.createTempDirectory("into-play-nocustom");
        // The token scripts, which the short constructor leaves out — and
        // nothing complains until a card makes a token. Then TokenInfo asks
        // StaticData.getAllTokens(), gets null, and the game thread dies with
        // an NPE in the middle of resolving: Voldaren Epicure's Blood token
        // froze a real game with the last prompt still on screen. Forge's own
        // FModel passes this reader; so does the bridge now.
        CardStorageReader tokenReader = new CardStorageReader(
                res + "/tokenscripts", CardStorageReader.ProgressObserver.emptyObserver, false);
        new StaticData(reader, tokenReader, null, null, res + "/editions", noCustom.toString(),
                res + "/blockdata", "", "LatestCoreExp", true, false, false, false);
        // TypeLists.txt, and this is the most expensive omission in the whole
        // bootstrap because it fails silently and looks like something else.
        // Without it CardType.Constant.LAND_TYPES and friends are empty, so
        // every subtype on every card is dropped at parse time: a Mountain
        // comes out as "Basic Land" with no "Mountain" subtype. Forge grants a
        // basic land's "{T}: Add {R}" from that subtype, so the land produces
        // no mana, Lightning Bolt can never be paid for, and the engine simply
        // waits forever inside applyManaToCost with no error anywhere. Hours.
        forge.model.FModel.loadDynamicGamedata();
        shareCardDatabaseWithFModel();
        System.out.printf("cards loaded: %d in %d ms%n",
                StaticData.instance().getCommonCards().getUniqueCards().size(),
                System.currentTimeMillis() - t0);
    }

    /**
     * Point Forge's FModel at the card database already loaded above.
     *
     * <p>Some of Forge's GUI code asks FModel.getMagicDb() rather than
     * StaticData.instance() — naming a card (Pithing Needle, Meddling Mage)
     * is one. FModel builds its own database on first use from card readers it
     * expects its own startup to have set; the bridge never runs that startup,
     * so the readers are null and the game thread died mid-resolution. Rather
     * than load all 34,000 cards a second time, FModel's memoized supplier is
     * told to hand back the database that exists. Read and set, not patched.
     */
    static void shareCardDatabaseWithFModel() {
        try {
            java.lang.reflect.Field f = forge.model.FModel.class.getDeclaredField("magicDb");
            f.setAccessible(true);
            Object supplier = f.get(null);
            java.lang.reflect.Field d = supplier.getClass().getDeclaredField("delegate");
            d.setAccessible(true);
            d.set(supplier, (com.google.common.base.Supplier<StaticData>) StaticData::instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("could not share the card database with FModel: " + e);
        }
    }

    /**
     * Sixty cards of mono-red: Mountains and Lightning Bolt.
     *
     * <p>Chosen because Bolt is the card the phantom work exists for — "bolt
     * that creature" aimed at cardboard the engine cannot see — so the smallest
     * deck that proves the bridge is also the deck the next step needs.
     */
    static Deck redDeck(final String name) {
        CardDb db = StaticData.instance().getCommonCards();
        Deck d = new Deck(name);
        PaperCard mountain = db.getCard("Mountain");
        PaperCard bolt = db.getCard("Lightning Bolt");
        // Bolt-heavy, and not for flavour: an opening seven needs at least one
        // of each for the Bolt sequence to have anything to do, and 36 lands to
        // 24 spells gave an all-land hand often enough to fail the check for a
        // reason that had nothing to do with the bridge.
        if (mountain != null) {
            d.getMain().add(mountain, 24);
        }
        if (bolt != null) {
            d.getMain().add(bolt, 36);
        }
        return d;
    }

    /**
     * The seat that sits across the table. Its cards are cardboard.
     *
     * <p>With several opponents (multiplayer Commander) there is one such seat
     * each, named by the player — "Mark", "Anna" — so "each opponent" counts
     * right and every life total is a real engine player. OFF_TABLE is the
     * first of them: the one "their table", blocks and the "who goes first"
     * answer refer to.
     */
    static volatile String OFF_TABLE = "Phantom";
    static volatile List<String> OPPONENTS = List.of("Phantom");

    static boolean isOffTable(final String name) {
        return name != null && OPPONENTS.contains(name);
    }

    /** The opponents the client named, cleaned up: unique, not our seat, at most five. */
    static List<String> opponentsFrom(final com.google.gson.JsonObject setup) {
        List<String> out = new ArrayList<>();
        if (setup != null && setup.has("opponents") && setup.get("opponents").isJsonArray()) {
            for (com.google.gson.JsonElement e : setup.getAsJsonArray("opponents")) {
                String n = e.isJsonNull() ? "" : e.getAsString().trim();
                if (n.isEmpty() || n.equalsIgnoreCase(SEAT) || out.contains(n) || out.size() >= 5) {
                    continue;
                }
                out.add(n.length() > 24 ? n.substring(0, 24) : n);
            }
        }
        if (out.isEmpty()) {
            out.add("Phantom");
        }
        return List.copyOf(out);
    }

    /**
     * A gui for the seat that cannot answer.
     *
     * <p>The phantom seat is a real engine {@link Player}, which is what makes
     * its life total real and its permanents targetable. The consequence is that
     * the engine asks it things — "target opponent chooses one of these three
     * cards" is a question for them, not for us — and the person it stands for
     * is holding cardboard, not a device. Left alone, those questions are put to
     * a seat that will never answer, and the game stops.
     *
     * <p>So: the phantom exists and can never act. Every question aimed at it
     * arrives on our screen, labelled as theirs, and the person holding the
     * tablet answers on their behalf — which is what already happens out loud at
     * the table.
     *
     * <p>Forge needs no change to allow it. {@code PlayerControllerHuman.setGui}
     * is per controller, not per game, so that seat can be handed a gui of its
     * own. This is that gui: it is the real one, with every call wrapped in a
     * note saying who it was for. A {@link Proxy} rather than ~90 delegating
     * methods, which is also how this file already adapts {@code IGuiBase}.
     *
     * <p>The finally is load-bearing. An exception out of a prompt would
     * otherwise leave the tag set, and the next question we are genuinely asked
     * would be presented as the opponent's.
     *
     * <h2>Not wired up yet, and why</h2>
     *
     * Handing this to {@code human.setGui} hangs the game. The same controller
     * is also given to {@code FControlGameEventHandler}, which reaches the gui
     * back through {@code human.getGui()} — so the proxy wraps the EVENT path as
     * well as the question path, and the priority input parks in
     * {@code IGuiGame.awaitInput} on a latch that is never released. Watched in a
     * thread dump rather than guessed at.
     *
     * <p>The mechanism is still right; the placement is wrong. The tag belongs
     * around the calls the engine makes to ASK that seat something, not around
     * everything that seat's controller ever touches. The likely fix is to
     * narrow it — either wrap only the asking methods, or set the tag from
     * inside {@code PlayerControllerHuman}'s own entry points rather than at the
     * gui boundary — and that needs a session with a thread dump open, not a
     * guess at the end of one.
     */
    static forge.gui.interfaces.IGuiGame proxyGuiFor(final BridgeGui gui, final String seat) {
        return (forge.gui.interfaces.IGuiGame) Proxy.newProxyInstance(
                forge.gui.interfaces.IGuiGame.class.getClassLoader(),
                new Class<?>[] { forge.gui.interfaces.IGuiGame.class },
                (p, method, args) -> {
                    gui.setAskingFor(seat);
                    try {
                        return method.invoke(gui, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    } finally {
                        gui.setAskingFor(null);
                    }
                });
    }

    /**
     * Stop the off-table seat from drawing, so it cannot deck itself out.
     *
     * <p>The problem is real and was watched happening: that seat is a genuine
     * engine {@link Player}, which is what makes its life total real, but its
     * sixty cards are on the table and not in the engine's library. A real
     * player who draws from an empty library loses — rule 704.5b — so the proxy
     * would hand us the game within a few turns for no reason anyone at the
     * table could see.
     *
     * <p>Preventing the <i>draw</i> rather than the <i>loss</i>, and the order
     * matters: {@code drawCards} checks {@code canDraw()} before touching the
     * library, so no draw is ever attempted and
     * {@code triedToDrawFromEmptyLibrary} is never set. Blocking the loss
     * instead would leave a failed draw happening every turn, firing whatever
     * watches for one.
     *
     * <p>Done with Forge's own machinery — a static ability on a card in the
     * command zone, which is one of {@code STATIC_ABILITIES_SOURCE_ZONES} — so
     * there is no patch here and no special case inside the engine. Their
     * opening seven still arrives, because the opening hand is dealt before the
     * game starts and skips the check; that is fine, and it means the hand
     * count starts out roughly honest.
     */
    static void sealOffTableLibrary(final Game game, final Player seat) {
        List<String> script = List.of(
                "Name:Off-Table Seat",
                "Types:Effect",
                // EffectZone$ Command is load-bearing: a static ability is only
                // active in the battlefield unless it says otherwise, so without
                // it the card sits in the command zone with its ability parsed,
                // attached, and doing nothing at all. canDraw() stayed true and
                // the proxy decked itself out exactly as before.
                // The seat never takes a turn. Their turn happens in the room,
                // with their own cards, their own untaps and their own draws —
                // none of which the engine can see. Having it run a full turn
                // for that seat is theatre: it untaps nothing, draws nothing,
                // plays nothing, and doubles how long a game takes.
                //
                // Forge's own mechanism. PhaseHandler.getNextActivePlayer runs
                // a BeginTurn replacement and, if anything replaced it, calls
                // itself again with the next player — so a skip here is the
                // engine's normal path, not a hole punched through it.
                //
                // ActiveZones$ Command for the same reason the static needs
                // EffectZone: a command-zone card is inert otherwise.
                //
                // NO LONGER SKIPPED (2026-10): their turn runs, auto-passed, so
                // "at the beginning of each upkeep", "each end step" and
                // "during an opponent's turn" happen when they should. It costs
                // nothing to watch: the seat yields every step, nobody is woken
                // but at their end step (the "their turn" window) or when
                // something triggers — see BridgeGui.isUiSetToSkipPhase. The
                // seat never attacks in the engine (its creatures are
                // cardboard); see declareAttackers in lobbyPlayer.
                //
                // Their draws stay blocked: their cards are on the table.
                "S:Mode$ CantDraw | ValidPlayer$ You | EffectZone$ Command "
                        + "| Description$ This seat's cards are on the table, not in the engine.",
                "Oracle:");
        PaperCard pc = new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
        Card proxy = CardFactory.getCard(pc, seat, game);
        game.getAction().moveTo(ZoneType.Command, proxy, null, null);
        // Assert it, rather than assuming the script took. A seal that silently
        // does nothing is worse than no seal: the proxy decks itself out and
        // hands over the game for a reason nobody at the table can see.
        System.out.println("seal: " + seat.getName()
                + " proxy in " + proxy.getZone()
                + " statics=" + proxy.getStaticAbilities().size()
                + " replacements=" + proxy.getReplacementEffects().size()
                + " canDraw=" + seat.canDraw());
    }

    /**
     * Put the board where the scenario wants it, using Forge's own dev format.
     *
     * <p>Why a state file rather than playing to the position: a test that plays
     * a game to reach a board is testing the route, not the destination. The
     * Bolt client had to draw a Mountain, hold priority, remember whether it had
     * already played a land — none of which has anything to do with whether a
     * Bolt can be aimed off-table, and all of which can fail for reasons that
     * look like the thing under test failing. Forge already solved this for its
     * own puzzles: {@link GameState} parses the position and applies it.
     *
     * <p>The format is Forge's, so a scenario here is also a scenario their dev
     * mode can load:
     *
     * <pre>
     *   humanlife=20
     *   humanhand=Intuition;Island
     *   humanbattlefield=Island|Tapped:False;Island|Tapped:False;Island|Tapped:False
     *   ailife=20
     *   activeplayer=human
     *   activephase=MAIN1
     * </pre>
     *
     * <p>{@code human} is seat 0 (ours) and {@code ai} is seat 1 (the off-table
     * seat). Both have to appear: applyToGame throws if the state names fewer
     * players than the game has.
     *
     * <p><b>The trap, found by reading setupPlayerState:</b> it clears every
     * zone first, and its own comment says "e.g. in command zone". That is the
     * zone the off-table seal lives in, so applying a state silently unseals the
     * proxy and it starts taking turns and decking itself again. Hence the
     * re-seal below, after the state rather than before.
     */
    static void applyState(final Game game, final String path) {
        java.util.List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(path));
        } catch (java.io.IOException e) {
            System.out.println("[state] cannot read " + path + ": " + e);
            return;
        }
        // Blank lines have to go before parse(). GameState.splitLine starts with
        // line.charAt(0) to test for a '#' comment and never checks the length,
        // so an empty line throws StringIndexOutOfBounds from inside a stream —
        // and the trace points at parseLine, not at the file. Comments are fine;
        // it is only the blank separators between them that bite. Dropped here
        // rather than patched there, to keep the fork at two patched files.
        lines = lines.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
        forge.game.GameState st = new forge.game.GameState();
        st.parse(lines);
        // Already on the game thread inside the start hook, so applyGameOnThread
        // would do; applyToGame's invoke() runs it inline from here and keeps us
        // on Forge's own entry point.
        st.applyToGame(game);
        System.out.println("[state] applied " + path);
        for (Player p : game.getPlayers()) {
            System.out.printf("[state]   %-12s life %-4d hand %-3d library %-3d "
                            + "battlefield %-3d graveyard %d%n",
                    p.getName(), p.getLife(),
                    p.getZone(ZoneType.Hand).size(),
                    p.getZone(ZoneType.Library).size(),
                    p.getZone(ZoneType.Battlefield).size(),
                    p.getZone(ZoneType.Graveyard).size());
        }
    }

    /**
     * @param setup the client's {@code {"t":"start"}}: its deck and who goes
     *              first. Null when a scenario file started the game on
     *              connect, which keeps the old test clients working unchanged.
     */
    /** True while two real players share the engine (no off-table seat). */
    static volatile boolean VERSUS;

    /**
     * Two people, two screens, one game: no Phantom. Each player's deck is
     * real, each has their own gui — so a prompt, a question, a library search
     * reaches only the screen of the player it belongs to, as in Forge's own
     * network play — and each screen sees the other's hand and library only
     * as counts.
     *
     * <p>Nothing of the off-table machinery runs: no seal, no cardboard
     * blocks, no "their table", no take-back (it would undo the other
     * player's turn too).
     */
    static void runVersus(final BridgeGui guiA, final JsonPair a, final BridgeGui guiB, final JsonPair b,
                          final String first) {
        VERSUS = true;
        OPPONENTS = List.of();
        OFF_TABLE = "";
        current = guiA;
        Deck deckA = deckFrom(a.setup, guiA);
        Deck deckB = deckFrom(b.setup, guiB);
        final boolean commander = !deckA.getCommanders().isEmpty() || !deckB.getCommanders().isEmpty();
        // "first": a seat's name, or anything else for Forge's coin.
        String firstSeat = first == null ? null : "seat:" + first;
        List<RegisteredPlayer> registered = new ArrayList<>();
        registered.add((commander ? RegisteredPlayer.forCommander(deckA) : new RegisteredPlayer(deckA))
                .setPlayer(lobbyPlayer(a.name, firstSeat)));
        registered.add((commander ? RegisteredPlayer.forCommander(deckB) : new RegisteredPlayer(deckB))
                .setPlayer(lobbyPlayer(b.name, firstSeat)));
        GameRules rules = new GameRules(commander ? GameType.Commander : GameType.Constructed);
        if (commander) {
            rules.addAppliedVariant(GameType.Commander);
        }
        Match match = new Match(rules, registered, "Bridge versus");
        Game game = new Game(registered, rules, match);
        currentGame = game;

        for (Player p : game.getPlayers()) {
            if (!(p.getController() instanceof PlayerControllerHuman human)) {
                continue;
            }
            BridgeGui g = p.getName().equals(a.name) ? guiA : guiB;
            human.setGui(g);
            human.getYieldController().setPref(
                    forge.localinstance.properties.ForgePreferences.FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true");
            g.setGameView(null);
            g.setGameView(game.getView());
            g.setOriginalGameController(p.getView(), human);
            game.subscribeToEvents(new FControlGameEventHandler(human));
        }
        for (BridgeGui g : List.of(guiA, guiB)) {
            forge.gui.control.GameEventForwarder fw = new forge.gui.control.GameEventForwarder(g);
            game.subscribeToEvents(fw);
            for (Player p : game.getPlayers()) {
                if (p.getController() instanceof PlayerControllerHuman human) {
                    human.getInputQueue().addObserver(fw);
                }
            }
            if (g == guiA) {
                currentForwarder = fw;
            }
        }
        for (Player p : game.getPlayers()) {
            TrackableCollection<forge.game.player.PlayerView> own = new TrackableCollection<>();
            own.add(p.getView());
            BridgeGui g = p.getName().equals(a.name) ? guiA : guiB;
            g.setVersus(true);
            g.openView(own);
        }
        System.out.println("=== starting versus: " + a.name + " vs " + b.name + " ===");
        match.startGame(game);
        System.out.println("=== versus over ===");
    }

    /** A player's name and their {"deck":…, "commanders":…} for {@link #runVersus}. */
    record JsonPair(String name, com.google.gson.JsonObject setup) { }

    static void runGame(final String res, final BridgeGui gui, final String statePath,
                        final com.google.gson.JsonObject setup) {
        current = gui;
        VERSUS = false;

        final boolean ownDeck = setup != null && setup.has("deck") && setup.get("deck").isJsonArray();
        // "me", "them", or anything else for Forge's own coin toss.
        final String first = setup != null && setup.has("first") ? setup.get("first").getAsString() : null;

        // A Commander game when the deck names a commander: Forge's own variant,
        // so the command zone, commander tax, 40 life and 21 commander damage
        // are the engine's, not the app's. The off-table seat is registered the
        // same way for its 40 life; its commander is cardboard, so it has none.
        Deck ourDeck = ownDeck ? deckFrom(setup, gui) : redDeck(SEAT);
        final boolean commander = !ourDeck.getCommanders().isEmpty();
        // Who sits across the table, by the names the player gave (one seat
        // each; "Phantom" when none were given, as test positions expect).
        OPPONENTS = opponentsFrom(setup);
        OFF_TABLE = OPPONENTS.get(0);

        List<RegisteredPlayer> registered = new ArrayList<>();
        registered.add((commander ? RegisteredPlayer.forCommander(ourDeck) : new RegisteredPlayer(ourDeck))
                .setPlayer(lobbyPlayer(SEAT, first)));
        for (String name : OPPONENTS) {
            Deck theirDeck = ownDeck ? placeholderDeck() : redDeck(name);
            registered.add((commander ? RegisteredPlayer.forCommander(theirDeck) : new RegisteredPlayer(theirDeck))
                    .setPlayer(lobbyPlayer(name, first)));
        }
        if (OPPONENTS.size() > 1) {
            System.out.println("opponents: " + OPPONENTS);
        }

        GameRules rules = new GameRules(commander ? GameType.Commander : GameType.Constructed);
        if (commander) {
            rules.addAppliedVariant(GameType.Commander);
            System.out.println("commander game: " + ourDeck.getCommanders());
        }
        Match match = new Match(rules, registered, "Bridge");
        Game game = new Game(registered, rules, match);
        currentGame = game;

        TrackableCollection<forge.game.player.PlayerView> mine = new TrackableCollection<>();
        for (Player p : game.getPlayers()) {
            if (!(p.getController() instanceof PlayerControllerHuman human)) {
                continue;
            }
            // NOT YET the proxy gui — see proxyGuiFor below for the mechanism
            // and why it is still parked. Wiring it here hangs the game: the
            // same gui reference is handed to FControlGameEventHandler, so the
            // proxy ends up wrapping the event path as well as the question
            // path, and the priority input never releases its latch.
            human.setGui(gui);
            // Forge only reports what is playable right now (setWeaklySelectable)
            // when its desktop "show actionable highlights" preference is on, and
            // a headless engine has no preferences file — so nothing was ever
            // sent. The board's green glow is that report.
            human.getYieldController().setPref(
                    forge.localinstance.properties.ForgePreferences.FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true");
            // Twice, and in this order: the first clears whatever view the gui
            // held from a previous game so the second does not copy into stale
            // state. Forge does the same and the comment there says why.
            gui.setGameView(null);
            gui.setGameView(game.getView());
            gui.setOriginalGameController(p.getView(), human);
            // Two subscribers, doing different jobs. FControlGameEventHandler
            // translates events into the gui calls that keep it current —
            // updateCards, showCombat, setCurrentPlayer — and is what a gui
            // acting directly needs. GameEventForwarder hands the raw events to
            // handleGameEvents() instead, which Forge uses only for a gui that
            // proxies to somewhere else. The bridge is both: it acts directly
            // and it is a long way from its client, so it takes both, and the
            // event stream becomes Into Play's log written by the thing that
            // knows rather than by the client guessing after each tap.
            game.subscribeToEvents(new FControlGameEventHandler(human));
            mine.add(p.getView());
        }
        // Once, not once per seat. Both seats share this gui, and subscribing
        // the forwarder inside the loop above sent every event to the client
        // twice — the whole log, doubled, for as long as the bridge existed.
        forge.gui.control.GameEventForwarder forwarder = new forge.gui.control.GameEventForwarder(gui);
        currentForwarder = forwarder;
        game.subscribeToEvents(forwarder);
        // Blocks with cardboard: asked when blockers are declared against the
        // off-table seat, played out by stand-ins, reported when combat ends.
        game.subscribeToEvents(new CardboardBlocks(game, gui, OFF_TABLE));
        // Their deck as a reservoir, and the table defined when our cards
        // need it. See TheirSide.
        game.subscribeToEvents(new TheirSide(game, gui, SEAT, OFF_TABLE));
        // And it must watch each seat's input queue. The forwarder buffers
        // events and sends them in batches; its last flush is meant to happen
        // when the engine stops to wait for a player — which it only hears
        // about as an observer of the InputQueue. Without this, whatever
        // happened just before a prompt (the step changing, a land landing)
        // sat in the buffer until the next event came along, so the screen
        // was always one step behind and showed no phase at all.
        for (Player p : game.getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman human) {
                human.getInputQueue().addObserver(forwarder);
            }
        }
        // Before openView, so the first message the client sees already names
        // the proxy seat.
        gui.setOffTableSeat(OFF_TABLE);
        gui.openView(mine);

        for (Player p : game.getPlayers()) {
            if (isOffTable(p.getName())) {
                sealOffTableLibrary(game, p);
            }
        }

        System.out.println("=== starting game ===");
        if (statePath == null || statePath.isBlank()) {
            match.startGame(game);
        } else {
            // The hook runs after opening hands are dealt and the first turn is
            // set up, but before priority is offered (PhaseHandler.setupFirstTurn
            // sets givePriorityToPlayer=false around it). So the client's first
            // prompt is already in the seeded position: nothing has to be played
            // to get there, and nothing can go wrong on the way.
            match.startGame(game, () -> {
                applyState(game, statePath);
                // applyState clears the command zone along with everything else,
                // which takes the seal with it. Re-seal, and let the assertion
                // inside sealOffTableLibrary print so a silent unseal is visible.
                for (Player p : game.getPlayers()) {
                    if (isOffTable(p.getName())) {
                        sealOffTableLibrary(game, p);
                    }
                }
            });
        }

        System.out.println("=== game over ===");
        for (Player p : game.getPlayers()) {
            System.out.printf("  %-12s life %-4d hand %-3d library %-3d battlefield %d%n",
                    p.getName(), p.getLife(),
                    p.getZone(ZoneType.Hand).size(),
                    p.getZone(ZoneType.Library).size(),
                    p.getZone(ZoneType.Battlefield).size());
        }
    }
}
