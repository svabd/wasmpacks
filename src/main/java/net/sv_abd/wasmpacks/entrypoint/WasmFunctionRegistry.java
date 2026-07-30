package net.sv_abd.wasmpacks.entrypoint;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds all currently active wasm-backed mcfunction entry points.
 *
 * This is populated by {@link McFunctionEntryPointType} after each reload and
 * cleared at the start of each reload. The function dispatcher
 * ({@link net.sv_abd.wasmpacks.WasmFunctionDispatcher}) uses this registry to
 * look up and invoke functions when the game calls {@code /function}.
 */
public final class WasmFunctionRegistry {

    private static final Map<Identifier, McFunctionEntryPointType.WasmBackedFunction> FUNCTIONS = new HashMap<>();

    private WasmFunctionRegistry() {}

    static void register(Identifier id, McFunctionEntryPointType.WasmBackedFunction fn) {
        FUNCTIONS.put(id, fn);
    }

    static void clear() {
        FUNCTIONS.clear();
    }

    /**
     * Returns the wasm-backed function with the given id, or null if not registered.
     */
    public static McFunctionEntryPointType.WasmBackedFunction get(Identifier id) {
        return FUNCTIONS.get(id);
    }

    /**
     * Returns an unmodifiable view of all registered wasm functions.
     */
    public static Map<Identifier, McFunctionEntryPointType.WasmBackedFunction> getAll() {
        return Collections.unmodifiableMap(FUNCTIONS);
    }
}
