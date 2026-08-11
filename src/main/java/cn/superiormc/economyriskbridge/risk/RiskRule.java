package cn.superiormc.economyriskbridge.risk;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum RiskRule {

    SELF_TRANSFER("ERB-R001", "基础校验", "付款人与收款人为同一 UUID"),
    SINGLE_LIMIT("ERB-R002", "额度", "单笔金额超过配置上限"),
    SENDER_DAILY_LIMIT("ERB-R003", "额度", "付款方当日累计转出超过上限"),
    RECEIVER_DAILY_LIMIT("ERB-R004", "额度", "收款方当日累计流入超过上限"),
    PAIR_DAILY_LIMIT("ERB-R005", "额度", "双方当日双向往来超过组合上限"),
    CLUSTER_DAILY_LIMIT("ERB-R006", "关联账户", "付款方关联账户组当日累计转出异常"),
    TRANSFER_VELOCITY("ERB-R007", "行为", "短时间内连续发起过多转账"),
    SAME_RECENT_IP("ERB-R008", "关联账户", "付款人与收款人处于同一近期匿名 IP 关系簇（直接或链式）"),
    NEW_ACCOUNT("ERB-R009", "账户画像", "首次进服时间较短的账户参与转账"),
    LOW_PLAYTIME("ERB-R010", "账户画像", "累计在线时长较低的账户参与转账"),
    PROBATION_LARGE_TRANSFER("ERB-R011", "资金来源", "试用期账户出现与成长阶段不相称的大额资金"),
    UNEXPLAINED_FUNDS("ERB-R012", "资金来源", "累计收支超过按在线时长估算的合理产出能力"),
    FAN_IN("ERB-R013", "资金路径", "收款方短期从多个不同账户归集资金"),
    FAN_OUT("ERB-R014", "资金路径", "付款方短期向多个不同账户分散资金"),
    REVERSE_FLOW("ERB-R015", "资金路径", "双方短期内存在反向回流"),
    CIRCULAR_FLOW("ERB-R016", "资金路径", "近期资金图中出现三方或多方环形回流"),
    RAPID_PASS_THROUGH("ERB-R017", "资金路径", "付款方在短期收款后迅速转出相近金额，疑似过桥账户"),
    STRUCTURING("ERB-R018", "拆单", "多笔交易贴近单笔或累计阈值，疑似拆单绕限"),
    BALANCE_DRAIN("ERB-R019", "行为", "单笔转出接近付款方全部余额，疑似小号归集或盗号清空"),
    IP_CLUSTER("ERB-R020", "关联账户", "同一匿名网络标识关联的近期账户数量较多"),
    IP_CHURN("ERB-R021", "关联账户", "账户近期网络标识变动频繁，可能使用代理或账号共享"),
    DORMANT_REACTIVATION("ERB-R022", "账户画像", "长期休眠账户恢复活跃后立即发生大额转账"),
    REPEATED_AMOUNT("ERB-R023", "拆单", "短期重复相同金额，疑似脚本化搬运或拆单"),
    NEAR_LIMIT("ERB-R024", "拆单", "金额或累计金额贴近配置额度边界"),
    ROUND_AMOUNT("ERB-R025", "行为", "大额整数转账，与其他信号组合时提高风险"),
    SHOP_ALT_LIMIT("ERB-R026", "服务器来源", "UltimateShop 关联账户组累计卖出已超过单个玩家配置限额"),
    QUICK_SHOP_RELATED_ACCOUNTS("ERB-R027", "关联账户",
        "QuickShop 交易双方处于同一近期匿名 IP 关系簇，可能绕过玩家转账风控"),
    PARTIAL_SETTLEMENT("ERB-R090", "系统完整性", "仅观测到转账一侧余额变化，可能存在插件冲突或余额封顶"),
    UNKNOWN_TARGET("ERB-E001", "运行校验", "无法从安全缓存识别收款玩家"),
    AUDIT_UNAVAILABLE("ERB-E002", "运行校验", "审计数据库或有界写队列不可用"),
    CURRENCY_MISSING("ERB-E003", "运行校验", "命令匹配规则引用了不存在的 ExcellentEconomy 货币"),
    ESCROW_RECONCILIATION("ERB-E004", "系统完整性", "托管跨插件状态在宕机窗口内不确定，需要人工对账"),
    WRONG_TRANSFER_NODE("ERB-E005", "跨服一致性", "当前后端不是唯一转账节点；已阻止跨服重复使用本地日限额");

    private final String code;
    private final String category;
    private final String operatorReason;

    RiskRule(String code, String category, String operatorReason) {
        this.code = code;
        this.category = category;
        this.operatorReason = operatorReason;
    }

    public String code() {
        return this.code;
    }

    public String category() {
        return this.category;
    }

    public String operatorReason() {
        return this.operatorReason;
    }

    public static Optional<RiskRule> byCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(rule -> rule.code.equals(normalized)).findFirst();
    }
}
