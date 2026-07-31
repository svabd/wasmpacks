package net.sv_abd.wasmpacks.loader;

import net.minecraft.resources.Identifier;

/**
 * The parsed form of a single simple-block JSON file.
 *
 * JSON schema (data/<ns>/wasmpacks/simple_blocks/<name>.json):
 * <pre>{@code
 * {
 *   "texture":       "minecraft:block/stone",
 *   "hardness":      1.5,
 *   "resistance":    6.0,
 *   "sound":         "stone",
 *   "map_color":     "stone",
 *   "requires_tool": true,
 *   "luminance":     0,
 *   "block_item":    true
 * }
 * }</pre>
 *
 * Fields:
 *   texture        – Identifier of an EXISTING texture (vanilla or another already-loaded
 *                    mod's) to render on every face. Data packs cannot ship new textures,
 *                    so simple blocks can only reuse art that's already on the client.
 *   hardness       – Mining time factor. Defaults to 1.5 (stone-ish) if omitted.
 *   resistance     – Blast resistance. Defaults to equal `hardness` if omitted.
 *   sound          – Key into a small built-in table of vanilla SoundTypes
 *                    (e.g. "stone", "wood", "gravel", "metal", "glass", "wool", "sand").
 *                    Resolved to an actual SoundType at registration time, not here.
 *   map_color      – Key into a small built-in table of vanilla MapColors, same idea as sound.
 *   requires_tool  – Whether the correct tool is required to drop items (like stone needing
 *                    a pickaxe). Defaults to false.
 *   luminance      – Light level emitted, 0-15. Defaults to 0.
 *   block_item     – Whether a corresponding BlockItem should be generated automatically so
 *                    the block is obtainable/placeable from the inventory. Defaults to true.
 *
 * This class intentionally stores only primitives/String/Identifier — it does NOT resolve
 * `sound`/`map_color` to actual game objects. That resolution happens in the registration
 * step (where the block/item registries are briefly unfrozen), keeping any API-shape risk
 * isolated to one place instead of scattered across the loader.
 *
 * The block itself is identified by the Identifier derived from its file path:
 *   data/mymod/wasmpacks/simple_blocks/pebble.json  ->  Identifier("mymod", "pebble")
 */
public final class SimpleBlockDefinition {

    private final Identifier texture;
    private final float hardness;
    private final float resistance;
    private final String sound;
    private final String mapColor;
    private final boolean requiresTool;
    private final int luminance;
    private final boolean blockItem;

    public SimpleBlockDefinition(Identifier texture, float hardness, float resistance, String sound,
                                  String mapColor, boolean requiresTool, int luminance, boolean blockItem) {
        this.texture = texture;
        this.hardness = hardness;
        this.resistance = resistance;
        this.sound = sound;
        this.mapColor = mapColor;
        this.requiresTool = requiresTool;
        this.luminance = luminance;
        this.blockItem = blockItem;
    }

    public Identifier texture()     { return texture; }
    public float hardness()         { return hardness; }
    public float resistance()       { return resistance; }
    public String sound()           { return sound; }
    public String mapColor()        { return mapColor; }
    public boolean requiresTool()   { return requiresTool; }
    public int luminance()          { return luminance; }
    public boolean blockItem()      { return blockItem; }

    @Override
    public String toString() {
        return "SimpleBlockDefinition{texture=" + texture
                + ", hardness=" + hardness
                + ", resistance=" + resistance
                + ", sound='" + sound + '\''
                + ", mapColor='" + mapColor + '\''
                + ", requiresTool=" + requiresTool
                + ", luminance=" + luminance
                + ", blockItem=" + blockItem + '}';
    }
}
