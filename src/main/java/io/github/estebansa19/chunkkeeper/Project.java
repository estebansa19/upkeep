package io.github.estebansa19.chunkkeeper;

import io.github.estebansa19.chunkkeeper.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Where Chunk Keeper stores its own files for a world (never inside the world folder). */
public final class Project {
    public final Path dir;

    public Project(Path dataRoot, World world) {
        String name = world.dir.getFileName().toString().replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        this.dir = dataRoot.resolve("worlds").resolve(name + "-" + hash(world.dir.toString()));
    }

    public Path selection(String dimKey) { return dir.resolve("selections").resolve(dimKey + ".csv"); }

    public Path cache() { return dir.resolve("cache"); }

    public static Path defaultDataRoot() { return Path.of(System.getProperty("user.home"), "ChunkKeeper"); }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
