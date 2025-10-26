package insidate.hardcoreplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Simple statistics manager for tracking persistent counters like reset_count
 * in the server run directory (hc_stats.properties).
 */
public class StatsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardcoreplus");

    private static Path statsPath(Path runDir) {
        return runDir.resolve("hc_stats.properties");
    }

    public static int getResetCount(Path runDir) {
        try {
            Path p = statsPath(runDir);
            if (!Files.exists(p)) return 0;
            Properties props = new Properties();
            try (var in = Files.newInputStream(p)) { props.load(in); }
            String v = props.getProperty("reset_count", "0");
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
        } catch (IOException e) {
            LOGGER.debug("Failed to read reset_count", e);
            return 0;
        }
    }

    public static int incrementResetCount(Path runDir) {
        int current = 0;
        Properties props = new Properties();
        Path p = statsPath(runDir);
        try {
            if (Files.exists(p)) {
                try (var in = Files.newInputStream(p)) { props.load(in); }
                String v = props.getProperty("reset_count", "0");
                try { current = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { current = 0; }
            }
            int next = Math.max(0, current) + 1;
            props.setProperty("reset_count", Integer.toString(next));
            try (var out = Files.newOutputStream(p, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "HardcorePlus+ stats");
            }
            return next;
        } catch (IOException e) {
            LOGGER.warn("Failed to increment reset_count", e);
            return current;
        }
    }
}
