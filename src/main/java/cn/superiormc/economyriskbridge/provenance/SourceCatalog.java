package cn.superiormc.economyriskbridge.provenance;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum SourceCatalog {

    PLAYER_TRANSFER("ERB-S001", "玩家转账"),
    ULTIMATE_SHOP("ERB-S002", "UltimateShop 系统商店"),
    CUSTOM_FISHING("ERB-S003", "CustomFishing 水产市场/比赛"),
    BETON_QUEST("ERB-S004", "BetonQuest 委托/任务"),
    TIMED_ORDERS("ERB-S005", "EllanTimedOrders 限时订单"),
    CMI("ERB-S006", "CMI 在线奖励/管理经济操作"),
    ENDER_DRAGON("ERB-S007", "EnderDragon 击杀与排名奖励"),
    CROP_ACTION("ERB-S008", "CustomCrops/CraftEngine 配置动作"),
    ZMENU("ERB-S009", "zMenu 菜单商店/兑换"),
    QUICK_SHOP("ERB-S010", "QuickShop 玩家商店"),
    REAL_SHOPS("ERB-S011", "RealShops 玩家商店"),
    AUCTION("ERB-S012", "ezAuctions 玩家拍卖"),
    BOUNTY("ERB-S013", "NotBounties 悬赏"),
    DUELS("ERB-S014", "Duels 决斗押注"),
    VEGAS("ERB-S015", "Vegas 筹码/赌场结算"),
    BLACKJACK("ERB-S016", "Blackjack 牌局结算"),
    LIARS_BAR("ERB-S017", "LiarsBar 入场费/退款"),
    RESIDENCE("ERB-S018", "Residence 领地交易/费用"),
    HUSK_HOMES("ERB-S019", "HuskHomes 传送费用"),
    ULTIMATE_TIMBER("ERB-S020", "UltimateTimber 配置奖励"),
    CROSS_SERVER_SYNC("ERB-S021", "跨服同步或共享存储"),
    SERVER_BANK_HOLD("ERB-S090", "服务器银行托管扣款"),
    ESCROW_RELEASE("ERB-S091", "服务器银行托管放行"),
    ESCROW_REFUND("ERB-S092", "服务器银行托管退款"),
    RECONCILIATION("ERB-S093", "人工对账中的余额变化"),
    OTHER_PLUGIN("ERB-S098", "其他插件余额操作"),
    UNKNOWN_EXTERNAL("ERB-S099", "未归因外部余额操作");

    private final String code;
    private final String displayName;

    SourceCatalog(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return this.code;
    }

    public String displayName() {
        return this.displayName;
    }

    public static Optional<SourceCatalog> byCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(source -> source.code.equals(normalized)).findFirst();
    }

    public static SourceCatalog fromPlugin(String pluginName) {
        String normalized = pluginName.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (normalized.contains("ultimateshop")) return ULTIMATE_SHOP;
        if (normalized.contains("customfishing")) return CUSTOM_FISHING;
        if (normalized.contains("betonquest")) return BETON_QUEST;
        if (normalized.contains("ellantimedorders")) return TIMED_ORDERS;
        if (normalized.equals("cmi")) return CMI;
        if (normalized.contains("enderdragon")) return ENDER_DRAGON;
        if (normalized.contains("customcrops") || normalized.contains("craftengine")) return CROP_ACTION;
        if (normalized.contains("zmenu")) return ZMENU;
        if (normalized.contains("quickshop")) return QUICK_SHOP;
        if (normalized.contains("realshops")) return REAL_SHOPS;
        if (normalized.contains("ezauctions")) return AUCTION;
        if (normalized.contains("notbounties")) return BOUNTY;
        if (normalized.contains("duels")) return DUELS;
        if (normalized.equals("vegas")) return VEGAS;
        if (normalized.contains("blackjack")) return BLACKJACK;
        if (normalized.contains("liarsbar")) return LIARS_BAR;
        if (normalized.contains("residence")) return RESIDENCE;
        if (normalized.contains("huskhomes")) return HUSK_HOMES;
        if (normalized.contains("ultimatetimber")) return ULTIMATE_TIMBER;
        if (normalized.contains("invsync") || normalized.contains("husksync")) return CROSS_SERVER_SYNC;
        return OTHER_PLUGIN;
    }
}
