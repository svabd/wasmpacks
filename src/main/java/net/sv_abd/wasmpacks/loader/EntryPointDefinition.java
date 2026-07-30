package net.sv_abd.wasmpacks.loader;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The parsed form of a single entry point JSON file.
 *
 * JSON schema (data/<ns>/wasmpacks/entry_points/<name>.json):
 * <pre>{@code
 * {
 *   "wasm_module": "mymod:myfunctions",
 *   "export":      "on_player_tick",
 *   "type":        "mcfunction",
 *   "args":        ["player_name", "message"]
 * }
 * }</pre>
 *
 * Fields:
 *   wasm_module  – Identifier of the wasm module to invoke (must exist in WasmCodeLoader)
 *   export       – Name of the exported Wasm function to call
 *   type         – Entry point type id (looked up in EntryPointTypeRegistry)
 *   args         – Ordered list of argument names this entry point accepts (may be empty).
 *                  When the function is invoked, callers supply one string value per
 *                  declared argument. The wasm module reads them via the
 *                  {@code get_arg} / {@code arg_len} host imports.
 *
 * The entry point itself is identified by the Identifier derived from its file path:
 *   data/mymod/wasmpacks/entry_points/on_tick.json  ->  Identifier("mymod", "on_tick")
 */
public final class EntryPointDefinition {

    private final Identifier wasmModule;
    private final String export;
    private final String type;
    private final List<String> args;

    /** Full constructor — args list is copied defensively. */
    public EntryPointDefinition(Identifier wasmModule, String export, String type, List<String> args) {
        this.wasmModule = wasmModule;
        this.export = export;
        this.type = type;
        this.args = List.copyOf(args);
    }

    /** Convenience constructor for entry points with no arguments. */
    public EntryPointDefinition(Identifier wasmModule, String export, String type) {
        this(wasmModule, export, type, List.of());
    }

    public Identifier wasmModule() { return wasmModule; }
    public String export()         { return export; }
    public String type()           { return type; }

    /** Ordered list of declared argument names. Never null; may be empty. */
    public List<String> args()     { return args; }

    @Override
    public String toString() {
        return "EntryPointDefinition{wasmModule=" + wasmModule
                + ", export='" + export + '\''
                + ", type='" + type + '\''
                + ", args=" + args + '}';
    }
}