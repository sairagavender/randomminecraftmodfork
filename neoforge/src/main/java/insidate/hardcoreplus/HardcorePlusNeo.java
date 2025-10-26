package insidate.hardcoreplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod(HardcorePlusNeo.MOD_ID)
public class HardcorePlusNeo {
	public static final String MOD_ID = "hardcoreplus";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Path flagsDir(Path runDir) {
		Path d = runDir.resolve("HardcorePlus+");
		try { if (!Files.exists(d)) Files.createDirectories(d); } catch (IOException ignored) {}
		return d;
	}

	// Shared guard and world-time tracking
	public static final AtomicBoolean PROCESSING = new AtomicBoolean(false);
	public static volatile long WORLD_START_MILLIS = 0L;
	public static volatile String CURRENT_LEVEL_NAME = "world";

	public HardcorePlusNeo(net.neoforged.bus.api.IEventBus modBus) {
		LOGGER.info("HardcorePlus+ (NeoForge) initializing");
		// Load config
		ConfigManager.load();
		// Listen for game events on NeoForge bus
		NeoForge.EVENT_BUS.register(this);
		// Setup if needed
		modBus.addListener(this::onCommonSetup);
	}

	// Reason for initiating a reset
	private enum ResetReason { DEATH, COMMAND }

	private void onCommonSetup(FMLCommonSetupEvent evt) {
		LOGGER.info("HardcorePlus+ common setup (NeoForge)");
	}

	// Handle reset marker before server fully starts (backup/delete old world folder)
	@SubscribeEvent
	public void onServerAboutToStart(ServerAboutToStartEvent event) {
		MinecraftServer server = event.getServer();
		if (server == null) return;
		try { if (!server.isDedicatedServer()) return; } catch (Throwable ignored) { return; }
		try { ConfigManager.reload(); } catch (Throwable ignored) {}
		try {
			Path runDir = server.getServerDirectory();
			Path markerNew = flagsDir(runDir).resolve("hc_reset.flag");
			Path markerOld = runDir.resolve("hc_reset.flag");
			Path marker = Files.exists(markerNew) ? markerNew : markerOld;
			if (!Files.exists(marker)) return;

			LOGGER.info("hc_reset.flag detected; preparing to rotate world (NeoForge)");

			String levelName = "world";
			try {
				Properties mp = new Properties();
				try (var r = Files.newBufferedReader(marker)) { mp.load(r); }
				String fromMarker = mp.getProperty("old-level-name");
				if (fromMarker != null && !fromMarker.isBlank()) levelName = fromMarker;
			} catch (IOException ignored) {}
			if ("world".equals(levelName)) {
				Path propsFile = runDir.resolve("server.properties");
				if (Files.exists(propsFile)) {
					try (var in = Files.newInputStream(propsFile)) {
						Properties p = new Properties();
						p.load(in);
						levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName);
					}
				}
			}
			Path worldDir = runDir.resolve(levelName);
			boolean doBackup = ConfigManager.getBoolean("backup_old_worlds");
			boolean deleteInstead = ConfigManager.getBoolean("delete_instead_of_backup");
			String backupFolderName = Optional.ofNullable(ConfigManager.get("backup_folder_name")).orElse("Old Worlds");

			if (Files.exists(worldDir)) {
				if (doBackup && !deleteInstead) {
					Path backupRoot = runDir.resolve(backupFolderName);
					if (!Files.exists(backupRoot)) Files.createDirectories(backupRoot);
					String fmtStr = Optional.ofNullable(ConfigManager.get("backup_name_format")).orElse("%name%_%ts%");
					DateTimeFormatter fmt = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());
					String ts = fmt.format(Instant.now());
					String id = UUID.randomUUID().toString().substring(0, 8);
					String backupName = fmtStr.replace("%name%", levelName).replace("%ts%", ts).replace("%id%", id);
					Path backupTarget = backupRoot.resolve(backupName);
					boolean moved = false;
					try {
						Files.move(worldDir, backupTarget, StandardCopyOption.ATOMIC_MOVE);
						LOGGER.info("Moved old world to {}", backupTarget.toAbsolutePath());
						moved = true;
					} catch (IOException e) {
						// This is common on Windows when files are locked; fallback paths handle it
						LOGGER.info("Atomic move failed; trying non-atomic (expected on Windows): {}", e.toString());
						try { Files.move(worldDir, backupTarget); moved = true; } catch (IOException ex) { LOGGER.info("Non-atomic move failed; copying (expected on Windows): {}", ex.toString()); }
					}
					if (!moved) {
						try {
							Files.walk(worldDir).forEach(src -> {
								try {
									Path dest = backupTarget.resolve(worldDir.relativize(src));
									if (Files.isDirectory(src)) {
										if (!Files.exists(dest)) Files.createDirectories(dest);
									} else {
										if (src.getFileName().toString().equalsIgnoreCase("session.lock")) { LOGGER.info("Skipping session.lock"); return; }
										Files.copy(src, dest);
									}
								} catch (IOException ex) { LOGGER.debug("Error copying {} (continuing): {}", src, ex.toString()); }
							});
							Files.walk(worldDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored2) {} });
							LOGGER.info("Copied old world to {} and deleted original", backupTarget.toAbsolutePath());
						} catch (IOException ex) {
							LOGGER.error("Failed to copy-and-delete old world", ex);
						}
					}
				} else {
					try {
						Files.walk(worldDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored2) {} });
						LOGGER.info("Deleted old world {}", worldDir.toAbsolutePath());
					} catch (IOException e) {
						LOGGER.error("Failed to delete old world", e);
					}
				}
			}

			try { Files.deleteIfExists(marker); } catch (IOException ignored) {}
		} catch (Throwable t) {
			LOGGER.error("Exception while handling hc_reset.flag (NeoForge)", t);
		}
	}

	// Record world start time
	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		if (server == null) return;
		try {
			Path runDir = server.getServerDirectory();
			Path propsFile = runDir.resolve("server.properties");
			String levelName = "world";
			if (Files.exists(propsFile)) {
				try (var in = Files.newInputStream(propsFile)) { Properties p = new Properties(); p.load(in); levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName); }
			}
			CURRENT_LEVEL_NAME = levelName;
			Path worldStartNew = flagsDir(runDir).resolve("hc_world_start.flag");
			Path worldStart = Files.exists(worldStartNew) ? worldStartNew : runDir.resolve("hc_world_start.flag");
			long start = System.currentTimeMillis();
			boolean matched = false;
			if (Files.exists(worldStart)) {
				try (var r = Files.newBufferedReader(worldStart)) { Properties pp = new Properties(); pp.load(r); String ln = pp.getProperty("level-name"), st = pp.getProperty("start"); if (ln != null && st != null && ln.equals(levelName)) { try { start = Long.parseLong(st); matched = true; } catch (NumberFormatException ignored) {} } }
			}
			Properties out = new Properties();
			out.setProperty("level-name", levelName);
			out.setProperty("start", Long.toString(start));
			try (var w = Files.newBufferedWriter(worldStartNew)) { out.store(w, "HardcorePlus+ world start timestamp"); }
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
					Path baseFilePref = flagsDir(runDir).resolve("hc_base_motd.txt");
					Path baseFileOld = runDir.resolve("hc_base_motd.txt");
					Path baseFile = Files.exists(baseFilePref) ? baseFilePref : baseFileOld;
					if (Files.exists(baseFile)) {
						baseMotd = Files.readString(baseFile).trim();
					} else {
						baseMotd = "";
						try {
							Properties p = new Properties();
							if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); } }
							baseMotd = Optional.ofNullable(p.getProperty("motd")).orElse("");
						} catch (Throwable ignored) {}
						// Always write to designated folder; migrate legacy root if present
						try {
							if (!Files.exists(baseFilePref.getParent())) Files.createDirectories(baseFilePref.getParent());
							Files.writeString(baseFilePref, baseMotd, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
							// Optionally remove old root copy
							try { if (Files.exists(baseFileOld)) Files.delete(baseFileOld); } catch (Throwable ignored2) {}
						} catch (Throwable ignored) {}
					}
					String newMotd = format.replace("{motd}", baseMotd).replace("{resetcount}", Integer.toString(resets));
					newMotd = MotdUtil.applyFormatting(newMotd);
					try {
						Properties p = new Properties();
						if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); } }
						p.setProperty("motd", newMotd);
						try (var fos = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) { p.store(fos, "server.properties (modified by HardcorePlus+) update MOTD with reset count"); }
						LOGGER.info("Updated MOTD with reset count ({}): {}", resets, newMotd);
					} catch (Throwable t) { LOGGER.info("Failed to update MOTD", t); }
				}
			} catch (Throwable t) { LOGGER.info("MOTD update skipped due to error", t); }
		} catch (Throwable t) {
			LOGGER.warn("Failed to initialize world start tracking (NeoForge)", t);
		}
	}

	// Commands
	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		var dispatcher = event.getDispatcher();
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hcp")
				.executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("Use /hcp help for available commands."), false); return 1; })
				.then(Commands.literal("help").executes(this::cmdHelp))
				.then(Commands.literal("status").executes(this::cmdStatus))
				.then(Commands.literal("config").requires(s -> s.hasPermission(2)).executes(this::cmdConfig))
				.then(Commands.literal("preview").executes(this::cmdPreview))
				.then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(this::cmdReload))
				.then(Commands.literal("reset").requires(s -> s.hasPermission(2))
						.then(Commands.literal("confirm").executes(this::cmdResetConfirm))
						.executes(this::cmdReset))
				.then(Commands.literal("masskill").requires(s -> s.hasPermission(2))
						.then(Commands.literal("confirm").executes(this::cmdMasskillConfirm))
						.executes(this::cmdMasskill))
				.then(Commands.literal("time").executes(this::cmdTime));
		dispatcher.register(root);
		LOGGER.info("[hcp] Registered /hcp commands (NeoForge)");
	}

	private int cmdHelp(CommandContext<CommandSourceStack> ctx) {
		boolean isOp = false; try { isOp = ctx.getSource().hasPermission(2); } catch (Throwable ignored) {}
		StringBuilder sb = new StringBuilder();
		sb.append("HardcorePlus+ commands:\n");
		sb.append("  /hcp status - Show hardcore/processing/players\n");
		sb.append("  /hcp preview - Show next world name and seed\n");
		sb.append("  /hcp time - Show current world time and real uptime\n");
		if (isOp) {
			sb.append("  /hcp masskill - Request mass-kill (confirm required)\n");
			sb.append("  /hcp masskill confirm - Confirm mass-kill and schedule restart\n");
			sb.append("  /hcp reset - Schedule world rotation (confirm required)\n");
			sb.append("  /hcp reset confirm - Confirm rotation and stop server\n");
			sb.append("  /hcp config - Show effective config\n");
			sb.append("  /hcp reload - Reload config file\n");
		} else {
			sb.append("  (Op-only) masskill, reset, config, reload\n");
		}
		ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	private int cmdConfig(CommandContext<CommandSourceStack> ctx) {
		try { ConfigManager.reload(); } catch (Throwable ignored) {}
		String msg = String.join("\n",
				"HardcorePlus+ config:",
				"  new_level_name_format=" + String.valueOf(ConfigManager.get("new_level_name_format")),
				"  time_format=" + String.valueOf(ConfigManager.get("time_format")),
				"  force_new_seed=" + ConfigManager.getBoolean("force_new_seed"),
				"  seed_mode=" + String.valueOf(ConfigManager.get("seed_mode")),
				"  custom_seed=" + String.valueOf(ConfigManager.get("custom_seed")),
				"  backup_old_worlds=" + ConfigManager.getBoolean("backup_old_worlds"),
				"  delete_instead_of_backup=" + ConfigManager.getBoolean("delete_instead_of_backup"),
				"  backup_folder_name=" + String.valueOf(ConfigManager.get("backup_folder_name")),
				"  backup_name_format=" + String.valueOf(ConfigManager.get("backup_name_format")),
				"  restart_delay_seconds=" + ConfigManager.getInt("restart_delay_seconds", 10),
				"  auto_restart=" + ConfigManager.getBoolean("auto_restart")
		);
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private int cmdStatus(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		boolean isHardcore = false;
		try { isHardcore = server.getWorldData().isHardcore(); } catch (Throwable ignored) {}
		boolean propsHardcore = false;
		try {
			Path propsFile = server.getServerDirectory().resolve("server.properties");
			if (Files.exists(propsFile)) {
				Properties p = new Properties(); try (var in = Files.newInputStream(propsFile)) { p.load(in); }
				String hv = p.getProperty("hardcore");
				if (hv != null) propsHardcore = hv.equalsIgnoreCase("true") || hv.equalsIgnoreCase("1") || hv.equalsIgnoreCase("yes");
			}
		} catch (Throwable ignored) {}
		int players = 0; try { players = server.getPlayerList().getPlayers().size(); } catch (Throwable ignored) {}
		String msg = String.format("Hardcore (world): %s, server.properties: %s, Processing: %s, Online players: %d", isHardcore, propsHardcore, PROCESSING.get(), players);
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private int cmdPreview(CommandContext<CommandSourceStack> ctx) {
		try { ConfigManager.reload(); } catch (Throwable ignored) {}
		MinecraftServer server = ctx.getSource().getServer();
		String oldName = "world";
		try { Properties p = new Properties(); Path props = server.getServerDirectory().resolve("server.properties"); if (Files.exists(props)) { try (var in = Files.newInputStream(props)) { p.load(in); } oldName = Optional.ofNullable(p.getProperty("level-name")).orElse(oldName);} } catch (Throwable ignored) {}
		// Prefer base name file if present; strip any trailing time suffixes to avoid duplication
		String baseName = oldName;
		try {
			Path baseNew = flagsDir(server.getServerDirectory()).resolve("hc_base_name.txt");
			Path baseOld = server.getServerDirectory().resolve("hc_base_name.txt");
			if (Files.exists(baseNew)) {
				String tmp = Files.readString(baseNew).trim();
				if (!tmp.isEmpty()) baseName = tmp;
			} else if (Files.exists(baseOld)) {
				String tmp = Files.readString(baseOld).trim();
				if (!tmp.isEmpty()) baseName = tmp;
			}
			baseName = NameUtil.stripTimeSuffixes(baseName);
		} catch (Throwable ignored) {}
		String timePattern = Optional.ofNullable(ConfigManager.get("time_format")).filter(s -> !s.isBlank()).orElse("HH-mm-ss_uuuu-MM-dd");
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.systemDefault());
		String timeStr = fmt.format(Instant.now());
		String nameFmt = Optional.ofNullable(ConfigManager.get("new_level_name_format")).filter(s -> !s.isBlank()).orElse("%name%_%time%");
		String id = UUID.randomUUID().toString().substring(0, 8);
		String newName = NameUtil.sanitizeName(nameFmt.replace("%name%", baseName).replace("%time%", timeStr).replace("%id%", id));
		String seedInfo = "(unchanged)";
		if (ConfigManager.getBoolean("force_new_seed")) {
			String mode = Optional.ofNullable(ConfigManager.get("seed_mode")).orElse("random").trim().toLowerCase();
			if (mode.equals("custom")) {
				String cs = Optional.ofNullable(ConfigManager.get("custom_seed")).orElse("");
				seedInfo = cs.isBlank() ? "<empty custom_seed> -> random" : cs;
			} else seedInfo = Long.toString(ThreadLocalRandom.current().nextLong());
		}
		String msg = "Preview rotation => new level-name: '" + newName + "', seed: " + seedInfo;
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private int cmdReload(CommandContext<CommandSourceStack> ctx) {
		ConfigManager.reload();
		ctx.getSource().sendSuccess(() -> Component.literal("HardcorePlus+ config reloaded."), false);
		return 1;
	}

	private int cmdReset(CommandContext<CommandSourceStack> ctx) {
		// Simple confirm gate using source UUID where possible isn't directly available; keep simple
		ctx.getSource().sendSuccess(() -> Component.literal("Reset requested. Confirm with /hcp reset confirm within 30 seconds."), false);
		// Not implementing per-user confirm map now; immediate confirm expected
		return 1;
	}

	private int cmdResetConfirm(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		requestResetAndStop(server, ResetReason.COMMAND, ctx.getSource().getTextName());
		ctx.getSource().sendSuccess(() -> Component.literal("Reset scheduled. Server will stop shortly."), false);
		return 1;
	}

	private int cmdMasskill(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal("Mass-kill requested. Confirm with /hcp masskill confirm within 30 seconds."), false);
		return 1;
	}

	private int cmdMasskillConfirm(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		boolean hardcore = false; try { hardcore = server.getWorldData().isHardcore(); } catch (Throwable ignored) {}
		if (!hardcore) { ctx.getSource().sendSuccess(() -> Component.literal("World is not hardcore; aborting masskill."), false); return 0; }
		performMassKill(server);
		// After mass-kill, schedule a reset/restart using COMMAND reason to avoid "player has died" phrasing
		try { requestResetAndStop(server, ResetReason.COMMAND, ctx.getSource().getTextName()); }
		catch (Throwable t) { LOGGER.error("Failed to schedule reset after mass-kill", t); }
		ctx.getSource().sendSuccess(() -> Component.literal("Mass-kill executed. Restart scheduled."), false);
		return 1;
	}

	private int cmdTime(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		ServerLevel level = server.overworld();
		long todFull = level.getDayTime(); // total time including days
		long timeTotal = level.getGameTime(); // total game time in ticks since world start
		long tod = Math.floorMod(todFull, 24000L);
		long day = Math.floorDiv(todFull, 24000L);
		long hour = (tod / 1000L + 6L) % 24L; // 0 ticks = 06:00
		long minute = (tod % 1000L) * 60L / 1000L;
		String mcClock = String.format("Day %d, %02d:%02d", day, hour, minute);
		long uptimeMs = WORLD_START_MILLIS > 0 ? (System.currentTimeMillis() - WORLD_START_MILLIS) : 0L;
		String uptime = formatDuration(uptimeMs);
		Component msg = Component.empty()
				.append(Component.literal("World Time: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(mcClock).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
				.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal("Ticks: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Long.toString(timeTotal)).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal("Uptime: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(uptime).withStyle(ChatFormatting.GOLD));
		ctx.getSource().sendSuccess(() -> msg, false);
		return 1;
	}

	// Player death handling
	@SubscribeEvent
	public void onLivingDeath(LivingDeathEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		try { if (!server.isDedicatedServer()) return; } catch (Throwable ignored) { return; }
		boolean hardcore = false; try { hardcore = server.getWorldData().isHardcore(); } catch (Throwable ignored) {}
		if (!hardcore) return;

		if (PROCESSING.get()) return;
		LOGGER.info("[hcp] Player death detected in hardcore world; performing mass-kill and scheduling reset");
		try { performMassKill(server); } catch (Throwable t) { LOGGER.warn("performMassKill failed", t); }
		try { requestResetAndStop(server, ResetReason.DEATH, player.getGameProfile().getName()); } catch (Throwable t) { LOGGER.error("requestResetAndStop failed", t); }
	}

	// Core operations
	public static void performMassKill(MinecraftServer server) {
		if (server == null) return;
		if (!PROCESSING.compareAndSet(false, true)) { LOGGER.info("[hcp] performMassKill called but processing already true"); return; }
		try {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				try {
					if (!p.isDeadOrDying()) {
						LOGGER.info("[hcp] Killing player: {}", p.getGameProfile().getName());
						try { p.kill(); } catch (Throwable t) { try { p.hurt(p.damageSources().fellOutOfWorld(), Float.MAX_VALUE); } catch (Throwable ignored) {} }
					}
				} catch (Throwable t) { LOGGER.warn("[hcp] Exception while attempting to kill {}", p.getGameProfile().getName(), t); }
			}
		} finally {
			PROCESSING.set(false);
			LOGGER.debug("[hcp] performMassKill processing flag cleared");
		}
	}

	public static void requestResetAndStop(MinecraftServer server, String triggeringPlayerName) {
		// Backwards-compat: default to DEATH reason when only a name is provided
		requestResetAndStop(server, ResetReason.DEATH, triggeringPlayerName);
	}

	public static void requestResetAndStop(MinecraftServer server, ResetReason reason, String triggeringPlayerName) {
		if (server == null) return;
		try { if (!server.isDedicatedServer()) return; } catch (Throwable ignored) { return; }
		try { ConfigManager.reload(); } catch (Throwable ignored) {}
		try {
			Path runDir = server.getServerDirectory();
			Path existingMarkerNew = flagsDir(runDir).resolve("hc_reset.flag");
			Path existingMarkerOld = runDir.resolve("hc_reset.flag");
			if (Files.exists(existingMarkerNew) || Files.exists(existingMarkerOld)) { LOGGER.debug("hc_reset.flag already exists; skipping duplicate reset request"); return; }

			int delay = ConfigManager.getInt("restart_delay_seconds", 10);
			String name = (triggeringPlayerName != null && !triggeringPlayerName.isBlank()) ? triggeringPlayerName : "A player";
			long startMs = WORLD_START_MILLIS;
			if (startMs <= 0) {
				try {
					Properties pp = new Properties(); Path worldStart = runDir.resolve("hc_world_start.flag");
					if (Files.exists(worldStart)) { try (var r = Files.newBufferedReader(worldStart)) { pp.load(r); } String ln = pp.getProperty("level-name"), st = pp.getProperty("start"); if (ln != null && st != null && ln.equals(CURRENT_LEVEL_NAME)) { try { startMs = Long.parseLong(st); } catch (NumberFormatException ignored) {} } }
				} catch (Throwable ignored) {}
			}
			String dur = formatDuration(Math.max(0L, System.currentTimeMillis() - Math.max(0L, startMs)));
			try {
				Component msg;
				if (reason == ResetReason.DEATH) {
					msg = Component.empty()
							.append(Component.literal(name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
							.append(Component.literal(" has died. ").withStyle(ChatFormatting.RED))
							.append(Component.literal("World lasted ").withStyle(ChatFormatting.GRAY))
							.append(Component.literal(dur).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
							.append(Component.literal(". Restart in ").withStyle(ChatFormatting.GRAY))
							.append(Component.literal(Integer.toString(delay)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
							.append(Component.literal(" seconds.").withStyle(ChatFormatting.GRAY));
				} else { // COMMAND
					msg = Component.empty()
							.append(Component.literal("Restart Triggered. ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
							.append(Component.literal("World lasted ").withStyle(ChatFormatting.GRAY))
							.append(Component.literal(dur).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
							.append(Component.literal(". Restart in ").withStyle(ChatFormatting.GRAY))
							.append(Component.literal(Integer.toString(delay)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
							.append(Component.literal(" seconds.").withStyle(ChatFormatting.GRAY));
				}
				server.getPlayerList().broadcastSystemMessage(msg, false);
			} catch (Throwable t) { LOGGER.warn("Failed to broadcast restart message", t); }

			// Update server.properties with new level-name and maybe seed
			Path propsFile = runDir.resolve("server.properties");
			Properties p = new Properties();
			String oldLevelName = "world";
			if (Files.exists(propsFile)) { try (var in = Files.newInputStream(propsFile)) { p.load(in); oldLevelName = Optional.ofNullable(p.getProperty("level-name")).orElse(oldLevelName); } }

			// Stable base name (normalize by stripping trailing time suffixes if present)
			Path baseFileNew = flagsDir(runDir).resolve("hc_base_name.txt");
			Path baseFileOld = runDir.resolve("hc_base_name.txt");
			String baseLevelName = oldLevelName;
			try {
				if (Files.exists(baseFileNew)) {
					String s = Files.readString(baseFileNew).trim();
					if (!s.isEmpty()) baseLevelName = s;
				} else if (Files.exists(baseFileOld)) {
					// Migrate legacy base name file
					try { if (!Files.exists(baseFileNew.getParent())) Files.createDirectories(baseFileNew.getParent()); Files.move(baseFileOld, baseFileNew); } catch (Throwable ignored2) {}
					String s = Files.exists(baseFileNew) ? Files.readString(baseFileNew).trim() : Files.readString(baseFileOld).trim();
					if (!s.isEmpty()) baseLevelName = s;
				}
				String cleaned = NameUtil.stripTimeSuffixes(baseLevelName);
				if (!cleaned.equals(baseLevelName)) {
					baseLevelName = cleaned;
					try { if (!Files.exists(baseFileNew.getParent())) Files.createDirectories(baseFileNew.getParent()); Files.writeString(baseFileNew, baseLevelName); LOGGER.info("Normalized base level-name to '{}' in {}", baseLevelName, baseFileNew.toAbsolutePath()); } catch (Throwable ignored3) {}
				}
				if (!Files.exists(baseFileNew)) {
					try { if (!Files.exists(baseFileNew.getParent())) Files.createDirectories(baseFileNew.getParent()); Files.writeString(baseFileNew, baseLevelName); LOGGER.info("Saved base level-name '{}' to {}", baseLevelName, baseFileNew.toAbsolutePath()); } catch (Throwable ignored4) {}
				}
			} catch (Throwable t) { LOGGER.warn("Failed to read/write base level-name; using current", t); baseLevelName = oldLevelName; }

			String timePattern = Optional.ofNullable(ConfigManager.get("time_format")).filter(s -> !s.isBlank()).orElse("HH-mm-ss_uuuu-MM-dd");
			DateTimeFormatter dtf = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.systemDefault());
			String timeStr = dtf.format(Instant.now());
			String nameFmt = Optional.ofNullable(ConfigManager.get("new_level_name_format")).filter(s -> !s.isBlank()).orElse("%name%_%time%");
			String id = UUID.randomUUID().toString().substring(0, 8);
			String newLevelName = NameUtil.sanitizeName(nameFmt.replace("%name%", baseLevelName).replace("%time%", timeStr).replace("%id%", id));
			p.setProperty("level-name", newLevelName);

			String newSeedWritten = null;
			if (ConfigManager.getBoolean("force_new_seed")) {
				String seedMode = Optional.ofNullable(ConfigManager.get("seed_mode")).orElse("random").trim().toLowerCase();
				if (seedMode.equals("custom")) {
					String customSeed = Optional.ofNullable(ConfigManager.get("custom_seed")).orElse("");
					if (!customSeed.isBlank()) { p.setProperty("level-seed", customSeed); newSeedWritten = customSeed; }
					else { long s = ThreadLocalRandom.current().nextLong(); p.setProperty("level-seed", Long.toString(s)); newSeedWritten = Long.toString(s); }
				} else {
					long s = ThreadLocalRandom.current().nextLong(); p.setProperty("level-seed", Long.toString(s)); newSeedWritten = Long.toString(s);
				}
			}

			try (var out = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
				p.store(out, "server.properties (modified by HardcorePlus+ to rotate world: new level-name and optional seed)");
			}
			LOGGER.info("Prepared rotation: old-level-name='{}' -> new-level-name='{}'{}", oldLevelName, newLevelName, newSeedWritten == null ? "" : ", level-seed=" + newSeedWritten);

			// Write marker for startup handler
			try {
				int newCount = StatsManager.incrementResetCount(runDir);
				if (ConfigManager.getBoolean("motd_enable")) {
					String format = Optional.ofNullable(ConfigManager.get("motd_format")).orElse("{motd} | World Reset Count:{resetcount}");
					if (!format.contains("{resetcount}")) format = format + " {resetcount}";
					Path motdBaseFilePref = flagsDir(runDir).resolve("hc_base_motd.txt");
					Path motdBaseFileOld = runDir.resolve("hc_base_motd.txt");
					Path motdBaseFile = Files.exists(motdBaseFilePref) ? motdBaseFilePref : motdBaseFileOld;
					String baseMotd;
					if (Files.exists(motdBaseFile)) {
						baseMotd = Files.readString(motdBaseFile).trim();
					} else {
						baseMotd = "";
						try {
							Properties p0 = new Properties();
							if (Files.exists(propsFile)) { try (var in0 = Files.newInputStream(propsFile)) { p0.load(in0); } }
							baseMotd = Optional.ofNullable(p0.getProperty("motd")).orElse("");
						} catch (Throwable ignored) {}
						try {
							if (!Files.exists(motdBaseFilePref.getParent())) Files.createDirectories(motdBaseFilePref.getParent());
							Files.writeString(motdBaseFilePref, baseMotd, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
							try { if (Files.exists(motdBaseFileOld)) Files.delete(motdBaseFileOld); } catch (Throwable ignored2) {}
						} catch (Throwable ignored) {}
					}
					String nextMotd = format.replace("{motd}", baseMotd).replace("{resetcount}", Integer.toString(newCount));
					nextMotd = MotdUtil.applyFormatting(nextMotd);
					try {
						Properties p2 = new Properties();
						if (Files.exists(propsFile)) { try (var in2 = Files.newInputStream(propsFile)) { p2.load(in2); } }
						p2.setProperty("motd", nextMotd);
						try (var fos2 = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) { p2.store(fos2, "server.properties (modified by HardcorePlus+) update MOTD with reset count (pre-restart)"); }
						LOGGER.info("Prewrote MOTD for next start with reset count ({}): {}", newCount, nextMotd);
					} catch (Throwable t) { LOGGER.info("Failed to prewrite MOTD", t); }
				}
			} catch (Throwable t) { LOGGER.info("Failed to increment reset_count", t); }
			Path marker = flagsDir(runDir).resolve("hc_reset.flag");
			Properties mp = new Properties();
			mp.setProperty("requestedBy", "mod");
			mp.setProperty("time", Long.toString(System.currentTimeMillis()));
			mp.setProperty("old-level-name", oldLevelName);
			mp.setProperty("new-level-name", newLevelName);
			mp.setProperty("base-level-name", baseLevelName);
			if (newSeedWritten != null) mp.setProperty("new-seed", newSeedWritten);
			try (var w = Files.newBufferedWriter(marker)) { mp.store(w, "HardcorePlus+ world rotation metadata"); }
			LOGGER.info("Wrote hc_reset.flag at {} with rotation metadata", marker.toAbsolutePath());

			boolean autoRestart = ConfigManager.getBoolean("auto_restart");
			// Non-blocking delay thread (delay already computed above)
			LOGGER.info("HardcorePlus+ initiating server stop for reset in {} seconds", delay);
			try {
				Thread t = new Thread(() -> {
					try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
					try { server.execute(() -> { server.halt(false); if (autoRestart) LOGGER.info("auto_restart is true; server process should be restarted by wrapper if present"); }); } catch (Throwable ex) { LOGGER.error("Failed to stop server after delay", ex); }
				}, "hcp-restart-timer");
				t.setDaemon(true); t.start();
			} catch (Throwable t) { LOGGER.warn("Failed to start restart timer thread; stopping immediately as fallback", t); try { server.halt(false); } catch (Throwable ex) { LOGGER.error("Failed to stop server", ex); } }

		} catch (Throwable t) {
			LOGGER.error("Exception while requesting reset and stop (NeoForge)", t);
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
