package io.github.estebansa19.upkeep.nbt;

import java.util.ArrayList;

/** NBT list: remembers its element type so empty lists round-trip unchanged. */
public final class NbtList extends ArrayList<Object> {
    private final byte elementType;

    public NbtList(byte elementType) { this.elementType = elementType; }

    public byte elementType() { return elementType; }
}
