package net.sv_abd.wasmpacks.entrypoint;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.sv_abd.wasmpacks.WasmPacks;
import net.sv_abd.wasmpacks.loader.EntryPointDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registers the {@code /wasmfunction} command which invokes wasm-backed
 * functions explicitly without going through the vanilla function dispatcher.
 *
 * <h2>Syntax</h2>
 * <pre>
 *   /wasmfunction run  &lt;namespace:path&gt; [arg1] [arg2] ...
 *   /wasmfunction list
 * </pre>
 *
 * Arguments are separated by spaces and passed positionally to the wasm module
 * via the {@code get_arg} / {@code arg_len} host imports. The number and names
 * of expected arguments are declared in the entry point JSON.
 *
 * If the entry point declares no arguments the trailing argument string must
 * be omitted (or will be ignored if present). If it declares arguments, supply
 * them space-separated after the function id; quote with double quotes if a
 * single argument value contains spaces
 * (e.g. {@code /wasmfunction run ex:greet Steve "Hello World"}).
 */
public class WasmFunctionDispatcher {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        SuggestionProvider<CommandSourceStack> wasmFunctionSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggestResource(
                        new ArrayList<>(WasmFunctionRegistry.getAll().keySet()),
                        builder
                );

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wasmfunction")

                // /wasmfunction run <id> [args...]
                // The id and all arguments are consumed by a single greedyString, then
                // split manually so that quoted tokens with spaces are supported in a
                // future extension without changing the argument parser.
                .then(Commands.literal("run")
                        .then(Commands.argument("id_and_args", StringArgumentType.greedyString())
                                .suggests(wasmFunctionSuggestions)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "id_and_args");

                                    // Split on the first space to separate <id> from [args...]
                                    // Everything after the first space is the raw argument string.
                                    String idStr;
                                    List<String> argValues;

                                    int firstSpace = raw.indexOf(' ');
                                    if (firstSpace == -1) {
                                        idStr = raw;
                                        argValues = Collections.emptyList();
                                    } else {
                                        idStr = raw.substring(0, firstSpace);
                                        String argTail = raw.substring(firstSpace + 1);
                                        // Split on whitespace; each token is one argument value.
                                        argValues = Arrays.asList(argTail.split("\\s+"));
                                    }

                                    // Parse resource location
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
                                        // Warn but proceed — extra args are accessible via get_arg
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
