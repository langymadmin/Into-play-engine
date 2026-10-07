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

    // Two real players (see BridgeMain.runVersus): the host waiting for the
    // second player, then one gui and one screen per player.
    private volatile JsonObject versusHost;
    private volatile Channel versusHostChannel;
    private final java.util.Map<String, BridgeGui> seatGuis = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Channel> seatChannels = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Channel, String> channelSeat = new java.util.concurrent.ConcurrentHashMap<>();

    private void sendSeat(final String seat, final String json) {
        Channel c = seatChannels.get(seat);
        if (c != null && c.isActive()) {
            c.writeAndFlush(new TextWebSocketFrame(json));
        }
        // That player's phone, holding their hand.
        handSeat.forEach((ch, s) -> {
            if (s.equals(seat) && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        });
    }

    /** Versus: each phone and the player whose hand it holds. */
    private final java.util.Map<Channel, String> handSeat = new java.util.concurrent.ConcurrentHashMap<>();

    /** Versus: tell a player's tablet how many phones hold their hand. */
    private void announceSeatScreens(final String seat) {
        long n = handSeat.values().stream().filter(seat::equals).count();
        Channel c = seatChannels.get(seat);
        if (c != null && c.isActive()) {
            c.writeAndFlush(new TextWebSocketFrame("{\"t\":\"screens\",\"hands\":" + n + "}"));
        }
    }

    private static String nameOf(final JsonObject setup, final String fallback) {
        String n = setup != null && setup.has("name") && !setup.get("name").isJsonNull() ? setup.get("name").getAsString().trim() : "";
        return n.isEmpty() ? fallback : (n.length() > 24 ? n.substring(0, 24) : n);
    }

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

    /**
     * Deal a game — ending the one in progress first, if there is one, in the
     * same Java process. Cards take half a minute to load; a new game should
     * not.
     *
     * <p>The old game is retired (its gui goes quiet and lets go of any
     * question it is parked on) and every seat concedes, so its thread runs to
     * "game over" on its own. The new game gets a fresh gui, so nothing the old
     * one still says on its way out reaches the screen.
     */
    private void newGame(final JsonObject setup) {
        // From a versus game (or its lobby) back to a game against the table.
        if (!seatGuis.isEmpty() || versusHost != null) {
            endEverything();
            versusHost = null;
            versusHostChannel = null;
            gui = new BridgeGui(this::send);
        }
        BridgeGui old = gui;
        forge.game.Game running = BridgeMain.currentGame;
        if (started.get() && old != null) {
            System.out.println("new game: ending the one in progress");
            old.retire();
            if (running != null && !running.isGameOver()) {
                INPUT.execute(() -> {
                    for (forge.game.player.Player p : running.getPlayers()) {
                        try {
                            IGameController c = old.controllerFor(p.getName());
                            if (c != null) {
                                c.concede();
                            }
                        } catch (RuntimeException e) {
                            System.out.println("concede " + p.getName() + ": " + e);
                        }
                    }
                });
            }
            gui = new BridgeGui(this::send);
            gui.inheritStops(old);
            started.set(false);
        }
        begin(gui, setup);
    }

    /** Everything that is running stops: the single-screen game and a versus one. */
    private void endEverything() {
        BridgeGui old = gui;
        forge.game.Game running = BridgeMain.currentGame;
        java.util.List<BridgeGui> guis = new java.util.ArrayList<>(seatGuis.values());
        if (old != null) {
            guis.add(old);
        }
        for (BridgeGui g : guis) {
            g.retire();
        }
        if (running != null && !running.isGameOver()) {
            INPUT.execute(() -> {
                for (forge.game.player.Player p : running.getPlayers()) {
                    for (BridgeGui g : guis) {
                        try {
                            IGameController c = g.controllerFor(p.getName());
                            if (c != null) {
                                c.concede();
                                break;
                            }
                        } catch (RuntimeException e) {
                            System.out.println("concede " + p.getName() + ": " + e);
                        }
                    }
                }
            });
        }
        seatGuis.clear();
        seatChannels.clear();
        channelSeat.clear();
        handSeat.clear();
        started.set(false);
    }

    private String lobbyJson(final boolean youHost) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "lobby");
        o.addProperty("host", nameOf(versusHost, "Player 1"));
        o.addProperty("youHost", youHost);
        // Where the second player's device should go (a QR on the host's screen).
        o.add("addresses", BridgeGui.lanAddresses());
        return o.toString();
    }

    /** The first player: their deck and name, waiting for the second. */
    private void hostVersus(final Channel ch, final JsonObject setup) {
        endEverything();
        versusHost = setup;
        versusHostChannel = ch;
        client = ch;
        System.out.println("versus: " + nameOf(setup, "Player 1") + " is waiting for a second player");
        ch.writeAndFlush(new TextWebSocketFrame(lobbyJson(true)));
        // Anyone already connected (the second device opened first) is shown
        // the lobby too.
        for (Channel j : joined) {
            j.writeAndFlush(new TextWebSocketFrame(lobbyJson(false)));
        }
    }

    /** The second player arrives: deal, one gui and one screen each. */
    private void joinVersus(final Channel ch, final JsonObject setup) {
        JsonObject host = versusHost;
        Channel hostCh = versusHostChannel;
        if (host == null || hostCh == null) {
            ch.writeAndFlush(new TextWebSocketFrame("{\"t\":\"idle\"}"));
            return;
        }
        String a = nameOf(host, "Player 1");
        String b = nameOf(setup, "Player 2");
        if (b.equalsIgnoreCase(a)) {
            b = b + " 2";
        }
        final String nameA = a, nameB = b;
        BridgeGui ga = new BridgeGui(json -> sendSeat(nameA, json));
        BridgeGui gb = new BridgeGui(json -> sendSeat(nameB, json));
        seatGuis.put(nameA, ga);
        seatGuis.put(nameB, gb);
        seatChannels.put(nameA, hostCh);
        seatChannels.put(nameB, ch);
        channelSeat.put(hostCh, nameA);
        channelSeat.put(ch, nameB);
        for (BridgeGui g : java.util.List.of(ga, gb)) {
            g.setStops(java.util.List.of("MAIN1", "COMBAT_DECLARE_ATTACKERS", "MAIN2", "END_OF_TURN"));
        }
        gui = ga;
        versusHost = null;
        versusHostChannel = null;
        started.set(true);
        // Who goes first, as the host chose: "me" is the host, "them" the guest.
        String f = host.has("first") ? host.get("first").getAsString() : "toss";
        String first = "me".equals(f) ? nameA : "them".equals(f) ? nameB : null;
        // Each screen is told which seat it is, to come back to it after a reconnect.
        hostCh.writeAndFlush(new TextWebSocketFrame("{\"t\":\"you\",\"seat\":" + com.google.gson.JsonParser.parseString("\"" + nameA.replace("\"", "") + "\"") + "}"));
        ch.writeAndFlush(new TextWebSocketFrame("{\"t\":\"you\",\"seat\":" + com.google.gson.JsonParser.parseString("\"" + nameB.replace("\"", "") + "\"") + "}"));
        System.out.println("versus: " + nameA + " vs " + nameB);
        forge.util.ThreadUtil.invokeInGameThread(() -> {
            try {
                BridgeMain.runVersus(ga, new BridgeMain.JsonPair(nameA, host), gb, new BridgeMain.JsonPair(nameB, setup), first);
            } catch (Throwable t) {
                System.out.println("[versus ended] " + t);
                t.printStackTrace(System.out);
                ga.sendFatal(String.valueOf(t));
                gb.sendFatal(String.valueOf(t));
            }
        });
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
                                // checkStartsWith (the last true): "/play?role=hand"
                                // must reach the handshake too. The three-argument
                                // form's true is allowExtensions, and an exact path
                                // match silently dropped any query string.
                                .addLast(new WebSocketServerProtocolHandler("/play", null, true, 1 << 20, false, true))
                                // Forge's sound effects for the screen (GET /sound/x.mp3).
                                // The metagame for "What are they playing?" (GET /meta/...).
                                .addLast(new MetaService())
                                .addLast(new SoundFiles())
                                // The table screen itself, when started with -Dintoplay.app.
                                .addLast(new AppFiles())
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

    /**
     * Screens that joined the running game rather than starting one: the
     * phone's hand view ({@code /play?role=hand}). One seat, two screens — the
     * tablet shows the table, the phone shows the hand — and both hear
     * everything and may act: a card cast from the phone is the same tap as
     * one cast from the tablet.
     */
    private final java.util.Set<Channel> joined = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * How many phones hold the hand. The tablet turns its own hand row face
     * down while one does, and private choices (scry, a library search) are
     * made on the phone.
     */
    private void announceScreens() {
        send("{\"t\":\"screens\",\"hands\":" + joined.size() + "}");
    }

    private void send(final String json) {
        Channel c = client;
        if (c != null && c.isActive()) {
            c.writeAndFlush(new TextWebSocketFrame(json));
        }
        for (Channel j : joined) {
            if (j.isActive()) {
                j.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    private final class Handler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete hs) {
                // A second screen for the same game (the phone's hand): join,
                // do not start anything. It is told who sits where — the
                // "open" the tablet got when the game began — and asks for the
                // board itself, as every client does on connect.
                // Before the tablet has started anything it simply waits: the
                // game's messages reach it when there is a game.
                if (hs.requestUri() != null && hs.requestUri().contains("role=hand")) {
                    // Versus: the phone of one player ("seat=Name") joins that
                    // player's seat and hears only what that player may see.
                    java.util.regex.Matcher hm = java.util.regex.Pattern.compile("[?&]seat=([^&]+)").matcher(hs.requestUri());
                    if (hm.find()) {
                        String seat = java.net.URLDecoder.decode(hm.group(1), java.nio.charset.StandardCharsets.UTF_8);
                        BridgeGui sg = seatGuis.get(seat);
                        if (sg != null) {
                            handSeat.put(ctx.channel(), seat);
                            channelSeat.put(ctx.channel(), seat);
                            System.out.println("versus: " + seat + "'s phone joined");
                            for (String m : sg.catchUp()) {
                                ctx.channel().writeAndFlush(new TextWebSocketFrame(m));
                            }
                            announceSeatScreens(seat);
                            super.userEventTriggered(ctx, evt);
                            return;
                        }
                    }
                    joined.add(ctx.channel());
                    System.out.println("hand screen joined");
                    announceScreens();
                    if (gui != null) {
                        for (String m : gui.catchUp()) {
                            ctx.channel().writeAndFlush(new TextWebSocketFrame(m));
                        }
                    }
                    super.userEventTriggered(ctx, evt);
                    return;
                }
                // Versus: a player's screen coming back ("seat=Name").
                String uri = hs.requestUri() == null ? "" : hs.requestUri();
                java.util.regex.Matcher sm = java.util.regex.Pattern.compile("[?&]seat=([^&]+)").matcher(uri);
                if (sm.find() && !seatGuis.isEmpty()) {
                    String seat = java.net.URLDecoder.decode(sm.group(1), java.nio.charset.StandardCharsets.UTF_8);
                    BridgeGui sg = seatGuis.get(seat);
                    if (sg != null) {
                        seatChannels.put(seat, ctx.channel());
                        channelSeat.put(ctx.channel(), seat);
                        System.out.println("versus: " + seat + " reconnected");
                        for (String m : sg.catchUp()) {
                            ctx.channel().writeAndFlush(new TextWebSocketFrame(m));
                        }
                        super.userEventTriggered(ctx, evt);
                        return;
                    }
                }
                // Versus: the host is waiting for a second player — this screen
                // may be that player. It is shown the lobby; nothing starts.
                if (versusHost != null && ctx.channel() != versusHostChannel) {
                    ctx.channel().writeAndFlush(new TextWebSocketFrame(lobbyJson(false)));
                    super.userEventTriggered(ctx, evt);
                    return;
                }
                // A versus game is running and this screen is neither player:
                // it must not see a player's hand. Told so, and nothing more.
                if (!seatGuis.isEmpty()) {
                    ctx.channel().writeAndFlush(new TextWebSocketFrame(
                            "{\"t\":\"lobby\",\"full\":true,\"players\":" + seatGuis.keySet().size() + "}"));
                    super.userEventTriggered(ctx, evt);
                    return;
                }
                client = ctx.channel();
                // A game is already running: this is the tablet coming back —
                // a reload, a screen that slept, a dropped Wi-Fi. Rejoin it,
                // as the phone does, rather than dealing a new one; "New game"
                // is a message ({"t":"start"}), never a side effect of a
                // connection.
                forge.game.Game running = BridgeMain.currentGame;
                // "Begun", not "Forge has a game object": through the coin toss
                // and the mulligans there is none yet (or currentGame is still
                // the last, finished one), and a reload there used to deal a
                // second game beside the first — leaving a phone that joined
                // later looking at the wrong one.
                boolean live = gui != null && started.get() && !gui.hasEnded();
                if (live) {
                    System.out.println("client reconnected to the running game");
                    for (String m : gui.catchUp()) {
                        ctx.channel().writeAndFlush(new TextWebSocketFrame(m));
                    }
                    ctx.channel().writeAndFlush(new TextWebSocketFrame("{\"t\":\"screens\",\"hands\":" + joined.size() + "}"));
                    super.userEventTriggered(ctx, evt);
                    return;
                }
                gui = new BridgeGui(BridgeServer.this::send);
                System.out.println("client connected");
                // A phone may have joined first.
                ctx.channel().writeAndFlush(new TextWebSocketFrame("{\"t\":\"screens\",\"hands\":" + joined.size() + "}"));
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
                } else {
                    // No game here (a fresh engine, or the last one ended): a
                    // screen that reconnects with an old board must not keep
                    // drawing it as if it were live.
                    ctx.channel().writeAndFlush(new TextWebSocketFrame("{\"t\":\"idle\"}"));
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
            final JsonObject in;
            try {
                in = JsonParser.parseString(frame.text()).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("unparseable from client: " + frame.text());
                return;
            }
            final String t = in.has("t") ? in.get("t").getAsString() : "";
            // The second player joining a waiting versus game.
            if ("join".equals(t)) {
                joinVersus(ctx.channel(), in);
                return;
            }
            // The host starting a game against a second player on their own
            // screen: wait for them.
            if ("start".equals(t) && in.has("versus") && in.get("versus").getAsBoolean()) {
                hostVersus(ctx.channel(), in);
                return;
            }
            // A versus player's screen speaks for its own gui only.
            String seatOf = channelSeat.get(ctx.channel());
            BridgeGui g = seatOf != null ? seatGuis.get(seatOf) : gui;
            if (g == null) {
                return;
            }
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
                    newGame(in);
                    break;
                // "Take that back": the game as it was before our last action
                // (see Rewind). Run on the game's own pool while our seat waits
                // for priority, as Forge's dev-mode state setup is.
                case "rewind": {
                    forge.game.Game game = BridgeMain.currentGame;
                    if (game == null) {
                        break;
                    }
                    game.getAction().invoke(() -> {
                        try {
                            if (Rewind.back(game)) {
                                game.getAction().checkStateEffects(true);
                                // The restore writes the game, not its view: the
                                // stack, combat and turn the screen reads have to
                                // be told, or a taken-back spell stays drawn.
                                game.updateStackForView();
                                game.updateCombatForView();
                                game.updatePhaseForView();
                                game.updateTurnForView();
                                game.updatePlayerTurnForView();
                                g.tell(java.util.List.of("Taken back — the game is as it was before that."));
                            } else {
                                g.tell(java.util.List.of("Nothing to take back yet."));
                            }
                        } catch (RuntimeException e) {
                            System.out.println("rewind failed: " + e);
                            e.printStackTrace(System.out);
                            g.tell(java.util.List.of("Could not take that back: " + e.getMessage()));
                        }
                        if (BridgeMain.currentForwarder != null) {
                            BridgeMain.currentForwarder.flush();
                        }
                        g.sendBoard();
                        g.reshowPrompts();
                    });
                    break;
                }
                // Something the opponent's cardboard did to our side, enacted
                // through the engine's own actions. See Declarations.
                case "declare":
                    Declarations.apply(BridgeMain.currentGame, g, BridgeMain.SEAT, in);
                    break;
                // The table, defined from the panel: {graveyardTypes, creatures,
                // addToGraveyard:[name], removeFromGraveyard:[id]}. See TheirSide.
                case "table": {
                    TheirSide side = TheirSide.current;
                    forge.game.Game game = BridgeMain.currentGame;
                    if (side == null || game == null) {
                        break;
                    }
                    game.getAction().invoke(() -> {
                        try {
                            side.apply(in);
                        } catch (RuntimeException e) {
                            // invoke's pool swallows exceptions; say it.
                            System.out.println("table failed: " + e);
                            e.printStackTrace(System.out);
                            g.tell(java.util.List.of("The engine could not apply that: " + e.getMessage()));
                        }
                        game.getAction().checkStateEffects(true);
                        if (BridgeMain.currentForwarder != null) {
                            BridgeMain.currentForwarder.flush();
                        }
                        g.sendBoard();
                        g.reshowPrompts();
                    });
                    break;
                }

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
                    if (in.has("theirSteps")) {
                        java.util.List<String> theirs = new java.util.ArrayList<>();
                        for (com.google.gson.JsonElement e : in.getAsJsonArray("theirSteps")) {
                            theirs.add(e.getAsString());
                        }
                        g.setTheirStops(theirs);
                        System.out.println("their stops: " + theirs);
                    }
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
                    // Their REAL card (Control Magic, Clone, Hostage Taker): it
                    // is put on their side first, quietly — it is already in
                    // play at the table — and then aimed at like any card,
                    // so what the spell does to it is real. On the game's
                    // pool, then back here to select it.
                    if (in.has("real") && in.get("real").getAsBoolean() && described != null
                            && TheirSide.current != null && BridgeMain.currentGame != null) {
                        final String seatName = in.has("seat") ? in.get("seat").getAsString() : null;
                        final IGameController ctl = c;
                        BridgeMain.currentGame.getAction().invoke(() -> {
                            forge.game.card.Card real = TheirSide.current.putOnBattlefieldFor(described, false, seatName);
                            if (real == null) {
                                g.tell(java.util.List.of("The engine doesn't know a card called \"" + described + "\"."));
                                return;
                            }
                            // The input fixed its candidates when targeting began,
                            // before this card existed. Added to that list — read
                            // and written by reflection, not patched — if the
                            // spell could really target it.
                            try {
                                java.lang.reflect.Field sf = forge.gamemodes.match.input.InputSelectTargets.class.getDeclaredField("sa");
                                sf.setAccessible(true);
                                forge.game.spellability.SpellAbility tsa = (forge.game.spellability.SpellAbility) sf.get(targeting);
                                java.lang.reflect.Field cf = forge.gamemodes.match.input.InputSelectTargets.class.getDeclaredField("choices");
                                cf.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                java.util.List<forge.game.card.Card> choices = (java.util.List<forge.game.card.Card>) cf.get(targeting);
                                if (tsa != null && tsa.canTarget(real) && !choices.contains(real)) {
                                    // A copy: the list handed in may be read-only.
                                    java.util.List<forge.game.card.Card> more = new java.util.ArrayList<>(choices);
                                    more.add(real);
                                    cf.set(targeting, more);
                                }
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                System.out.println("could not add the real card to the choices: " + e);
                            }
                            g.sendBoard();
                            INPUT.execute(() -> ctl.selectCard(forge.game.card.CardView.get(real), null, null));
                        });
                        break;
                    }
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
            channelSeat.remove(ctx.channel());
            String phoneOf = handSeat.remove(ctx.channel());
            if (phoneOf != null) {
                System.out.println("versus: " + phoneOf + "'s phone left");
                announceSeatScreens(phoneOf);
                return;
            }
            if (joined.remove(ctx.channel())) {
                System.out.println("hand screen disconnected");
                announceScreens();
                return;
            }
            if (client == ctx.channel()) {
                System.out.println("client disconnected");
                client = null;
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            System.out.println("socket error: " + cause);
            // A message that failed is not a dead connection: closing here left
            // the screen on "Connecting…" for good. Only a real I/O error closes.
            if (cause instanceof java.io.IOException) {
                ctx.close();
            } else {
                cause.printStackTrace(System.out);
            }
        }
    }
}
