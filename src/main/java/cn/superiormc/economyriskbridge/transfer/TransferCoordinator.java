package cn.superiormc.economyriskbridge.transfer;

import cn.superiormc.economyriskbridge.EconomyRiskBridgePlugin;
import cn.superiormc.economyriskbridge.casebook.CaseBook;
import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.escrow.EscrowBook;
import cn.superiormc.economyriskbridge.identity.IdentityService;
import cn.superiormc.economyriskbridge.integration.UltimateShopSourceMonitor;
import cn.superiormc.economyriskbridge.model.BalanceEntry;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.model.CounterSnapshot;
import cn.superiormc.economyriskbridge.model.EscrowRecord;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.PendingTransfer;
import cn.superiormc.economyriskbridge.model.RiskDecision;
import cn.superiormc.economyriskbridge.model.TransferAttempt;
import cn.superiormc.economyriskbridge.model.TransferState;
import cn.superiormc.economyriskbridge.persistence.AuditStore;
import cn.superiormc.economyriskbridge.provenance.SourceCatalog;
import cn.superiormc.economyriskbridge.provenance.SourceDetector;
import cn.superiormc.economyriskbridge.risk.RiskEngine;
import cn.superiormc.economyriskbridge.risk.RiskLedger;
import cn.superiormc.economyriskbridge.risk.RiskRule;
import cn.superiormc.economyriskbridge.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class TransferCoordinator {

    private final EconomyRiskBridgePlugin plugin;
    private final ExcellentEconomyAPI economy;
    private final IdentityService identities;
    private final AuditStore store;
    private final RiskLedger ledger;
    private final RiskEngine engine;
    private final CaseBook caseBook;
    private final EscrowBook escrowBook;
    private final UltimateShopSourceMonitor shopSourceMonitor;
    private final SourceDetector sourceDetector;
    private final ConcurrentHashMap<UUID, PendingTransfer> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LegKey, ConcurrentLinkedDeque<UUID>> legIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InternalKey, AtomicInteger> internalMutations = new ConcurrentHashMap<>();
    private final java.util.Set<PayoutKey> payoutsInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastJournalWarning = new AtomicLong();
    private volatile RiskConfig config;

    public TransferCoordinator(EconomyRiskBridgePlugin plugin, ExcellentEconomyAPI economy,
                               IdentityService identities, AuditStore store, RiskLedger ledger,
                               CaseBook caseBook, EscrowBook escrowBook,
                               UltimateShopSourceMonitor shopSourceMonitor, SourceDetector sourceDetector,
                               RiskConfig config) {
        this.plugin = plugin;
        this.economy = economy;
        this.identities = identities;
        this.store = store;
        this.ledger = ledger;
        this.engine = new RiskEngine();
        this.caseBook = caseBook;
        this.escrowBook = escrowBook;
        this.shopSourceMonitor = shopSourceMonitor;
        this.sourceDetector = sourceDetector;
        this.config = config;
    }

    public void updateConfig(RiskConfig config) {
        this.config = config;
    }

    public Optional<UUID> resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            this.identities.rememberName(online.getUniqueId(), online.getName(), System.currentTimeMillis());
            return Optional.of(online.getUniqueId());
        }
        Optional<UUID> known = this.identities.resolveName(name);
        if (known.isPresent()) {
            return known;
        }
        return this.economy.getCachedUserData(name).map(user -> {
            UUID id = user.getId();
            this.identities.rememberName(id, user.getName(), System.currentTimeMillis());
            return id;
        });
    }

    public Optional<ExcellentCurrency> currency(String currencyId) {
        return this.economy.currencyById(currencyId);
    }

    public PrecheckResult precheck(Player sender, UUID receiverId, String receiverName,
                                   ExcellentCurrency currency, long rawAmountMicros) {
        RiskConfig current = this.config;
        if (!this.store.isHealthy() && current.storage().failClosed()
            && current.mode() == RiskConfig.Mode.ENFORCE) {
            return PrecheckResult.error(current.messages().auditUnavailable());
        }

        long amount;
        try {
            amount = Money.fromDouble(currency.floorIfNeeded(Money.toDouble(rawAmountMicros)));
        } catch (IllegalArgumentException exception) {
            return PrecheckResult.ignore();
        }
        if (amount <= 0L || (currency.getMinTransferAmount() > 0D
            && Money.toDouble(amount) < currency.getMinTransferAmount())) {
            return PrecheckResult.ignore();
        }

        double balance = this.economy.getBalance(sender, currency);
        if (balance + 0.0000001D < Money.toDouble(amount)) {
            return PrecheckResult.ignore();
        }

        long now = System.currentTimeMillis();
        PartyProfile senderProfile = this.identities.profile(sender.getUniqueId(), sender.getName(), now);
        PartyProfile receiverProfile = this.identities.profile(receiverId, receiverName, now);
        TransferAttempt attempt = new TransferAttempt(UUID.randomUUID(), senderProfile, receiverProfile,
            currency.getId().toLowerCase(Locale.ROOT), amount, Money.fromDouble(balance), now,
            this.identities.shareRecentIp(sender.getUniqueId(), receiverId, now),
            this.identities.isTrusted(sender.getUniqueId(), receiverId),
            this.shopSourceMonitor != null && (this.shopSourceMonitor.hasViolation(senderProfile.clusterKey(), now)
                || this.shopSourceMonitor.hasViolation(receiverProfile.clusterKey(), now)));

        CounterSnapshot snapshot = this.ledger.snapshot(attempt, current.limits().velocityWindowSeconds(),
            current.profiling().behaviorWindowSeconds());
        RiskDecision decision = this.engine.evaluate(attempt, snapshot, current);
        boolean shadow = current.mode() == RiskConfig.Mode.SHADOW;

        if (!shadow && decision.reasons().stream()
            .anyMatch(reason -> reason.rule() == RiskRule.SELF_TRANSFER)) {
            CaseRecord caseRecord = createCase(attempt, decision, "无效自转账已拒绝，未发生扣款");
            this.caseBook.add(caseRecord);
            boolean audited = this.store.enqueueTransfer(attempt, decision, TransferState.CANCELLED)
                && this.store.enqueueCase(caseRecord);
            if (!audited && current.storage().failClosed()) {
                return PrecheckResult.error(current.messages().auditUnavailable());
            }
            this.plugin.notifyOperators(caseRecord);
            return PrecheckResult.error(current.messages().blocked()
                .replace("{case}", shortId(caseRecord.caseId()))
                .replace("{code}", RiskRule.SELF_TRANSFER.code()));
        }

        if (!shadow && decision.action() != RiskDecision.Action.PASS) {
            this.ledger.reserve(attempt);
            CaseRecord caseRecord = createCase(attempt, decision, "自动进入服务器银行托管");
            CompletableFuture<Boolean> prepared = this.store.prepareEscrow(attempt, decision, caseRecord, balance);
            if (prepared.isDone() && !prepared.join()) {
                this.ledger.release(attempt.transactionId());
                return PrecheckResult.error(current.messages().auditUnavailable());
            }
            beginEscrowAfterPrepare(sender.getUniqueId(), currency, attempt, decision, caseRecord, balance, prepared);
            return PrecheckResult.escrow(caseRecord.caseId(), decision,
                current.messages().escrowProcessing()
                    .replace("{case}", shortId(caseRecord.caseId()))
                    .replace("{code}", decision.primaryCode()));
        }

        PendingTransfer pendingTransfer = new PendingTransfer(attempt, decision,
            now + current.pendingTimeoutSeconds() * 1_000L);
        this.ledger.reserve(attempt);
        registerPending(pendingTransfer);
        TransferState initialState = shadow ? TransferState.SHADOW_PENDING : TransferState.PENDING;
        if (!this.store.enqueueTransfer(attempt, decision, initialState)) {
            removePending(pendingTransfer, false);
            if (!shadow && current.storage().failClosed()) {
                return PrecheckResult.error(current.messages().auditUnavailable());
            }
        }

        if (decision.action() != RiskDecision.Action.PASS) {
            CaseRecord caseRecord = createCase(attempt, decision, "SHADOW 模式：仅监控，未托管");
            this.caseBook.add(caseRecord);
            this.store.enqueueCase(caseRecord);
            this.plugin.notifyOperators(caseRecord);
        }
        return PrecheckResult.allow(attempt.transactionId());
    }

    public void cancelPending(UUID transactionId, String note) {
        PendingTransfer transfer = this.pending.get(transactionId);
        if (transfer == null) {
            return;
        }
        removePending(transfer, false);
        this.store.enqueueTransferState(transactionId, TransferState.CANCELLED, System.currentTimeMillis());
    }

    public void onBalanceChange(UUID playerId, String playerName, String currencyId,
                                long balanceBeforeMicros, long balanceAfterMicros, long nowMillis) {
        long deltaMicros;
        try {
            deltaMicros = Math.subtractExact(balanceAfterMicros, balanceBeforeMicros);
        } catch (ArithmeticException exception) {
            return;
        }
        if (deltaMicros == 0L) {
            return;
        }
        InternalKey internalKey = new InternalKey(playerId, currencyId.toLowerCase(Locale.ROOT), deltaMicros);
        AtomicInteger suppression = this.internalMutations.get(internalKey);
        if (suppression != null && suppression.getAndDecrement() > 0) {
            if (suppression.get() <= 0) {
                this.internalMutations.remove(internalKey, suppression);
            }
            return;
        }

        LegKey key = new LegKey(playerId, currencyId.toLowerCase(Locale.ROOT), deltaMicros);
        ConcurrentLinkedDeque<UUID> candidates = this.legIndex.get(key);
        if (candidates == null) {
            recordExternalMovement(playerId, playerName, currencyId, balanceBeforeMicros,
                balanceAfterMicros, nowMillis);
            return;
        }
        while (true) {
            UUID id = candidates.pollFirst();
            if (id == null) {
                this.legIndex.remove(key, candidates);
                recordExternalMovement(playerId, playerName, currencyId, balanceBeforeMicros,
                    balanceAfterMicros, nowMillis);
                return;
            }
            PendingTransfer transfer = this.pending.get(id);
            if (transfer == null) {
                continue;
            }
            if (transfer.expiresAtMillis() < nowMillis) {
                expire(transfer, nowMillis);
                continue;
            }
            PendingTransfer.Leg leg = deltaMicros > 0L ? PendingTransfer.Leg.CREDIT : PendingTransfer.Leg.DEBIT;
            PendingTransfer.LegResult result = transfer.markLeg(leg);
            if (leg == PendingTransfer.Leg.CREDIT) {
                prioritizeExpectedDebit(transfer);
            }
            recordPlayerTransferMovement(transfer, leg, playerId, playerName, currencyId,
                balanceBeforeMicros, balanceAfterMicros, nowMillis);
            if (result == PendingTransfer.LegResult.COMPLETE) {
                removePending(transfer, true);
                this.store.enqueueTransferState(id, TransferState.COMMITTED, nowMillis);
            }
            return;
        }
    }

    public void expirePending(long nowMillis) {
        for (PendingTransfer transfer : this.pending.values()) {
            if (transfer.expiresAtMillis() < nowMillis) {
                expire(transfer, nowMillis);
            }
        }
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public void releaseEscrow(String casePrefix, CommandSender actor) {
        resolveWithPayout(casePrefix, actor, false);
    }

    public void refundEscrow(String casePrefix, CommandSender actor) {
        resolveWithPayout(casePrefix, actor, true);
    }

    public void confiscateEscrow(String casePrefix, CommandSender actor, String note) {
        Optional<EscrowRecord> found = this.escrowBook.findByPrefix(casePrefix);
        if (found.isEmpty()) {
            this.plugin.tell(actor, "&c案件编号不存在或前缀不唯一。");
            return;
        }
        EscrowRecord current = found.get();
        if (current.status() != EscrowRecord.Status.HELD) {
            this.plugin.tell(actor, "&c仅能处置 HELD 状态的托管，当前为 " + current.status() + "。");
            return;
        }
        CaseRecord caseRecord = this.caseBook.get(current.caseId()).orElse(null);
        if (caseRecord == null) {
            this.plugin.tell(actor, "&c案件元数据缺失，拒绝处置。");
            return;
        }
        long now = System.currentTimeMillis();
        CaseRecord closed = caseRecord.withStatus(CaseRecord.Status.CLOSED, actor.getName(), note);
        EscrowRecord updated = current.transition(EscrowRecord.Status.CONFISCATED, 0L, now,
            actor.getName(), note);
        if (!this.store.transitionEscrowDurable(current.caseId(), EscrowRecord.Status.HELD, updated,
            TransferState.ESCROW_CONFISCATED, closed)) {
            this.plugin.tell(actor, "&c数据库未确认处置，资金仍保持托管。");
            return;
        }
        this.escrowBook.transition(current.caseId(), EscrowRecord.Status.HELD,
            EscrowRecord.Status.CONFISCATED, 0L, now, actor.getName(), note);
        this.caseBook.update(current.caseId(), CaseRecord.Status.CLOSED, actor.getName(), note);
        this.escrowBook.addConfiscated(caseRecord.currencyId(), caseRecord.amountMicros());
        this.plugin.tell(actor, "&a已将案件 " + shortId(current.caseId()) + " 的托管金额充公。");
    }

    public void reconcileEscrow(String casePrefix, String outcome, CommandSender actor, String note) {
        Optional<EscrowRecord> found = this.escrowBook.findByPrefix(casePrefix);
        if (found.isEmpty()) {
            this.plugin.tell(actor, "&c案件编号不存在或前缀不唯一。");
            return;
        }
        EscrowRecord current = found.get();
        if (current.status() != EscrowRecord.Status.NEEDS_RECONCILIATION) {
            this.plugin.tell(actor, "&c仅能核销 NEEDS_RECONCILIATION 状态，当前为 " + current.status() + "。");
            return;
        }
        CaseRecord caseRecord = this.caseBook.get(current.caseId()).orElse(null);
        if (caseRecord == null) {
            this.plugin.tell(actor, "&c案件元数据缺失。");
            return;
        }

        EscrowRecord.Status next;
        TransferState transferState;
        CaseRecord.Status caseStatus;
        switch (outcome.toLowerCase(Locale.ROOT)) {
            case "held" -> {
                next = EscrowRecord.Status.HELD;
                transferState = TransferState.ESCROW_HELD;
                caseStatus = CaseRecord.Status.OPEN;
            }
            case "released" -> {
                next = EscrowRecord.Status.RELEASED;
                transferState = TransferState.ESCROW_RELEASED;
                caseStatus = CaseRecord.Status.CLOSED;
            }
            case "failed", "not-debited" -> {
                next = EscrowRecord.Status.FAILED;
                transferState = TransferState.ESCROW_FAILED;
                caseStatus = CaseRecord.Status.CLOSED;
            }
            case "refunded" -> {
                next = EscrowRecord.Status.REFUNDED;
                transferState = TransferState.ESCROW_REFUNDED;
                caseStatus = CaseRecord.Status.CLOSED;
            }
            default -> {
                this.plugin.tell(actor, "&c核销结果只能是 held、released、refunded 或 failed。");
                return;
            }
        }

        long now = System.currentTimeMillis();
        String fullNote = "人工核销为 " + next + "：" + note;
        CaseRecord updatedCase = caseRecord.withStatus(caseStatus, actor.getName(), fullNote);
        EscrowRecord updated = current.transition(next, next == EscrowRecord.Status.HELD ? now : 0L,
            next == EscrowRecord.Status.HELD ? 0L : now, actor.getName(), fullNote);
        if (!this.store.transitionEscrowDurable(current.caseId(), EscrowRecord.Status.NEEDS_RECONCILIATION,
            updated, transferState, updatedCase)) {
            this.plugin.tell(actor, "&c数据库未确认人工核销。");
            return;
        }
        this.escrowBook.transition(current.caseId(), EscrowRecord.Status.NEEDS_RECONCILIATION, next,
            next == EscrowRecord.Status.HELD ? now : 0L, next == EscrowRecord.Status.HELD ? 0L : now,
            actor.getName(), fullNote);
        this.caseBook.update(current.caseId(), caseStatus, actor.getName(), fullNote);
        if (next == EscrowRecord.Status.FAILED || next == EscrowRecord.Status.REFUNDED) {
            this.ledger.reverseCommitted(current.transactionId());
        }
        this.plugin.tell(actor, "&a案件 " + shortId(current.caseId()) + " 已人工核销为 " + next + "。");
    }

    public void markInterruptedEscrows() {
        for (EscrowRecord escrow : this.escrowBook.recentOpen(0, 2_000)) {
            if (escrow.status() != EscrowRecord.Status.PREPARED
                && escrow.status() != EscrowRecord.Status.RESOLVING_RELEASE
                && escrow.status() != EscrowRecord.Status.RESOLVING_REFUND) {
                continue;
            }
            CaseRecord caseRecord = this.caseBook.get(escrow.caseId()).orElse(null);
            String note = "服务器重启中断于 " + escrow.status() + "，需对照 ExcellentEconomy operations.log 人工核对";
            CaseRecord updatedCase = caseRecord == null ? null
                : caseRecord.withStatus(CaseRecord.Status.OPEN, "SYSTEM", note);
            EscrowRecord updated = escrow.transition(EscrowRecord.Status.NEEDS_RECONCILIATION, 0L, 0L,
                "SYSTEM", note);
            TransferState state = TransferState.ESCROW_NEEDS_RECONCILIATION;
            if (this.store.transitionEscrowDurable(escrow.caseId(), escrow.status(), updated, state, updatedCase)) {
                this.escrowBook.transition(escrow.caseId(), escrow.status(), EscrowRecord.Status.NEEDS_RECONCILIATION,
                    0L, 0L, "SYSTEM", note);
                if (updatedCase != null) {
                    this.caseBook.update(escrow.caseId(), CaseRecord.Status.OPEN, "SYSTEM", note);
                }
            }
        }
    }

    private void beginEscrowAfterPrepare(UUID senderId, ExcellentCurrency currency, TransferAttempt attempt,
                                         RiskDecision decision, CaseRecord caseRecord, double senderBalanceBefore,
                                         CompletableFuture<Boolean> prepared) {
        prepared.whenComplete((success, throwable) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (throwable != null || !Boolean.TRUE.equals(success)) {
                this.ledger.release(attempt.transactionId());
                Player sender = Bukkit.getPlayer(senderId);
                if (sender != null) {
                    this.plugin.tell(sender, this.config.messages().auditUnavailable());
                }
                return;
            }

            EscrowRecord preparedRecord = new EscrowRecord(caseRecord.caseId(), attempt.transactionId(),
                EscrowRecord.Status.PREPARED, senderBalanceBefore, 0L, 0L, "", "");
            this.caseBook.add(caseRecord);
            this.escrowBook.add(preparedRecord);
            this.plugin.notifyOperators(caseRecord);
            Player sender = Bukkit.getPlayer(senderId);
            if (sender == null || !sender.isOnline()) {
                failPreparedEscrow(preparedRecord, caseRecord, "付款玩家在托管扣款前离线");
                return;
            }

            double amount = Money.toDouble(attempt.amountMicros());
            double actualBefore = this.economy.getBalance(sender, currency);
            if (actualBefore + 0.0000001D < amount) {
                failPreparedEscrow(preparedRecord, caseRecord, "托管扣款时余额不足");
                return;
            }

            InternalKey mutation = beginInternal(senderId, currency.getId(), -attempt.amountMicros());
            boolean withdrawn;
            try {
                withdrawn = this.economy.withdraw(sender, currency, amount,
                    OperationContext.custom("EconomyRiskBridge:ESCROW_HOLD"));
            } finally {
                endInternalIfUnused(mutation);
            }
            double actualAfter = this.economy.getBalance(sender, currency);
            long beforeMicros;
            long afterMicros;
            long observedDebit;
            try {
                beforeMicros = Money.fromDouble(actualBefore);
                afterMicros = Money.fromDouble(actualAfter);
                observedDebit = Math.subtractExact(beforeMicros, afterMicros);
            } catch (IllegalArgumentException | ArithmeticException exception) {
                markHoldReconciliation(preparedRecord, caseRecord, sender, null, withdrawn,
                    "托管扣款后无法核验余额差额");
                return;
            }
            if (!withdrawn && observedDebit == 0L) {
                failPreparedEscrow(preparedRecord, caseRecord, "ExcellentEconomy 拒绝托管扣款");
                return;
            }
            if (!withdrawn || observedDebit != attempt.amountMicros()) {
                BalanceEntry uncertain = observedDebit == 0L ? null : new BalanceEntry(
                    UUID.randomUUID(), System.currentTimeMillis(), senderId, sender.getName(),
                    currency.getId().toLowerCase(Locale.ROOT), -observedDebit, beforeMicros, afterMicros,
                    SourceCatalog.RECONCILIATION, BalanceEntry.Confidence.VERIFIED,
                    "托管扣款结果不一致，观测扣款 " + Money.format(observedDebit),
                    attempt.transactionId(), caseRecord.caseId(), null, "服务器银行托管"
                );
                markHoldReconciliation(preparedRecord, caseRecord, sender, uncertain, observedDebit != 0L,
                    "托管扣款结果不一致：观测 " + Money.format(observedDebit)
                        + "，预期 " + Money.format(attempt.amountMicros()));
                return;
            }

            long heldAt = System.currentTimeMillis();
            EscrowRecord held = preparedRecord.transition(EscrowRecord.Status.HELD, heldAt, 0L, "SYSTEM",
                "等待管理员复核");
            BalanceEntry holdEntry = new BalanceEntry(
                UUID.randomUUID(), heldAt, senderId, sender.getName(), currency.getId().toLowerCase(Locale.ROOT),
                -attempt.amountMicros(), beforeMicros, afterMicros, SourceCatalog.SERVER_BANK_HOLD,
                BalanceEntry.Confidence.VERIFIED, "风险转账金额进入服务器银行托管",
                attempt.transactionId(), caseRecord.caseId(), null, "服务器银行托管"
            );
            boolean durable = this.store.transitionEscrowDurable(caseRecord.caseId(), EscrowRecord.Status.PREPARED,
                held, TransferState.ESCROW_HELD, null, holdEntry);
            this.ledger.commit(attempt.transactionId(), heldAt);
            if (durable) {
                this.escrowBook.transition(caseRecord.caseId(), EscrowRecord.Status.PREPARED,
                    EscrowRecord.Status.HELD, heldAt, 0L, "SYSTEM", "等待管理员复核");
                String message = this.config.messages().escrowHeld()
                    .replace("{amount}", Money.format(attempt.amountMicros()))
                    .replace("{currency}", currency.getId())
                    .replace("{case}", shortId(caseRecord.caseId()))
                    .replace("{code}", decision.primaryCode());
                this.plugin.tell(sender, message);
            } else {
                recordBalanceEntry(holdEntry);
                String note = "余额已扣，但托管状态写盘失败；禁止自动放款，需人工核对";
                this.escrowBook.transition(caseRecord.caseId(), EscrowRecord.Status.PREPARED,
                    EscrowRecord.Status.NEEDS_RECONCILIATION, heldAt, 0L, "SYSTEM", note);
                this.caseBook.update(caseRecord.caseId(), CaseRecord.Status.OPEN, "SYSTEM", note);
                this.plugin.getLogger().severe(note + "，案件 " + caseRecord.caseId());
                this.plugin.tell(sender, "&c托管状态异常，资金已冻结，请联系管理员并提供案件 "
                    + shortId(caseRecord.caseId()) + "。");
            }
        }));
    }

    private void markHoldReconciliation(EscrowRecord prepared, CaseRecord caseRecord, Player sender,
                                        BalanceEntry balanceEntry, boolean potentiallyDebited, String reason) {
        long now = System.currentTimeMillis();
        EscrowRecord uncertain = prepared.transition(EscrowRecord.Status.NEEDS_RECONCILIATION,
            balanceEntry == null ? 0L : now, 0L, "SYSTEM", reason);
        CaseRecord uncertainCase = caseRecord.withStatus(CaseRecord.Status.OPEN, "SYSTEM", reason);
        boolean durable = this.store.transitionEscrowDurable(caseRecord.caseId(), EscrowRecord.Status.PREPARED,
            uncertain, TransferState.ESCROW_NEEDS_RECONCILIATION, uncertainCase, balanceEntry);
        if (!durable && balanceEntry != null) {
            recordBalanceEntry(balanceEntry);
        }
        this.escrowBook.transition(caseRecord.caseId(), EscrowRecord.Status.PREPARED,
            EscrowRecord.Status.NEEDS_RECONCILIATION, balanceEntry == null ? 0L : now, 0L, "SYSTEM", reason);
        this.caseBook.update(caseRecord.caseId(), CaseRecord.Status.OPEN, "SYSTEM", reason);
        if (potentiallyDebited) {
            this.ledger.commit(prepared.transactionId(), now);
        } else {
            this.ledger.release(prepared.transactionId());
        }
        this.plugin.getLogger().severe(reason + "，案件 " + caseRecord.caseId());
        this.plugin.tell(sender, "&c托管扣款状态不确定，资金已冻结等待人工对账。错误代码：ERB-E004 &7(案件 "
            + shortId(caseRecord.caseId()) + ")");
    }

    private void failPreparedEscrow(EscrowRecord escrow, CaseRecord caseRecord, String reason) {
        long now = System.currentTimeMillis();
        CaseRecord failedCase = caseRecord.withStatus(CaseRecord.Status.OPEN, "SYSTEM",
            reason + "；未扣款，但保留风险尝试供管理员检查");
        EscrowRecord failed = escrow.transition(EscrowRecord.Status.FAILED, 0L, now, "SYSTEM", reason);
        this.store.transitionEscrowDurable(escrow.caseId(), EscrowRecord.Status.PREPARED, failed,
            TransferState.ESCROW_FAILED, failedCase);
        this.escrowBook.transition(escrow.caseId(), EscrowRecord.Status.PREPARED, EscrowRecord.Status.FAILED,
            0L, now, "SYSTEM", reason);
        this.caseBook.update(escrow.caseId(), CaseRecord.Status.OPEN, "SYSTEM", failedCase.note());
        this.ledger.release(escrow.transactionId());
    }

    private void resolveWithPayout(String casePrefix, CommandSender actor, boolean refund) {
        Optional<EscrowRecord> found = this.escrowBook.findByPrefix(casePrefix);
        if (found.isEmpty()) {
            this.plugin.tell(actor, "&c案件编号不存在或前缀不唯一。");
            return;
        }
        EscrowRecord current = found.get();
        if (current.status() != EscrowRecord.Status.HELD) {
            this.plugin.tell(actor, "&c仅能处理 HELD 状态的托管，当前为 " + current.status() + "。");
            return;
        }
        CaseRecord caseRecord = this.caseBook.get(current.caseId()).orElse(null);
        if (caseRecord == null) {
            this.plugin.tell(actor, "&c案件元数据缺失，拒绝放款。");
            return;
        }
        ExcellentCurrency currency = this.economy.getCurrency(caseRecord.currencyId());
        if (currency == null) {
            this.plugin.tell(actor, "&c货币 " + caseRecord.currencyId() + " 已不存在，拒绝放款。");
            return;
        }

        UUID targetId = refund ? caseRecord.senderId() : caseRecord.receiverId();
        PayoutKey payoutKey = new PayoutKey(targetId, currency.getId().toLowerCase(Locale.ROOT));
        if (!this.payoutsInFlight.add(payoutKey)) {
            this.plugin.tell(actor, "&c该玩家的同货币放款正在处理中，请等待上一笔完成。");
            return;
        }

        EscrowRecord.Status resolving = refund ? EscrowRecord.Status.RESOLVING_REFUND
            : EscrowRecord.Status.RESOLVING_RELEASE;
        TransferState resolvingState = refund ? TransferState.ESCROW_RESOLVING_REFUND
            : TransferState.ESCROW_RESOLVING_RELEASE;
        String actionName = refund ? "退款" : "放行";
        EscrowRecord staged = current.transition(resolving, 0L, 0L, actor.getName(), "正在" + actionName);
        if (!this.store.transitionEscrowDurable(current.caseId(), EscrowRecord.Status.HELD, staged,
            resolvingState, null)) {
            this.payoutsInFlight.remove(payoutKey);
            this.plugin.tell(actor, "&c数据库未确认处理状态，资金仍保持托管。");
            return;
        }
        this.escrowBook.transition(current.caseId(), EscrowRecord.Status.HELD, resolving,
            0L, 0L, actor.getName(), "正在" + actionName);
        this.plugin.tell(actor, "&e已进入" + actionName + "流程，等待 ExcellentEconomy 确认入账……");

        double amount = Money.toDouble(caseRecord.amountMicros());
        this.economy.getBalanceAsync(targetId, currency).thenCompose(before -> {
            double expectedAfter = currency.floorAndLimit(before + amount);
            if (Math.subtractExact(Money.fromDouble(expectedAfter), Money.fromDouble(before))
                != caseRecord.amountMicros()) {
                return CompletableFuture.completedFuture(new PayoutResult(false, "目标余额上限不足", before, before));
            }
            InternalKey mutation = beginInternal(targetId, currency.getId(), caseRecord.amountMicros());
            return this.economy.depositAsync(targetId, currency, amount,
                    OperationContext.custom(refund ? "EconomyRiskBridge:ESCROW_REFUND"
                        : "EconomyRiskBridge:ESCROW_RELEASE"))
                .thenCompose(result -> this.economy.getBalanceAsync(targetId, currency)
                    .thenApply(after -> new PayoutResult(result == OperationResult.SUCCESS, "", before, after)))
                .whenComplete((ignored, throwable) -> endInternalIfUnused(mutation));
        }).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                if (throwable != null || result == null) {
                    markPayoutReconciliation(staged, resolving, caseRecord, actor,
                        "ExcellentEconomy 异步操作结果未知");
                    return;
                }
                long beforeMicros;
                long afterMicros;
                long observed;
                try {
                    beforeMicros = Money.fromDouble(result.before());
                    afterMicros = Money.fromDouble(result.after());
                    observed = Math.subtractExact(afterMicros, beforeMicros);
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    markPayoutReconciliation(staged, resolving, caseRecord, actor, "无法核验入账差额");
                    return;
                }
                if (!result.success() && observed == 0L) {
                    String reason = !result.message().isEmpty() ? result.message() : "ExcellentEconomy 拒绝入账";
                    restoreHeldAfterFailedPayout(staged, resolving, caseRecord, actor, reason);
                    return;
                }
                if (!result.success() || observed != caseRecord.amountMicros()) {
                    BalanceEntry uncertainEntry = observed == 0L ? null : payoutEntry(caseRecord, targetId,
                        refund, beforeMicros, afterMicros, SourceCatalog.RECONCILIATION,
                        "托管" + actionName + "结果不一致，观测入账 " + Money.format(observed));
                    markPayoutReconciliation(staged, resolving, caseRecord, actor, uncertainEntry,
                        "观测入账差额为 " + Money.format(observed) + "，预期 "
                            + Money.format(caseRecord.amountMicros()));
                    return;
                }

                long now = System.currentTimeMillis();
                EscrowRecord.Status finalStatus = refund ? EscrowRecord.Status.REFUNDED
                    : EscrowRecord.Status.RELEASED;
                TransferState finalState = refund ? TransferState.ESCROW_REFUNDED
                    : TransferState.ESCROW_RELEASED;
                CaseRecord closed = caseRecord.withStatus(CaseRecord.Status.CLOSED, actor.getName(),
                    actionName + "完成");
                EscrowRecord completed = staged.transition(finalStatus, 0L, now, actor.getName(),
                    actionName + "完成");
                SourceCatalog payoutSource = refund ? SourceCatalog.ESCROW_REFUND : SourceCatalog.ESCROW_RELEASE;
                BalanceEntry payoutEntry = payoutEntry(caseRecord, targetId, refund, beforeMicros, afterMicros,
                    payoutSource, "服务器银行托管" + actionName + "完成");
                if (!this.store.transitionEscrowDurable(current.caseId(), resolving, completed, finalState, closed,
                    payoutEntry)) {
                    markPayoutReconciliation(staged, resolving, caseRecord, actor, payoutEntry,
                        "款项已入账但终态写盘失败");
                    return;
                }
                this.escrowBook.transition(current.caseId(), resolving, finalStatus, 0L, now,
                    actor.getName(), actionName + "完成");
                this.caseBook.update(current.caseId(), CaseRecord.Status.CLOSED, actor.getName(),
                    actionName + "完成");
                if (refund) {
                    this.ledger.reverseCommitted(current.transactionId());
                }
                this.plugin.tell(actor, "&a案件 " + shortId(current.caseId()) + " 已" + actionName + "完成。");
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) {
                    this.plugin.tell(target, "&a托管案件 " + shortId(current.caseId()) + " 已" + actionName
                        + "，入账 " + Money.format(caseRecord.amountMicros()) + " " + caseRecord.currencyId() + "。");
                }
            } finally {
                this.payoutsInFlight.remove(payoutKey);
            }
        }));
    }

    private void restoreHeldAfterFailedPayout(EscrowRecord staged, EscrowRecord.Status resolving,
                                               CaseRecord caseRecord, CommandSender actor, String reason) {
        EscrowRecord restored = staged.transition(EscrowRecord.Status.HELD, 0L, 0L, actor.getName(), reason);
        TransferState state = TransferState.ESCROW_HELD;
        if (this.store.transitionEscrowDurable(staged.caseId(), resolving, restored, state, null)) {
            this.escrowBook.transition(staged.caseId(), resolving, EscrowRecord.Status.HELD, 0L, 0L,
                actor.getName(), reason);
            this.plugin.tell(actor, "&c放款未完成：" + reason + "；资金仍保持托管。");
        } else {
            markPayoutReconciliation(staged, resolving, caseRecord, actor, "放款结果与数据库均未确认");
        }
    }

    private void markPayoutReconciliation(EscrowRecord staged, EscrowRecord.Status resolving,
                                          CaseRecord caseRecord, CommandSender actor, String reason) {
        markPayoutReconciliation(staged, resolving, caseRecord, actor, null, reason);
    }

    private void markPayoutReconciliation(EscrowRecord staged, EscrowRecord.Status resolving,
                                          CaseRecord caseRecord, CommandSender actor,
                                          BalanceEntry balanceEntry, String reason) {
        EscrowRecord updated = staged.transition(EscrowRecord.Status.NEEDS_RECONCILIATION,
            0L, 0L, actor.getName(), reason);
        CaseRecord updatedCase = caseRecord.withStatus(CaseRecord.Status.OPEN, actor.getName(), reason);
        boolean durable = this.store.transitionEscrowDurable(staged.caseId(), resolving, updated,
            TransferState.ESCROW_NEEDS_RECONCILIATION, updatedCase, balanceEntry);
        if (!durable && balanceEntry != null) {
            recordBalanceEntry(balanceEntry);
        }
        this.escrowBook.transition(staged.caseId(), resolving, EscrowRecord.Status.NEEDS_RECONCILIATION,
            0L, 0L, actor.getName(), reason);
        this.caseBook.update(staged.caseId(), CaseRecord.Status.OPEN, actor.getName(), reason);
        this.plugin.getLogger().severe(reason + "，案件 " + staged.caseId());
        this.plugin.tell(actor, "&c案件进入 NEEDS_RECONCILIATION，禁止重试；请先核对 EE 操作日志。");
    }

    private BalanceEntry payoutEntry(CaseRecord caseRecord, UUID targetId, boolean refund,
                                     long beforeMicros, long afterMicros, SourceCatalog source, String detail) {
        String targetName = refund ? caseRecord.senderName() : caseRecord.receiverName();
        UUID counterpartyId = refund ? null : caseRecord.senderId();
        String counterpartyName = refund ? "服务器银行托管" : caseRecord.senderName();
        return new BalanceEntry(
            UUID.randomUUID(), System.currentTimeMillis(), targetId, targetName,
            caseRecord.currencyId().toLowerCase(Locale.ROOT), afterMicros - beforeMicros,
            beforeMicros, afterMicros, source, BalanceEntry.Confidence.VERIFIED, detail,
            caseRecord.transactionId(), caseRecord.caseId(), counterpartyId, counterpartyName
        );
    }

    private void expire(PendingTransfer transfer, long nowMillis) {
        boolean partial = transfer.hasAnyLeg();
        removePending(transfer, partial);
        this.store.enqueueTransferState(transfer.id(), partial ? TransferState.PARTIAL : TransferState.EXPIRED,
            nowMillis);
        if (partial) {
            CaseRecord record = createCase(transfer.attempt(),
                new RiskDecision(RiskDecision.Action.BLOCK, this.config.scores().blockThreshold(),
                    List.of(new RiskDecision.Reason(RiskRule.PARTIAL_SETTLEMENT, 0, true))),
                "仅观测到 ExcellentEconomy 转账的一侧余额变化，需人工对账");
            this.caseBook.add(record);
            this.store.enqueueCase(record);
            this.plugin.notifyOperators(record);
            this.plugin.getLogger().severe("Partial ExcellentEconomy settlement for transaction " + transfer.id());
        }
    }

    private void registerPending(PendingTransfer transfer) {
        TransferAttempt attempt = transfer.attempt();
        this.pending.put(transfer.id(), transfer);
        index(new LegKey(attempt.receiver().uuid(), attempt.currencyId(), attempt.amountMicros()), transfer.id());
        index(new LegKey(attempt.sender().uuid(), attempt.currencyId(), -attempt.amountMicros()), transfer.id());
    }

    private void index(LegKey key, UUID id) {
        this.legIndex.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>()).addLast(id);
    }

    private void removePending(PendingTransfer transfer, boolean committed) {
        if (!this.pending.remove(transfer.id(), transfer)) {
            return;
        }
        TransferAttempt attempt = transfer.attempt();
        removeIndex(new LegKey(attempt.receiver().uuid(), attempt.currencyId(), attempt.amountMicros()), transfer.id());
        removeIndex(new LegKey(attempt.sender().uuid(), attempt.currencyId(), -attempt.amountMicros()), transfer.id());
        if (committed) {
            this.ledger.commit(transfer.id(), System.currentTimeMillis());
        } else {
            this.ledger.release(transfer.id());
        }
    }

    private void removeIndex(LegKey key, UUID id) {
        ConcurrentLinkedDeque<UUID> queue = this.legIndex.get(key);
        if (queue == null) {
            return;
        }
        queue.remove(id);
        if (queue.isEmpty()) {
            this.legIndex.remove(key, queue);
        }
    }

    private void prioritizeExpectedDebit(PendingTransfer transfer) {
        TransferAttempt attempt = transfer.attempt();
        LegKey debitKey = new LegKey(attempt.sender().uuid(), attempt.currencyId(), -attempt.amountMicros());
        ConcurrentLinkedDeque<UUID> queue = this.legIndex.get(debitKey);
        if (queue != null && queue.remove(transfer.id())) {
            queue.addFirst(transfer.id());
        }
    }

    private InternalKey beginInternal(UUID playerId, String currencyId, long expectedDeltaMicros) {
        InternalKey key = new InternalKey(playerId, currencyId.toLowerCase(Locale.ROOT), expectedDeltaMicros);
        this.internalMutations.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        return key;
    }

    private void endInternalIfUnused(InternalKey key) {
        AtomicInteger count = this.internalMutations.get(key);
        if (count != null && count.get() > 0 && count.decrementAndGet() <= 0) {
            this.internalMutations.remove(key, count);
        }
    }

    private void recordPlayerTransferMovement(PendingTransfer transfer, PendingTransfer.Leg leg,
                                              UUID playerId, String playerName, String currencyId,
                                              long balanceBeforeMicros, long balanceAfterMicros, long nowMillis) {
        TransferAttempt attempt = transfer.attempt();
        PartyProfile counterparty = leg == PendingTransfer.Leg.CREDIT ? attempt.sender() : attempt.receiver();
        String detail = leg == PendingTransfer.Leg.CREDIT
            ? "玩家转账入账，直接来源：" + counterparty.name()
            : "玩家转账扣款，直接去向：" + counterparty.name();
        recordBalanceEntry(new BalanceEntry(
            UUID.randomUUID(), nowMillis, playerId, playerName, currencyId.toLowerCase(Locale.ROOT),
            balanceAfterMicros - balanceBeforeMicros, balanceBeforeMicros, balanceAfterMicros,
            SourceCatalog.PLAYER_TRANSFER, BalanceEntry.Confidence.VERIFIED, detail,
            attempt.transactionId(), null, counterparty.uuid(), counterparty.name()
        ));
    }

    private void recordExternalMovement(UUID playerId, String playerName, String currencyId,
                                        long balanceBeforeMicros, long balanceAfterMicros, long nowMillis) {
        SourceDetector.Attribution attribution = this.sourceDetector.detect();
        recordBalanceEntry(new BalanceEntry(
            UUID.randomUUID(), nowMillis, playerId, playerName, currencyId.toLowerCase(Locale.ROOT),
            balanceAfterMicros - balanceBeforeMicros, balanceBeforeMicros, balanceAfterMicros,
            attribution.source(), attribution.confidence(), attribution.detail(),
            null, null, null, ""
        ));
    }

    private void recordBalanceEntry(BalanceEntry entry) {
        if (this.store.enqueueBalanceEntry(entry)) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = this.lastJournalWarning.get();
        if (now - previous > 60_000L && this.lastJournalWarning.compareAndSet(previous, now)) {
            this.plugin.getLogger().warning("Balance journal queue is unavailable; EE operations.log remains the fallback.");
        }
    }

    private CaseRecord createCase(TransferAttempt attempt, RiskDecision decision, String note) {
        boolean hard = decision.reasons().stream().anyMatch(RiskDecision.Reason::hard);
        CaseRecord.Severity severity = hard ? CaseRecord.Severity.CRITICAL
            : decision.action() == RiskDecision.Action.BLOCK ? CaseRecord.Severity.HIGH : CaseRecord.Severity.MEDIUM;
        return new CaseRecord(UUID.randomUUID(), attempt.transactionId(), attempt.createdAtMillis(), severity,
            CaseRecord.Status.OPEN, attempt.sender().uuid(), attempt.sender().name(), attempt.receiver().uuid(),
            attempt.receiver().name(), attempt.currencyId(), attempt.amountMicros(), decision.score(),
            decision.reasonCodes(), note, "");
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public record PrecheckResult(Outcome outcome, UUID transactionId, UUID caseId, RiskDecision decision,
                                 String message) {

        public static PrecheckResult allow(UUID transactionId) {
            return new PrecheckResult(Outcome.ALLOW_NATIVE, transactionId, null, null, "");
        }

        public static PrecheckResult escrow(UUID caseId, RiskDecision decision, String message) {
            return new PrecheckResult(Outcome.CANCEL_ESCROW, null, caseId, decision, message);
        }

        public static PrecheckResult error(String message) {
            return new PrecheckResult(Outcome.CANCEL_ERROR, null, null, null, message);
        }

        public static PrecheckResult ignore() {
            return new PrecheckResult(Outcome.IGNORE, null, null, null, "");
        }
    }

    public enum Outcome {
        ALLOW_NATIVE,
        CANCEL_ESCROW,
        CANCEL_ERROR,
        IGNORE
    }

    private record LegKey(UUID playerId, String currencyId, long deltaMicros) {
    }

    private record InternalKey(UUID playerId, String currencyId, long expectedDeltaMicros) {
    }

    private record PayoutResult(boolean success, String message, double before, double after) {
    }

    private record PayoutKey(UUID playerId, String currencyId) {
    }
}
