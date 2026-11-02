package io.github.estebansa19.chunkkeeper.game;

import io.github.estebansa19.chunkkeeper.web.Json;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Official Minecraft server jars, one folder per version under {@code <data>/servers/<version>/} (the server unpacks its
 * libraries there and keeps its eula.txt), plus the Java needed to run them.
 */
public final class ServerJars {
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String EULA_URL = "https://aka.ms/MinecraftEULA";

    private final Path root;

    public ServerJars(Path dataRoot) { this.root = dataRoot.resolve("servers"); }

    /** A server ready to run: jar folder and the Java to run it with. */
    public record Ready(String version, int dataVersion, Path dir, Path java) {}

    public record Java(Path exe, int major) {}

    public static final class Download {
        public final String version;
        public volatile String phase = "starting";
        public final AtomicLong done = new AtomicLong(), total = new AtomicLong();
        public volatile String error;
        public volatile boolean finished;

        public Download(String version) { this.version = version; }
    }

    public Path dir(String version) {
        if (!version.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("odd version name " + version);
        return root.resolve(version);
    }

    public Path jar(String version) { return dir(version).resolve("server.jar"); }

    /** What the UI shows: whether the jar is there, the EULA accepted, and which Java would run it. */
    public Map<String, Object> status(String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        Path jar = jar(version);
        GameVersions.Installed info = Files.isRegularFile(jar) ? GameVersions.describe(jar) : null;
        boolean present = info != null && info.id().equals(version);
        m.put("version", version);
        m.put("present", present);
        m.put("jar", jar.toString());
        m.put("eula", eulaAccepted(version));
        m.put("eulaUrl", EULA_URL);
        int need = present ? javaNeeded(jar) : 21;
        Java j = findJava(need);
        m.put("javaNeeded", need);
        m.put("java", j == null ? null : j.exe().toString());
        m.put("javaVersion", j == null ? null : j.major());
        return m;
    }

    /** Everything needed to run the server for {@code version}, or an exception saying what is missing. */
    public Ready ready(String version) throws IOException {
        Path jar = jar(version);
        GameVersions.Installed info = Files.isRegularFile(jar) ? GameVersions.describe(jar) : null;
        if (info == null || !info.id().equals(version)) throw new IOException("The Minecraft " + version + " server is not set up yet");
        if (!eulaAccepted(version)) throw new IOException("Accept the Minecraft EULA first");
        int need = javaNeeded(jar);
        Java j = findJava(need);
        if (j == null) throw new IOException("Java " + need + " or newer is needed to run the Minecraft " + version + " server");
        return new Ready(version, info.dataVersion(), dir(version), j.exe());
    }

    public boolean eulaAccepted(String version) {
        Path f = dir(version).resolve("eula.txt");
        try {
            return Files.isRegularFile(f) && Files.readAllLines(f, StandardCharsets.UTF_8).stream().anyMatch(l -> l.trim().equals("eula=true"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Writes the eula.txt the server checks. Only called when the person ticks the box in the UI. */
    public void setEula(String version, boolean accept) throws IOException {
        Files.createDirectories(dir(version));
        Files.writeString(dir(version).resolve("eula.txt"), "# Accepted in Chunk Keeper on " + LocalDate.now() + " (" + EULA_URL + ")\neula="
            + accept + "\n", StandardCharsets.UTF_8);
    }

    /** Copies a server jar the person already has, after checking that it is the server for {@code version}. */
    public void importJar(String version, Path from) throws IOException {
        if (!Files.isRegularFile(from)) throw new IOException("No file at " + from);
        GameVersions.Installed info = GameVersions.describe(from);
        if (info == null || !isServerJar(from)) throw new IOException(from.getFileName() + " is not a Minecraft server jar");
        if (!info.id().equals(version)) throw new IOException(from.getFileName() + " is the server for " + info.id() + ", not " + version);
        Files.createDirectories(dir(version));
        Path tmp = dir(version).resolve("server.jar.part");
        Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, jar(version), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Downloads the official server jar from Mojang and checks its SHA-1 against Mojang's version file. */
    public void download(Download p) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        p.phase = "looking up " + p.version;
        Object versions = Json.object(getText(http, MANIFEST)).get("versions");
        String versionUrl = null;
        if (versions instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> m && p.version.equals(m.get("id"))) versionUrl = String.valueOf(m.get("url"));
        if (versionUrl == null) throw new IOException("Mojang doesn't list a version called " + p.version);
        Map<String, Object> server = serverDownload(Json.object(getText(http, versionUrl)));
        if (server == null) throw new IOException("Mojang has no server download for " + p.version);
        String url = String.valueOf(server.get("url")), sha1 = String.valueOf(server.get("sha1"));
        p.total.set(((Number) server.get("size")).longValue());
        p.phase = "downloading";
        Files.createDirectories(dir(p.version));
        Path tmp = dir(p.version).resolve("server.jar.part");
        HttpResponse<InputStream> r = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("Download failed: HTTP " + r.statusCode());
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-1"); } catch (Exception e) { throw new IOException(e); }
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) { out.write(b, 0, n); md.update(b, 0, n); p.done.addAndGet(n); }
        }
        if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha1)) {
            Files.deleteIfExists(tmp);
            throw new IOException("The download was damaged (SHA-1 mismatch). Try again.");
        }
        Files.move(tmp, jar(p.version), StandardCopyOption.REPLACE_EXISTING);
        p.phase = "done";
    }

    /** {@code downloads.server} of a Mojang version file: {url, sha1, size}, or null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> serverDownload(Map<String, Object> versionJson) {
        if (versionJson.get("downloads") instanceof Map<?, ?> d && d.get("server") instanceof Map<?, ?> s
            && s.get("url") instanceof String && s.get("sha1") instanceof String && s.get("size") instanceof Number)
            return (Map<String, Object>) s;
        return null;
    }

    private static String getText(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
        return r.body();
    }

    private static HttpRequest request(String url) throws IOException {
        URI u = URI.create(url);
        if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().endsWith(".mojang.com")) throw new IOException("Unexpected download address " + url);
        return HttpRequest.newBuilder(u).timeout(Duration.ofMinutes(10)).header("User-Agent", "ChunkKeeper").GET().build();
    }

    static boolean isServerJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("net/minecraft/bundler/Main.class") != null || z.getEntry("net/minecraft/server/dedicated/DedicatedServer.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** java_version from the jar's version.json (21 when it doesn't say). */
    static int javaNeeded(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var e = z.getEntry("version.json");
            if (e == null) return 21;
            try (InputStream in = z.getInputStream(e)) {
                Object v = Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get("java_version");
                return v instanceof Number n ? n.intValue() : 21;
            }
        } catch (IOException | RuntimeException e) {
            return 21;
        }
    }

    /** A Java of at least {@code major}: the one running Chunk Keeper, JAVA_HOME, or one a launcher installed. */
    public static Java findJava(int major) {
        String home = System.getProperty("user.home");
        String appData = Optional.ofNullable(System.getenv("APPDATA")).orElse(home + "/AppData/Roaming");
        String localAppData = Optional.ofNullable(System.getenv("LOCALAPPDATA")).orElse(home + "/AppData/Local");
        List<Path> homes = new ArrayList<>();
        homes.add(Paths.get(System.getProperty("java.home")));
        if (System.getenv("JAVA_HOME") != null) homes.add(Paths.get(System.getenv("JAVA_HOME")));
        for (Path root : List.of(Paths.get(appData, "PrismLauncher/java"), Paths.get(home, ".local/share/PrismLauncher/java"),
            Paths.get(home, "Library/Application Support/PrismLauncher/java"), Paths.get(appData, ".minecraft/runtime"),
            Paths.get(localAppData, "Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime"),
            Paths.get(appData, "ModrinthApp/meta/java_versions")))
            if (Files.isDirectory(root))
                try (Stream<Path> s = Files.find(root, 4, (p, a) -> p.getFileName().toString().equals("release"))) {
                    s.forEach(r -> homes.add(r.getParent()));
                } catch (IOException ignored) {
                    // unreadable launcher folder
                }
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (Path h : homes) {
            Path exe = h.resolve(win ? "bin/java.exe" : "bin/java");
            int v = javaMajor(h);
            if (v >= major && Files.isRegularFile(exe)) return new Java(exe, v);
        }
        return null;
    }

    private static int javaMajor(Path javaHome) {
        if (javaHome.equals(Paths.get(System.getProperty("java.home")))) return Runtime.version().feature();
        try {
            for (String l : Files.readAllLines(javaHome.resolve("release"), StandardCharsets.UTF_8))
                if (l.startsWith("JAVA_VERSION=")) {
                    String v = l.substring(13).replace("\"", "").trim();
                    if (v.startsWith("1.")) v = v.substring(2);
                    return Integer.parseInt(v.split("[.+_-]")[0]);
                }
        } catch (IOException | RuntimeException ignored) {
            // not a usable Java
        }
        return 0;
    }
}
