package io.github.estebansa19.chunkkeeper.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Finds worlds in the usual launcher folders (vanilla, Prism, MultiMC-style, CurseForge, Modrinth). */
public final class WorldFinder {
    private WorldFinder() {}

    public record Found(Path dir, String launcher, String instance) {}

    public static List<Found> find() {
        String home = System.getProperty("user.home");
        String appData = Optional.ofNullable(System.getenv("APPDATA")).orElse(home + "/AppData/Roaming");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Found> out = new ArrayList<>();

        List<Path> vanilla = os.contains("win") ? List.of(Paths.get(appData, ".minecraft"))
            : os.contains("mac") ? List.of(Paths.get(home, "Library/Application Support/minecraft"))
            : List.of(Paths.get(home, ".minecraft"));
        for (Path mc : vanilla) addSaves(out, mc.resolve("saves"), "Minecraft", null);

        List<Path> instanceRoots = new ArrayList<>();
        if (os.contains("win")) {
            instanceRoots.add(Paths.get(appData, "PrismLauncher/instances"));
            instanceRoots.add(Paths.get(appData, "PolyMC/instances"));
            instanceRoots.add(Paths.get(appData, "ModrinthApp/profiles"));
            instanceRoots.add(Paths.get(appData, "com.modrinth.theseus/profiles"));
        } else if (os.contains("mac")) {
            instanceRoots.add(Paths.get(home, "Library/Application Support/PrismLauncher/instances"));
            instanceRoots.add(Paths.get(home, "Library/Application Support/ModrinthApp/profiles"));
        } else {
            instanceRoots.add(Paths.get(home, ".local/share/PrismLauncher/instances"));
            instanceRoots.add(Paths.get(home, ".local/share/ModrinthApp/profiles"));
        }
        instanceRoots.add(Paths.get(home, "curseforge/minecraft/Instances"));
        for (Path root : instanceRoots) {
            if (!Files.isDirectory(root)) continue;
            String launcher = root.toString().contains("Prism") ? "Prism Launcher" : root.toString().contains("PolyMC") ? "PolyMC"
                : root.toString().toLowerCase(Locale.ROOT).contains("modrinth") ? "Modrinth" : "CurseForge";
            try (Stream<Path> s = Files.list(root)) {
                for (Path inst : (Iterable<Path>) s::iterator) {
                    String name = inst.getFileName().toString();
                    addSaves(out, inst.resolve("minecraft/saves"), launcher, name);
                    addSaves(out, inst.resolve(".minecraft/saves"), launcher, name);
                    addSaves(out, inst.resolve("saves"), launcher, name);
                }
            } catch (IOException ignored) {
                // unreadable launcher folder
            }
        }
        return out;
    }

    private static void addSaves(List<Found> out, Path saves, String launcher, String instance) {
        if (!Files.isDirectory(saves)) return;
        try (Stream<Path> s = Files.list(saves)) {
            for (Path w : (Iterable<Path>) s::iterator) if (World.isWorld(w)) out.add(new Found(w.toAbsolutePath().normalize(), launcher, instance));
        } catch (IOException ignored) {
            // unreadable saves folder
        }
    }
}
