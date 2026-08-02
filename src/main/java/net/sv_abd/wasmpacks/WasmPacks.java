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
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.sv_abd.wasmpacks.network.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import net.sv_abd.wasmpacks.debug.WasmPacksDebugCommand;
import net.sv_abd.wasmpacks.entrypoint.EntryPointDispatcher;
import net.sv_abd.wasmpacks.entrypoint.EntryPointTypeRegistry;
import net.sv_abd.wasmpacks.entrypoint.McFunctionEntryPointType;
import net.sv_abd.wasmpacks.loader.EntryPointLoader;
import net.sv_abd.wasmpacks.loader.SimpleBlockLoader;
import net.sv_abd.wasmpacks.loader.SimpleItemLoader;
import net.sv_abd.wasmpacks.loader.WasmCodeLoader;
import net.sv_abd.wasmpacks.registry.SimpleRegistryApplier;

import java.util.Map;

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
        // Payload *registration* (as opposed to configuration-task registration,
        // which is a per-connection game event further below) happens once, on
        // the mod bus, same lifecycle stage as everything else in this constructor.
        // RegisterConfigurationTasksEvent is a mod-bus event too (NOT the game
        // event bus — registering it via NeoForge.EVENT_BUS.register(this) below
        // threw IllegalArgumentException: "... has @SubscribeEvent annotation,
        // but takes an argument that is not valid for this bus" at startup),
        // so it's wired up here explicitly instead of via @SubscribeEvent.
        modEventBus.addListener(this::onRegisterConfigurationTasks);


        modEventBus.addListener(this::registerPayloads);

        // Register ourselves for server-side game events
        NeoForge.EVENT_BUS.register(this);

        // Register wasm-function dispatcher and debug commands as event listeners
        NeoForge.EVENT_BUS.register(new WasmFunctionDispatcher());
        NeoForge.EVENT_BUS.register(new WasmPacksDebugCommand(wasmCodeLoader, entryPointLoader));

        // Register built-in entry point types early (before first reload)
        EntryPointTypeRegistry.register("mcfunction", mcFunctionType);

        LOGGER.info("[WasmPacks] Initialized. Built-in entry point types: mcfunction");
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1.0.0");

        // 1. Clientbound configuration payload
        registrar.configurationToClient(
                WasmPacksSyncPayload.TYPE,
                WasmPacksSyncPayload.CODEC,
                WasmPacksSyncPayloadHandler::handle
        );

        // 2. REQUIRED: Serverbound configuration payload
        // This resolves the UnsupportedOperationException!
        registrar.configurationToServer(
                WasmPacksSyncAckPayload.TYPE,
                WasmPacksSyncAckPayload.CODEC,
                WasmPacksSyncAckPayloadHandler::handle
        );
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

        // Register the simple block/item loaders (JSON files). These only parse
        // and cache definitions on every reload; the trigger listener below turns
        // that cached output into real Block/Item registry entries. Registration
        // is idempotent per identifier — every world load (not just the first one
        // this process) runs this, but ids already present in the registry from
        // an earlier world load this process are left alone (see
        // SimpleRegistryApplier for why a full one-shot-per-process guard was
        // wrong here).
        event.addListener(SimpleBlockLoader.ID, simpleBlockLoader);
        event.addListener(SimpleItemLoader.ID, simpleItemLoader);

        // Register a synthetic trigger listener that actually turns the parsed
        // simple block/item definitions into real registry entries. This MUST
        // run after both simple_* loaders (needs their parsed output) and
        // BEFORE vanilla's tag/recipe/loot/advancement reload (which may
        // reference our new ids by id, and would silently drop or hard-fail on
        // unknown ones otherwise, since those load in this same reload batch).
        // The actual "run before vanilla" ordering is set up further down,
        // once this listener is registered (addDependency requires both sides
        // to already be registered).
        Identifier simpleRegistryApplierId = Identifier.fromNamespaceAndPath(MOD_ID, "simple_registry_applier");
        event.addListener(
                simpleRegistryApplierId,
                new net.minecraft.server.packs.resources.SimplePreparableReloadListener<@NotNull Void>() {
                    @Override
                    protected Void prepare(net.minecraft.server.packs.resources.@NotNull ResourceManager manager,
                                           net.minecraft.util.profiling.@NotNull ProfilerFiller profiler) {
                        return null; // no off-thread work needed
                    }

                    @Override
                    protected void apply(Void prepared,
                                         net.minecraft.server.packs.resources.@NotNull ResourceManager manager,
                                         net.minecraft.util.profiling.@NotNull ProfilerFiller profiler) {
                        SimpleRegistryApplier.apply(simpleBlockLoader, simpleItemLoader);
                    }
                }
        );
        event.addDependency(SimpleBlockLoader.ID, simpleRegistryApplierId);
        event.addDependency(SimpleItemLoader.ID, simpleRegistryApplierId);

        // There is no confirmed public constant for vanilla's tag/recipe/loot/
        // advancement reload listener keys in this NeoForge version (an earlier
        // attempt to reference a `VanillaServerListeners` helper failed to
        // compile — it isn't part of the public API here). Instead, scan the
        // listeners NeoForge has already registered by this point (vanilla's
        // own listeners are pre-populated before this event reaches us) and add
        // an explicit ordering edge against anything whose key looks
        // tag/recipe/loot/advancement-related, so our block/item registration
        // runs before content that might reference the new ids by id.
        //
        // RISK NOTE: this is a best-effort heuristic, not a verified mechanism.
        // If it silently matches nothing (vanilla's keys don't contain these
        // substrings in this version), the fallback is the documented default:
        // we still run after vanilla, which likely means an ordering bug for
        // the very first data pack load. Please verify the actual keys vanilla
        // registers under by checking net.minecraft.server.ReloadableServerResources
        // (or wherever this version builds the vanilla listener list) and swap
        // this heuristic for exact keys if it doesn't line up.
        for (Map.Entry<Identifier, net.minecraft.server.packs.resources.PreparableReloadListener> vanillaEntry
                : event.getRegistry().entrySet()) {
            String path = vanillaEntry.getKey().getPath().toLowerCase(java.util.Locale.ROOT);
            if (path.contains("tag") || path.contains("recipe") || path.contains("loot") || path.contains("advancement")) {
                try {
                    event.addDependency(simpleRegistryApplierId, vanillaEntry.getKey());
                    LOGGER.debug("[WasmPacks] Ordered simple_registry_applier before {}", vanillaEntry.getKey());
                } catch (IllegalArgumentException ex) {
                    LOGGER.warn("[WasmPacks] Could not add ordering dependency against {}: {}",
                            vanillaEntry.getKey(), ex.getMessage());
                }
            }
        }

        // Register a synthetic reload listener that dispatches entry points to
        // their type handlers. It runs after both loaders complete.
        // We use an anonymous SimplePreparableReloadListener as a trigger.
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "dispatcher"),
                new net.minecraft.server.packs.resources.SimplePreparableReloadListener<@NotNull Void>() {
                    @Override
                    protected Void prepare(net.minecraft.server.packs.resources.@NotNull ResourceManager manager,
                                           net.minecraft.util.profiling.@NotNull ProfilerFiller profiler) {
                        return null; // no off-thread work needed
                    }

                    @Override
                    protected void apply(Void prepared,
                                         net.minecraft.server.packs.resources.@NotNull ResourceManager manager,
                                         net.minecraft.util.profiling.@NotNull ProfilerFiller profiler) {
                        EntryPointDispatcher.dispatch(wasmCodeLoader, entryPointLoader);
                    }
                }
        );

        LOGGER.info("[WasmPacks] Registered wasm_code, entry_points, simple_blocks, simple_items, and dispatcher reload listeners");
    }

    // -------------------------------------------------------------------------
    // Multiplayer sync: ship simple block/item definitions to connecting
    // clients during the configuration phase, before they can enter the play
    // state. See WasmPacksSyncPayload/WasmPacksSyncTask for why this timing
    // matters and SimpleRegistryApplier for why ordering matters.
    // -------------------------------------------------------------------------

    /**
     * Registers the {@link WasmPacksSyncPayload} codec/handler. Fired once, on
     * the mod bus, at the same lifecycle stage as everything else registered
     * in the constructor.
     *
     * RISK NOTE: PayloadRegistrar's exact method name for a configuration-phase,
     * server-to-client-only payload (here assumed to be
     * {@code configurationToClient}) has not been verified against real 26.2
     * NeoForge sources — check NeoForge's own networking primer if this
     * doesn't compile. The payload/codec/handler classes underneath are
     * unaffected either way.
     */
    private void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MOD_ID).versioned("1");
        registrar.configurationToClient(
                WasmPacksSyncPayload.TYPE,
                WasmPacksSyncPayload.CODEC,
                WasmPacksSyncPayloadHandler::handle);
        LOGGER.debug("[WasmPacks] Registered sync payload handler");
    }

    /**
     * Adds {@link WasmPacksSyncTask} to every connecting client's
     * configuration-phase task list.
     * <p>
     * NOT annotated {@code @SubscribeEvent} — {@code RegisterConfigurationTasksEvent}
     * is a mod-bus event, not a {@code NeoForge.EVENT_BUS} one (confirmed by an
     * {@code IllegalArgumentException} at startup when it was registered that
     * way). Wired up via {@code modEventBus.addListener(...)} in the
     * constructor instead, same as {@link #registerPayloadHandlers}.
     */
    private void onRegisterConfigurationTasks(RegisterConfigurationTasksEvent event) {
        event.register(new WasmPacksSyncTask());
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