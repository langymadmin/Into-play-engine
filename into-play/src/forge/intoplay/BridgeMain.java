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
        return new LobbyPlayerHuman(name) {
            @Override
            public Player createIngamePlayer(final Game game, final int id) {
                Player p = new Player(getName(), game, id);
                p.setFirstController(new PlayerControllerHuman(game, p, this));
                return p;
            }
        };
    }

    public static void main(final String[] args) throws Exception {
        final String res = args.length > 0 ? args[0] : "forge-gui/res";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        // Order matters. GuiBase first: ForgeConstants' paths are static final
        // and computed from getAssetsDir() the first time the class is touched,
        // which card loading does.
        java.nio.file.Path resPath = java.nio.file.Paths.get(res).toAbsolutePath().normalize();
        GuiBase.setInterface(headlessGuiBase(resPath.getParent().toString() + java.io.File.separator));
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "/languages/");
        loadCards(res);

        BridgeServer server = new BridgeServer(port, gui -> {
            try {
                runGame(res, gui);
            } catch (Throwable t) {
                System.out.println("[game ended] " + t);
                t.printStackTrace(System.out);
            }
        });
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
        new StaticData(reader, null, res + "/editions", noCustom.toString(), res + "/blockdata",
                "LatestCoreExp", true, false);
        // TypeLists.txt, and this is the most expensive omission in the whole
        // bootstrap because it fails silently and looks like something else.
        // Without it CardType.Constant.LAND_TYPES and friends are empty, so
        // every subtype on every card is dropped at parse time: a Mountain
        // comes out as "Basic Land" with no "Mountain" subtype. Forge grants a
        // basic land's "{T}: Add {R}" from that subtype, so the land produces
        // no mana, Lightning Bolt can never be paid for, and the engine simply
        // waits forever inside applyManaToCost with no error anywhere. Hours.
        forge.model.FModel.loadDynamicGamedata();
        System.out.printf("cards loaded: %d in %d ms%n",
                StaticData.instance().getCommonCards().getUniqueCards().size(),
                System.currentTimeMillis() - t0);
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

    /** The seat that sits across the table. Its cards are cardboard. */
    static final String OFF_TABLE = "Phantom";

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
                + " canDraw=" + seat.canDraw());
    }

    static void runGame(final String res, final BridgeGui gui) {
        current = gui;

        List<RegisteredPlayer> registered = new ArrayList<>();
        registered.add(new RegisteredPlayer(redDeck("Into Play")).setPlayer(lobbyPlayer("Into Play")));
        registered.add(new RegisteredPlayer(redDeck("Phantom")).setPlayer(lobbyPlayer("Phantom")));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, registered, "Bridge");
        Game game = new Game(registered, rules, match);

        TrackableCollection<forge.game.player.PlayerView> mine = new TrackableCollection<>();
        for (Player p : game.getPlayers()) {
            if (!(p.getController() instanceof PlayerControllerHuman human)) {
                continue;
            }
            human.setGui(gui);
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
            game.subscribeToEvents(new forge.gui.control.GameEventForwarder(gui));
            mine.add(p.getView());
        }
        // Before openView, so the first message the client sees already names
        // the proxy seat.
        gui.setOffTableSeat(OFF_TABLE);
        gui.openView(mine);

        for (Player p : game.getPlayers()) {
            if (OFF_TABLE.equals(p.getName())) {
                sealOffTableLibrary(game, p);
            }
        }

        System.out.println("=== starting game ===");
        match.startGame(game);

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
