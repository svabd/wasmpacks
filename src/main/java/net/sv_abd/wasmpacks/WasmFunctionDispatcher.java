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
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registers the {@code /wasmfunction} command which invokes wasm-backed
 * functions explicitly without going through the vanilla function dispatcher.
 *
 * Wasm functions are accessible via:
 *   /wasmfunction run <namespace:path> [arg1] [arg2] ...
 *
 * Arguments are space-separated and passed positionally to the wasm module via
 * the {@code get_arg}/{@code arg_len} host imports; the number and names of
 * expected arguments are declared per entry point in its {@code args} JSON field.
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

                // /wasmfunction run <id> [args...]
                // The id and all arguments are consumed by a single greedyString, then split
                // manually on the first space so that quoting is left to a future extension
                // without changing the argument parser shape.
                .then(Commands.literal("run")
                        .then(Commands.argument("id_and_args", StringArgumentType.greedyString())
                                .suggests(wasmFunctionSuggestions)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "id_and_args");

                                    String idStr;
                                    List<String> argValues;
                                    int firstSpace = raw.indexOf(' ');
                                    if (firstSpace == -1) {
                                        idStr = raw;
                                        argValues = Collections.emptyList();
                                    } else {
                                        idStr = raw.substring(0, firstSpace);
                                        argValues = Arrays.asList(raw.substring(firstSpace + 1).split("\\s+"));
                                    }

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

                                    // Validate argument count against declared args
                                    EntryPointDefinition def = fn.getDefinition();
                                    int declared = def.args().size();
                                    int supplied = argValues.size();
                                    if (supplied < declared) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "[WasmPacks] Function " + idStr + " expects " + declared
                                                        + " arg(s) (" + String.join(", ", def.args())
                                                        + ") but only " + supplied + " were supplied."));
                                        return 0;
                                    }
                                    if (supplied > declared && declared > 0) {
                                        WasmPacks.LOGGER.warn(
                                                "[WasmPacks] Function {} declares {} arg(s) but {} were supplied — "
                                                        + "extra args are still accessible via get_arg",
                                                id, declared, supplied);
                                    }

                                    WasmPacks.LOGGER.info("[WasmPacks] Invoking wasm function {} with {} arg(s)", id, supplied);
                                    fn.invoke(ctx.getSource(), argValues);

                                    final String displayArgs = supplied == 0 ? "" : " [" + String.join(", ", argValues) + "]";
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("[WasmPacks] Executed: " + idStr + displayArgs), true);
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
                                    EntryPointDefinition def = fn.getDefinition();
                                    String argInfo = def.args().isEmpty()
                                            ? "(no args)"
                                            : "args: " + String.join(", ", def.args());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("  " + key + " -> "
                                                    + def.wasmModule() + "#" + def.export()
                                                    + "  [" + argInfo + "]"),
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
