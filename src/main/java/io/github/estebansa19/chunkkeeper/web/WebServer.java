package io.github.estebansa19.chunkkeeper.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.estebansa19.chunkkeeper.Main;
import io.github.estebansa19.chunkkeeper.Project;
import io.github.estebansa19.chunkkeeper.game.GameVersions;
import io.github.estebansa19.chunkkeeper.game.ServerJars;
import io.github.estebansa19.chunkkeeper.plan.Applier;
import io.github.estebansa19.chunkkeeper.plan.DryRun;
import io.github.estebansa19.chunkkeeper.plan.Selection;
import io.github.estebansa19.chunkkeeper.render.BlockColors;
import io.github.estebansa19.chunkkeeper.scan.ChunkInspector;
import io.github.estebansa19.chunkkeeper.scan.RegionData;
import io.github.estebansa19.chunkkeeper.scan.WorldScan;
import io.github.estebansa19.chunkkeeper.world.Dimension;
import io.github.estebansa19.chunkkeeper.world.World;
import io.github.estebansa19.chunkkeeper.world.WorldFinder;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/** Local-only HTTP server: serves the single-page UI and its JSON API. One world is open at a time. */
public final class WebServer {
    private final Path dataRoot;
    private final String token;
    private HttpServer server;
    private int port;

    private final ServerJars servers;
    private volatile Session session;
    private volatile Applier.Progress applyProgress;
    private volatile ServerJars.Download download;

    private record Session(World world, WorldScan scan, Project project) {}

    public WebServer(Path dataRoot) {
        this.dataRoot = dataRoot;
        this.servers = new ServerJars(dataRoot);
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public int start(int preferredPort) throws IOException {
        InetAddress lo = InetAddress.getLoopbackAddress();
        IOException last = null;
        for (int p : new int[] { preferredPort, preferredPort + 1, preferredPort + 2, preferredPort + 3, 0 }) {
            try {
                server = HttpServer.create(new InetSocketAddress(lo, p), 0);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last;
        server.setExecutor(Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "http"); t.setDaemon(true); return t; }));
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        return port;
    }

    public String url() { return "http://127.0.0.1:" + port + "/"; }

    public void openWorld(Path dir) throws IOException {
        World w = World.open(dir);
        Session old = session;
        if (old != null) old.scan().close();
        Project project = new Project(dataRoot, w);
        Path jar = BlockColors.findClientJar(w.versionName);
        BlockColors colors = jar == null ? BlockColors.builtIn() : BlockColors.fromClientJar(jar, dataRoot.resolve("colors"));
        WorldScan scan = new WorldScan(w, project.cache(), colors);
        session = new Session(w, scan, project);
        scan.scanAll();
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = Optional.ofNullable(ex.getRequestHeaders().getFirst("Host")).orElse("");
            if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) { send(ex, 403, "text/plain", "forbidden host"); return; }
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith("/api/")) { serveStatic(ex, path); return; }
            if (!token.equals(ex.getRequestHeaders().getFirst("X-CK-Token")) && !token.equals(query(ex).get("t"))) {
                send(ex, 403, "text/plain", "missing token");
                return;
            }
            api(ex, path.substring(5), query(ex));
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String route, Map<String, String> q) throws Exception {
        String method = ex.getRequestMethod();
        switch (route) {
            case "worlds": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (WorldFinder.Found f : WorldFinder.find()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", f.dir().toString());
                    m.put("folder", f.dir().getFileName().toString());
                    m.put("launcher", f.launcher());
                    m.put("instance", f.instance());
                    try {
                        World w = World.open(f.dir());
                        m.put("name", w.levelName);
                        m.put("version", w.versionName);
                        m.put("lastPlayed", w.lastPlayed);
                    } catch (Exception e) {
                        m.put("name", f.dir().getFileName().toString());
                        m.put("error", e.getMessage());
                    }
                    out.add(m);
                }
                out.sort(Comparator.comparingLong((Map<String, Object> m) -> m.get("lastPlayed") instanceof Long l ? -l : 0L));
                sendJson(ex, 200, Map.of("worlds", out, "app", Main.VERSION));
                return;
            }
            case "open": {
                requirePost(method);
                String p = String.valueOf(Json.object(body(ex)).get("path"));
                Path dir = Paths.get(p.trim().replaceAll("^\"|\"$", ""));
                if (!World.isWorld(dir) && World.isWorld(dir.getParent() == null ? dir : dir.getParent())) dir = dir.getParent();
                if (!World.isWorld(dir)) throw new IllegalArgumentException("No Minecraft world in " + dir + " (level.dat not found)");
                openWorld(dir);
                sendJson(ex, 200, sessionInfo());
                return;
            }
            case "session": {
                sendJson(ex, 200, session == null ? Map.of("open", false, "app", Main.VERSION) : sessionInfo());
                return;
            }
            case "status": {
                Session s = need();
                Map<String, Object> m = new LinkedHashMap<>();
                Map<String, Object> dims = new LinkedHashMap<>();
                for (Dimension d : s.world().dimensions) {
                    int[] p = s.scan().progress().getOrDefault(d.key(), new int[] { 0, 0 });
                    int done;
                    synchronized (p) { done = p[0]; }
                    dims.put(d.key(), Map.of("done", done, "total", p[1], "finished", s.scan().dimensionDone(d)));
                }
                m.put("dims", dims);
                m.put("locked", s.world().isLocked());
                sendJson(ex, 200, m);
                return;
            }
            case "region": {
                Session s = need();
                Dimension d = dim(s, q);
                RegionData r = s.scan().region(d, Integer.parseInt(q.get("rx")), Integer.parseInt(q.get("rz")));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rx", r.rx); m.put("rz", r.rz);
                m.put("state", r.state); m.put("dv", r.dataVersion); m.put("inh", r.inhabitedSec); m.put("reasons", r.reasons);
                m.put("pb", r.playerBlocks); m.put("sb", r.structureBlocks); m.put("be", r.playerBlockEntities); m.put("ci", r.containersWithItems);
                m.put("ent", r.playerEntities); m.put("vil", r.villagers); m.put("portal", r.portalBlocks); m.put("blend", r.blending);
                Map<String, Object> st = new LinkedHashMap<>();
                r.starts.forEach((k, v) -> st.put(String.valueOf(k), v));
                m.put("starts", st);
                m.put("errors", r.errors.size() > 20 ? r.errors.subList(0, 20) : r.errors);
                sendJson(ex, 200, m);
                return;
            }
            case "tile": {
                Session s = need();
                Dimension d = dim(s, q);
                int lod = Math.max(0, Math.min(2, Integer.parseInt(q.getOrDefault("lod", "0"))));
                Path tile = s.scan().tile(d, Integer.parseInt(q.get("rx")), Integer.parseInt(q.get("rz")), lod);
                byte[] b = Files.readAllBytes(tile);
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                return;
            }
            case "global": {
                Session s = need();
                Dimension d = dim(s, q);
                if (!s.scan().dimensionDone(d)) { sendJson(ex, 200, Map.of("pending", true)); return; }
                List<long[]> l = new ArrayList<>();
                s.scan().globalReasons(d).forEach((k, v) -> l.add(new long[] { WorldScan.keyX(k), WorldScan.keyZ(k), v }));
                sendJson(ex, 200, Map.of("reasons", l, "notes", s.scan().globalNotes(d)));
                return;
            }
            case "selection": {
                Session s = need();
                Dimension d = dim(s, q);
                Path f = s.project().selection(d.key());
                if (method.equals("PUT") || method.equals("POST")) {
                    String text = body(ex);
                    Set<Long> sel;
                    synchronized (this) { // one writer at a time, in arrival order
                        sel = Selection.parse(text, text.contains("inverted") ? s.scan().existingChunks(d) : null);
                        Selection.save(f, sel);
                    }
                    sendJson(ex, 200, Map.of("ok", true, "count", sel.size()));
                } else {
                    String text = Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
                    send(ex, 200, "text/plain; charset=utf-8", text);
                }
                return;
            }
            case "chunk": {
                Session s = need();
                sendJson(ex, 200, ChunkInspector.inspect(dim(s, q), Integer.parseInt(q.get("cx")), Integer.parseInt(q.get("cz"))));
                return;
            }
            case "dryrun": {
                requirePost(method);
                Session s = need();
                Map<String, Object> req = Json.object(body(ex));
                sendJson(ex, 200, DryRun.run(s.scan(), keepSets(s, req), target(s, req)));
                return;
            }
            case "versions": {
                Session s = need();
                List<Map<String, Object>> list = new ArrayList<>();
                for (GameVersions.Installed i : GameVersions.installed())
                    if (i.dataVersion() >= s.world().dataVersion) list.add(Map.of("id", i.id(), "dataVersion", i.dataVersion()));
                if (list.isEmpty()) list.add(Map.of("id", s.world().versionName, "dataVersion", s.world().dataVersion));
                String inst = GameVersions.instanceVersion(s.world().dir), def = String.valueOf(list.get(0).get("id"));
                for (Map<String, Object> v : list) if (v.get("id").equals(inst)) def = inst;
                sendJson(ex, 200, Map.of("versions", list, "default", def));
                return;
            }
            case "server": {
                String v = String.valueOf(q.get("version"));
                Map<String, Object> m = new LinkedHashMap<>(servers.status(v));
                ServerJars.Download d = download;
                if (d != null && d.version.equals(v)) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("phase", d.phase); dm.put("done", d.done.get()); dm.put("total", d.total.get());
                    dm.put("finished", d.finished); dm.put("error", d.error);
                    m.put("download", dm);
                }
                sendJson(ex, 200, m);
                return;
            }
            case "server/download": {
                requirePost(method);
                String v = String.valueOf(Json.object(body(ex)).get("version"));
                servers.dir(v); // validates the name
                ServerJars.Download running = download;
                if (running != null && !running.finished) throw new IllegalStateException("A download is already running");
                ServerJars.Download d = new ServerJars.Download(v);
                download = d;
                Thread t = new Thread(() -> {
                    try { servers.download(d); } catch (Exception e) { d.error = e.getMessage() == null ? e.toString() : e.getMessage(); } finally { d.finished = true; }
                }, "server-download");
                t.setDaemon(true);
                t.start();
                sendJson(ex, 200, Map.of("started", true));
                return;
            }
            case "server/import": {
                requirePost(method);
                Map<String, Object> req = Json.object(body(ex));
                try {
                    servers.importJar(String.valueOf(req.get("version")), Paths.get(String.valueOf(req.get("path")).trim().replaceAll("^\"|\"$", "")));
                } catch (IOException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
                sendJson(ex, 200, servers.status(String.valueOf(req.get("version"))));
                return;
            }
            case "server/eula": {
                requirePost(method);
                Map<String, Object> req = Json.object(body(ex));
                servers.setEula(String.valueOf(req.get("version")), Boolean.TRUE.equals(req.get("accept")));
                sendJson(ex, 200, servers.status(String.valueOf(req.get("version"))));
                return;
            }
            case "defaults": {
                Session s = need();
                Path parent = s.world().dir.getParent();
                String base = s.world().dir.getFileName() + " (regenerated)";
                Path out = parent.resolve(base);
                for (int i = 2; Files.exists(out); i++) out = parent.resolve(base.replace(")", " " + i + ")"));
                sendJson(ex, 200, Map.of("output", out.toString(), "name", s.world().levelName + " (regenerated)"));
                return;
            }
            case "apply": {
                Session s = need();
                if (method.equals("GET")) {
                    Applier.Progress p = applyProgress;
                    if (p == null) { sendJson(ex, 200, Map.of("running", false)); return; }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("phase", p.phase); m.put("done", p.done.get()); m.put("total", p.total.get());
                    m.put("error", p.error); m.put("result", p.result);
                    m.put("running", p.result == null && p.error == null);
                    sendJson(ex, 200, m);
                    return;
                }
                requirePost(method);
                Applier.Progress running = applyProgress;
                if (running != null && running.result == null && running.error == null) throw new IllegalStateException("An apply is already running");
                Map<String, Object> req = Json.object(body(ex));
                Map<String, Set<Long>> keep = keepSets(s, req);
                if (keep.isEmpty()) throw new IllegalArgumentException("No dimension is set to be regenerated");
                boolean sharp = "sharp".equals(req.get("borders"));
                ServerJars.Ready upgrade = null;
                if (sharp && needsUpgrade(s, keep, target(s, req))) {
                    try { upgrade = servers.ready(target(s, req).version()); } catch (IOException e) { throw new IllegalStateException(e.getMessage()); }
                }
                Applier.Options o = new Applier.Options(Paths.get(String.valueOf(req.get("output")).trim()), keep, sharp,
                    req.get("newName") == null ? null : String.valueOf(req.get("newName")), upgrade);
                Applier.Progress p = new Applier.Progress();
                applyProgress = p;
                Thread t = new Thread(() -> {
                    try { p.result = Applier.run(s.world(), o, p); } catch (Exception e) { p.error = e.getMessage() == null ? e.toString() : e.getMessage(); }
                }, "apply");
                t.setDaemon(true);
                t.start();
                sendJson(ex, 200, Map.of("started", true));
                return;
            }
            case "reveal": {
                requirePost(method);
                Path p = Paths.get(String.valueOf(Json.object(body(ex)).get("path")));
                if (Files.isDirectory(p) && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(p.toFile());
                sendJson(ex, 200, Map.of("ok", true));
                return;
            }
            default:
                send(ex, 404, "text/plain", "unknown api " + route);
        }
    }

    /** The version the copy will be played in (request field "target", else the world's own version) and its old-chunk marker. */
    private static DryRun.Target target(Session s, Map<String, Object> req) {
        String v = req.get("target") instanceof String t && !t.isBlank() ? t : s.world().versionName;
        GameVersions.Marker m = GameVersions.blendingMarker(v);
        return new DryRun.Target(v, m == null ? null : m.dataVersion());
    }

    /** Sharp borders need the server step when kept Overworld chunks are older than the marker (or the marker is unknown). */
    private static boolean needsUpgrade(Session s, Map<String, Set<Long>> keep, DryRun.Target t) throws IOException {
        if (!keep.containsKey("overworld")) return false;
        for (DryRun.DimSummary d : DryRun.run(s.scan(), keep, t).dims())
            if (d.key().equals("overworld")) return d.keptBelowMarker() != 0 && d.keep() > 0;
        return false;
    }

    /** Dimension key -> kept chunks, for every dimension the request marks as regenerated (from the saved selections). */
    private Map<String, Set<Long>> keepSets(Session s, Map<String, Object> req) throws IOException {
        Map<String, Set<Long>> keep = new LinkedHashMap<>();
        Object dims = req.get("dims");
        if (dims instanceof Map<?, ?> m)
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) continue;
                Dimension d = s.world().dimension(String.valueOf(e.getKey()));
                if (d == null) throw new IllegalArgumentException("unknown dimension " + e.getKey());
                keep.put(d.key(), Selection.load(s.project().selection(d.key()), s.scan().existingChunks(d)));
            }
        return keep;
    }

    private Map<String, Object> sessionInfo() throws IOException {
        Session s = need();
        World w = s.world();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", true);
        m.put("app", Main.VERSION);
        m.put("name", w.levelName);
        m.put("path", w.dir.toString());
        m.put("version", w.versionName);
        m.put("dataVersion", w.dataVersion);
        m.put("seed", w.seed == null ? null : String.valueOf(w.seed));
        m.put("locked", w.isLocked());
        m.put("colors", s.scan().colors.source);
        m.put("projectDir", s.project().dir.toString());
        m.put("spawn", w.spawn);
        List<Map<String, Object>> dims = new ArrayList<>();
        for (Dimension d : w.dimensions) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("key", d.key());
            dm.put("id", d.id);
            dm.put("label", d.label());
            dm.put("regions", d.regions());
            dm.put("hasSelection", Files.exists(s.project().selection(d.key())));
            dims.add(dm);
        }
        m.put("dims", dims);
        return m;
    }

    private Session need() {
        Session s = session;
        if (s == null) throw new IllegalStateException("No world is open");
        return s;
    }

    private static Dimension dim(Session s, Map<String, String> q) {
        Dimension d = s.world().dimension(q.getOrDefault("dim", ""));
        if (d == null) throw new IllegalArgumentException("unknown dimension " + q.get("dim"));
        return d;
    }

    private static void requirePost(String method) {
        if (!method.equals("POST")) throw new IllegalArgumentException("use POST");
    }

    // ------------------------------------------------------------------ plumbing

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) { send(ex, 404, "text/plain", "not found"); return; }
        String devDir = System.getProperty("chunkkeeper.web"); // development: serve the UI straight from the source folder
        Path devFile = devDir == null ? null : Paths.get(devDir, path.substring(1));
        try (InputStream in = devFile != null && Files.isRegularFile(devFile) ? Files.newInputStream(devFile) : WebServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) { send(ex, 404, "text/plain", "not found"); return; }
            byte[] b = in.readAllBytes();
            String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".png") ? "image/png"
                : "application/octet-stream";
            if (path.endsWith(".html")) b = new String(b, StandardCharsets.UTF_8).replace("{{TOKEN}}", token).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void sendJson(HttpExchange ex, int code, Object o) throws IOException {
        byte[] b = Json.write(o).getBytes(StandardCharsets.UTF_8);
        String enc = Optional.ofNullable(ex.getRequestHeaders().getFirst("Accept-Encoding")).orElse("");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (b.length > 4096 && enc.contains("gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(b); }
            b = bos.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    private static void send(HttpExchange ex, int code, String type, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }
}
