package com.savagelich.spawndistancebiomes.scan;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.savagelich.spawndistancebiomes.SpawnDistanceBiomes;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Path;

/**
 * Server-side worldgen scanner: {@code /sdbscan [step] [radius]}.
 *
 * Also auto-runs on startup when the {@code sdb.scan} system property is set,
 * writing output then shutting the server down — so the whole thing can be
 * driven headlessly from a script.
 */
@EventBusSubscriber(modid = SpawnDistanceBiomes.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ScanCommand {

    private static final String OUTPUT_DIR = "sdb_scan";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("sdbscan")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    run(ctx.getSource().getServer(), 10, 40, 5000);
                    return 1;
                })
                .then(Commands.argument("step", IntegerArgumentType.integer(1, 256))
                    .executes(ctx -> {
                        int s = IntegerArgumentType.getInteger(ctx, "step");
                        run(ctx.getSource().getServer(), s, s * 4, 5000);
                        return 1;
                    })
                    .then(Commands.argument("radius", IntegerArgumentType.integer(64, 20000))
                        .executes(ctx -> {
                            int s = IntegerArgumentType.getInteger(ctx, "step");
                            run(ctx.getSource().getServer(), s, s * 4,
                                IntegerArgumentType.getInteger(ctx, "radius"));
                            return 1;
                        }))));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("sdb.scan")) return;
        int step = Integer.getInteger("sdb.scan.step", 10);
        int heightStep = Integer.getInteger("sdb.scan.heightStep", step * 4);
        int radius = Integer.getInteger("sdb.scan.radius", 5000);
        MinecraftServer server = event.getServer();
        Thread thread = new Thread(() -> {
            try {
                run(server, step, heightStep, radius);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            server.execute(() -> server.halt(false));
        }, "sdb-scan");
        thread.setDaemon(false);
        thread.start();
    }

    private static void run(MinecraftServer server, int step, int heightStep, int radius) {
        ServerLevel overworld = server.overworld();
        Path outDir = Path.of(OUTPUT_DIR).toAbsolutePath();
        try {
            new WorldgenScanner().scan(overworld, step, heightStep, radius, outDir);
        } catch (Exception e) {
            SpawnDistanceBiomes.LOGGER.error("sdbscan failed", e);
        }
    }
}
