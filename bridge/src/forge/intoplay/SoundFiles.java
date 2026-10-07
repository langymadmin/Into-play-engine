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

/**
 * Forge's own sound effects, served from its resource folder:
 * {@code GET /sound/tap.mp3}.
 *
 * <p>The files stay in this (GPL) repository and are only played by the
 * screen, which is told which one fits each game event (BridgeGui's
 * {@code sound} message, chosen by Forge's EventVisualizer). Nothing is copied
 * into the app.
 *
 * <p>Sits after the WebSocket handler, which passes on every request that is
 * not the {@code /play} upgrade.
 */
final class SoundFiles extends SimpleChannelInboundHandler<FullHttpRequest> {

    static volatile Path dir;

    // Only /sound/: the rest goes on to AppFiles.
    @Override
    public boolean acceptInboundMessage(final Object msg) {
        return msg instanceof FullHttpRequest r && r.uri().startsWith("/sound/");
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        String uri = req.uri();
        int q = uri.indexOf('?');
        if (q >= 0) {
            uri = uri.substring(0, q);
        }
        FullHttpResponse res;
        // Names only — letters, digits, underscores — so no path can escape.
        if (dir != null && uri.matches("/sound/[A-Za-z0-9_]+\\.(mp3|wav)")) {
            Path f = dir.resolve(uri.substring("/sound/".length()));
            try {
                byte[] bytes = Files.readAllBytes(f);
                res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
                res.headers().set(HttpHeaderNames.CONTENT_TYPE, uri.endsWith(".wav") ? "audio/wav" : "audio/mpeg");
                res.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=86400");
            } catch (java.io.IOException e) {
                res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            }
        } else {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        }
        res.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }

    static void init(final String res) {
        dir = Paths.get(res, "sound").toAbsolutePath().normalize();
    }
}
