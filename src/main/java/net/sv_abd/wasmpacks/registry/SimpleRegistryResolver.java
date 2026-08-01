package net.sv_abd.wasmpacks.registry;

import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.sv_abd.wasmpacks.WasmPacks;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Resolves the small string keys used in simple_blocks/simple_items JSON
 * (validated against a known-key set at parse time — see
 * {@link net.sv_abd.wasmpacks.loader.SimpleBlockLoader#KNOWN_SOUNDS} etc.)
 * into actual game constants.
 *
 * WHY REFLECTION: SoundType/MapColor constant field names have been very
 * stable across MC versions (SoundType.STONE, MapColor.STONE, etc.), but this
 * project targets MC 26.2, which this environment cannot compile against to
 * verify. Reflective lookup-by-name means a wrong guess about a constant
 * degrades to a logged warning + safe default at runtime instead of a hard
 * compile failure or, worse, a wrong silent value. Once this has been run
 * against real 26.2 jars, the sound/color tables below are the first place
 * to check if a key doesn't resolve.
 *
 * Rarity specifically is NOT handled reflectively the same way: it was
 * reorganized into a registry-backed concept as of MC 1.21.2, so a static
 * enum-constant lookup may not even apply anymore. We try the legacy
 * enum-constant shape first (cheap, safe to attempt) and fall back to
 * COMMON with a warning if that fails — do not block/item registration
 * on getting rarity exactly right.
 */
public final class SimpleRegistryResolver {

    private SimpleRegistryResolver() {}

    public static SoundType resolveSound(String key) {
        SoundType resolved = lookupStaticField(SoundType.class, key.toUpperCase(Locale.ROOT), SoundType.class);
        if (resolved != null) return resolved;
        WasmPacks.LOGGER.warn("[WasmPacks] Could not resolve SoundType for key '{}', falling back to STONE", key);
        return SoundType.STONE;
    }

    public static MapColor resolveMapColor(String key) {
        MapColor resolved = lookupStaticField(MapColor.class, key.toUpperCase(Locale.ROOT), MapColor.class);
        if (resolved != null) return resolved;
        WasmPacks.LOGGER.warn("[WasmPacks] Could not resolve MapColor for key '{}', falling back to STONE", key);
        return MapColor.STONE;
    }

    public static Rarity resolveRarity(String key) {
        Rarity resolved = lookupStaticField(Rarity.class, key.toUpperCase(Locale.ROOT), Rarity.class);
        if (resolved != null) return resolved;
        WasmPacks.LOGGER.warn(
                "[WasmPacks] Could not resolve Rarity for key '{}' (Rarity may be registry-backed in this MC "
                        + "version rather than a static constant) — falling back to COMMON", key);
        return Rarity.COMMON;
    }

    /**
     * Looks up a public static field of the given name/type on {@code owner}.
     * Returns null (never throws) if it doesn't exist, isn't accessible, or
     * isn't assignable to {@code type} — callers are expected to fall back.
     */
    private static <T> T lookupStaticField(Class<?> owner, String fieldName, Class<T> type) {
        try {
            Field f = owner.getField(fieldName);
            Object value = f.get(null);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
