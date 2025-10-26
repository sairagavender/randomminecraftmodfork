package insidate.hardcoreplus;

public final class MotdUtil {
    private MotdUtil() {}

    /**
     * Convert ampersand-style color/format codes to section sign codes.
     * Supports: &0-9a-fk-or and hex codes in the form of '&#RRGGBB'.
     */
    public static String applyFormatting(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        // Legacy codes &0-9a-fk-or
        out = out.replaceAll("(?i)&([0-9A-FK-OR])", "\u00A7$1");
        // Hex codes like '&#RRGGBB' -> §x§R§R§G§G§B§B (manual find/replace)
        out = replaceHex(out);
        return out;
    }

    /**
     * Escape section signs for server.properties using unicode escapes. The properties
     * file is parsed by java.util.Properties; writing "\\u00A7" ensures the runtime
     * value becomes the actual section sign (§) and avoids encoding issues.
     */
    public static String escapeForProperties(String s) {
        if (s == null || s.isEmpty()) return s;
        // Replace literal § with \u00A7 so Properties.load decodes to § at runtime
        String out = s.replace("\u00A7", "\\u00A7");
        return out;
    }

    private static String replaceHex(String s) {
        StringBuilder result = new StringBuilder(s.length());
        for (int i = 0; i < s.length();) {
            char c = s.charAt(i);
            if (c == '&' && i + 7 < s.length() && s.charAt(i + 1) == '#') {
                String hex = s.substring(i + 2, i + 8);
                if (hex.matches("(?i)[0-9A-F]{6}")) {
                    result.append('\u00A7').append('x');
                    for (int j = 0; j < 6; j++) {
                        char hc = Character.toUpperCase(hex.charAt(j));
                        result.append('\u00A7').append(hc);
                    }
                    i += 8; // skip '&#RRGGBB'
                    continue;
                }
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }
}
