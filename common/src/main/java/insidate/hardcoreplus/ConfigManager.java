package insidate.hardcoreplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic config manager that reads/writes config/hardcoreplus.properties
 * under the current working directory (server root/run dir).
 */
public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardcoreplus");
    private static Properties props = new Properties();
    private static Path configPath;

    // Keys and comments
    private static final Map<String, String> KEY_COMMENTS = new LinkedHashMap<>();
    static {
        KEY_COMMENTS.put("backup_old_worlds", "Whether to move/copy old world to a backup folder on reset (true) or delete it (false)");
        KEY_COMMENTS.put("delete_instead_of_backup", "If true, deletes old worlds instead of backing up (overrides backup_old_worlds)");
        KEY_COMMENTS.put("backup_folder_name", "Name of the folder under run directory where backups are stored");
        KEY_COMMENTS.put("backup_name_format", "Format for backup folder name; tokens: %name%, %ts%, %id%");
        KEY_COMMENTS.put("new_level_name_format", "Format for the new level-name; tokens: %name%, %time%, %id%");
        KEY_COMMENTS.put("time_format", "Time format pattern for %time% (java.time DateTimeFormatter)");
        KEY_COMMENTS.put("force_new_seed", "If true, writes a new level-seed to server.properties on rotation");
        KEY_COMMENTS.put("seed_mode", "random or custom");
        KEY_COMMENTS.put("custom_seed", "Custom seed to use when seed_mode=custom");
        KEY_COMMENTS.put("restart_delay_seconds", "Seconds to wait before stopping the server after a reset request");
        KEY_COMMENTS.put("auto_restart", "Whether an external wrapper should restart the server after stop (informational)");
        KEY_COMMENTS.put("motd_enable", "If true, modify the server MOTD to include the world reset count");
        KEY_COMMENTS.put("motd_format", "Format for MOTD when motd_enable=true; tokens: {motd}, {resetcount}. Supports &-codes and hex like &#RRGGBB");
    }

    public static void load() {
        try {
            Path runDir = java.nio.file.Path.of("").toAbsolutePath().normalize();
            Path configDir = runDir.resolve("config");
            try { if (!Files.exists(configDir)) Files.createDirectories(configDir); } catch (IOException ignored) {}

            // Use config/hardcoreplus.properties by default (no legacy migration)
            configPath = configDir.resolve("hardcoreplus.properties");

            if (!Files.exists(configPath)) {
                props = defaults();
                saveFormatted(props);
            } else {
                props = new Properties();
                try (var in = Files.newInputStream(configPath)) { props.load(in); }
                // ensure defaults exist
                Properties def = defaults();
                for (String k : def.stringPropertyNames()) if (!props.containsKey(k)) props.setProperty(k, def.getProperty(k));
                saveFormatted(props);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load config", e);
        }
    }

    public static void reload() { load(); }

    public static String get(String key) { return props.getProperty(key); }
    public static boolean getBoolean(String key) { return Boolean.parseBoolean(props.getProperty(key, "false")); }
    public static int getInt(String key, int def) { try { return Integer.parseInt(props.getProperty(key, Integer.toString(def))); } catch (NumberFormatException e) { return def; } }
    
    /**
     * Return all effective config key/value pairs.
     * - Known keys are returned first in the fixed order of KEY_COMMENTS
     * - Unknown keys are appended after, sorted by key
     */
    public static Map<String, String> getAll() {
        // Ensure defaults are present in props
        try {
            if (props == null || props.isEmpty()) load();
        } catch (Throwable ignored) {}

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        // Known keys in defined order
        for (String k : KEY_COMMENTS.keySet()) {
            out.put(k, props.getProperty(k, ""));
        }
        // Unknown keys appended in alpha order
        java.util.TreeSet<String> unknown = new java.util.TreeSet<>();
        for (String k : props.stringPropertyNames()) if (!KEY_COMMENTS.containsKey(k)) unknown.add(k);
        for (String k : unknown) out.put(k, props.getProperty(k, ""));
        return out;
    }

    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("backup_old_worlds", "true");
        p.setProperty("delete_instead_of_backup", "false");
        p.setProperty("backup_folder_name", "Old Worlds");
        p.setProperty("backup_name_format", "%name%_%ts%");
        p.setProperty("new_level_name_format", "%name%_%time%");
        p.setProperty("time_format", "HH-mm-ss_uuuu-MM-dd");
        p.setProperty("force_new_seed", "true");
        p.setProperty("seed_mode", "random");
        p.setProperty("custom_seed", "");
        p.setProperty("restart_delay_seconds", "10");
        p.setProperty("auto_restart", "true");
        p.setProperty("motd_enable", "false");
        p.setProperty("motd_format", "&6&l{motd} &8| &bWorld Resets: &e{resetcount}");
        return p;
    }

    private static void saveFormatted(Properties p) throws IOException {
        // Build annotated file content
        StringBuilder sb = new StringBuilder();
        sb.append("# HardcorePlus+ configuration (MultiLoader common)\n");
        sb.append("# Generated and managed by the mod. Unknown keys preserved.\n\n");
        for (Map.Entry<String, String> e : KEY_COMMENTS.entrySet()) {
            sb.append("# ").append(e.getValue()).append("\n");
            String k = e.getKey();
            String v = p.getProperty(k, "");
            sb.append(k).append("=").append(v == null ? "" : v).append("\n\n");
        }
        // Preserve other keys at the end
        for (String k : p.stringPropertyNames()) {
            if (KEY_COMMENTS.containsKey(k)) continue;
            sb.append(k).append("=").append(p.getProperty(k, "")).append("\n");
        }
        if (configPath != null) {
            try { Files.createDirectories(configPath.getParent()); } catch (IOException ignored) {}
            Files.writeString(configPath, sb.toString());
        }
    }
}
