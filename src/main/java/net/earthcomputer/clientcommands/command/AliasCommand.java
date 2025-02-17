package net.earthcomputer.clientcommands.command;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.earthcomputer.clientcommands.features.BrigadierRemover;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.regex.Pattern;

import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.earthcomputer.clientcommands.command.ClientCommandManager.*;
import static net.minecraft.server.command.CommandManager.*;

public class AliasCommand {

    private static final Logger LOGGER = LogManager.getLogger("clientcommands");

    private static final Path ALIAS_PATH = FabricLoader.getInstance().getConfigDir().resolve("clientcommands").resolve("alias_list.json");

    private static final SimpleCommandExceptionType ILLEGAL_FORMAT_EXCEPTION = new SimpleCommandExceptionType(new TranslatableText("commands.calias.illegalFormatException"));

    private static final DynamicCommandExceptionType ALIAS_EXISTS_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.calias.addAlias.aliasAlreadyExists", arg));
    private static final DynamicCommandExceptionType COMMAND_EXISTS_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.calias.addAlias.commandAlreadyExists", arg));
    private static final DynamicCommandExceptionType NOT_FOUND_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.calias.notFound", arg));

    private static final HashMap<String, String> aliasMap = loadAliases();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        addClientSideCommand("calias");

        dispatcher.register(literal("calias")
                .then(literal("add")
                        .then(argument("key", string())
                                .then(argument("command", greedyString())
                                        .executes(ctx -> addAlias(getString(ctx, "key"), getString(ctx, "command"))))))
                .then(literal("list")
                        .executes(ctx -> listAliases()))
                .then(literal("remove")
                        .then(argument("key", string())
                                .executes(ctx -> removeAlias(getString(ctx, "key"))))));

        for (String key: aliasMap.keySet()) {
            if (dispatcher.getRoot().getChildren().stream().map(CommandNode::getName).noneMatch(literal -> literal.equals(key))) {
                addClientSideCommand(key);
                dispatcher.register(literal(key)
                        .executes(ctx -> executeAliasCommand(key, null))
                        .then(argument("arguments", greedyString())
                                .executes(ctx -> executeAliasCommand(key, getString(ctx, "arguments")))));
            } else {
                LOGGER.error("Attempted to register alias /{}, but that command already exists", key);
            }
        }
    }

    private static int executeAliasCommand(String aliasKey, String arguments) throws CommandSyntaxException {
        String cmd = aliasMap.get(aliasKey);
        if (cmd == null) {
            throw NOT_FOUND_EXCEPTION.create(aliasKey);
        }
        int inlineArgumentCount = (int) Pattern.compile("(?<!%)%(?:%%)*(?!%)").matcher(cmd).results().count();
        if (inlineArgumentCount > 0) {
            String[] argumentArray = arguments.split(" ", inlineArgumentCount + 1);

            String trailingArguments = "";
            if (argumentArray.length > inlineArgumentCount) {
                trailingArguments = " " + argumentArray[inlineArgumentCount];
            }
            try {
                cmd = String.format(cmd, (Object[]) argumentArray) + trailingArguments;
            } catch (IllegalFormatException e) {
                throw ILLEGAL_FORMAT_EXCEPTION.create();
            }
        } else if (arguments != null){
            cmd += " " + arguments;
        }
        assert MinecraftClient.getInstance().player != null;
        MinecraftClient.getInstance().player.sendChatMessage(cmd);

        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int addAlias(String key, String command) throws CommandSyntaxException {
        ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
        assert networkHandler != null;
        var dispatcher = (CommandDispatcher<ServerCommandSource>) (CommandDispatcher<?>) networkHandler.getCommandDispatcher();

        if (aliasMap.containsKey(key)) {
            throw ALIAS_EXISTS_EXCEPTION.create(key);
        }
        if (dispatcher.getRoot().getChildren().stream().map(CommandNode::getName).anyMatch(literal -> literal.equals(key))) {
            throw COMMAND_EXISTS_EXCEPTION.create(key);
        }
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        addClientSideCommand(key);

        dispatcher.register(literal(key)
                .executes(ctx -> executeAliasCommand(key, null))
                .then(argument("arguments", greedyString())
                        .executes(ctx -> executeAliasCommand(key, getString(ctx, "arguments")))));
        aliasMap.put(key, command);

        saveAliases();
        sendFeedback(new TranslatableText("commands.calias.addAlias.success", key));
        return 0;
    }

    private static int listAliases() {
        if (aliasMap.isEmpty()) {
            sendFeedback(new TranslatableText("commands.calias.listAliases.noAliasesRegistered"));
        } else {
            sendFeedback("commands.calias.listAliases.success", aliasMap.size());
            for (String key: aliasMap.keySet()) {
                sendFeedback(Formatting.BOLD + key + Formatting.RESET+ ": "+ aliasMap.get(key).replace("%","%%"));
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int removeAlias(String key) throws CommandSyntaxException {
        ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
        assert networkHandler != null;
        var dispatcher = (CommandDispatcher<ServerCommandSource>) (CommandDispatcher<?>) networkHandler.getCommandDispatcher();

        if (aliasMap.containsKey(key)) {
            BrigadierRemover.of(dispatcher).get(key).remove();
            aliasMap.remove(key);
        } else {
            throw NOT_FOUND_EXCEPTION.create(key);
        }

        saveAliases();
        sendFeedback(new TranslatableText("commands.calias.removeAlias.success", key));
        return 0;
    }

    private static HashMap<String, String> loadAliases() {
        if (!Files.exists(ALIAS_PATH)) {
            return new HashMap<>();
        }
        Gson gson = new Gson();
        try (Reader fileReader = Files.newBufferedReader(ALIAS_PATH)) {
            return gson.fromJson(new JsonReader(fileReader), new TypeToken<HashMap<String, String>>(){}.getType());
        } catch (IOException e) {
            LOGGER.error("Error reading aliases file", e);
            return new HashMap<>();
        }
    }

    private static void saveAliases() {
        try (Writer writer = Files.newBufferedWriter(ALIAS_PATH)) {
            Gson gson = new Gson();
            gson.toJson(aliasMap, writer);
            writer.flush();
        } catch (IOException e) {
            LOGGER.error(e);
        }
    }
}
