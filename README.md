# WasmPacks

Run WebAssembly in Minecraft data packs. Write functions in Rust, C, Zig, or any language that compiles to `.wasm` — and call them in-game as if they were ordinary `.mcfunction` files.

The WebAssembly runtime is [Chicory](https://github.com/dylibso/chicory) — a pure-JVM implementation with zero native dependencies. It works on every platform Minecraft runs on.

---

## Quick start

### 1. Compile your code to WebAssembly

Any language works. Here's a minimal Rust example (no_std, bare wasm32):

```rust
// src/lib.rs  (cargo build --target wasm32-unknown-unknown)

// Host imports — provided by WasmPacks at runtime
extern "C" {
    fn log_info(ptr: i32, len: i32);
    fn run_command(ptr: i32, len: i32) -> i32;
    fn arg_len(index: i32) -> i32;
    fn get_arg(index: i32, buf_ptr: i32, buf_len: i32) -> i32;
}

#[no_mangle]
pub extern "C" fn greet() {
    // Read arg 0 ("player_name") from the host
    let len = unsafe { arg_len(0) };
    if len < 0 {
        let msg = b"No player name supplied";
        unsafe { log_info(msg.as_ptr() as i32, msg.len() as i32); }
        return;
    }

    let mut buf = vec![0u8; len as usize];
    unsafe { get_arg(0, buf.as_mut_ptr() as i32, buf.len() as i32) };
    let name = core::str::from_utf8(&buf).unwrap_or("unknown");

    let cmd = format!("say Hello, {}!", name);
    unsafe { run_command(cmd.as_ptr() as i32, cmd.len() as i32); }
}
```

### 2. Place the compiled .wasm in your data pack

```
data/
  example/
    wasmpacks/
      wasm_code/
        greet.wasm                 ← your compiled module
      entry_points/
        greet.json                 ← entry point definition
```

### 3. Write the entry point JSON

`data/example/wasmpacks/entry_points/greet.json`:
```json
{
  "wasm_module": "example:greet",
  "export":      "greet",
  "type":        "mcfunction",
  "args":        ["player_name"]
}
```

Fields:
- `wasm_module` — ResourceLocation pointing to a `.wasm` file (`<namespace>:<path>` → `data/<namespace>/wasmpacks/wasm_code/<path>.wasm`)
- `export` — the name of the exported function inside the wasm module
- `type` — the entry point type (currently only `"mcfunction"`)
- `args` *(optional)* — ordered list of argument names this function accepts; omit or use `[]` for no arguments

### 4. Call it in-game

```
/wasmfunction run example:greet Steve
```

Functions with no declared args are called without any trailing text:

```
/wasmfunction run example:say_hello
```

---

## Passing arguments to wasm functions

Arguments are declared by name in the entry point JSON and supplied positionally at call time.

```json
{
  "wasm_module": "mymod:actions",
  "export":      "teleport_player",
  "type":        "mcfunction",
  "args":        ["player", "x", "y", "z"]
}
```

```
/wasmfunction run mymod:teleport_player Steve 100 64 200
```

Inside the wasm module, use the `arg_len` + `get_arg` host imports to read them:

| Import | Signature | Description |
|---|---|---|
| `arg_len` | `(index: i32) -> i32` | Returns the UTF-8 byte length of argument `index` (0-based), or `-1` if out of range. Call this first to allocate the right buffer. |
| `get_arg` | `(index: i32, buf_ptr: i32, buf_len: i32) -> i32` | Copies argument `index` as UTF-8 bytes into the wasm buffer at `buf_ptr`. Returns bytes written, or `-1` if index is out of range or the buffer is too small. Bytes are **not** null-terminated. |

### Argument rules

- Arguments are always strings. Numeric values must be parsed by the wasm module.
- If fewer arguments are supplied than declared, the command is rejected with an error message before the wasm module is invoked.
- If more arguments are supplied than declared, the extras are still accessible via `get_arg` (with their 0-based index) but a warning is logged.
- Functions with no `"args"` field (or `"args": []`) can be called without any trailing arguments.

---

## How it works

### Two reload listeners

**`WasmCodeLoader`** — Scans `data/*/wasmpacks/wasm_code/*.wasm` across all data packs. Reads each file as raw bytes and parses it with Chicory (validation + type-checking). The resulting `WasmModule` objects are stored in memory, keyed by ResourceLocation.

**`EntryPointLoader`** — Scans `data/*/wasmpacks/entry_points/*.json` across all data packs. Parses each JSON into an `EntryPointDefinition` (wasm_module + export + type + args).

After both loaders finish, a dispatcher wires them together: it looks up each entry point's module, finds the right type handler, and registers the function.

### Entry point types

The type system is extensible. Each type is a handler registered in `EntryPointTypeRegistry`:

```java
// In your mod's constructor or static block:
EntryPointTypeRegistry.register("my_custom_type", new MyCustomEntryPointType());
```

A custom type just implements `IEntryPointType`:

```java
public class MyCustomEntryPointType implements IEntryPointType {
    @Override
    public void register(ResourceLocation id, EntryPointDefinition def, LoadedWasmModule module) {
        // def.args() gives you the declared argument names
        // Connect the wasm function to whatever game system you want
    }

    @Override
    public void onReload() {
        // Called at the start of each reload — un-register previous state
    }
}
```

### Wasm module instantiation

Each `/wasmfunction run` call instantiates the Chicory module freshly. This means:
- **Complete memory isolation** between calls — no state leakage
- WebAssembly's sandboxed memory model prevents any access outside the module
- Interaction with Minecraft only happens through explicitly provided host imports

### Host imports (what your wasm can call)

Your wasm module may import from the `env` namespace:

| Import | Signature | Description |
|---|---|---|
| `run_command` | `(ptr: i32, len: i32) -> i32` | Runs a Minecraft command. String at `ptr`/`len` (UTF-8) in wasm memory. Returns the command result. |
| `log_info` | `(ptr: i32, len: i32)` | Logs a message to the server log as `[WasmPacks/<id>] <message>`. |
| `arg_len` | `(index: i32) -> i32` | Returns the UTF-8 byte length of argument `index`, or `-1` if out of range. |
| `get_arg` | `(index: i32, buf_ptr: i32, buf_len: i32) -> i32` | Copies argument `index` into the wasm buffer. Returns bytes written or `-1` on error. |

Strings are passed as `(pointer, length)` pairs. Your code is responsible for allocating memory and providing valid pointers.

---

## Commands

| Command | Description |
|---|---|
| `/wasmfunction run <id> [args...]` | Invoke a wasm function, passing space-separated string arguments |
| `/wasmfunction list` | List all active wasm functions and their declared argument names |
| `/wasmpacks debug modules` | List all loaded .wasm modules and their byte sizes |
| `/wasmpacks debug entrypoints` | List all loaded entry point definitions |
| `/wasmpacks debug functions` | List all wasm functions currently active |
| `/wasmpacks debug types` | List all registered entry point type handlers |

---

## Why not hook into `/function` directly?

Vanilla's `/function` dispatcher looks up `CommandFunction` objects from a server-side registry populated by reading `.mcfunction` files. Injecting wasm-backed entries into that registry requires either a mixin into `ServerFunctionLibrary` or a NeoForge AT. That's possible but adds complexity. The `wasmfunction` command is a clean, dependency-free alternative — and it supports arguments, which vanilla `.mcfunction` files don't have natively.

---

## Adding more entry point types (for other mod authors)

```java
@Mod("yourmod")
public class YourMod {
    public YourMod(IEventBus modEventBus) {
        // Register before the first reload — mod constructors run before server start
        EntryPointTypeRegistry.register("yourmod:on_tick", new YourTickEntryPointType());
    }
}
```

Then data pack authors can use:
```json
{
  "wasm_module": "yourpack:some_module",
  "export":      "on_tick",
  "type":        "yourmod:on_tick",
  "args":        ["delta_time"]
}
```

The `EntryPointDefinition.args()` list is available to your type handler so you can forward arguments however your system needs.
