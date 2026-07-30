package net.sv_abd.wasmpacks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.sv_abd.wasmpacks.entrypoint.McFunctionEntryPointType;
import net.sv_abd.wasmpacks.entrypoint.WasmFunctionRegistry;

import java.util.stream.Collectors;

/**
 * Registers the {@code /wasmfunction} command which invokes wasm-backed
 * functions explicitly without going through the vanilla function dispatcher.
 *
 * Wasm functions are accessible via:
 *   /wasmfunction run <namespace:path>
 *
 * This is separate from the vanilla {@code /function} command because hooking
 * into that system requires mixin or AT access. Using our own command keeps
 * the implementation clean and avoids compatibility issues.
 *
 * Note: In a future version, integration with vanilla /function is possible
 * via the CustomFunctionAction system or via a registered CustomFunction codec,
 * but that requires deeper NeoForge integration.
 */
public class WasmFunctionDispatcher {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        SuggestionProvider<CommandSourceStack> wasmFunctionSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggestResource(
                        WasmFunctionRegistry.getAll().keySet().stream().collect(Collectors.toList()),
                        builder
                );

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wasmfunction")

                // /wasmfunction run <id>
                .then(Commands.literal("run")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .suggests(wasmFunctionSuggestions)
                                .executes(ctx -> {
                                    String idStr = StringArgumentType.getString(ctx, "id");
                                    Identifier id;
                                    try {
                                        id = Identifier.parse(idStr);
                                    } catch (Exception e) {
                                        ctx.getSource().sendFailure(
                                                Component.literal("[WasmPacks] Invalid resource location: " + idStr));
                                        return 0;
                                    }

                                    McFunctionEntryPointType.WasmBackedFunction fn = WasmFunctionRegistry.get(id);
                                    if (fn == null) {
                                        ctx.getSource().sendFailure(
                                                Component.literal("[WasmPacks] No wasm function registered with id: " + idStr
                                                        + ". Use /wasmpacks debug to list loaded functions."));
                                        return 0;
                                    }

                                    WasmPacks.LOGGER.info("[WasmPacks] Invoking wasm function {} via command", id);
                                    fn.invoke(ctx.getSource());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("[WasmPacks] Executed wasm function: " + idStr), true);
                                    return 1;
                                })
                        )
                )

                // /wasmfunction list
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            var all = WasmFunctionRegistry.getAll();
                            if (all.isEmpty()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("[WasmPacks] No wasm functions registered."), false);
                            } else {
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("[WasmPacks] Registered wasm functions (" + all.size() + "):"), false);
                                for (Identifier key : all.keySet()) {
                                    var fn = all.get(key);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("  " + key + " -> "
                                                    + fn.getDefinition().wasmModule() + "#" + fn.getDefinition().export()),
                                            false);
                                }
                            }
                            return all.size();
                        })
                );

        dispatcher.register(root);
        WasmPacks.LOGGER.debug("[WasmPacks] Registered /wasmfunction command");
    }
}
