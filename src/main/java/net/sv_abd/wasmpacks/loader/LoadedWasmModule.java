package net.sv_abd.wasmpacks.loader;

import com.dylibso.chicory.wasm.WasmModule;

/**
 * Holds a loaded WebAssembly module and its source location for debugging.
 * The WasmModule is Chicory's parsed (but not yet instantiated) representation
 * of a .wasm binary. Instantiation happens per-invocation so each call gets
 * a clean, isolated linear memory.
 */
public record LoadedWasmModule(
    WasmModule module,
    int byteSize
) {}
