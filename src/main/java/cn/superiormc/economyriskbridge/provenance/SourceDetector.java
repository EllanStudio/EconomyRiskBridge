package cn.superiormc.economyriskbridge.provenance;

import cn.superiormc.economyriskbridge.model.BalanceEntry;
import org.bukkit.plugin.Plugin;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SourceDetector {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Set<String> BRIDGES = Set.of(
        "excellenteconomy", "economyriskbridge", "vault", "nightcore", "cmilib"
    );

    private final Map<ClassLoader, String> pluginByClassLoader;

    public SourceDetector(Plugin[] plugins) {
        Map<ClassLoader, String> mapped = new IdentityHashMap<>();
        for (Plugin plugin : plugins) {
            mapped.put(plugin.getClass().getClassLoader(), plugin.getName());
        }
        this.pluginByClassLoader = Map.copyOf(mapped);
    }

    public Attribution detect() {
        String pluginName = WALKER.walk(frames -> frames
            .map(frame -> this.pluginByClassLoader.get(frame.getDeclaringClass().getClassLoader()))
            .filter(name -> name != null && !BRIDGES.contains(name.toLowerCase(Locale.ROOT)))
            .findFirst()
            .orElse(""));
        if (pluginName.isEmpty()) {
            return new Attribution(SourceCatalog.UNKNOWN_EXTERNAL, BalanceEntry.Confidence.UNKNOWN,
                "ExcellentEconomy/控制台/未知 API；请结合 operations.log 核对");
        }
        SourceCatalog source = SourceCatalog.fromPlugin(pluginName);
        return new Attribution(source, BalanceEntry.Confidence.INFERRED,
            "调用栈归因插件：" + pluginName);
    }

    public record Attribution(SourceCatalog source, BalanceEntry.Confidence confidence, String detail) {
    }
}
