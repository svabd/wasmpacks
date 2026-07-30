package net.sv_abd.wasmpacks.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.entrypoint.EntryPointTypeRegistry;
import net.sv_abd.wasmpacks.entrypoint.WasmFunctionRegistry;
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;
import net.sv_abd.wasmpacks.loader.EntryPointLoader;
import net.sv_abd.wasmpacks.loader.LoadedWasmModule;
import net.sv_abd.wasmpacks.loader.WasmCodeLoader;

import java.util.Map;

/**
 * Registers the {@code /wasmpacks} command group for runtime debugging.
 *
 * Subcommands:
 *   /wasmpacks debug modules       — list all loaded wasm modules
 *   /wasmpacks debug entrypoints   — list all loaded entry point definitions
 *   /wasmpacks debug functions     — list all currently active wasm functions
 *   /wasmpacks debug types         — list registered entry point type handlers
 */
public class WasmPacksDebugCommand {

    private final WasmCodeLoader wasmLoader;
    private final EntryPointLoader entryPointLoader;

    public WasmPacksDebugCommand(WasmCodeLoader wasmLoader, EntryPointLoader entryPointLoader) {
        this.wasmLoader = wasmLoader;
        this.entryPointLoader = entryPointLoader;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wasmpacks")
                .then(Commands.literal("debug")

                        // /wasmpacks debug modules
                        .then(Commands.literal("modules")
                                .executes(ctx -> {
                                    Map<Identifier, LoadedWasmModule> modules = wasmLoader.getModules();
                                    if (modules.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] No wasm modules loaded."), false);
                                    } else {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] Loaded wasm modules (" + modules.size() + "):"), false);
                                        for (Map.Entry<Identifier, LoadedWasmModule> e : modules.entrySet()) {
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("  " + e.getKey()
                                                            + " (" + e.getValue().byteSize() + " bytes)"),
                                                    false);
                                        }
                                    }
                                    return modules.size();
                                })
                        )

                        // /wasmpacks debug entrypoints
                        .then(Commands.literal("entrypoints")
                                .executes(ctx -> {
                                    Map<Identifier, EntryPointDefinition> eps = entryPointLoader.getEntryPoints();
                                    if (eps.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] No entry points loaded."), false);
                                    } else {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] Loaded entry points (" + eps.size() + "):"), false);
                                        for (Map.Entry<Identifier, EntryPointDefinition> e : eps.entrySet()) {
                                            EntryPointDefinition def = e.getValue();
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("  " + e.getKey()
                                                            + " [type=" + def.type() + "] -> "
                                                            + def.wasmModule() + "#" + def.export()),
                                                    false);
                                        }
                                    }
                                    return eps.size();
                                })
                        )

                        // /wasmpacks debug functions
                        .then(Commands.literal("functions")
                                .executes(ctx -> {
                                    var fns = WasmFunctionRegistry.getAll();
                                    if (fns.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] No wasm functions active."), false);
                                    } else {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[WasmPacks] Active wasm functions (" + fns.size() + "):"), false);
                                        fns.forEach((id, fn) ->
                                                ctx.getSource().sendSuccess(
                                                        () -> Component.literal("  /wasmfunction run " + id
                                                                + "  (module: " + fn.getDefinition().wasmModule()
                                                                + ", export: " + fn.getDefinition().export() + ")"),
                                                        false)
                                        );
                                    }
                                    return fns.size();
                                })
                        )

                        // /wasmpacks debug types
                        .then(Commands.literal("types")
                                .executes(ctx -> {
                                    var types = EntryPointTypeRegistry.getAll();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("[WasmPacks] Registered entry point types (" + types.size() + "):"), false);
                                    types.keySet().forEach(typeId ->
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("  " + typeId
                                                            + " -> " + EntryPointTypeRegistry.get(typeId).getClass().getSimpleName()),
                                                    false)
                                    );
                                    return types.size();
                                })
                        )
                );

        dispatcher.register(root);
        WasmPacks.LOGGER.debug("[WasmPacks] Registered /wasmpacks command");
    }
}
