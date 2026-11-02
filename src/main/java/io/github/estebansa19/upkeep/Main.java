package io.github.estebansa19.upkeep;

import io.github.estebansa19.upkeep.ui.ControlWindow;
import io.github.estebansa19.upkeep.web.WebServer;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Starts the local web UI: {@code Upkeep.exe} or {@code java -jar upkeep.jar [world folder] [--port N] [--no-browser]
 * [--no-window] [--data-dir DIR]}. A second start just opens the browser at the one already running.
 */
public final class Main {
    public static final String VERSION = Main.class.getPackage().getImplementationVersion() != null
        ? Main.class.getPackage().getImplementationVersion() : "dev";

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 8765;
        boolean browser = true, window = !GraphicsEnvironment.isHeadless();
        Path dataRoot = Project.defaultDataRoot();
        Path world = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--no-browser": browser = false; break;
                case "--no-window": window = false; break;
                case "--data-dir": dataRoot = Path.of(args[++i]); break;
                case "--version": System.out.println("Upkeep " + VERSION); return;
                case "-h": case "--help":
                    System.out.println("Upkeep " + VERSION + "\n\nUsage: java -jar upkeep.jar [world folder] [options]\n"
                        + "  --port N        port for the local web page (default 8765)\n"
                        + "  --no-browser    do not open the browser automatically\n"
                        + "  --no-window     no control window (stop with Ctrl+C)\n"
                        + "  --data-dir DIR  where selections and map cache are stored (default ~/Upkeep)\n");
                    return;
                default: world = Path.of(args[i]);
            }
        }
        String running = findRunning(port);
        if (running != null) {
            System.out.println("Upkeep is already running at " + running);
            if (browser) openBrowser(running);
            return;
        }
        WebServer server = new WebServer(dataRoot);
        try {
            server.start(port);
        } catch (IOException e) {
            if (window) ControlWindow.error("Upkeep could not start its local page:\n" + e.getMessage());
            throw e;
        }
        if (world != null) {
            try { server.openWorld(world); } catch (IOException e) { System.out.println("Could not open " + world + ": " + e.getMessage()); }
        }
        String url = server.url();
        System.out.println("Upkeep " + VERSION + " is running at " + url);
        System.out.println("Your selections and map cache: " + dataRoot.toAbsolutePath());
        if (window) ControlWindow.show(url, () -> openBrowser(url), server::busy);
        else System.out.println("Keep this window open while you use the page. Close it (Ctrl+C) to stop.");
        if (browser) openBrowser(url);
        Thread.currentThread().join();
    }

    /** The address of an Upkeep already running on one of the ports this one would try, or null. */
    static String findRunning(int port) {
        for (int p = port; p <= port + 3; p++) {
            String base = "http://127.0.0.1:" + p;
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(base + WebServer.PING).toURL().openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(500);
                c.setReadTimeout(2000);
                if (c.getResponseCode() != 200) continue;
                try (InputStream in = c.getInputStream()) {
                    if (new String(in.readNBytes(64), StandardCharsets.UTF_8).equals(WebServer.PING_REPLY)) return base + "/";
                }
            } catch (IOException ignored) {
                // nothing (or something else) on that port
            }
        }
        return null;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the platform command
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] { "rundll32", "url.dll,FileProtocolHandler", url }
            : os.contains("mac") ? new String[] { "open", url } : new String[] { "xdg-open", url };
        try { new ProcessBuilder(cmd).start(); } catch (Exception e) { System.out.println("Open this address in your browser: " + url); }
    }
}
