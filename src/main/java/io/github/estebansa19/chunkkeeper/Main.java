package io.github.estebansa19.chunkkeeper;

import io.github.estebansa19.chunkkeeper.web.WebServer;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** Starts the local web UI: {@code java -jar chunk-keeper.jar [world folder] [--port N] [--no-browser] [--data-dir DIR]}. */
public final class Main {
    public static final String VERSION = Main.class.getPackage().getImplementationVersion() != null
        ? Main.class.getPackage().getImplementationVersion() : "dev";

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 8765;
        boolean browser = true;
        Path dataRoot = Project.defaultDataRoot();
        Path world = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--no-browser": browser = false; break;
                case "--data-dir": dataRoot = Path.of(args[++i]); break;
                case "--version": System.out.println("Chunk Keeper " + VERSION); return;
                case "-h": case "--help":
                    System.out.println("Chunk Keeper " + VERSION + "\n\nUsage: java -jar chunk-keeper.jar [world folder] [options]\n"
                        + "  --port N        port for the local web page (default 8765)\n"
                        + "  --no-browser    do not open the browser automatically\n"
                        + "  --data-dir DIR  where selections and map cache are stored (default ~/ChunkKeeper)\n");
                    return;
                default: world = Path.of(args[i]);
            }
        }
        WebServer server = new WebServer(dataRoot);
        server.start(port);
        if (world != null) server.openWorld(world);
        String url = server.url();
        System.out.println("Chunk Keeper " + VERSION + " is running at " + url);
        System.out.println("Your selections and map cache: " + dataRoot.toAbsolutePath());
        System.out.println("Keep this window open while you use the page. Close it (Ctrl+C) to stop.");
        if (browser) openBrowser(url);
        Thread.currentThread().join();
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
