package forge.intoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import forge.interfaces.IGameController;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * A WebSocket the client half of Into Play talks to.
 *
 * <p>Netty and Gson are already dependencies of forge-gui, so the bridge adds no
 * library to a GPL fork — worth keeping that way, since every dependency here is
 * one more thing to carry.
 *
 * <p>The protocol is deliberately small and self-describing. Every message is a
 * JSON object with a "t" field.
 *
 * <h2>Engine to client</h2>
 * <pre>
 *   {"t":"prompt",  "player":"…", "text":"…"}        something to read
 *   {"t":"buttons", "ok":"Play", "cancel":"Draw", …} what the two buttons mean
 *   {"t":"choose",  "id":7, "message":"…", "min":1, "max":1,
 *                   "options":[{"i":0,"label":"…"}]} a question, answer with id
 *   {"t":"confirm", "id":8, "text":"…", "yes":"…", "no":"…"}
 *   {"t":"options", "id":9, …} {"t":"input", "id":10, …}
 *   {"t":"order",   "id":11, "options":[…], "destFrom":n}
 *   {"t":"manipulate","id":12, "options":[…], "movable":[…]}
 *   {"t":"turn",    "player":"…"}
 *   {"t":"phase",   "step":"MAIN1", "group":1, "turnPlayer":"…", "stopsHere":true}
 *   {"t":"board",   "seats":[{"name":…,"life":…,"hand":[…],"battlefield":[…]}]}
 *   {"t":"cards"} {"t":"reveal"} {"t":"highlight"} {"t":"focus"}
 *   {"t":"combat"} {"t":"gameOver"} {"t":"alert"} {"t":"rejected"}
 * </pre>
 *
 * <h2>Client to engine</h2>
 * <pre>
 *   {"t":"ok","player":"…"}         press the first button for that seat
 *   {"t":"cancel","player":"…"}     press the second
 *   {"t":"choose","id":7,"picked":[0]}   answer prompt 7 (indices, or a string
 *                                        for an input prompt)
 *   {"t":"card","cardId":42}        tap a card: discard it, play it, cast it
 *   {"t":"seat","name":"…"}         tap a player
 *   {"t":"board"}                   send the table again
 *   {"t":"start","deck":[{"name":"…","count":4}],"first":"me|them|toss"}
 *                                   deal a game from this deck
 *   {"t":"start","deck":[{"name":"…","count":4}],"first":"me|them|toss"}
 *                                   deal a game from this deck
 *   {"t":"stops","steps":["UPKEEP","MAIN1"]}  wake me only at these steps
 *   {"t":"concede"}
 * </pre>
 *
 * <p>The asymmetry is the point and took a running game to discover: buttons are
 * fire-and-forget into {@link IGameController}, which releases a latch the engine
 * is blocked on, while "choose" carries an id because the engine thread is
 * parked waiting for that specific answer to be handed back.
 *
 * <h2>Threads</h2>
 *
 * Netty's event loop is the socket thread; the game runs on its own. Replies
 * therefore arrive on a different thread from the one that is blocked, which is
 * exactly what Forge requires — it throws if its input latch is awaited from the
 * thread it considers the UI.
 *
 * <p>Taps and presses go one step further, onto the input thread, because they
 * can themselves end in a question (see channelRead0) and the socket thread is
 * the only one that can deliver its answer.
 */
public final class BridgeServer {

    private final int port;
    private final BiConsumer<BridgeGui, JsonObject> onReady;
    private final boolean startOnConnect;
    /** Whether this connection's game has begun, so a second start is ignored. */
    private final AtomicBoolean started = new AtomicBoolean();
    private EventLoopGroup boss, work;
    private volatile Channel client;
    private volatile BridgeGui gui;

    /** Messages that drive Forge's input, and so may end up waiting on a question. */
    private static final java.util.Set<String> ON_INPUT_THREAD =
            java.util.Set.of("ok", "cancel", "concede", "card", "seat", "offTable", "declare");

    /**
     * One thread, so taps reach Forge in the order they were made. Not named
     * "Game…": ThreadUtil.isGameThread() goes by that prefix, and this thread
     * stands where desktop Forge's EDT does, not where the game runs.
     */
    private static final java.util.concurrent.ExecutorService INPUT =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "intoplay-input");
                th.setDaemon(true);
                return th;
            });

    /**
     * @param onReady run once a client connects, with the gui it should drive.
     *                This is where a game gets started.
     */
    public BridgeServer(final int port0, final BiConsumer<BridgeGui, JsonObject> onReady0,
                        final boolean startOnConnect0) {
        port = port0;
        onReady = onReady0;
        startOnConnect = startOnConnect0;
    }

    /**
     * Begin this connection's game, once. On Forge's own pool, not a thread of
     * ours — see the handshake below for why that is not a detail.
     */
    private void begin(final BridgeGui g, final JsonObject setup) {
        if (!started.compareAndSet(false, true)) {
            System.out.println("a game is already running on this connection");
            return;
        }
        forge.util.ThreadUtil.invokeInGameThread(() -> onReady.accept(g, setup));
    }

    public BridgeGui gui() {
        return gui;
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        work = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, work)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1 << 20))
                                .addLast(new WebSocketServerProtocolHandler("/play", null, true))
                                .addLast(new Handler());
                    }
                });
        b.bind(port).sync();
        System.out.println("bridge listening on ws://localhost:" + port + "/play");
    }

    public void stop() {
        if (boss != null) boss.shutdownGracefully();
        if (work != null) work.shutdownGracefully();
    }

    private void send(final String json) {
        Channel c = client;
        if (c != null && c.isActive()) {
            c.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    private final class Handler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                client = ctx.channel();
                gui = new BridgeGui(BridgeServer.this::send);
                System.out.println("client connected");
                // Starting the game here, rather than at boot, means the first
                // prompt cannot be sent before anyone is listening to hear it.
                //
                // On Forge's own pool, not a thread of ours, and that is not a
                // detail. ThreadUtil.isGameThread() is
                //
                //     Thread.currentThread().getName().startsWith("Game")
                //
                // and GameAction.invoke() consults it: on the game thread it
                // runs the work inline, otherwise it hands it to that pool. A
                // game running on a thread named anything else therefore sends
                // its own nested work away to a stranger — which is how paying
                // for a spell deadlocked. The engine parked in applyManaToCost
                // waiting for a latch that the mana ability, now executing on
                // an unrelated pool thread, never got far enough to release.
                // No exception, no log line, just a game that stops.
                started.set(false);
                if (startOnConnect) {
                    begin(gui, null);
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
            BridgeGui g = gui;
            if (g == null) {
                return;
            }
            final JsonObject in;
            try {
                in = JsonParser.parseString(frame.text()).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("unparseable from client: " + frame.text());
                return;
            }
            final String t = in.has("t") ? in.get("t").getAsString() : "";
            // A press may name the seat it is for. Omitting it means "whichever
            // seat was just given buttons", which is right for one screen and
            // wrong the moment the phone and the tablet both press.
            final IGameController c = in.has("player")
                    ? g.controllerFor(in.get("player").getAsString())
                    : g.controller();

            // Anything that hands control to Forge's input runs on the input
            // thread, never here. Tapping Mind Stone is why: selectCard reaches
            // getAbilityToPlay, which with two abilities ASKS — and the ask
            // parks its thread until {"t":"choose"} comes back. On this thread
            // that answer is the next frame on the same socket, which a parked
            // event loop can never read. The game froze with no error, and every
            // later tap queued behind it. Answers, board requests and stops stay
            // here: they never block, and an answer must not wait behind the
            // very tap that is waiting for it.
            if (ON_INPUT_THREAD.contains(t)) {
                INPUT.execute(() -> {
                    try {
                        handle(g, t, in, c);
                    } catch (RuntimeException e) {
                        System.out.println("input " + t + " failed: " + e);
                    }
                });
            } else {
                handle(g, t, in, c);
            }
        }

        private void handle(final BridgeGui g, final String t, final JsonObject in, final IGameController c) {
            switch (t) {
                case "ok":
                    if (c != null) c.selectButtonOk();
                    break;
                case "cancel":
                    if (c != null) c.selectButtonCancel();
                    break;
                case "concede":
                    if (c != null) c.concede();
                    break;
                case "choose": {
                    int id = in.has("id") ? in.get("id").getAsInt() : -1;
                    JsonArray picked = in.has("picked") ? in.getAsJsonArray("picked") : new JsonArray();
                    if (!g.answer(id, picked)) {
                        // Usually a double click, or an answer that arrived
                        // after the game moved on. Not fatal, but not silent.
                        System.out.println("no prompt " + id + " was waiting for an answer");
                    }
                    break;
                }
                // Tapping a card. Unlike "choose" this carries no id, because
                // the engine never asked a question — it is waiting to be told
                // what the player touched, and decides for itself whether that
                // was legal. A discard, a land drop and a cast all arrive here.
                case "card": {
                    if (c == null) {
                        break;
                    }
                    forge.game.card.CardView cv = g.card(in.has("cardId") ? in.get("cardId").getAsInt() : -1);
                    if (cv == null) {
                        System.out.println("no such card: " + in);
                        break;
                    }
                    c.selectCard(cv, null, null);
                    break;
                }
                case "seat": {
                    if (c == null) {
                        break;
                    }
                    forge.game.player.PlayerView pv = g.player(
                            in.has("name") ? in.get("name").getAsString() : null);
                    if (pv != null) {
                        c.selectPlayer(pv, null);
                    }
                    break;
                }
                case "board":
                    g.sendBoard();
                    break;
                // The player's deck and who goes first. The game waits for this
                // rather than starting on connect, because until it arrives
                // there is no deck to deal from.
                case "start":
                    begin(g, in);
                    break;
                // Something the opponent's cardboard did to our side, enacted
                // through the engine's own actions. See Declarations.
                case "declare":
                    Declarations.apply(BridgeMain.currentGame, g, BridgeMain.SEAT, in);
                    break;
                // The phase dial, as a message. Each named step is one the
                // player wants to be woken at; an empty list means all of them.
                case "stops": {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    if (in.has("steps")) {
                        for (com.google.gson.JsonElement e : in.getAsJsonArray("steps")) {
                            names.add(e.getAsString());
                        }
                    }
                    g.setStops(names);
                    System.out.println("stops: " + names);
                    break;
                }
                // Aim the spell at something the engine cannot see. The whole
                // fork is for this message.
                //
                // Reaching into the live input is deliberate rather than lazy:
                // Forge's targeting state lives in InputSelectTargets — its own
                // target set, its min/max accounting, its OK button — and going
                // around it via sa.getTargets() would add the phantom while
                // leaving the input thinking nothing had been chosen.
                case "offTable": {
                    if (!(c instanceof forge.player.PlayerControllerHuman human)) {
                        break;
                    }
                    forge.gamemodes.match.input.Input in0 = human.getInputQueue().getInput();
                    if (!(in0 instanceof forge.gamemodes.match.input.InputSelectTargets targeting)) {
                        System.out.println("nothing is waiting for a target right now");
                        break;
                    }
                    String described = in.has("describe")
                            ? in.get("describe").getAsString() : null;
                    String effect = targeting.selectOffTable(described);
                    if (effect == null) {
                        // A restriction even a phantom cannot satisfy. Say so
                        // rather than leaving the player wondering.
                        g.flashIncorrectAction();
                        System.out.println("the ability refused a phantom: " + described);
                        break;
                    }
                    g.offTableAimed(g.focus(), described, effect);
                    // Which ability this is decides whether there is something
                    // to remember (Parallax Wave) — see BridgeGui.noteExileWith.
                    // The input keeps its spell ability private; read, not patched.
                    try {
                        java.lang.reflect.Field f = forge.gamemodes.match.input.InputSelectTargets.class.getDeclaredField("sa");
                        f.setAccessible(true);
                        g.noteExileWith((forge.game.spellability.SpellAbility) f.get(targeting), described, effect);
                    } catch (ReflectiveOperationException e) {
                        System.out.println("could not read the targeting ability: " + e);
                    }
                    break;
                }
                default:
                    System.out.println("unknown message from client: " + t);
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            System.out.println("client disconnected");
            client = null;
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            System.out.println("socket error: " + cause);
            ctx.close();
        }
    }
}
