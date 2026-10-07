package forge.intoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the opponent is probably playing — the metagame, for "What are they
 * playing?" at New game.
 *
 * <pre>
 *   GET /meta/archetypes/modern          the format's archetypes, by share,
 *                                        with three key cards each
 *   GET /meta/archetype/modern-eldrazi   the cards that archetype plays: how
 *                                        many copies on average, in what share
 *                                        of decks (main deck and sideboard)
 *   GET /meta/commander/Atraxa, Praetors' Voice
 *                                        a commander's most played cards
 *   …?refresh=1                          fetch again now
 * </pre>
 *
 * <p>Constructed comes from MTGGoldfish's metagame pages, Commander from
 * EDHREC. Fetched here, by the engine on the player's own computer — the
 * browser cannot read those sites (they do not allow it cross-origin) — once,
 * politely (one request per page, a plain User-Agent that says what this is),
 * and kept on disk for days: refreshed at home, it still works offline at the
 * venue. A failed fetch falls back to whatever was kept, marked stale.
 *
 * <p>For personal use. MTGGoldfish's terms likely do not allow automated
 * collection at scale; a hosted Into Play would need their permission or its
 * own numbers (e.g. from the official MTGO decklists).
 */
final class MetaService extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Duration KEEP = Duration.ofDays(3);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) IntoPlay/0.3 (personal use; one request per page, cached)";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "intoplay-meta");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile Path cacheDir = Paths.get("bridge", "cache", "meta");

    MetaService() {
        super(false); // released by hand: the request is answered on another thread
    }

    @Override
    public boolean acceptInboundMessage(final Object msg) {
        return msg instanceof FullHttpRequest r && r.uri().startsWith("/meta/");
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        final String uri = req.uri();
        req.release();
        POOL.execute(() -> {
            JsonObject body;
            HttpResponseStatus status = HttpResponseStatus.OK;
            try {
                body = route(uri);
                if (body == null) {
                    status = HttpResponseStatus.NOT_FOUND;
                    body = new JsonObject();
                    body.addProperty("error", "unknown");
                }
            } catch (Exception e) {
                status = HttpResponseStatus.BAD_GATEWAY;
                body = new JsonObject();
                body.addProperty("error", String.valueOf(e.getMessage()));
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            res.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            res.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        });
    }

    private static JsonObject route(final String uri) throws Exception {
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        boolean refresh = q >= 0 && uri.substring(q).contains("refresh=1");
        String[] parts = path.split("/", 4); // "", "meta", kind, arg
        if (parts.length < 4) {
            return null;
        }
        String arg = java.net.URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
        switch (parts[2]) {
            case "archetypes":
                if (!arg.matches("[a-z]+")) {
                    return null;
                }
                return cached("archetypes-" + arg, refresh, () -> archetypes(arg));
            case "archetype":
                if (!arg.matches("[a-z0-9-]+")) {
                    return null;
                }
                return cached("archetype-" + arg, refresh, () -> archetype(arg));
            case "commander": {
                String slug = edhrecSlug(arg);
                if (slug.isEmpty()) {
                    return null;
                }
                return cached("commander-" + slug, refresh, () -> commander(slug, arg));
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Kept on disk
    // ------------------------------------------------------------------

    interface Fetch {
        JsonObject get() throws Exception;
    }

    private static synchronized JsonObject cached(final String key, final boolean refresh, final Fetch fetch) throws Exception {
        Path f = cacheDir.resolve(key + ".json");
        JsonObject kept = null;
        if (Files.exists(f)) {
            try {
                kept = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                kept = null;
            }
        }
        if (kept != null && !refresh && kept.has("fetchedAt")
                && System.currentTimeMillis() - kept.get("fetchedAt").getAsLong() < KEEP.toMillis()) {
            return kept;
        }
        try {
            JsonObject fresh = fetch.get();
            fresh.addProperty("fetchedAt", System.currentTimeMillis());
            Files.createDirectories(cacheDir);
            Files.writeString(f, fresh.toString(), StandardCharsets.UTF_8);
            return fresh;
        } catch (Exception e) {
            if (kept != null) {
                kept.addProperty("stale", true); // offline: what we have
                return kept;
            }
            throw e;
        }
    }

    private static String get(final String url) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA).header("Accept-Language", "en").GET().build();
        HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new java.io.IOException(url + " answered " + res.statusCode());
        }
        return res.body();
    }

    // ------------------------------------------------------------------
    // MTGGoldfish
    // ------------------------------------------------------------------

    private static final Pattern TILE_SPLIT = Pattern.compile("<div class='archetype-tile' id='");
    private static final Pattern TILE_LINK = Pattern.compile("href=\"/archetype/([a-z0-9-]+)#online\">([^<]+)</a>");
    private static final Pattern TILE_LI = Pattern.compile("<li>([^<]+)</li>");
    private static final Pattern TILE_SHARE = Pattern.compile("metagame-percentage'>.*?archetype-tile-statistic-value'>\\s*([0-9.]+)%", Pattern.DOTALL);

    private static JsonObject archetypes(final String format) throws Exception {
        String html = get("https://www.mtggoldfish.com/metagame/" + format + "/full");
        String[] tiles = TILE_SPLIT.split(html);
        JsonArray list = new JsonArray();
        for (int i = 1; i < tiles.length; i++) {
            String t = tiles[i];
            Matcher link = TILE_LINK.matcher(t);
            if (!link.find()) {
                continue;
            }
            JsonObject a = new JsonObject();
            a.addProperty("slug", link.group(1));
            a.addProperty("name", unescape(link.group(2)));
            Matcher share = TILE_SHARE.matcher(t);
            if (share.find()) {
                a.addProperty("share", Double.parseDouble(share.group(1)));
            }
            JsonArray keys = new JsonArray();
            Matcher li = TILE_LI.matcher(t);
            while (li.find() && keys.size() < 3) {
                keys.add(unescape(li.group(1)));
            }
            a.add("keyCards", keys);
            list.add(a);
        }
        if (list.isEmpty()) {
            throw new java.io.IOException("no archetypes found for " + format);
        }
        JsonObject o = new JsonObject();
        o.addProperty("format", format);
        o.addProperty("source", "MTGGoldfish");
        o.add("archetypes", list);
        return o;
    }

    private static final Pattern SECTION = Pattern.compile("<h3>([^<]+)</h3>");
    private static final Pattern CARD = Pattern.compile(
            "price-card-invisible-label'>([^<]+)</span>.*?archetype-breakdown-featured-card-text'>\\s*([0-9.]+) in ([0-9.]+)% of decks",
            Pattern.DOTALL);

    private static JsonObject archetype(final String slug) throws Exception {
        String html = get("https://www.mtggoldfish.com/archetype/" + slug);
        int start = html.indexOf("deck-archetype-breakdown");
        if (start < 0) {
            throw new java.io.IOException("no card breakdown on " + slug);
        }
        String part = html.substring(start);
        // Sections ("Creatures", …, "Sideboard") by position, to tag each card.
        java.util.TreeMap<Integer, String> sections = new java.util.TreeMap<>();
        Matcher s = SECTION.matcher(part);
        while (s.find()) {
            sections.put(s.start(), s.group(1).trim());
        }
        Map<String, JsonObject> cards = new LinkedHashMap<>();
        Matcher m = CARD.matcher(part);
        while (m.find()) {
            String name = unescape(m.group(1)).trim();
            Map.Entry<Integer, String> sec = sections.floorEntry(m.start());
            String section = sec == null ? "" : sec.getValue();
            if (cards.containsKey(name)) {
                continue; // main deck first
            }
            JsonObject c = new JsonObject();
            c.addProperty("name", name);
            c.addProperty("avg", Double.parseDouble(m.group(2)));
            c.addProperty("pct", Double.parseDouble(m.group(3)));
            c.addProperty("section", section);
            if ("Sideboard".equalsIgnoreCase(section)) {
                c.addProperty("sideboard", true);
            }
            cards.put(name, c);
        }
        if (cards.isEmpty()) {
            throw new java.io.IOException("no cards read on " + slug);
        }
        JsonArray list = new JsonArray();
        cards.values().stream()
                .sorted((x, y) -> Double.compare(y.get("pct").getAsDouble(), x.get("pct").getAsDouble()))
                .forEach(list::add);
        JsonObject o = new JsonObject();
        o.addProperty("slug", slug);
        o.addProperty("source", "MTGGoldfish");
        o.add("cards", list);
        return o;
    }

    // ------------------------------------------------------------------
    // EDHREC
    // ------------------------------------------------------------------

    static String edhrecSlug(final String name) {
        String n = java.text.Normalizer.normalize(name.toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        n = n.split(" // ")[0];
        return n.replaceAll("[^a-z0-9 -]", "").trim().replaceAll("\\s+", "-");
    }

    private static JsonObject commander(final String slug, final String name) throws Exception {
        JsonObject root = JsonParser.parseString(get("https://json.edhrec.com/pages/commanders/" + slug + ".json")).getAsJsonObject();
        JsonArray lists = root.getAsJsonObject("container").getAsJsonObject("json_dict").getAsJsonArray("cardlists");
        Map<String, JsonObject> cards = new LinkedHashMap<>();
        for (JsonElement le : lists) {
            JsonObject l = le.getAsJsonObject();
            String header = l.has("header") ? l.get("header").getAsString() : "";
            for (JsonElement ce : l.getAsJsonArray("cardviews")) {
                JsonObject cv = ce.getAsJsonObject();
                if (!cv.has("name") || !cv.has("num_decks") || !cv.has("potential_decks")) {
                    continue;
                }
                double pot = cv.get("potential_decks").getAsDouble();
                double pct = pot > 0 ? Math.round(1000.0 * cv.get("num_decks").getAsDouble() / pot) / 10.0 : 0;
                String n = cv.get("name").getAsString();
                JsonObject prev = cards.get(n);
                if (prev == null || prev.get("pct").getAsDouble() < pct) {
                    JsonObject c = new JsonObject();
                    c.addProperty("name", n);
                    c.addProperty("pct", pct);
                    c.addProperty("avg", 1);
                    c.addProperty("section", header);
                    cards.put(n, c);
                }
            }
        }
        JsonArray list = new JsonArray();
        cards.values().stream()
                .sorted((x, y) -> Double.compare(y.get("pct").getAsDouble(), x.get("pct").getAsDouble()))
                .limit(150)
                .forEach(list::add);
        JsonObject o = new JsonObject();
        o.addProperty("commander", name);
        o.addProperty("slug", slug);
        o.addProperty("source", "EDHREC");
        o.add("cards", list);
        return o;
    }

    private static String unescape(final String s) {
        return s.replace("&#39;", "'").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
