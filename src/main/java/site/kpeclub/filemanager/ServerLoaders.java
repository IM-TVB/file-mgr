package site.kpeclub.filemanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which Modrinth "loaders" this server can run, so the plugin installer only offers plugins
 * for it and never mods (Fabric, Forge, ...). Paper runs paper, spigot and bukkit plugins;
 * Purpur adds purpur; Spigot runs spigot and bukkit; Folia only runs folia-ready plugins.
 */
public final class ServerLoaders {

    /** Used until the plugin has looked at the running server. */
    public static final List<String> DEFAULT = List.of("paper", "spigot", "bukkit", "purpur", "folia");

    /** Loaders of mods and other things that are not server plugins. Never shown. */
    private static final Set<String> MOD_LOADERS = Set.of(
            "fabric", "forge", "neoforge", "quilt", "liteloader", "modloader", "rift", "babric", "bta-babric",
            "legacy-fabric", "ornithe", "nilloader", "java-agent", "minecraft", "iris", "optifine", "vanilla", "datapack");

    private static volatile List<String> compatible = DEFAULT;

    private ServerLoaders() {}

    /** The loaders this server can run, most specific first. */
    public static List<String> get() {
        return compatible;
    }

    /** Sets the loaders. Only plain names (a-z, 0-9, - and _) are kept, so they are safe to put in a URL. */
    public static void set(List<String> loaders) {
        List<String> clean = new ArrayList<>();
        if (loaders != null) {
            for (String l : loaders) {
                if (l == null) continue;
                String v = l.trim().toLowerCase(Locale.ROOT);
                if (v.matches("[a-z0-9_-]{1,32}") && !MOD_LOADERS.contains(v) && !clean.contains(v)) clean.add(v);
            }
        }
        compatible = clean.isEmpty() ? DEFAULT : List.copyOf(clean);
    }

    /** Works out the loaders from what the server reports about itself. */
    public static List<String> detect(String serverName, String serverVersion,
                                      boolean hasFolia, boolean hasPaper, boolean hasSpigot) {
        String n = ((serverName == null ? "" : serverName) + " " + (serverVersion == null ? "" : serverVersion))
                .toLowerCase(Locale.ROOT);
        if (hasFolia || n.contains("folia")) return List.of("folia");
        if (n.contains("purpur")) return List.of("purpur", "paper", "spigot", "bukkit");
        if (hasPaper || n.contains("paper") || n.contains("pufferfish") || n.contains("leaf")) {
            return List.of("paper", "spigot", "bukkit");
        }
        if (hasSpigot || n.contains("spigot")) return List.of("spigot", "bukkit");
        return List.of("bukkit");
    }

    /** True if a version runs on this server. A version that lists no loader is given the benefit of the doubt. */
    public static boolean matches(List<String> versionLoaders) {
        return matches(versionLoaders, compatible);
    }

    /** True if a version runs on one of the allowed loaders. No loader listed counts as a match. */
    public static boolean matches(List<String> versionLoaders, List<String> allowedLoaders) {
        if (versionLoaders == null || versionLoaders.isEmpty()) return true;
        for (String l : versionLoaders) {
            if (l != null && allowedLoaders.contains(l.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** The same list without mod loaders, for showing badges. */
    public static List<String> withoutModLoaders(List<String> loaders) {
        List<String> out = new ArrayList<>();
        if (loaders != null) {
            for (String l : loaders) {
                if (l != null && !MOD_LOADERS.contains(l.toLowerCase(Locale.ROOT))) out.add(l);
            }
        }
        return out;
    }
}
