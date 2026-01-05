package insidate.hardcoreplus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class Hardcoreplus implements ModInitializer {
    public static final String MOD_ID = "hardcoreplus";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final AtomicBoolean PROCESSING = new AtomicBoolean(false);
    public static volatile long WORLD_START_MILLIS = 0L;
    public static volatile String CURRENT_LEVEL_NAME = "world";
    private static java.nio.file.Path flagsDir(java.nio.file.Path runDir) {
        var dir = runDir.resolve("HardcorePlus+");
        try { if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir); } catch (Throwable ignored) {}
        return dir;
    }

    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final long CONFIRM_TIMEOUT_MS = 30_000L;
    public static final Map<UUID, Long> PENDING_CONFIRM = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("HardcorePlus+ initializing (Fabric)");
        ConfigManager.load();

        // Pre-start rotation handler (backup/delete old world if marker exists)
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                if (!server.isDedicated()) return;
            } catch (Throwable ignored) { return; }

            try { ConfigManager.reload(); } catch (Throwable ignored) {}
            try {
                var runDir = server.getRunDirectory();
                var newMarker = flagsDir(runDir).resolve("hc_reset.flag");
                var oldMarker = runDir.resolve("hc_reset.flag");
                var marker = Files.exists(newMarker) ? newMarker : oldMarker;
                if (!Files.exists(marker)) return;

                LOGGER.info("hc_reset.flag detected; preparing to rotate world (Fabric)");

                String levelName = "world";
                try (var r = Files.newBufferedReader(marker)) {
                    var mp = new Properties();
                    mp.load(r);
                    var fromMarker = mp.getProperty("old-level-name");
                    if (fromMarker != null && !fromMarker.isBlank()) levelName = fromMarker;
                } catch (IOException ignored) {
                    var propsFile = runDir.resolve("server.properties");
                    if (Files.exists(propsFile)) {
                        try (var in = Files.newInputStream(propsFile)) {
                            var p = new Properties();
                            p.load(in);
                            levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName);
                        }
                    }
                }

                var worldDir = runDir.resolve(levelName);
                boolean doBackup = ConfigManager.getBoolean("backup_old_worlds");
                boolean deleteInstead = ConfigManager.getBoolean("delete_instead_of_backup");
                String backupFolderName = Optional.ofNullable(ConfigManager.get("backup_folder_name")).orElse("Old Worlds");

                if (Files.exists(worldDir)) {
                    if (doBackup && !deleteInstead) {
                        var backupRoot = runDir.resolve(backupFolderName);
                        if (!Files.exists(backupRoot)) Files.createDirectories(backupRoot);
                        String format = Optional.ofNullable(ConfigManager.get("backup_name_format")).orElse("%name%_%ts%");
                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());
                        String ts = fmt.format(Instant.now());
                        String id = UUID.randomUUID().toString().substring(0, 8);
                        String backupName = format.replace("%name%", levelName).replace("%ts%", ts).replace("%id%", id);
                        var backupTarget = backupRoot.resolve(backupName);
                        boolean moved = false;
                        try {
                            Files.move(worldDir, backupTarget, StandardCopyOption.ATOMIC_MOVE);
                            LOGGER.info("Moved old world to {}", backupTarget.toAbsolutePath());
                            moved = true;
                        } catch (IOException e) {
                            LOGGER.info("Atomic move failed; attempting non-atomic move: {}", e.toString());
                            try {
                                Files.move(worldDir, backupTarget);
                                LOGGER.info("Moved old world to {} (non-atomic)", backupTarget.toAbsolutePath());
                                moved = true;
                            } catch (IOException ex) {
                                // On Linux, cross-filesystem moves fail entirely; on Windows, locked files cause failure
                                LOGGER.info("Non-atomic move failed (cross-filesystem or locked files); falling back to copy+delete: {}", ex.toString());
                            }
                        }

                        if (!moved) {
                            try {
                                Files.walk(worldDir).forEach(source -> {
                                    try {
                                        var dest = backupTarget.resolve(worldDir.relativize(source));
                                        if (Files.isDirectory(source)) {
                                            if (!Files.exists(dest)) Files.createDirectories(dest);
                                        } else {
                                            if (source.getFileName().toString().equalsIgnoreCase("session.lock")) {
                                                LOGGER.info("Skipping locked file during backup copy: {}", source);
                                                return;
                                            }
                                            Files.copy(source, dest);
                                        }
                                    } catch (IOException ex) {
                                        LOGGER.info("Error copying file to backup (continuing): {}", source);
                                    }
                                });
                                // delete original
                                Files.walk(worldDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                                    try { Files.deleteIfExists(p); } catch (IOException ignored2) {}
                                });
                                LOGGER.info("Copied old world to {} and deleted original", backupTarget.toAbsolutePath());
                            } catch (IOException ex) {
                                LOGGER.warn("Failed to copy-and-delete old world to backup", ex);
                            }
                        }
                    } else {
                        // delete
                        try {
                            Files.walk(worldDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored2) {}
                            });
                            LOGGER.info("Deleted old world folder {}", worldDir.toAbsolutePath());
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete old world folder", e);
                        }
                    }
                }

                // remove marker
                try { Files.deleteIfExists(marker); } catch (IOException ignored) {}
                // Clean up legacy marker if we handled new one
                if (!marker.equals(oldMarker)) { try { Files.deleteIfExists(oldMarker); } catch (IOException ignored) {} }
            } catch (Throwable t) {
                LOGGER.warn("Exception while handling hc_reset.flag (Fabric)", t);
            }
        });

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                CommandManager.literal("hcp")
                    .then(CommandManager.literal("masskill").requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("confirm").executes(ctx -> {
                            var source = ctx.getSource();
                            var server = source.getServer();
                            if (server == null) { source.sendFeedback(() -> Text.literal("Server not available."), false); return 0; }
                            try { if (!server.isDedicated() && source.getEntity() != null) { source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false); return 0; } } catch (Throwable ignored) { source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false); return 0; }

                            UUID who = CONSOLE_UUID;
                            try { if (source.getEntity() != null) who = source.getEntity().getUuid(); } catch (Throwable ignored) { who = CONSOLE_UUID; }
                            Long expiry = PENDING_CONFIRM.get(who);
                            long now = System.currentTimeMillis();
                            if (expiry == null || expiry < now) { PENDING_CONFIRM.remove(who); source.sendFeedback(() -> Text.literal("No pending mass-kill confirmation. Run /hcp masskill to request one."), false); return 0; }
                            PENDING_CONFIRM.remove(who);

                            boolean isHardcore = false;
                            try { if (server.getSaveProperties() != null) isHardcore = server.getSaveProperties().isHardcore(); } catch (Throwable ignored) {}
                            if (!isHardcore) { source.sendFeedback(() -> Text.literal("World is not hardcore; aborting masskill."), false); return 0; }

                            performMassKill(server);
                            broadcastCommandRestart(server);
                            requestResetAndStop(server);
                            source.sendFeedback(() -> Text.literal("Mass-kill executed. Restart scheduled."), false);
                            return 1;
                        }))
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            var server = source.getServer();
                            if (server == null) { source.sendFeedback(() -> Text.literal("Server not available."), false); return 0; }
                            try { if (!server.isDedicated() && source.getEntity() != null) { source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false); return 0; } } catch (Throwable ignored) { source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false); return 0; }

                            UUID who = CONSOLE_UUID;
                            try { if (source.getEntity() != null) who = source.getEntity().getUuid(); } catch (Throwable ignored) { who = CONSOLE_UUID; }
                            long expiry = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
                            PENDING_CONFIRM.put(who, expiry);
                            source.sendFeedback(() -> Text.literal("Mass-kill requested. Confirm with /hcp masskill confirm within 30 seconds."), false);
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("status").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
                        var source = ctx.getSource();
                        var server = source.getServer();
                        if (server == null) { source.sendFeedback(() -> Text.literal("Server not available."), false); return 0; }
                        boolean isHardcore = false; try { if (server.getSaveProperties() != null) isHardcore = server.getSaveProperties().isHardcore(); } catch (Throwable ignored) {}
                        boolean propsHardcore = false;
                        try {
                            var propsFile = server.getRunDirectory().resolve("server.properties");
                            if (Files.exists(propsFile)) {
                                var p = new Properties(); try (var in = Files.newInputStream(propsFile)) { p.load(in); }
                                var hv = p.getProperty("hardcore"); propsHardcore = hv != null && (hv.equalsIgnoreCase("true") || hv.equalsIgnoreCase("1") || hv.equalsIgnoreCase("yes"));
                            }
                        } catch (Throwable ignored) {}
                        String msg = String.format("Hardcore (world): %s, server.properties: %s, Processing: %s, Online players: %d", isHardcore, propsHardcore, PROCESSING.get(), server.getPlayerManager().getPlayerList().size());
                        source.sendFeedback(() -> Text.literal(msg), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("config").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
                        try { ConfigManager.reload(); } catch (Throwable ignored) {}
                        var source = ctx.getSource();
                        var all = ConfigManager.getAll();
                        StringBuilder sb = new StringBuilder();
                        sb.append("HardcorePlus+ config:\n");
                        for (var e : all.entrySet()) {
                            sb.append("  ").append(e.getKey()).append("=").append(String.valueOf(e.getValue())).append("\n");
                        }
                        source.sendFeedback(() -> Text.literal(sb.toString().trim()), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("preview").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
                        try { ConfigManager.reload(); } catch (Throwable ignored) {}
                        var source = ctx.getSource();
                        String oldLevelName = "world";
                        try {
                            var propsFile = source.getServer().getRunDirectory().resolve("server.properties");
                            if (Files.exists(propsFile)) {
                                var p = new Properties(); try (var in = Files.newInputStream(propsFile)) { p.load(in); }
                                oldLevelName = Optional.ofNullable(p.getProperty("level-name")).orElse(oldLevelName);
                            }
                        } catch (Throwable ignored) {}

                        String baseLevelName = oldLevelName;
                        try {
                            var baseFileNew = flagsDir(source.getServer().getRunDirectory()).resolve("hc_base_name.txt");
                            var baseFileOld = source.getServer().getRunDirectory().resolve("hc_base_name.txt");
                            if (Files.exists(baseFileNew)) {
                                String tmp = Files.readString(baseFileNew).trim();
                                if (!tmp.isEmpty()) baseLevelName = tmp;
                            } else if (Files.exists(baseFileOld)) {
                                String tmp = Files.readString(baseFileOld).trim();
                                if (!tmp.isEmpty()) baseLevelName = tmp;
                            }
                        } catch (Throwable ignored) {}
                        baseLevelName = NameUtil.stripTimeSuffixes(baseLevelName);

                        String timePattern = Optional.ofNullable(ConfigManager.get("time_format")).filter(s -> !s.isBlank()).orElse("HH-mm-ss_uuuu-MM-dd");
                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.systemDefault());
                        String timeStr = fmt.format(Instant.now());
                        String nameFormat = Optional.ofNullable(ConfigManager.get("new_level_name_format")).filter(s -> !s.isBlank()).orElse("%name%_%time%");
                        String id = UUID.randomUUID().toString().substring(0, 8);
                        String newLevelName = nameFormat.replace("%name%", baseLevelName).replace("%time%", timeStr).replace("%id%", id);
                        newLevelName = NameUtil.sanitizeName(newLevelName);

                        String seedInfo = "(unchanged)";
                        if (ConfigManager.getBoolean("force_new_seed")) {
                            String seedMode = Optional.ofNullable(ConfigManager.get("seed_mode")).orElse("random").trim().toLowerCase();
                            if (seedMode.equals("custom")) {
                                String customSeed = Optional.ofNullable(ConfigManager.get("custom_seed")).orElse("");
                                seedInfo = customSeed.isBlank() ? "<empty custom_seed> -> random" : customSeed;
                            } else {
                                seedInfo = Long.toString(ThreadLocalRandom.current().nextLong());
                            }
                        }

                        String msg3 = "Preview rotation => new level-name: '" + newLevelName + "', seed: " + seedInfo;
                        source.sendFeedback(() -> Text.literal(msg3), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("reload").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
                        ConfigManager.reload();
                        ctx.getSource().sendFeedback(() -> Text.literal("HardcorePlus+ config reloaded."), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("reset").requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("confirm").executes(ctx -> {
                            var source = ctx.getSource();
                            var server = source.getServer();
                            if (server == null) { source.sendFeedback(() -> Text.literal("Server not available."), false); return 0; }
                            broadcastCommandRestart(server);
                            requestResetAndStop(server);
                            source.sendFeedback(() -> Text.literal("Reset scheduled. Server will stop shortly."), false);
                            return 1;
                        }))
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            UUID who = CONSOLE_UUID;
                            try { if (source.getEntity() != null) who = source.getEntity().getUuid(); } catch (Throwable ignored) {}
                            long expiry = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
                            PENDING_CONFIRM.put(who, expiry);
                            source.sendFeedback(() -> Text.literal("Reset requested. Confirm with /hcp reset confirm within 30 seconds."), false);
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("help").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
                        var src = ctx.getSource();
                        boolean isOp = false; try { isOp = src.hasPermissionLevel(2); } catch (Throwable ignored) {}
                        StringBuilder sb = new StringBuilder();
                        sb.append("HardcorePlus+ commands:\n");
                        sb.append("  /hcp status - Show hardcore/processing/players\n");
                        sb.append("  /hcp preview - Show next world name and seed\n");
                        sb.append("  /hcp time - Show current world time and real uptime\n");
                        if (isOp) {
                            sb.append("  /hcp masskill - Request mass-kill (confirm required)\n");
                            sb.append("  /hcp masskill confirm - Confirm mass-kill\n");
                            sb.append("  /hcp reset - Schedule world rotation (confirm required)\n");
                            sb.append("  /hcp reset confirm - Confirm rotation and stop server\n");
                            sb.append("  /hcp config - Show effective config\n");
                            sb.append("  /hcp reload - Reload config file\n");
                        } else {
                            sb.append("  (Op-only) masskill, reset, config, reload\n");
                        }
                        src.sendFeedback(() -> Text.literal(sb.toString()), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("time").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
                        var source = ctx.getSource();
                        var server = source.getServer();
                        if (server == null) { source.sendFeedback(() -> Text.literal("Server not available."), false); return 0; }
                        net.minecraft.server.world.ServerWorld world;
                        try { world = server.getOverworld(); } catch (Throwable t) { source.sendFeedback(() -> Text.literal("World not available."), false); return 0; }
                        long todFull; long timeTotal;
                        try { todFull = world.getTimeOfDay(); timeTotal = world.getTime(); } catch (Throwable t) { todFull = 0L; timeTotal = 0L; }
                        long tod = Math.floorMod(todFull, 24000L);
                        long day = Math.floorDiv(todFull, 24000L);
                        long hour = (tod / 1000L + 6L) % 24L;
                        long minute = (tod % 1000L) * 60L / 1000L;
                        String mcClock = String.format("Day %d, %02d:%02d", day, hour, minute);
                        long uptimeMs = WORLD_START_MILLIS > 0 ? System.currentTimeMillis() - WORLD_START_MILLIS : 0L;
                        String uptime = formatDuration(uptimeMs);
                        Text msg = Text.empty()
                                .append(Text.literal("World Time: ").formatted(Formatting.GRAY))
                                .append(Text.literal(mcClock).formatted(Formatting.AQUA, Formatting.BOLD))
                                .append(Text.literal("  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Ticks: ").formatted(Formatting.GRAY))
                                .append(Text.literal(Long.toString(timeTotal)).formatted(Formatting.YELLOW))
                                .append(Text.literal("  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Uptime: ").formatted(Formatting.GRAY))
                                .append(Text.literal(uptime).formatted(Formatting.GOLD));
                        source.sendFeedback(() -> msg, false);
                        return 1;
                    }))
                    .executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal("Use /hcp help for available commands."), false); return 1; })
            );
            LOGGER.info("[hcp] Registered /hcp commands (Fabric)");
        });

        // Post-start world tracking
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var runDir = server.getRunDirectory();
                var propsFile = runDir.resolve("server.properties");
                String levelName = "world";
                if (Files.exists(propsFile)) {
                    try (var in = Files.newInputStream(propsFile)) { var p = new Properties(); p.load(in); levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName); }
                }
                CURRENT_LEVEL_NAME = levelName;
                var worldStartNew = flagsDir(runDir).resolve("hc_world_start.flag");
                var worldStartOld = runDir.resolve("hc_world_start.flag");
                var worldStart = Files.exists(worldStartNew) ? worldStartNew : worldStartOld;
                long start = System.currentTimeMillis(); boolean matched = false;
                if (Files.exists(worldStart)) {
                    try (var r = Files.newBufferedReader(worldStart)) { var pp = new Properties(); pp.load(r); String ln = pp.getProperty("level-name"); String st = pp.getProperty("start"); if (ln != null && ln.equals(levelName) && st != null) { try { start = Long.parseLong(st); matched = true; } catch (NumberFormatException ignored) {} } }
                }
                try { var out = new Properties(); out.setProperty("level-name", levelName); out.setProperty("start", Long.toString(start)); try (var w = Files.newBufferedWriter(worldStartNew)) { out.store(w, "HardcorePlus+ world start timestamp"); } } catch (Throwable t) { LOGGER.info("Failed to write world start flag", t); }
                WORLD_START_MILLIS = start;
                LOGGER.info("World '{}' start time set{}: {}", levelName, matched ? " (restored)" : "", new java.util.Date(start));

                // Optionally update MOTD with reset count
                try {
                    if (ConfigManager.getBoolean("motd_enable")) {
                        int resets = StatsManager.getResetCount(runDir);
                        String format = Optional.ofNullable(ConfigManager.get("motd_format")).orElse("{motd} | World Reset Count:{resetcount}");
                        if (!format.contains("{resetcount}")) {
                            LOGGER.info("motd_format missing {resetcount}; appending token at end.");
                            format = format + " {resetcount}";
                        }
                        String baseMotd;
                        var baseFile = flagsDir(runDir).resolve("hc_base_motd.txt");
                        if (Files.exists(baseFile)) {
                            baseMotd = Files.readString(baseFile).trim();
                        } else {
                            baseMotd = "";
                            try {
                                var p = new Properties();
                                if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); } }
                                baseMotd = Optional.ofNullable(p.getProperty("motd")).orElse("");
                            } catch (Throwable ignored) {}
                            try { Files.writeString(baseFile, baseMotd, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING); } catch (Throwable ignored) {}
                        }
                        String newMotd = format.replace("{motd}", baseMotd).replace("{resetcount}", Integer.toString(resets));
                        newMotd = MotdUtil.applyFormatting(newMotd);
                        try {
                            var p = new Properties();
                            if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); } }
                            p.setProperty("motd", newMotd);
                            try (var out = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) { p.store(out, "server.properties (modified by HardcorePlus+) update MOTD with reset count"); }
                            LOGGER.info("Updated MOTD with reset count ({}): {}", resets, newMotd);
                        } catch (Throwable t) { LOGGER.info("Failed to update MOTD", t); }
                    }
                } catch (Throwable t) { LOGGER.info("MOTD update skipped due to error", t); }
            } catch (Throwable t) { LOGGER.info("Failed to initialize world start tracking", t); }
        });
    }

    public static void requestResetAndStop(MinecraftServer server) {
        if (server == null) return;
        try { if (!server.isDedicated()) { LOGGER.info("RequestResetAndStop refused: not a dedicated server"); return; } } catch (Throwable ignored) { LOGGER.info("RequestResetAndStop refused: unable to determine server type"); return; }
        try { ConfigManager.reload(); } catch (Throwable ignored) {}
        try {
            var runDir = server.getRunDirectory();
            var existingMarkerNew = flagsDir(runDir).resolve("hc_reset.flag");
            var existingMarkerOld = runDir.resolve("hc_reset.flag");
            if (Files.exists(existingMarkerNew) || Files.exists(existingMarkerOld)) { LOGGER.warn("hc_reset.flag already exists; a reset is already scheduled. Skipping duplicate request."); return; }

            var propsFile = runDir.resolve("server.properties");
            var p = new Properties();
            String oldLevelName = "world";
            if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); oldLevelName = Optional.ofNullable(p.getProperty("level-name")).orElse(oldLevelName); } }

            var baseFileNew = flagsDir(runDir).resolve("hc_base_name.txt");
            var baseFileOld = runDir.resolve("hc_base_name.txt");
            String baseLevelName = oldLevelName;
            try {
                if (Files.exists(baseFileNew)) {
                    baseLevelName = Files.readString(baseFileNew).trim();
                    if (baseLevelName.isEmpty()) baseLevelName = oldLevelName;
                } else if (Files.exists(baseFileOld)) {
                    // Migrate legacy root file to designated folder
                    try { Files.createDirectories(baseFileNew.getParent()); Files.move(baseFileOld, baseFileNew); } catch (Throwable ignored2) {}
                    String tmp = Files.exists(baseFileNew) ? Files.readString(baseFileNew).trim() : Files.readString(baseFileOld).trim();
                    if (!tmp.isEmpty()) baseLevelName = tmp; else baseLevelName = oldLevelName;
                } else {
                    Files.createDirectories(baseFileNew.getParent());
                    Files.writeString(baseFileNew, baseLevelName, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    LOGGER.info("Saved base level-name '{}' to {}", baseLevelName, baseFileNew.toAbsolutePath());
                }
            } catch (Throwable t) { LOGGER.info("Failed to read/write base level-name; using current level-name as base", t); baseLevelName = oldLevelName; }
            baseLevelName = NameUtil.stripTimeSuffixes(baseLevelName);

            String timePattern = Optional.ofNullable(ConfigManager.get("time_format")).filter(s -> !s.isBlank()).orElse("HH-mm-ss_uuuu-MM-dd");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.systemDefault());
            String timeStr = fmt.format(Instant.now());
            String nameFormat = Optional.ofNullable(ConfigManager.get("new_level_name_format")).filter(s -> !s.isBlank()).orElse("%name%_%time%");
            String id = UUID.randomUUID().toString().substring(0, 8);
            String newLevelName = nameFormat.replace("%name%", baseLevelName).replace("%time%", timeStr).replace("%id%", id);
            newLevelName = NameUtil.sanitizeName(newLevelName);
            p.setProperty("level-name", newLevelName);

            String newSeedWritten = null;
            boolean forceNewSeed = ConfigManager.getBoolean("force_new_seed");
            if (forceNewSeed) {
                String seedMode = Optional.ofNullable(ConfigManager.get("seed_mode")).orElse("random").trim().toLowerCase();
                if (seedMode.equals("custom")) {
                    String customSeed = Optional.ofNullable(ConfigManager.get("custom_seed")).orElse("");
                    if (!customSeed.isBlank()) { p.setProperty("level-seed", customSeed); newSeedWritten = customSeed; }
                    else { long newSeed = ThreadLocalRandom.current().nextLong(); p.setProperty("level-seed", Long.toString(newSeed)); newSeedWritten = Long.toString(newSeed); }
                } else {
                    long newSeed = ThreadLocalRandom.current().nextLong(); p.setProperty("level-seed", Long.toString(newSeed)); newSeedWritten = Long.toString(newSeed);
                }
            }

            try (var out = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) { p.store(out, "server.properties (modified by HardcorePlus+) new level-name & optional seed"); }
            LOGGER.info("Prepared rotation: old-level-name='{}' -> new-level-name='{}'{}", oldLevelName, newLevelName, newSeedWritten == null ? "" : ", level-seed=" + newSeedWritten);

            // Increment persistent reset counter and pre-write next MOTD with updated count
            try {
                int newCount = StatsManager.incrementResetCount(runDir);
                if (ConfigManager.getBoolean("motd_enable")) {
                    var propsFile2 = runDir.resolve("server.properties");
                    String format = Optional.ofNullable(ConfigManager.get("motd_format")).orElse("{motd} | World Reset Count:{resetcount}");
                    if (!format.contains("{resetcount}")) format = format + " {resetcount}";
                    // Ensure base MOTD is captured
                    var motdBaseFile = flagsDir(runDir).resolve("hc_base_motd.txt");
                    String baseMotd;
                    if (Files.exists(motdBaseFile)) {
                        baseMotd = Files.readString(motdBaseFile).trim();
                    } else {
                        baseMotd = "";
                        try {
                            var p0 = new Properties();
                            if (Files.exists(propsFile2)) { try (var in0 = Files.newInputStream(propsFile2)) { p0.load(in0); } }
                            baseMotd = Optional.ofNullable(p0.getProperty("motd")).orElse("");
                        } catch (Throwable ignored) {}
                        try { Files.writeString(motdBaseFile, baseMotd, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING); } catch (Throwable ignored) {}
                    }
                    String newMotd = format.replace("{motd}", baseMotd).replace("{resetcount}", Integer.toString(newCount));
                    newMotd = MotdUtil.applyFormatting(newMotd);
                    try {
                        var p2 = new Properties();
                        if (Files.exists(propsFile2)) { try (var in2 = Files.newInputStream(propsFile2)) { p2.load(in2); } }
                        p2.setProperty("motd", newMotd);
                        try (var out2 = Files.newOutputStream(propsFile2, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) { p2.store(out2, "server.properties (modified by HardcorePlus+) update MOTD with reset count (pre-restart)"); }
                        LOGGER.info("Prewrote MOTD for next start with reset count ({}): {}", newCount, newMotd);
                    } catch (Throwable t) { LOGGER.info("Failed to prewrite MOTD", t); }
                }
            } catch (Throwable t) { LOGGER.info("Failed to increment reset_count", t); }

            var marker = flagsDir(runDir).resolve("hc_reset.flag");
            var mp = new Properties();
            mp.setProperty("requestedBy", "mod");
            mp.setProperty("time", Long.toString(System.currentTimeMillis()));
            mp.setProperty("old-level-name", oldLevelName);
            mp.setProperty("new-level-name", newLevelName);
            mp.setProperty("base-level-name", baseLevelName);
            if (newSeedWritten != null) mp.setProperty("new-seed", newSeedWritten);
            try (var w = Files.newBufferedWriter(marker)) { mp.store(w, "HardcorePlus+ world rotation metadata"); }
            LOGGER.info("Wrote hc_reset.flag at {} with rotation metadata", marker.toAbsolutePath());

            boolean autoRestart = ConfigManager.getBoolean("auto_restart");
            int delay = ConfigManager.getInt("restart_delay_seconds", 10);

            LOGGER.info("HardcorePlus+ initiating server stop for reset in {} seconds", delay);
            try {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
                    try {
                        server.execute(() -> {
                            server.stop(false);
                            if (autoRestart) { LOGGER.info("auto_restart is true; server process should be restarted by wrapper if present"); }
                        });
                        
                    } catch (Throwable ex) { LOGGER.warn("Failed to stop server after delay", ex); }
                }, "hcp-restart-timer");
                t.setDaemon(true); t.start();
            } catch (Throwable t) {
                LOGGER.warn("Failed to start restart timer thread; stopping immediately as fallback", t);
                try { server.stop(false); } catch (Throwable ex) { LOGGER.warn("Failed to stop server", ex); }
            }
        } catch (Throwable t) {
            LOGGER.warn("Exception while requesting reset and stop (Fabric)", t);
        }
    }

    public static void requestResetAndStop(MinecraftServer server, String triggeringPlayerName) {
        if (server == null) return;
        try { if (!server.isDedicated()) return; } catch (Throwable ignored) { return; }
        try { ConfigManager.reload(); } catch (Throwable ignored) {}
        try {
            var runDir = server.getRunDirectory();
            var existingMarkerNew = flagsDir(runDir).resolve("hc_reset.flag");
            var existingMarkerOld = runDir.resolve("hc_reset.flag");
            if (Files.exists(existingMarkerNew) || Files.exists(existingMarkerOld)) { LOGGER.debug("Reset already scheduled; suppressing duplicate restart announcement"); return; }
            int delay = ConfigManager.getInt("restart_delay_seconds", 10);
            long startMs = WORLD_START_MILLIS;
            if (startMs <= 0) {
                try {
                    var worldStartNew = flagsDir(runDir).resolve("hc_world_start.flag");
                    var worldStartOld = runDir.resolve("hc_world_start.flag");
                    var worldStart = Files.exists(worldStartNew) ? worldStartNew : worldStartOld;
                    if (Files.exists(worldStart)) {
                        var pp = new Properties(); try (var r = Files.newBufferedReader(worldStart)) { pp.load(r); }
                        String ln = pp.getProperty("level-name"); String st = pp.getProperty("start");
                        if (ln != null && st != null && ln.equals(CURRENT_LEVEL_NAME)) { try { startMs = Long.parseLong(st); } catch (NumberFormatException ignored) {} }
                    }
                } catch (Throwable ignored) {}
            }
            String dur = formatDuration(Math.max(0L, System.currentTimeMillis() - Math.max(0L, startMs)));
            try {
                Text msg = Text.empty()
                        .append(Text.literal(triggeringPlayerName != null && !triggeringPlayerName.isBlank() ? triggeringPlayerName : "A player").formatted(Formatting.GOLD, Formatting.BOLD))
                        .append(Text.literal(" has died. ").formatted(Formatting.RED))
                        .append(Text.literal("World lasted ").formatted(Formatting.GRAY))
                        .append(Text.literal(dur).formatted(Formatting.AQUA, Formatting.BOLD))
                        .append(Text.literal(". Restart in ").formatted(Formatting.GRAY))
                        .append(Text.literal(Integer.toString(delay)).formatted(Formatting.YELLOW, Formatting.BOLD))
                        .append(Text.literal(" seconds.").formatted(Formatting.GRAY));
                server.getPlayerManager().broadcast(msg, false);
            } catch (Throwable t) { LOGGER.info("Failed to broadcast restart message", t); }
        } catch (Throwable t) { LOGGER.info("Announcement pre-check failed; proceeding with reset request", t); }
        requestResetAndStop(server);
    }

    // Broadcast message for command-triggered restart (no "has died" phrasing)
    private static void broadcastCommandRestart(MinecraftServer server) {
        try { if (server == null || !server.isDedicated()) return; } catch (Throwable ignored) { return; }
        try { ConfigManager.reload(); } catch (Throwable ignored) {}
        int delay = ConfigManager.getInt("restart_delay_seconds", 10);
        long uptimeMs = WORLD_START_MILLIS > 0 ? System.currentTimeMillis() - WORLD_START_MILLIS : 0L;
        String dur = formatDuration(Math.max(0L, uptimeMs));
        try {
            Text msg = Text.empty()
                    .append(Text.literal("Restart Triggered").formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal(" — World Uptime: ").formatted(Formatting.GRAY))
                    .append(Text.literal(dur).formatted(Formatting.AQUA, Formatting.BOLD))
                    .append(Text.literal(" — Restart in ").formatted(Formatting.GRAY))
                    .append(Text.literal(Integer.toString(delay)).formatted(Formatting.YELLOW, Formatting.BOLD))
                    .append(Text.literal(" seconds.").formatted(Formatting.GRAY));
            server.getPlayerManager().broadcast(msg, false);
        } catch (Throwable t) { LOGGER.info("Failed to broadcast command-triggered restart message", t); }
    }

    public static void performMassKill(MinecraftServer server) {
        if (server == null) return;
        if (!PROCESSING.compareAndSet(false, true)) { LOGGER.info("[hcp] performMassKill called but processing already true"); return; }
        try {
            server.getPlayerManager().getPlayerList().forEach(player -> {
                try {
                    if (!player.isDead() && player.isAlive()) {
                        LOGGER.info("[hcp] Killing player: {}", player.getGameProfile().getName());
                        try {
                            player.kill();
                        } catch (Throwable t) {
                            LOGGER.info("[hcp] kill() failed for {} - falling back to setHealth(0)", player.getGameProfile().getName());
                            try { player.setHealth(0.0F); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) { LOGGER.info("[hcp] Exception while attempting to kill player {}", player.getGameProfile().getName(), t); }
            });
        } finally {
            PROCESSING.set(false);
        }
    }

    private static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long s = seconds % 60;
        long minutes = (seconds / 60) % 60;
        long hours = (seconds / 3600);
        return String.format("%02d:%02d:%02d", hours, minutes, s);
    }
}
