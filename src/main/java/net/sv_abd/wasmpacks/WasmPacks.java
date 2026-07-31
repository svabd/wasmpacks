package net.sv_abd.wasmpacks;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import net.sv_abd.wasmpacks.debug.WasmPacksDebugCommand;
import net.sv_abd.wasmpacks.entrypoint.EntryPointDispatcher;
import net.sv_abd.wasmpacks.entrypoint.EntryPointTypeRegistry;
import net.sv_abd.wasmpacks.entrypoint.McFunctionEntryPointType;
import net.sv_abd.wasmpacks.loader.EntryPointLoader;
import net.sv_abd.wasmpacks.loader.SimpleBlockLoader;
import net.sv_abd.wasmpacks.loader.SimpleItemLoader;
import net.sv_abd.wasmpacks.loader.WasmCodeLoader;

/**
 * Main mod class for WasmPacks.
 *
 * This mod adds two custom data pack registries:
 *   1. wasm_code      — accepts .wasm binary files
 *   2. entry_points   — accepts .json files describing how to connect wasm exports to the game
 *
 * The only built-in entry point type is {@code "mcfunction"}, which lets wasm
 * exports be called via {@code /wasmfunction run <id>} exactly as if they were
 * ordinary .mcfunction files. New types can be added by other mods via
 * {@link EntryPointTypeRegistry#register}.
 *
 * <h2>Data pack layout</h2>
 * <pre>
 * data/
 *   &lt;namespace&gt;/
 *     wasmpacks/
 *       wasm_code/
 *         &lt;name&gt;.wasm              <- compiled WebAssembly module
 *       entry_points/
 *         &lt;name&gt;.json              <- entry point definition
 * </pre>
 *
 * <h2>Entry point JSON format</h2>
 * <pre>{@code
 * {
 *   "wasm_module": "<namespace>:<name>",
 *   "export":      "exported_function_name",
 *   "type":        "mcfunction"
 * }
 * }</pre>
 *
 * <h2>Commands</h2>
 * <ul>
 *   <li>{@code /wasmfunction run <id>}        — invoke a wasm function</li>
 *   <li>{@code /wasmfunction list}            — list active wasm functions</li>
 *   <li>{@code /wasmpacks debug modules}      — list loaded .wasm modules</li>
 *   <li>{@code /wasmpacks debug entrypoints}  — list loaded entry point definitions</li>
 *   <li>{@code /wasmpacks debug functions}    — list active wasm-backed functions</li>
 *   <li>{@code /wasmpacks debug types}        — list registered entry point type handlers</li>
 * </ul>
 */
@Mod(WasmPacks.MOD_ID)
public class WasmPacks {

    public static final String MOD_ID = "wasmpacks";
    public static final Logger LOGGER = LogUtils.getLogger();

    // --- Core loaders (singletons; reused across reloads) ---
    private final WasmCodeLoader wasmCodeLoader = new WasmCodeLoader();
    private final EntryPointLoader entryPointLoader = new EntryPointLoader();
    private final SimpleBlockLoader simpleBlockLoader = new SimpleBlockLoader();
    private final SimpleItemLoader simpleItemLoader = new SimpleItemLoader();

    // --- Built-in entry point type handler ---
    private final McFunctionEntryPointType mcFunctionType = new McFunctionEntryPointType();

    public WasmPacks(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server-side game events
        NeoForge.EVENT_BUS.register(this);

        // Register wasm-function dispatcher and debug commands as event listeners
        NeoForge.EVENT_BUS.register(new WasmFunctionDispatcher());
        NeoForge.EVENT_BUS.register(new WasmPacksDebugCommand(wasmCodeLoader, entryPointLoader));

        // Register built-in entry point types early (before first reload)
        EntryPointTypeRegistry.register("mcfunction", mcFunctionType);

        LOGGER.info("[WasmPacks] Initialized. Built-in entry point types: mcfunction");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[WasmPacks] Common setup complete.");
    }

    // -------------------------------------------------------------------------
    // Server events
    // -------------------------------------------------------------------------

    /**
     * Register both reload listeners with the server's resource reload system.
     * They are registered in dependency order: wasm_code first, entry_points
     * second. The dispatch to type handlers happens in a third listener that
     * depends on both.
     */
    @SubscribeEvent
    public void onAddReloadListeners(AddServerReloadListenersEvent event) {
        // Register the wasm code loader (binary files)
        event.addListener(WasmCodeLoader.ID, wasmCodeLoader);

        // Register the entry point loader (JSON files)
        event.addListener(EntryPointLoader.ID, entryPointLoader);

        // Register the simple block/item loaders (JSON files). NOTE: these only
        // parse and cache definitions on every reload, same as the loaders above.
        // Actually turning them into real Block/Item registry entries requires
        // briefly unfreezing BLOCK/ITEM, which is only safe to do once, at world
        // load — that consumption step is separate and not wired in yet.
        event.addListener(SimpleBlockLoader.ID, simpleBlockLoader);
        event.addListener(SimpleItemLoader.ID, simpleItemLoader);

        // Register a synthetic reload listener that dispatches entry points to
        // their type handlers. It runs after both loaders complete.
        // We use an anonymous SimplePreparableReloadListener as a trigger.
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "dispatcher"),
                new net.minecraft.server.packs.resources.SimplePreparableReloadListener<Void>() {
                    @Override
                    protected Void prepare(net.minecraft.server.packs.resources.ResourceManager manager,
                                           net.minecraft.util.profiling.ProfilerFiller profiler) {
                        return null; // no off-thread work needed
                    }

                    @Override
                    protected void apply(Void prepared,
                                         net.minecraft.server.packs.resources.ResourceManager manager,
                                         net.minecraft.util.profiling.ProfilerFiller profiler) {
                        EntryPointDispatcher.dispatch(wasmCodeLoader, entryPointLoader);
                    }
                }
        );

        LOGGER.info("[WasmPacks] Registered wasm_code, entry_points, simple_blocks, simple_items, and dispatcher reload listeners");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        mcFunctionType.setServer(event.getServer());
        LOGGER.info("[WasmPacks] Server starting — mcfunction type connected to server");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        mcFunctionType.setServer(null);
        LOGGER.info("[WasmPacks] Server stopping — mcfunction type disconnected from server");
    }
}
