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
    static IGuiBase headlessGuiBase(final String assetsDir, final BridgeGui gui) {
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
                case "getAssetsDir":       return assetsDir;
                case "getNewGuiGame":      return gui;
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
        if (mountain != null) {
            d.getMain().add(mountain, 36);
        }
        if (bolt != null) {
            d.getMain().add(bolt, 24);
        }
        return d;
    }

    static void runGame(final String res, final BridgeGui gui) {
        GuiBase.setInterface(headlessGuiBase(res, gui));

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
        gui.openView(mine);

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
