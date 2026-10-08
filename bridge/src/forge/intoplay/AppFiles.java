package forge.intoplay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The table screen itself, served by the engine: the app's built files
 * ({@code into-play/dist}), so one process is the whole thing — no dev server,
 * no second window. The tablet opens {@code http://localhost:8099/?ui=3}, a
 * phone {@code http://<this PC>:8099/?ui=3&view=hand}.
 *
 * <p>Only when started with {@code -Dintoplay.app=<folder>}; otherwise every
 * request that reaches here is answered 404, as before. The app's code is not
 * part of this repository — it is read from that folder at run time.
 *
 * <p>Last in the pipeline: everything the WebSocket upgrade, /meta and /sound
 * did not take. A path that is not a file is the app's own route, answered
 * with index.html.
 */
final class AppFiles extends SimpleChannelInboundHandler<FullHttpRequest> {

    static volatile Path dir;

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        String uri = req.uri();
        int q = uri.indexOf('?');
        if (q >= 0) {
            uri = uri.substring(0, q);
        }
        FullHttpResponse res = null;
        Path root = dir;
        // "Is this page the engine's?" - the app asks, so its homepage can
        // send a chosen deck to the engine's table instead of a Firebase game.
        if ("/engine.json".equals(uri)) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.copiedBuffer("{\"engine\":true}", java.nio.charset.StandardCharsets.UTF_8));
            res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            root = null;
        }
        if (root != null) {
            Path f = null;
            try {
                String rel = java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8).replaceFirst("^/+", "");
                Path p = root.resolve(rel).normalize();
                // Never outside the app folder.
                if (p.startsWith(root) && Files.isRegularFile(p)) {
                    f = p;
                }
            } catch (IllegalArgumentException e) { // bad %-escape, or a name Windows refuses
                f = null;
            }
            // The app's own routes; a missing asset (a dotted name) stays a 404.
            if (f == null && !uri.substring(uri.lastIndexOf('/') + 1).contains(".")) {
                f = root.resolve("index.html");
            }
            if (f != null && Files.isRegularFile(f)) {
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
                    String name = f.getFileName().toString();
                    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
                    res.headers().set(HttpHeaderNames.CONTENT_TYPE, TYPES.getOrDefault(ext, "application/octet-stream"));
                    // Vite names its bundles by content hash: those keep forever.
                    // index.html always comes fresh, so a rebuild shows at once.
                    res.headers().set(HttpHeaderNames.CACHE_CONTROL,
                            f.getParent().endsWith("assets") ? "max-age=31536000, immutable" : "no-cache");
                } catch (java.io.IOException e) {
                    res = null;
                }
            }
        }
        if (res == null) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        }
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }

    /** From {@code -Dintoplay.app}; logged so a wrong folder is easy to spot. */
    static void init() {
        String p = System.getProperty("intoplay.app");
        if (p == null || p.isBlank()) {
            return;
        }
        // Taken even when not built yet: the launcher builds it while the
        // cards load, and every request reads the folder afresh.
        dir = Paths.get(p).toAbsolutePath().normalize();
        System.out.println("serving the app from " + dir
                + (Files.isRegularFile(dir.resolve("index.html")) ? "" : " (not built yet - npm run build)"));
    }
}
