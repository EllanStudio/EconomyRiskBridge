package cn.superiormc.economyriskbridge.provenance;

import cn.superiormc.economyriskbridge.model.BalanceEntry;
import org.bukkit.plugin.Plugin;

import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SourceDetector {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Set<String> BRIDGES = Set.of(
        "excellenteconomy", "economyriskbridge", "vault", "nightcore", "cmilib"
    );

    private final Map<ClassLoader, Attribution> attributionByClassLoader;

    public SourceDetector(Plugin[] plugins) {
        Map<ClassLoader, Attribution> mapped = new IdentityHashMap<>();
        for (Plugin plugin : plugins) {
            String pluginName = plugin.getName();
            if (BRIDGES.contains(pluginName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            mapped.put(plugin.getClass().getClassLoader(), new Attribution(
                SourceCatalog.fromPlugin(pluginName), BalanceEntry.Confidence.INFERRED,
                "调用栈归因插件：" + pluginName
            ));
        }
        this.attributionByClassLoader = Collections.unmodifiableMap(mapped);
    }

    public Attribution detect() {
        Attribution attribution = WALKER.walk(frames -> frames
            .map(frame -> this.attributionByClassLoader.get(frame.getDeclaringClass().getClassLoader()))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null));
        if (attribution == null) {
            return new Attribution(SourceCatalog.UNKNOWN_EXTERNAL, BalanceEntry.Confidence.UNKNOWN,
                "ExcellentEconomy/控制台/未知 API；请结合 operations.log 核对");
        }
        return attribution;
    }

    public record Attribution(SourceCatalog source, BalanceEntry.Confidence confidence, String detail) {
    }
}
