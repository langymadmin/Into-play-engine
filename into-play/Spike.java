import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.Lang;
import forge.util.Localizer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scripted-game spike: start a real Forge game with no GUI and watch what the
 * engine asks the player.
 *
 * The point is not to play well. It is to find out whether a Forge game can be
 * driven from outside, and to see the actual prompt traffic — which calls fire,
 * in what order, with what arguments — because that traffic IS the protocol the
 * into-play bridge has to carry.
 *
 * IGuiGame has ~104 methods, so rather than stub them by hand this installs a
 * dynamic proxy: every call is logged, void calls are swallowed, and non-void
 * calls get a conservative default. That is deliberately the same shape the real
 * bridge takes — a proxy that marshals each call somewhere instead of answering
 * it locally.
 */
public final class Spike {

    static int prompts = 0;

    static final String EDT_NAME = "into-play-edt";
    static final ExecutorService EDT =
            Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, EDT_NAME); t.setDaemon(true); return t; });

    /** Every call the engine makes to the UI, logged; answers are the safest default. */
    static IGuiGame loggingGui() {
        InvocationHandler h = (proxy, method, args) -> {
            Class<?> ret = method.getReturnType();
            String kind = ret == void.class ? "notify" : "PROMPT";
            if (ret != void.class) prompts++;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-7s %-28s", kind, method.getName()));
            if (args != null) {
                for (Object a : args) {
                    String s = String.valueOf(a);
                    if (s.length() > 48) s = s.substring(0, 45) + "...";
                    sb.append(" | ").append(s);
                }
            }
            System.out.println(sb);

            // Default methods on the interface carry real logic (overloads that
            // delegate); let them run rather than stubbing them out.
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);

            if (ret == void.class) return null;
            if (ret == boolean.class) return Boolean.FALSE;       // decline optional things
            if (ret == int.class) return 0;
            if (ret == Integer.class) return 0;
            if (ret == List.class) return new ArrayList<>();
            if (ret == Map.class) return Collections.emptyMap();
            return null;                                          // "no choice"
        };
        return (IGuiGame) Proxy.newProxyInstance(
                Spike.class.getClassLoader(), new Class<?>[]{IGuiGame.class}, h);
    }


    /**
     * The platform seam, as opposed to IGuiGame's game seam. Forge asks this for
     * thread dispatch, asset paths, images, audio — almost all of which a
     * headless engine has no opinion about. The four that matter run the
     * Runnable on the calling thread: there is no UI thread to marshal onto, so
     * "already on it" is the truthful answer.
     */
    static IGuiBase headlessGuiBase(String assetsDir) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                // A real second thread, not an inline call. Forge's input system
                // blocks the game thread on a latch and expects something else to
                // release it, and asserts loudly if the blocking happens on the UI
                // thread. In the bridge this executor is where socket replies land.
                case "invokeInEdtNow": case "invokeInEdtLater":
                    EDT.submit((Runnable) args[0]);
                    return null;
                case "invokeInEdtAndWait":
                    EDT.submit((Runnable) args[0]).get();
                    return null;
                case "runBackgroundTask":
                    ((Runnable) args[1]).run();
                    return null;
                case "isGuiThread":          return Thread.currentThread().getName().equals(EDT_NAME);
                case "isRunningOnDesktop":   return Boolean.TRUE;
                case "isLibgdxPort":         return Boolean.FALSE;
                case "hasNetGame":           return Boolean.FALSE;
                case "getCurrentVersion":    return "into-play-spike";
                case "getAssetsDir":         return assetsDir;
                case "getNewGuiGame":        return loggingGui();
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            Class<?> r = method.getReturnType();
            if (r == void.class) return null;
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == float.class) return 1f;
            if (r == List.class) return new ArrayList<>();
            return null;
        };
        return (IGuiBase) Proxy.newProxyInstance(
                Spike.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, h);
    }

    // LobbyPlayerHuman.createIngamePlayer() personalises the name from FModel
    // preferences, which would drag in the whole model. Same controller wiring,
    // without that lookup — copied from Forge's own HumanControlledGame test.
    static LobbyPlayerHuman lobbyPlayer(String name) {
        return new LobbyPlayerHuman(name) {
            @Override
            public Player createIngamePlayer(Game game, int id) {
                Player p = new Player(getName(), game, id);
                p.setFirstController(new PlayerControllerHuman(game, p, this));
                return p;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        String res = args.length > 0 ? args[0] : "forge-gui/res";
        GuiBase.setInterface(headlessGuiBase(res));
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "/languages/");

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(lobbyPlayer("Into Play")));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(lobbyPlayer("Phantom")));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Spike");
        Game game = new Game(players, rules, match);

        for (Player p : game.getPlayers()) {
            ((PlayerControllerHuman) p.getController()).setGui(loggingGui());
        }

        System.out.println("=== game constructed ===");
        System.out.println("players : " + game.getPlayers());
        System.out.println("phase   : " + game.getPhaseHandler().getPhase());
        System.out.println("turn    : " + game.getPhaseHandler().getTurn());
        System.out.println();
        System.out.println("=== starting match (prompt traffic below) ===");

        Thread t = new Thread(() -> {
            try {
                match.startGame(game);
            } catch (Throwable e) {
                System.out.println("\n[game thread ended] " + e);
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(20000); // the engine blocks on prompts; we only need the opening traffic

        System.out.println();
        System.out.println("=== after 20s ===");
        System.out.println("prompts seen : " + prompts);
        System.out.println("turn         : " + game.getPhaseHandler().getTurn());
        System.out.println("phase        : " + game.getPhaseHandler().getPhase());
        System.out.println("game over?   : " + game.isGameOver());
        for (Player p : game.getPlayers()) {
            System.out.printf("  %-12s life %-4d hand %-3d library %-3d battlefield %d%n",
                    p.getName(), p.getLife(),
                    p.getZone(forge.game.zone.ZoneType.Hand).size(),
                    p.getZone(forge.game.zone.ZoneType.Library).size(),
                    p.getZone(forge.game.zone.ZoneType.Battlefield).size());
        }
        System.exit(0);
    }
}
