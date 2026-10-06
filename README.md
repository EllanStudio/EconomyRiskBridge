# EconomyRiskBridge

EconomyRiskBridge 是一个独立的 Paper/Spigot 插件，用来给 ExcellentEconomy 的玩家转账、UltimateShop 系统商店产币和 QuickShop-Hikari 玩家商店增加实时风控、匿名关联账户分析、服务器银行托管和可审计的管理员处置流程。它不会修改这些上游插件的源码。

本分支以 Paper API `26.3.build.157-beta`、Java 25 编译；Spigot/Paper 运行时能力仍以实际安装的上游插件 API 为准。

> 这是一套游戏经济反滥用系统，不是现实金融机构的 AML/KYC 合规产品。“洗钱”在玩家提示中表示游戏币的分层、归集、回流与绕限行为。

风控执行边界包括配置匹配到的玩家 `/pay`，以及两个具有明确上游事件的可选桥接入口：UltimateShop 和 QuickShop-Hikari。其他任务、在线奖励、比赛、掉落、管理员奖励等赚钱行为仍只写入来源流水，不会被本插件取消、限额或回滚。来源异常不会直接扣除既有余额；现有资金只能由管理员结合日志人工处置。

## 处理结果

- `PASS`：放行 ExcellentEconomy 原生转账，并通过两条 `ChangeBalanceEvent` 核验收款与扣款是否都发生。
- `REVIEW/BLOCK + SHADOW`：原生转账照常执行，只生成案件，用于校准规则。
- `REVIEW/BLOCK + ENFORCE`：取消原始 `/pay`，先持久化托管意图，再从付款方扣款并记入插件的服务器银行托管账。
- `release`：管理员确认正常后，把托管金额发给原收款方。
- `confiscate`：管理员确认违规后充公，记入服务器银行逻辑余额，不再回到玩家流通。
- `refund`：误判或操作异常时退回付款方。
- `ERB-R026 + SHADOW`：IP 关系账户组合计突破 UltimateShop 单人商品额度后，成功出售照常完成，但立即建立来源案件并告警。
- `ERB-R026 + ENFORCE`：启用 `shop-alt-limit-hard-block` 时，在 UltimateShop 发放价款前取消会造成组额度超限的出售。
- `ERB-R027`：QuickShop 成交双方存在近期直接或链式 IP 关系时建立案件；ENFORCE 且 `same-ip-hard-block` 开启时在成交前取消。

服务器每 24 小时最多向转账玩家显示一次政策提示。具体命中原因不会发给玩家；玩家只看到稳定错误码和案件短编号，避免泄露阈值。在线 OP 与控制台会收到代码对应的中文原因；若当时没有 OP 在线，提醒会持久化并在下一位 OP 登录时送达。OP 也可用 `/erb case show <案件>` 查看完整信息；告警可通过 `notifications` 开关控制。

## IP 关联口径

- IP 使用服务端私钥做 HMAC-SHA256，只保存不可逆标识和最后出现时间，不保存明文地址。
- 两个账号的保留期 IP 集合完全没有交集、也不存在链式路径时，不会被 UltimateShop/QuickShop 关联账户规则合并风控。
- A 与 B 使用过同一 IP，B 又与 C 使用过另一个 IP 时，A/B/C 构成同一个近期 IP 关系簇；关系在 `privacy.ip-link-retention-days` 到期后自动移除。
- IP 关系本身只建立高风险关联；UltimateShop 还必须出现多账号合计突破单人额度，QuickShop 还必须实际尝试或完成双方交易，才会建立案件。
- `privacy.ip-relation-account-cap` 对所有关系图判定设上限，防止大型代理出口、校园网或错误代理转发造成无界扫描。

## 银行式余额流水

ExcellentEconomy 2.8.0 默认已将余额操作写入 `plugins/ExcellentEconomy/operations.log`。原生玩家转账日志包含货币、付款人、金额、收款人和双方新余额；`give/take/set` 也会记录执行者。它适合作为底层故障凭证，但仍是文本日志，没有稳定交易号、风险案件、托管生命周期、分页查询，也无法在 Vault 操作中区分上游插件。

本插件另外把每次实际 `ChangeBalanceEvent` 写成不可变结构化流水，字段包括：玩家、货币、变动前后余额、增减额、来源代码、归因置信度、交易号、案件号和直接对手方。OP 使用：

```text
/erb statement <玩家> [页] [货币]
/erb sources [来源代码]
```

置信度分三种：

- `VERIFIED`：桥接插件直接关联到受保护的玩家转账或服务器银行托管操作。
- `INFERRED`：余额事件发生时，根据实际调用栈映射回插件类加载器；可识别 Vault 上游插件，但仍应结合该插件日志核对争议案件。
- `UNKNOWN`：控制台、RCON、异步转发或没有保留上游调用栈的 API 变更；明确显示未知，不猜测来源。

流水在插件安装后开始采集，保留期使用 `storage.retention-days`。它记录的是直接入账渠道；游戏币是可混同余额，不能声称某一枚币的“最初来源”。资金链追踪使用交易号和玩家间转账图回溯。

## 实服资金来源目录

以下目录来自对当前三个运行实例的只读配置盘点。主币种为 `ellan_coin`，EE 文件日志已开启；实际余额操作绝大多数经 Vault 或控制台进入，因此采用上面的结构化归因层。

| 来源代码 | 当前服务器入口 | 性质 |
|---|---|---|
| `ERB-S001` | ExcellentEconomy `/pay` | 玩家到玩家，直接对手方已验证 |
| `ERB-S002` | UltimateShop 售卖/购买 | 系统商店产币与消费；限额商品同时进入 IP 关系组额度审计 |
| `ERB-S003` | CustomFishing 水产市场、比赛奖励 | 市场每日收入配置上限 5,000，比赛另有名次/参与奖励 |
| `ERB-S004` | BetonQuest 日常、周常、社区委托及声望商店 | 明确的任务奖励与消费命令 |
| `ERB-S005` | EllanTimedOrders 限时订单 | 配置的 `ellan_coin` 订单奖励 |
| `ERB-S006` | CMI 在线时长奖励、管理员经济命令 | 合法奖励与人工发币共用 CMI，争议时查 EE 日志和命令审计 |
| `ERB-S007` | EnderDragon 击杀、伤害排名奖励 | 控制台发放的龙战奖励 |
| `ERB-S008` | CustomCrops/CraftEngine 红包及配置动作 | 当前可见红包动作；未来同插件动作仍归本类 |
| `ERB-S009` | zMenu 收购商店、出售结算、道具/家具购买 | 同时包含产币和消费，以增减方向区分 |
| `ERB-S010` | QuickShop-Hikari | 余额流水按插件归因；IP 关系双方的购买成功事件另以 `ERB-R027` 建案 |
| `ERB-S011` | RealShops | 玩家商店资金转移 |
| `ERB-S012` | ezAuctions | 拍卖竞价、结算、手续费与退款 |
| `ERB-S013` | NotBounties | 悬赏发布、领取、回购与退款，主要是玩家资金再分配 |
| `ERB-S014` | Duels 金币押注 | 玩家间押注结算 |
| `ERB-S015` | Vegas | 筹码购买、出售和赌场结算 |
| `ERB-S016` | Blackjack | 下注、获胜、平局退款 |
| `ERB-S017` | LiarsBarPaper | 入场费及中止/离场退款 |
| `ERB-S018` | Residence | 领地创建、扩展、租售和银行资金 |
| `ERB-S019` | HuskHomes | 传送/设置家等经济费用 |
| `ERB-S020` | UltimateTimber 配置奖励 | 当前示例奖励概率为 0，启用后会自动归因 |
| `ERB-S021` | InvSync/HuskSync 类跨服同步 | 余额重放应人工确认，避免把同步误当新产出 |
| `ERB-S090`–`S093` | 服务器银行托管、放行、退款、对账 | 插件内部操作，交易号与案件号已验证 |
| `ERB-S098` | 未预先列入目录的其他插件 | 会保留实际插件名，便于以后增加固定代码 |
| `ERB-S099` | 控制台或无法识别的外部操作 | 必须结合 EE `operations.log` 核对 |

当前 UltimateShop 有 53 个可出售商品，按每个商品的玩家上限和最高动态售价计算，理论上全部打满约为每日 99,169.2；CustomFishing 市场另有每日 5,000 上限。默认“基础额度 10,000 + 每在线小时 5,000”的资金能力线因此仍是保守风控线，不是合法收入的硬上限：明确来源会留在流水中，命中时由管理员结合来源放行。

## 风控代码目录

| 错误代码 | OP 可见原因 | 服务器场景 |
|---|---|---|
| `ERB-R001` | 自转账 | 无效或探测命令 |
| `ERB-R002` | 单笔超过上限 | 直接大额搬运 |
| `ERB-R003` | 付款方每日转出超限 | 单号超额转出 |
| `ERB-R004` | 收款方每日流入超限 | 主号集中接收小号资金 |
| `ERB-R005` | 双方每日往来超限 | 两号反复互转 |
| `ERB-R006` | 关联账户组每日转出异常 | 多小号共享家庭/IP/人工确认集群 |
| `ERB-R007` | 短时转账频率过高 | 宏、脚本或快速拆单 |
| `ERB-R008` | 双方属于同一近期匿名 IP 关系簇 | 支持直接同 IP 与链式关系；也可能是家庭、宿舍或网吧，因此默认只加分 |
| `ERB-R009` | 新账户参与 | 刚进服的小号或新玩家 |
| `ERB-R010` | 累计在线时长低 | 账户成长不足，资金能力可疑 |
| `ERB-R011` | 试用期大额转账 | 首次进服或累计在线不足 24 小时却出现大额资金 |
| `ERB-R012` | 资金与在线产出能力不相称 | 超过“基础额度 + 在线小时 × 合理产出” |
| `ERB-R013` | 多对一归集 | 多个小号把收益集中到主号 |
| `ERB-R014` | 一对多分散 | 将资金拆向多个中转号 |
| `ERB-R015` | 短期反向回流 | A→B 后 B→A |
| `ERB-R016` | 环形资金流 | A→B→C→A 等分层回流 |
| `ERB-R017` | 快速过桥 | 收到钱后很快转出相近金额，余额停留时间很短 |
| `ERB-R018` | 拆单绕限 | 连续多笔贴近单笔或累计边界 |
| `ERB-R019` | 接近清空余额 | 小号将几乎全部余额归集，或盗号清空 |
| `ERB-R020` | 匿名网络账户簇过大 | 一个近期 IP 关联多个账号 |
| `ERB-R021` | 匿名网络标识频繁变化 | 代理/VPN、共享账号；默认与其他信号组合 |
| `ERB-R022` | 休眠号突活 | 长期不上线的账户突然大额转账 |
| `ERB-R023` | 短期重复相同金额 | 自动化搬运或等额拆单 |
| `ERB-R024` | 贴近限额边界 | 金额刻意卡在阈值以下 |
| `ERB-R025` | 大额整数 | 单独证据很弱，只用于组合评分 |
| `ERB-R026` | UltimateShop 关联组绕限 | 两个以上关联账号合计卖出次数超过该商品单玩家限额 |
| `ERB-R027` | QuickShop 关联账户交易 | 玩家商店交易双方存在近期直接或链式匿名 IP 关系 |
| `ERB-R090` | 单边成交 | EE 只出现收款或扣款一侧，可能是余额封顶、插件冲突或异常取消 |
| `ERB-E001` | 无法识别收款玩家 | 收款人从未被安全缓存记录 |
| `ERB-E002` | 审计不可用 | SQLite 或有界队列异常；ENFORCE 默认 fail-closed |
| `ERB-E003` | 配置货币不存在 | 转账命令规则引用错误货币 ID |
| `ERB-E004` | 需人工对账 | 宕机发生在跨插件余额操作与托管状态落盘之间 |
| `ERB-E005` | 非唯一转账节点 | 共享余额的多后端若各自计日限额，玩家可切服重复使用额度；ENFORCE 已拒绝非指定节点 |

现实风控的对应思路来自公开的一手资料：FinCEN 对拆分交易绕阈值（structuring）的说明、对多地归集后快速转出的 funnel account 描述，以及 FATF 对过桥账户、快速分拆、贴线金额、大额整数和环形资金流等风险指标的归纳。插件只借鉴行为模式；没有把现实金额阈值、法律结论或自动定罪逻辑搬进游戏。[FinCEN structuring](https://www.fincen.gov/resources/statutes-regulations/administrative-rulings/suspicious-activity-reporting-structuring) · [FinCEN funnel accounts](https://www.fincen.gov/resources/advisories/fincen-advisory-fin-2014-a005) · [FATF risk indicators](https://www.fatf-gafi.org/en/publications/Methodsandtrends/Trade-based-money-laundering-indicators.html)

## 性能设计

- 转账前热路径只访问内存：定点数计数器、近期资金流和匿名身份图，不查询数据库。
- 命令先按根命令做 O(1) 索引；与转账无关的玩家命令不会进入正则匹配或金额解析。
- “已成交 + 待核验预留”共同占用额度，避免同一 tick 或 EE 异步查人的窗口被并发 `/pay` 穿透。
- 待核验转账按参与者建立反向索引，单次风控只检查收付款双方相关记录，不扫描全服队列。
- SQLite 使用 WAL、单写线程、批量事务和有界优先队列；普通审计不阻塞主线程。
- 托管是低频高风险路径。只有在外部余额刚变化或即将放款时，才执行带条件的持久化状态转换。
- IP 使用服务端私钥做 HMAC-SHA256，只保存不可逆标识，不保存明文地址。密钥首次启动自动生成。
- UltimateShop 额度内存账按“玩家 UUID + 日期 + 商品”保存原始计数，判定时再解析当前 IP 关系图；账号后来换 IP 或形成链式关系时不会受旧 `cluster_key` 快照限制。
- 在线画像刷新被均摊到多个 tick；真正转账前仍会刷新收付款双方，避免用陈旧在线时长判断。
- 行为窗口只保留近期边，环路搜索有扫描上限；资金流、旧日计数、通知缓存和过期身份资料均做有界增量清理。

## 托管状态机与宕机安全

```text
PREPARED -> HELD -> RESOLVING_RELEASE -> RELEASED
                  -> RESOLVING_REFUND  -> REFUNDED
                  -> CONFISCATED
PREPARED -> FAILED
PREPARED/RESOLVING_* --重启或结果不一致--> NEEDS_RECONCILIATION
```

ExcellentEconomy 与本插件使用不同存储，无法做真正的跨数据库原子事务。插件采用“先写意图、条件状态转换、余额差额核验、禁止自动重试”的保守策略。`NEEDS_RECONCILIATION` 必须先对照 ExcellentEconomy 的 `operations.log`：

```text
/erb escrow reconcile <案件> held
/erb escrow reconcile <案件> released
/erb escrow reconcile <案件> refunded
/erb escrow reconcile <案件> failed
```

不要在未查日志与余额前核销；错误选择可能造成重复发钱或资金损失。

## 安装与配置

1. 服务器需安装 ExcellentEconomy 2.8.0 与其依赖 NightCore。ExcellentEconomy 2.8.0 所在服务端需使用其要求的 Java 版本。
2. 将 `EconomyRiskBridge-1.0.0.jar` 放入 `plugins/`。UltimateShop 与 QuickShop-Hikari 是可选软依赖；存在时分别启用来源侧额度和箱子商店关联交易桥接。
3. 首次启动保持 `mode: SHADOW`，确认 `interception.patterns` 覆盖 EE 实际启用的所有转账别名和每种货币。
4. 当前网络需在 survival、redstone、spawn 都安装，用 `/erb status` 核对 `current-node`；只把 `network.transfer-node` 指向一个后端。默认是 `spawn`。SHADOW 不限制节点，ENFORCE 时其他节点返回 `ERB-E005`。
5. 三个节点必须配置相同的 `privacy.ip-hmac-secret`，否则同一 IP 会生成不同匿名标识。当前构建的 SQLite 与内存计数仍是节点本地数据；唯一转账节点负责全网转账限额，不能在其他节点留下未拦截的转账别名。
6. 观察至少 3–7 天案件分布，按本服商店、任务、掉落与在线产出调整额度和 `earned-capacity-per-play-hour`。
7. 验证托管、放行、退款和备份恢复后，再切换到 `mode: ENFORCE`。

默认示例只包含：

```yaml
interception:
  patterns:
    - command: "pay {target} {amount}"
      currency: ellan_coin
    - command: "ellan_coin pay {target} {amount}"
      currency: ellan_coin
```

如果 EE 改过命令别名、货币根命令或另一个插件提供转账入口，必须逐条补齐。未配置的命令不会被拦截。

代理服必须先正确配置 Velocity/Bungee 的玩家地址转发，否则后端看到的可能全是代理地址，进而把全服玩家错误连接成一个 IP 关系簇。家庭、学校、宿舍、网吧和运营商 NAT 也会共享地址；建议先以 SHADOW 校准案件，再决定是否启用来源或 QuickShop 的硬拦截。

## 管理命令

```text
/erb status
/erb inspect <玩家> [货币]
/erb statement <玩家> [页] [货币]
/erb cases [页]
/erb case show <案件>
/erb case ack <案件> [备注]
/erb rules [错误代码]
/erb sources [来源代码]
/erb escrow list [页]
/erb escrow release <案件>
/erb escrow refund <案件>
/erb escrow confiscate <案件> [查证说明]
/erb escrow reconcile <案件> held|released|refunded|failed [对账说明]
/erb bank
/erb trust add|remove <玩家A> <玩家B>
/erb link add <玩家> <确认关联组>
/erb link remove <玩家>
/erb reload
```

权限：`economyriskbridge.admin`、`economyriskbridge.bypass`。

## 能力边界

- ExcellentEconomy 2.8.0 没有专用 `PayEvent`。插件只能拦截配置的玩家命令，再用余额事件核验；其他插件直接调用 EE `deposit/withdraw` 的资金路径不会自动被识别为玩家转账。
- 外部余额操作会进入结构化流水，但调用栈归因属于 `INFERRED`：异步任务、控制台/RCON 或中间件清除调用上下文时只能标记为 `UNKNOWN`，应与 EE `operations.log` 和来源插件日志交叉核对。
- UltimateShop 只有配置了正数玩家出售/使用次数上限的商品才能计算单人额度；无限额商品仍只进入普通余额来源流水。
- UltimateShop 自带出售箱通过相同的预交易/完成事件进入检查。若上游版本没有 `ItemPreTransactionEvent`，`/erb status` 会显示预交易拦截 API 不可用，但完成事件审计仍继续。
- QuickShop 桥接直接使用购买者、店主、商店方向和总价建案；完全没有 IP 关系的玩家商店交易不会触发 `ERB-R027`。`/erb trust add` 可豁免已确认的正常双方。
- 除 UltimateShop、QuickShop 和配置匹配的 `/pay` 外，其他赚钱与消费来源监听器仍运行在只读观察路径。
- Minecraft 客户端不提供可靠设备指纹。本插件不会伪造硬件识别，也不建议收集侵入性设备数据。
- 管理员赠款、活动奖励、拍卖、玩家商店、任务和跨服同步都可能形成合法大额来源。上线 ENFORCE 前，应为这些来源增加明确的桥接入口、豁免权限或人工放行流程。
- 当前版没有 Redis/MySQL 分布式预留。共享 EE 余额的三个后端必须使用“唯一转账节点”；UltimateShop 组额度也仍是每个 JVM 本地计数，玩家跨服出售时必须查询各节点日志或把系统商店限制在单一节点。银行式流水同样按节点保存。
- `privacy.ip-hmac-secret` 一旦更换，历史匿名 IP 无法与新记录关联。应与数据库一起安全备份。

## 构建

该仓库是可独立构建的 Gradle 项目：

```text
./gradlew test shadowJar
```

Windows 可使用 `gradlew.bat test shadowJar`。产物位于 `build/libs/EconomyRiskBridge-1.0.0.jar`。
UltimateShop/QuickShop 的可选 API 通过已重定位进产物的 [Sparrow Reflection 0.27](https://github.com/Xiao-MoMi/sparrow-reflection) 绑定，服端无需另装该库。

## 许可证与对应源码

EconomyRiskBridge 以 [GNU GPLv3](LICENSE)（`GPL-3.0-only`）发布，且不提供任何担保。
构建产物会包含完整许可证和 [第三方声明](THIRD_PARTY_NOTICES.md)。由于 Sparrow Reflection 0.27
被重定位并合入同一个 JAR，分发 JAR 时必须同时让接收者免费取得该二进制所对应的准确项目源码、
构建脚本及依赖源码；GitHub Release 应使用同一提交的标签，并在下载处明确链接该标签的源码归档。
无法对应到公开源码修订的 JAR 不应再分发。
