package net.sv_abd.wasmpacks.entrypoint;

import net.sv_abd.wasmpacks.WasmPacks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple registry mapping type-id strings to {@link IEntryPointType} handlers.
 *
 * This is intentionally a plain static registry (not a NeoForge DeferredRegister)
 * because entry point types are code-side constructs that don't need to be
 * data-driven or synced. They just need to be registered before the first
 * datapack reload completes.
 *
 * Built-in types are registered in {@link WasmPacks}.
 */
public final class EntryPointTypeRegistry {

    private static final Map<String, IEntryPointType> REGISTRY = new HashMap<>();

    private EntryPointTypeRegistry() {}

    /**
     * Register an entry point type handler.
     *
     * @param typeId  The string id used in entry_points JSON {@code "type"} field.
     * @param handler The handler to invoke when an entry point of this type is loaded.
     * @throws IllegalArgumentException if a handler with this id is already registered.
     */
    public static void register(String typeId, IEntryPointType handler) {
        if (REGISTRY.containsKey(typeId)) {
            throw new IllegalArgumentException("[WasmPacks] Entry point type '" + typeId + "' is already registered");
        }
        REGISTRY.put(typeId, handler);
        WasmPacks.LOGGER.debug("[WasmPacks] Registered entry point type: {}", typeId);
    }

    /**
     * Look up a handler by type id. Returns null if not found.
     */
    public static IEntryPointType get(String typeId) {
        return REGISTRY.get(typeId);
    }

    /**
     * Returns an unmodifiable view of all registered type handlers.
     */
    public static Map<String, IEntryPointType> getAll() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
