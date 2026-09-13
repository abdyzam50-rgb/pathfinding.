package com.abdy2.aotvpathfinder.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.abdy2.aotvpathfinder.path.PathBuilder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Declares the command grammar and nothing else.
 *
 * <p>Split out so the set of commands the mod offers can be read in one place, as a shape, without
 * the handler bodies interleaved. What each command does lives behind {@link Actions}, implemented
 * by whatever owns the routing state.
 */
public final class PathfinderCommands {
    private PathfinderCommands() {}

    /** What the commands do. One method per distinct action the grammar can invoke. */
    public interface Actions {
        int setGoal(CommandContext<?> context);

        int previewToGoal(CommandContext<?> context);

        int previewToCoords(CommandContext<?> context);

        int clearPreview(CommandContext<?> context);

        int clearAll(CommandContext<?> context);

        int showFaults(CommandContext<?> context);

        int clearFaults(CommandContext<?> context);

        int showSettings();

        int setMovementMode(PathBuilder.MovementMode mode);

        int setTeleportMode(PathBuilder.TeleportMode mode);

        int setAirChain(boolean enabled);
    }

    public static void register(Actions actions) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("setgoal")
                    .then(xyz(actions::setGoal))
            );

            dispatcher.register(
                literal("preview")
                    .executes(actions::previewToGoal)
                    .then(literal("clear").executes(actions::clearPreview))
                    .then(xyz(actions::previewToCoords))
            );

            dispatcher.register(literal("clearpath").executes(actions::clearAll));

            dispatcher.register(
                literal("aotv")
                    .then(literal("mode")
                        .then(literal("hybrid").executes(ctx -> actions.setMovementMode(PathBuilder.MovementMode.HYBRID)))
                        .then(literal("walk").executes(ctx -> actions.setMovementMode(PathBuilder.MovementMode.WALK_ONLY)))
                        .then(literal("teleport").executes(ctx -> actions.setMovementMode(PathBuilder.MovementMode.TELEPORT_ONLY)))
                    )
                    .then(literal("tpmode")
                        .then(literal("shift").executes(ctx -> actions.setTeleportMode(PathBuilder.TeleportMode.SHIFT_ONLY)))
                        .then(literal("hybrid").executes(ctx -> actions.setTeleportMode(PathBuilder.TeleportMode.HYBRID_TELEPORT)))
                        .then(literal("just").executes(ctx -> actions.setTeleportMode(PathBuilder.TeleportMode.JUST_TELEPORT)))
                    )
                    .then(literal("airchain")
                        .then(literal("on").executes(ctx -> actions.setAirChain(true)))
                        .then(literal("off").executes(ctx -> actions.setAirChain(false)))
                    )
                    .then(literal("clear").executes(actions::clearAll))
                    .then(literal("faults")
                        .executes(actions::showFaults)
                        .then(literal("clear").executes(actions::clearFaults))
                    )
                    .then(literal("show").executes(ctx -> actions.showSettings()))
            );
        });
    }

    /** The x/y/z argument chain shared by the commands that take a block position. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
        net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, Integer> xyz(
            com.mojang.brigadier.Command<
                net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> run) {
        return argument("x", IntegerArgumentType.integer())
            .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                    .executes(run)
                )
            );
    }
}
