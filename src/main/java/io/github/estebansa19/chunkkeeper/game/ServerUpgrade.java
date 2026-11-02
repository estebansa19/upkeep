package io.github.estebansa19.chunkkeeper.game;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs an official Minecraft server once with {@code --forceUpgrade --recreateRegionFiles} on a world, then stops it.
 * Every existing chunk is re-saved by that version and nothing new is generated (26.x has no spawn chunks to prepare).
 * {@code --recreateRegionFiles} matters: without it the upgrader doesn't wait for its last chunk write, which can get lost.
 */
public final class ServerUpgrade {
    private ServerUpgrade() {}

    private static final Pattern PROGRESS = Pattern.compile("(\\d+)% completed \\((\\d+) / (\\d+) chunks\\)");

    /** {@code progress} receives (chunks done, chunks total) while the upgrade runs. */
    public static void run(ServerJars.Ready server, Path world, Consumer<long[]> progress) throws IOException, InterruptedException {
        world = world.toAbsolutePath().normalize();
        if (!Files.isRegularFile(world.resolve("level.dat"))) throw new IOException("No level.dat in " + world);
        Files.writeString(server.dir().resolve("server.properties"), String.join("\n",
            "# written by Chunk Keeper for a one-off world upgrade",
            "server-ip=127.0.0.1", "online-mode=false", "enable-status=false", "enable-query=false", "enable-rcon=false",
            "max-players=1", "view-distance=2", "simulation-distance=2", "spawn-protection=0", ""), StandardCharsets.UTF_8);

        List<String> cmd = List.of(server.java().toString(), "-jar", "server.jar", "--nogui", "--forceUpgrade", "--recreateRegionFiles",
            "--universe", world.getParent().toString(), "--world", world.getFileName().toString(), "--port", String.valueOf(freePort()));
        Path userCache = world.getParent().resolve("usercache.json"); // the server keeps it next to the world, e.g. in saves/
        boolean hadUserCache = Files.exists(userCache);
        Process proc = new ProcessBuilder(cmd).directory(server.dir().toFile()).redirectErrorStream(true).start();
        Thread killer = new Thread(proc::destroyForcibly);
        Runtime.getRuntime().addShutdownHook(killer);
        Deque<String> tail = new ArrayDeque<>();
        boolean upgraded = false, done = false;
        String problem = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             Writer console = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (tail.size() == 30) tail.removeFirst();
                tail.addLast(line);
                Matcher m = PROGRESS.matcher(line);
                if (m.find()) progress.accept(new long[] { Long.parseLong(m.group(2)), Long.parseLong(m.group(3)) });
                if (line.contains("World optimization finished")) upgraded = true;
                if (line.contains("You need to agree to the EULA")) problem = "The Minecraft EULA has not been accepted";
                if (line.contains("No existing world data, creating new world")) { // wrong folder: never let it build a new world
                    problem = "The server did not find the world";
                    proc.destroyForcibly();
                    break;
                }
                if (line.contains("Done (") && !done) {
                    done = true;
                    console.write("stop\n");
                    console.flush();
                    Thread watchdog = new Thread(() -> { // the upgrade is on disk by now; don't hang if stop is ignored
                        try { if (!proc.waitFor(2, TimeUnit.MINUTES)) proc.destroyForcibly(); } catch (InterruptedException ignored) { /* exiting */ }
                    }, "server-stop");
                    watchdog.setDaemon(true);
                    watchdog.start();
                }
            }
        } finally {
            if (!proc.waitFor(2, TimeUnit.MINUTES)) { proc.destroyForcibly(); proc.waitFor(30, TimeUnit.SECONDS); }
            try { Runtime.getRuntime().removeShutdownHook(killer); } catch (IllegalStateException ignored) { /* shutting down */ }
            if (!hadUserCache) Files.deleteIfExists(userCache); // only the file this run created
        }
        if (problem != null || !upgraded || !done)
            throw new IOException((problem != null ? problem : "The Minecraft " + server.version() + " server stopped before the upgrade finished")
                + ". Last lines:\n" + String.join("\n", tail));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); }
    }
}
