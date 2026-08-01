package net.sv_abd.wasmpacks.loader;

import net.minecraft.resources.Identifier;

/**
 * The parsed form of a single simple-item JSON file.
 *
 * JSON schema (data/<ns>/wasmpacks/simple_items/<name>.json):
 * <pre>{@code
 * {
 *   "texture":         "minecraft:item/stick",
 *   "max_stack_size":  64,
 *   "max_durability":  0,
 *   "rarity":          "common",
 *   "fire_resistant":  false
 * }
 * }</pre>
 *
 * Fields:
 *   texture          – Identifier of an EXISTING item texture to reuse (same client-asset
 *                      constraint as simple blocks: data packs carry no new art).
 *   max_stack_size   – 1-64. Defaults to 64.
 *   max_durability   – 0 means the item has no durability bar. Defaults to 0.
 *   rarity           – Key into a small built-in table (e.g. "common", "uncommon", "rare",
 *                      "epic"), resolved at registration time, not here — rarity has been
 *                      reorganized into a registry-backed concept in recent MC versions, so
 *                      resolution is deliberately kept out of this plain-data class.
 *   fire_resistant   – Whether the item survives in fire/lava. Defaults to false.
 *                      NOTE: currently parsed and validated but NOT YET APPLIED at
 *                      registration (see SimpleRegistryApplier) — resolving it needs
 *                      RegistryAccess that isn't threaded through yet.
 *
 * The item itself is identified by the Identifier derived from its file path:
 *   data/mymod/wasmpacks/simple_items/pebble.json  ->  Identifier("mymod", "pebble")
 */
public final class SimpleItemDefinition {

    private final Identifier texture;
    private final int maxStackSize;
    private final int maxDurability;
    private final String rarity;
    private final boolean fireResistant;

    public SimpleItemDefinition(Identifier texture, int maxStackSize, int maxDurability,
                                 String rarity, boolean fireResistant) {
        this.texture = texture;
        this.maxStackSize = maxStackSize;
        this.maxDurability = maxDurability;
        this.rarity = rarity;
        this.fireResistant = fireResistant;
    }

    public Identifier texture()      { return texture; }
    public int maxStackSize()        { return maxStackSize; }
    public int maxDurability()       { return maxDurability; }
    public String rarity()           { return rarity; }
    public boolean fireResistant()   { return fireResistant; }

    @Override
    public String toString() {
        return "SimpleItemDefinition{texture=" + texture
                + ", maxStackSize=" + maxStackSize
                + ", maxDurability=" + maxDurability
                + ", rarity='" + rarity + '\''
                + ", fireResistant=" + fireResistant + '}';
    }
}
