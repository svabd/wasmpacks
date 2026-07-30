package net.sv_abd.wasmpacks.entrypoint;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;
import net.sv_abd.wasmpacks.loader.LoadedWasmModule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point type {@code "mcfunction"}.
 *
 * Registers each wasm export as a callable wasm-backed function in
 * {@link WasmFunctionRegistry}. These functions can then be invoked via:
 *   /wasmfunction run <namespace>:<path> [arg1] [arg2] ...
 *
 * <h2>Argument passing</h2>
 * Arguments are declared in the entry point JSON as a named list:
 * <pre>{@code
 * {
 *   "wasm_module": "mymod:greet",
 *   "export":      "say_hello",
 *   "type":        "mcfunction",
 *   "args":        ["player_name", "message"]
 * }
 * }</pre>
 *
 * At invoke time the caller supplies one string value per declared argument.
 * The wasm module reads them via the {@code get_arg} / {@code arg_len} host imports.
 *
 * <h2>Host imports provided to the wasm module</h2>
 * The wasm module may import from the {@code "env"} namespace:
 * <ul>
 *   <li>{@code run_command(ptr: i32, len: i32) -> i32} — runs a Minecraft command
 *       string read from wasm linear memory. Returns the command result.</li>
 *   <li>{@code log_info(ptr: i32, len: i32)} — logs a message to the server log.</li>
 *   <li>{@code arg_len(index: i32) -> i32} — returns the UTF-8 byte length of
 *       argument {@code index} (0-based), or {@code -1} if out of range. Call
 *       this first to allocate a correctly-sized buffer.</li>
 *   <li>{@code get_arg(index: i32, buf_ptr: i32, buf_len: i32) -> i32} — copies
 *       argument {@code index} as UTF-8 bytes into the wasm buffer at
 *       {@code buf_ptr} (max {@code buf_len} bytes). Returns the number of bytes
 *       written, or {@code -1} if the index is out of range or the buffer is too
 *       small. Bytes are NOT null-terminated.</li>
 * </ul>
 */
public class McFunctionEntryPointType implements IEntryPointType {

    /** All currently registered wasm-backed functions, keyed by their function id. */
    private final Map<Identifier, WasmBackedFunction> functions = new HashMap<>();

    /**
     * The server reference is set by {@link WasmPacks} when the server starts
     * and cleared when it stops.
     */
    private MinecraftServer server;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // -------------------------------------------------------------------------
    // IEntryPointType
    // -------------------------------------------------------------------------

    @Override
    public void register(Identifier id, EntryPointDefinition definition, LoadedWasmModule module) {
        WasmBackedFunction fn = new WasmBackedFunction(id, definition, module);
        functions.put(id, fn);
        WasmFunctionRegistry.register(id, fn);
        WasmPacks.LOGGER.debug("[WasmPacks] Registered mcfunction entry point: {} (args: {})",
                id, definition.args());
    }

    @Override
    public void onReload() {
        WasmFunctionRegistry.clear();
        functions.clear();
    }

    // -------------------------------------------------------------------------
    // WasmBackedFunction — one callable unit
    // -------------------------------------------------------------------------

    /**
     * A lazily-instantiated wasm function. The module is instantiated fresh for
     * each {@link #invoke} call to guarantee isolated linear memory and prevent
     * state leakage between calls.
     */
    public class WasmBackedFunction {

        private final Identifier id;
        private final EntryPointDefinition definition;
        private final LoadedWasmModule loadedModule;

        WasmBackedFunction(Identifier id, EntryPointDefinition definition, LoadedWasmModule loadedModule) {
            this.id = id;
            this.definition = definition;
            this.loadedModule = loadedModule;
        }

        /**
         * Invoke with no arguments.
         * If the entry point declares args, they will all read as empty strings
         * and a warning is logged.
         */
        public void invoke(CommandSourceStack source) {
            if (!definition.args().isEmpty()) {
                WasmPacks.LOGGER.warn(
                        "[WasmPacks] Entry point {} expects {} arg(s) ({}) but was invoked with none — "
                                + "all args will read as empty strings",
                        id, definition.args().size(), definition.args());
            }
            invoke(source, Collections.emptyList());
        }

        /**
         * Invoke with explicit argument values.
         *
         * @param source    The CommandSourceStack from the dispatch context.
         *                  May be null in non-command contexts.
         * @param argValues Positional string values, one per declared arg name.
         *                  Fewer values than declared args means the missing
         *                  trailing args return {@code -1} from {@code get_arg}.
         *                  Extra values beyond the declared count are accessible
         *                  (index still valid) but not declared in JSON.
         */
        public void invoke(CommandSourceStack source, List<String> argValues) {
            // Pre-encode every argument value to UTF-8 bytes once, before entering
            // wasm — avoids repeated encoding inside the hot callback path.
            final List<byte[]> encodedArgs = encodeArgs(argValues);

            try {
                // --- "env::run_command" ---
                // Signature: (ptr: i32, len: i32) -> (result: i32)
                HostFunction runCommand = new HostFunction(
                        "env",
                        "run_command",
                        FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I32)),
                        (Instance instance, long... args) -> {
                            int ptr = (int) args[0];
                            int len = (int) args[1];
                            String command = readString(instance, ptr, len);
                            int result = 0;
                            if (source != null && server != null) {
                                try {
                                    server.getCommands().performPrefixedCommand(source, command);
                                } catch (Exception e) {
                                    WasmPacks.LOGGER.warn("[WasmPacks] run_command failed for '{}': {}", command, e.getMessage());
                                    result = -1;
                                }
                            } else {
                                WasmPacks.LOGGER.warn("[WasmPacks] run_command called but no source/server available");
                            }
                            return new long[]{result};
                        }
                );

                // --- "env::log_info" ---
                // Signature: (ptr: i32, len: i32) -> ()
                HostFunction logInfo = new HostFunction(
                        "env",
                        "log_info",
                        FunctionType.of(List.of(ValType.I32, ValType.I32), List.of()),
                        (Instance instance, long... args) -> {
                            int ptr = (int) args[0];
                            int len = (int) args[1];
                            String msg = readString(instance, ptr, len);
                            WasmPacks.LOGGER.info("[WasmPacks/{}] {}", id, msg);
                            return new long[]{};
                        }
                );

                // --- "env::arg_len" ---
                // Signature: (index: i32) -> (byte_length: i32)
                // Returns the UTF-8 byte length of argument[index], or -1 if out of range.
                // Call this first to know how many bytes to allocate before calling get_arg.
                HostFunction argLen = new HostFunction(
                        "env",
                        "arg_len",
                        FunctionType.of(List.of(ValType.I32), List.of(ValType.I32)),
                        (Instance instance, long... args) -> {
                            int index = (int) args[0];
                            if (index < 0 || index >= encodedArgs.size()) {
                                return new long[]{-1L};
                            }
                            return new long[]{encodedArgs.get(index).length};
                        }
                );

                // --- "env::get_arg" ---
                // Signature: (index: i32, buf_ptr: i32, buf_len: i32) -> (written: i32)
                // Copies argument[index] bytes into the wasm buffer at buf_ptr.
                // Returns bytes written, or -1 if the index is out of range or buf_len
                // is smaller than the argument's byte length.
                // The written bytes are NOT null-terminated.
                HostFunction getArg = new HostFunction(
                        "env",
                        "get_arg",
                        FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32)),
                        (Instance instance, long... args) -> {
                            int index  = (int) args[0];
                            int bufPtr = (int) args[1];
                            int bufLen = (int) args[2];

                            if (index < 0 || index >= encodedArgs.size()) {
                                return new long[]{-1L};
                            }
                            byte[] bytes = encodedArgs.get(index);
                            if (bufLen < bytes.length) {
                                // Signal the caller to retry with a larger buffer.
                                return new long[]{-1L};
                            }
                            try {
                                instance.memory().write(bufPtr, bytes);
                                return new long[]{bytes.length};
                            } catch (Exception e) {
                                WasmPacks.LOGGER.warn(
                                        "[WasmPacks] get_arg write failed at ptr={} len={}: {}",
                                        bufPtr, bytes.length, e.getMessage());
                                return new long[]{-1L};
                            }
                        }
                );

                // --- Instantiate and call ---
                Store store = new Store();
                store.addFunction(runCommand, logInfo, argLen, getArg);
                Instance instance = store.instantiate(id.toString(), loadedModule.module());
                ExportFunction exportFn = instance.export(definition.export());
                exportFn.apply(); // () -> ()

            } catch (Exception e) {
                WasmPacks.LOGGER.error("[WasmPacks] Error invoking wasm entry point {}#{}: {}",
                        id, definition.export(), e.getMessage(), e);
            }
        }

        public Identifier getId() { return id; }
        public EntryPointDefinition getDefinition() { return definition; }
        public LoadedWasmModule getLoadedModule() { return loadedModule; }

        // ------------------------------------------------------------------
        // Private helpers
        // ------------------------------------------------------------------

        /**
         * Encode a list of string argument values to UTF-8 byte arrays.
         */
        private static List<byte[]> encodeArgs(List<String> argValues) {
            List<byte[]> encoded = new ArrayList<>(argValues.size());
            for (String v : argValues) {
                encoded.add(v.getBytes(StandardCharsets.UTF_8));
            }
            return Collections.unmodifiableList(encoded);
        }

        /**
         * Read a UTF-8 string from the wasm module's linear memory.
         */
        private static String readString(Instance instance, int ptr, int len) {
            try {
                byte[] bytes = instance.memory().readBytes(ptr, len);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                WasmPacks.LOGGER.warn(
                        "[WasmPacks] Failed to read string from wasm memory at ptr={} len={}: {}",
                        ptr, len, e.getMessage());
                return "<invalid string>";
            }
        }
    }

    public Map<Identifier, WasmBackedFunction> getFunctions() {
        return Collections.unmodifiableMap(functions);
    }
}
