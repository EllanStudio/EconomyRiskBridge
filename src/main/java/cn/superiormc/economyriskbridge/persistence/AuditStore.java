package cn.superiormc.economyriskbridge.persistence;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.model.BalanceEntry;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.model.CommittedTransfer;
import cn.superiormc.economyriskbridge.model.EscrowRecord;
import cn.superiormc.economyriskbridge.model.IdentityRecord;
import cn.superiormc.economyriskbridge.model.RiskDecision;
import cn.superiormc.economyriskbridge.model.ShopSourceRecord;
import cn.superiormc.economyriskbridge.model.TransferAttempt;
import cn.superiormc.economyriskbridge.model.TransferState;
import cn.superiormc.economyriskbridge.provenance.SourceCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuditStore implements AutoCloseable {

    private final Path databasePath;
    private final String jdbcUrl;
    private final RiskConfig.Storage config;
    private final Logger logger;
    private final LinkedBlockingDeque<WriteEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean healthy = new AtomicBoolean();
    private volatile String lastError = "not started";
    private Thread worker;

    public AuditStore(Path dataDirectory, RiskConfig.Storage config, Logger logger) {
        Path normalizedDirectory = dataDirectory.toAbsolutePath().normalize();
        Path candidate = normalizedDirectory.resolve(config.file()).normalize();
        if (!candidate.startsWith(normalizedDirectory)) {
            throw new IllegalArgumentException("storage.file must stay inside the plugin data directory");
        }
        this.databasePath = candidate;
        this.jdbcUrl = "jdbc:sqlite:" + candidate;
        this.config = config;
        this.logger = logger;
        this.queue = new LinkedBlockingDeque<>(config.queueCapacity());
    }

    public Bootstrap start(ZoneId zoneId, int identityRetentionDays) throws Exception {
        Files.createDirectories(this.databasePath.getParent());
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = open()) {
            initialise(connection);
            cleanup(connection, identityRetentionDays);
        }
        Bootstrap bootstrap = loadBootstrap(zoneId, identityRetentionDays);
        this.running.set(true);
        this.healthy.set(true);
        this.lastError = "";
        this.worker = Thread.ofPlatform().name("EconomyRiskBridge-audit").daemon(true).start(this::writerLoop);
        return bootstrap;
    }

    public boolean enqueueTransfer(TransferAttempt attempt, RiskDecision decision, TransferState state) {
        return offer(new TransferWrite(attempt, decision, state));
    }

    public boolean enqueueTransferState(UUID transactionId, TransferState state, long settledAtMillis) {
        return offer(new StateWrite(transactionId, state, settledAtMillis));
    }

    public boolean enqueueIdentity(IdentityRecord record) {
        return offer(new IdentityWrite(record));
    }

    public boolean enqueueTrust(UUID left, UUID right, boolean add, String actor, long atMillis) {
        return offer(new TrustWrite(left, right, add, actor, atMillis));
    }

    public boolean enqueueCluster(UUID playerId, String clusterId, boolean add, String actor, long atMillis) {
        return offer(new ClusterWrite(playerId, clusterId, add, actor, atMillis));
    }

    public boolean enqueueCase(CaseRecord record) {
        return offer(new CaseWrite(record));
    }

    public boolean enqueueCaseStatus(CaseRecord record) {
        return offer(new CaseStatusWrite(record));
    }

    public boolean enqueueShopSource(ShopSourceRecord record) {
        return offer(new ShopSourceWrite(record));
    }

    public boolean enqueueBalanceEntry(BalanceEntry entry) {
        return offer(new BalanceEntryWrite(entry));
    }

    public boolean enqueueOperatorAlert(UUID caseId, long createdAtMillis) {
        return offer(new OperatorAlertWrite(caseId, createdAtMillis));
    }

    public boolean enqueueOperatorAlertsDelivered(List<UUID> caseIds, String operator, long deliveredAtMillis) {
        if (caseIds == null || caseIds.isEmpty()) {
            return true;
        }
        return offer(new OperatorAlertsDeliveredWrite(List.copyOf(caseIds), operator, deliveredAtMillis));
    }

    public CompletableFuture<List<UUID>> pendingOperatorAlerts(int limit) {
        CompletableFuture<List<UUID>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("EconomyRiskBridge-operator-alerts").start(() -> {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT case_id FROM erb_operator_alerts WHERE delivered_at = 0 ORDER BY created_at LIMIT ?")) {
                statement.setInt(1, Math.max(1, Math.min(500, limit)));
                List<UUID> caseIds = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        caseIds.add(UUID.fromString(rows.getString(1)));
                    }
                }
                result.complete(List.copyOf(caseIds));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    public CompletableFuture<List<BalanceEntry>> statement(UUID playerId, String currencyId, int offset, int limit) {
        CompletableFuture<List<BalanceEntry>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("EconomyRiskBridge-statement").start(() -> {
            String normalizedCurrency = currencyId == null ? "" : currencyId.trim().toLowerCase(java.util.Locale.ROOT);
            String sql = normalizedCurrency.isEmpty()
                ? "SELECT entry_id, occurred_at, player_uuid, player_name, currency_id, delta_micros, "
                    + "balance_before_micros, balance_after_micros, source_code, confidence, detail, tx_id, "
                    + "case_id, counterparty_uuid, counterparty_name FROM erb_balance_entries "
                    + "WHERE player_uuid = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
                : "SELECT entry_id, occurred_at, player_uuid, player_name, currency_id, delta_micros, "
                    + "balance_before_micros, balance_after_micros, source_code, confidence, detail, tx_id, "
                    + "case_id, counterparty_uuid, counterparty_name FROM erb_balance_entries "
                    + "WHERE player_uuid = ? AND currency_id = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?";
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, playerId.toString());
                if (!normalizedCurrency.isEmpty()) {
                    statement.setString(index++, normalizedCurrency);
                }
                statement.setInt(index++, Math.max(1, Math.min(100, limit)));
                statement.setInt(index, Math.max(0, offset));
                List<BalanceEntry> entries = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        entries.add(readBalanceEntry(rows));
                    }
                }
                result.complete(List.copyOf(entries));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    public CompletableFuture<Boolean> prepareEscrow(TransferAttempt attempt, RiskDecision decision,
                                                     CaseRecord caseRecord, double senderBalanceBefore) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        EscrowRecord escrow = new EscrowRecord(caseRecord.caseId(), attempt.transactionId(),
            EscrowRecord.Status.PREPARED, senderBalanceBefore, 0L, 0L, "", "");
        if (!offerPriority(new EscrowPrepareWrite(attempt, decision, caseRecord, escrow, result))) {
            result.complete(false);
        }
        return result;
    }

    public boolean enqueueEscrowState(EscrowRecord escrow, TransferState transferState, CaseRecord caseRecord) {
        return offer(new EscrowStateWrite(escrow, transferState, caseRecord));
    }

    /**
     * Performs a guarded, durable escrow transition. This is intentionally reserved for the uncommon path where
     * an external economy balance has just changed or is about to change; normal transfer auditing stays queued.
     */
    public boolean transitionEscrowDurable(UUID caseId, EscrowRecord.Status expected,
                                           EscrowRecord updated, TransferState transferState,
                                           CaseRecord caseRecord) {
        return transitionEscrowDurable(caseId, expected, updated, transferState, caseRecord, null);
    }

    public boolean transitionEscrowDurable(UUID caseId, EscrowRecord.Status expected,
                                           EscrowRecord updated, TransferState transferState,
                                           CaseRecord caseRecord, BalanceEntry balanceEntry) {
        try (Connection connection = open()) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA synchronous=FULL");
            }
            connection.setAutoCommit(false);
            try (PreparedStatement escrow = connection.prepareStatement("""
                     UPDATE erb_escrows SET status = ?, held_at = ?, resolved_at = ?, resolver = ?, note = ?
                     WHERE case_id = ? AND status = ?
                     """);
                 PreparedStatement transfer = connection.prepareStatement(
                     "UPDATE erb_transfers SET state = ?, settled_at = ? WHERE tx_id = ?");
                 PreparedStatement caseUpdate = connection.prepareStatement(
                     "UPDATE erb_cases SET status = ?, note = ?, reviewer = ? WHERE case_id = ?")) {
                escrow.setString(1, updated.status().name());
                escrow.setLong(2, updated.heldAtMillis());
                escrow.setLong(3, updated.resolvedAtMillis());
                escrow.setString(4, updated.resolver());
                escrow.setString(5, updated.note());
                escrow.setString(6, caseId.toString());
                escrow.setString(7, expected.name());
                if (escrow.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                transfer.setString(1, transferState.name());
                transfer.setLong(2, updated.resolvedAtMillis() == 0L
                    ? updated.heldAtMillis() : updated.resolvedAtMillis());
                transfer.setString(3, updated.transactionId().toString());
                transfer.executeUpdate();

                if (caseRecord != null) {
                    caseUpdate.setString(1, caseRecord.status().name());
                    caseUpdate.setString(2, caseRecord.note());
                    caseUpdate.setString(3, caseRecord.reviewer());
                    caseUpdate.setString(4, caseRecord.caseId().toString());
                    caseUpdate.executeUpdate();
                }
                if (balanceEntry != null) {
                    writeBalanceEntry(connection, balanceEntry);
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            markUnhealthy(exception);
            return false;
        }
    }

    public boolean isHealthy() {
        return this.healthy.get();
    }

    public int queueSize() {
        return this.queue.size();
    }

    public int queueCapacity() {
        return this.config.queueCapacity();
    }

    public String lastError() {
        return this.lastError;
    }

    private boolean offer(WriteEvent event) {
        return this.running.get() && this.healthy.get() && this.queue.offerLast(event);
    }

    private boolean offerPriority(WriteEvent event) {
        return this.running.get() && this.healthy.get() && this.queue.offerFirst(event);
    }

    private void writerLoop() {
        List<WriteEvent> retry = null;
        try (Connection connection = open(); WriterStatements statements = new WriterStatements(connection)) {
            while (this.running.get() || !this.queue.isEmpty() || retry != null) {
                List<WriteEvent> batch = retry;
                if (batch == null) {
                    WriteEvent first;
                    try {
                        first = this.queue.poll(this.config.flushMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException exception) {
                        if (!this.running.get()) {
                            continue;
                        }
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (first == null) {
                        continue;
                    }
                    batch = new ArrayList<>(this.config.batchSize());
                    batch.add(first);
                    this.queue.drainTo(batch, this.config.batchSize() - 1);
                }

                try {
                    connection.setAutoCommit(false);
                    for (WriteEvent event : batch) {
                        statements.write(event);
                    }
                    connection.commit();
                    connection.setAutoCommit(true);
                    for (WriteEvent event : batch) {
                        event.committed();
                    }
                    retry = null;
                    this.healthy.set(true);
                    this.lastError = "";
                } catch (SQLException exception) {
                    rollback(connection);
                    retry = batch;
                    markUnhealthy(exception);
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException interrupted) {
                        if (!this.running.get()) {
                            break;
                        }
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (SQLException exception) {
            markUnhealthy(exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(this.jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static void initialise(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_transfers (
                    tx_id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    settled_at INTEGER,
                    sender_uuid TEXT NOT NULL,
                    sender_name TEXT NOT NULL,
                    receiver_uuid TEXT NOT NULL,
                    receiver_name TEXT NOT NULL,
                    currency_id TEXT NOT NULL,
                    amount_micros INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    recommended_action TEXT NOT NULL,
                    risk_score INTEGER NOT NULL,
                    reason_codes TEXT NOT NULL,
                    sender_ip_hash TEXT NOT NULL,
                    receiver_ip_hash TEXT NOT NULL,
                    sender_cluster TEXT NOT NULL,
                    sender_play_seconds INTEGER NOT NULL,
                    receiver_play_seconds INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_transfer_time ON erb_transfers(created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_transfer_sender ON erb_transfers(sender_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_transfer_receiver ON erb_transfers(receiver_uuid, created_at)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_cases (
                    case_id TEXT PRIMARY KEY,
                    tx_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    severity TEXT NOT NULL,
                    status TEXT NOT NULL,
                    sender_uuid TEXT NOT NULL,
                    sender_name TEXT NOT NULL,
                    receiver_uuid TEXT NOT NULL,
                    receiver_name TEXT NOT NULL,
                    currency_id TEXT NOT NULL,
                    amount_micros INTEGER NOT NULL,
                    risk_score INTEGER NOT NULL,
                    reason_codes TEXT NOT NULL,
                    note TEXT NOT NULL,
                    reviewer TEXT NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_case_status_time ON erb_cases(status, created_at)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_operator_alerts (
                    case_id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    delivered_at INTEGER NOT NULL DEFAULT 0,
                    delivered_to TEXT NOT NULL DEFAULT ''
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS erb_operator_alert_pending
                ON erb_operator_alerts(delivered_at, created_at)
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_identity (
                    player_uuid TEXT NOT NULL,
                    ip_hash TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    play_seconds INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, ip_hash)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_identity_ip ON erb_identity(ip_hash, last_seen)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_identity_name ON erb_identity(player_name)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_trusted_pairs (
                    left_uuid TEXT NOT NULL,
                    right_uuid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    created_by TEXT NOT NULL,
                    PRIMARY KEY (left_uuid, right_uuid)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_confirmed_clusters (
                    player_uuid TEXT PRIMARY KEY,
                    cluster_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    updated_by TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_escrows (
                    case_id TEXT PRIMARY KEY,
                    tx_id TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL,
                    sender_balance_before REAL NOT NULL,
                    held_at INTEGER NOT NULL,
                    resolved_at INTEGER NOT NULL,
                    resolver TEXT NOT NULL,
                    note TEXT NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_escrow_status ON erb_escrows(status)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_balance_entries (
                    entry_id TEXT PRIMARY KEY,
                    occurred_at INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    currency_id TEXT NOT NULL,
                    delta_micros INTEGER NOT NULL,
                    balance_before_micros INTEGER NOT NULL,
                    balance_after_micros INTEGER NOT NULL,
                    source_code TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    tx_id TEXT,
                    case_id TEXT,
                    counterparty_uuid TEXT,
                    counterparty_name TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS erb_balance_player_time
                ON erb_balance_entries(player_uuid, occurred_at DESC)
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS erb_balance_tx ON erb_balance_entries(tx_id)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS erb_shop_source_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    epoch_day INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    cluster_key TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    units INTEGER NOT NULL,
                    individual_limit INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS erb_shop_source_day_cluster
                ON erb_shop_source_events(epoch_day, cluster_key, source_key)
                """);
        }
    }

    private void cleanup(Connection connection, int identityRetentionDays) throws SQLException {
        long now = System.currentTimeMillis();
        long auditCutoff = now - this.config.retentionDays() * 86_400_000L;
        long identityCutoff = now - identityRetentionDays * 86_400_000L;
        try (PreparedStatement escrows = connection.prepareStatement("""
                 DELETE FROM erb_escrows
                 WHERE resolved_at > 0 AND resolved_at < ? AND status <> 'CONFISCATED'
                 """);
             PreparedStatement transfers = connection.prepareStatement("""
                 DELETE FROM erb_transfers WHERE created_at < ?
                 AND tx_id NOT IN (SELECT tx_id FROM erb_escrows)
                 """);
             PreparedStatement cases = connection.prepareStatement(
                 "DELETE FROM erb_cases WHERE created_at < ? AND status = 'CLOSED' "
                     + "AND case_id NOT IN (SELECT case_id FROM erb_escrows)");
             PreparedStatement operatorAlerts = connection.prepareStatement(
                 "DELETE FROM erb_operator_alerts WHERE delivered_at > 0 AND delivered_at < ?");
             PreparedStatement identities = connection.prepareStatement("DELETE FROM erb_identity WHERE last_seen < ?");
             PreparedStatement balances = connection.prepareStatement(
                 "DELETE FROM erb_balance_entries WHERE occurred_at < ?")) {
            escrows.setLong(1, auditCutoff);
            escrows.executeUpdate();
            transfers.setLong(1, auditCutoff);
            transfers.executeUpdate();
            cases.setLong(1, auditCutoff);
            cases.executeUpdate();
            operatorAlerts.setLong(1, auditCutoff);
            operatorAlerts.executeUpdate();
            identities.setLong(1, identityCutoff);
            identities.executeUpdate();
            balances.setLong(1, auditCutoff);
            balances.executeUpdate();
            try (PreparedStatement sources = connection.prepareStatement(
                "DELETE FROM erb_shop_source_events WHERE created_at < ?")) {
                sources.setLong(1, auditCutoff);
                sources.executeUpdate();
            }
        }
    }

    private Bootstrap loadBootstrap(ZoneId zoneId, int identityRetentionDays) throws SQLException {
        long now = System.currentTimeMillis();
        long identityCutoff = now - identityRetentionDays * 86_400_000L;
        long dayStart = LocalDate.ofInstant(Instant.ofEpochMilli(now), zoneId)
            .atStartOfDay(zoneId).toInstant().toEpochMilli();
        List<IdentityRecord> identities = new ArrayList<>();
        List<TrustedPair> trustedPairs = new ArrayList<>();
        List<ConfirmedCluster> clusters = new ArrayList<>();
        List<CommittedTransfer> transfers = new ArrayList<>();
        List<CaseRecord> cases = new ArrayList<>();
        List<EscrowRecord> escrows = new ArrayList<>();
        Map<String, Long> treasury = new LinkedHashMap<>();
        List<ShopSourceRecord> shopSources = new ArrayList<>();

        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, player_name, ip_hash, first_seen, last_seen, play_seconds "
                    + "FROM erb_identity WHERE last_seen >= ?")) {
                statement.setLong(1, identityCutoff);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        identities.add(new IdentityRecord(UUID.fromString(result.getString(1)), result.getString(2),
                            result.getString(3), result.getLong(4), result.getLong(5), result.getLong(6)));
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT left_uuid, right_uuid FROM erb_trusted_pairs")) {
                while (result.next()) {
                    trustedPairs.add(new TrustedPair(UUID.fromString(result.getString(1)),
                        UUID.fromString(result.getString(2))));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT player_uuid, cluster_id FROM erb_confirmed_clusters")) {
                while (result.next()) {
                    clusters.add(new ConfirmedCluster(UUID.fromString(result.getString(1)), result.getString(2)));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tx_id, created_at, sender_uuid, receiver_uuid, currency_id, amount_micros, sender_cluster
                FROM erb_transfers
                WHERE state IN ('COMMITTED', 'ESCROW_PREPARED', 'ESCROW_HELD', 'ESCROW_RESOLVING_RELEASE',
                                'ESCROW_RESOLVING_REFUND',
                                'ESCROW_RELEASED', 'ESCROW_CONFISCATED', 'ESCROW_NEEDS_RECONCILIATION')
                  AND created_at >= ?
                """)) {
                statement.setLong(1, dayStart);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        transfers.add(new CommittedTransfer(UUID.fromString(result.getString(1)), result.getLong(2),
                            UUID.fromString(result.getString(3)), UUID.fromString(result.getString(4)),
                            result.getString(5), result.getLong(6), result.getString(7)));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT case_id, tx_id, created_at, severity, status, sender_uuid, sender_name,
                       receiver_uuid, receiver_name, currency_id, amount_micros, risk_score,
                       reason_codes, note, reviewer
                FROM erb_cases ORDER BY created_at DESC LIMIT 2000
                """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        cases.add(readCase(result));
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                     SELECT case_id, tx_id, status, sender_balance_before, held_at,
                            resolved_at, resolver, note
                     FROM erb_escrows ORDER BY rowid DESC LIMIT 2000
                     """)) {
                while (result.next()) {
                    escrows.add(new EscrowRecord(UUID.fromString(result.getString(1)),
                        UUID.fromString(result.getString(2)), EscrowRecord.Status.valueOf(result.getString(3)),
                        result.getDouble(4), result.getLong(5), result.getLong(6), result.getString(7),
                        result.getString(8)));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                     SELECT c.currency_id, COALESCE(SUM(c.amount_micros), 0)
                     FROM erb_escrows e JOIN erb_cases c ON c.case_id = e.case_id
                     WHERE e.status = 'CONFISCATED' GROUP BY c.currency_id
                     """)) {
                while (result.next()) {
                    treasury.put(result.getString(1), result.getLong(2));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT created_at, epoch_day, player_uuid, cluster_key, source_key, units, individual_limit
                FROM erb_shop_source_events WHERE created_at >= ? ORDER BY created_at
                """)) {
                statement.setLong(1, dayStart);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        shopSources.add(new ShopSourceRecord(result.getLong(1), result.getLong(2),
                            UUID.fromString(result.getString(3)), result.getString(4), result.getString(5),
                            result.getLong(6), result.getLong(7)));
                    }
                }
            }
        }
        return new Bootstrap(identities, trustedPairs, clusters, transfers, cases, escrows, treasury, shopSources);
    }

    private static CaseRecord readCase(ResultSet result) throws SQLException {
        return new CaseRecord(UUID.fromString(result.getString(1)), UUID.fromString(result.getString(2)),
            result.getLong(3), CaseRecord.Severity.valueOf(result.getString(4)),
            CaseRecord.Status.valueOf(result.getString(5)), UUID.fromString(result.getString(6)),
            result.getString(7), UUID.fromString(result.getString(8)), result.getString(9), result.getString(10),
            result.getLong(11), result.getInt(12), result.getString(13), result.getString(14), result.getString(15));
    }

    private static BalanceEntry readBalanceEntry(ResultSet result) throws SQLException {
        SourceCatalog source = SourceCatalog.byCode(result.getString(9)).orElse(SourceCatalog.UNKNOWN_EXTERNAL);
        BalanceEntry.Confidence confidence;
        try {
            confidence = BalanceEntry.Confidence.valueOf(result.getString(10));
        } catch (IllegalArgumentException | NullPointerException exception) {
            confidence = BalanceEntry.Confidence.UNKNOWN;
        }
        return new BalanceEntry(
            UUID.fromString(result.getString(1)), result.getLong(2), UUID.fromString(result.getString(3)),
            result.getString(4), result.getString(5), result.getLong(6), result.getLong(7), result.getLong(8),
            source, confidence, result.getString(11), optionalUuid(result.getString(12)),
            optionalUuid(result.getString(13)), optionalUuid(result.getString(14)), result.getString(15)
        );
    }

    private static UUID optionalUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static void writeBalanceEntry(Connection connection, BalanceEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO erb_balance_entries (
                entry_id, occurred_at, player_uuid, player_name, currency_id, delta_micros,
                balance_before_micros, balance_after_micros, source_code, confidence, detail,
                tx_id, case_id, counterparty_uuid, counterparty_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(entry_id) DO NOTHING
            """)) {
            bindBalanceEntry(statement, entry);
            statement.executeUpdate();
        }
    }

    private static void bindBalanceEntry(PreparedStatement statement, BalanceEntry entry) throws SQLException {
        statement.setString(1, entry.entryId().toString());
        statement.setLong(2, entry.occurredAtMillis());
        statement.setString(3, entry.playerId().toString());
        statement.setString(4, entry.playerName());
        statement.setString(5, entry.currencyId());
        statement.setLong(6, entry.deltaMicros());
        statement.setLong(7, entry.balanceBeforeMicros());
        statement.setLong(8, entry.balanceAfterMicros());
        statement.setString(9, entry.source().code());
        statement.setString(10, entry.confidence().name());
        statement.setString(11, entry.detail());
        statement.setString(12, entry.transactionId() == null ? null : entry.transactionId().toString());
        statement.setString(13, entry.caseId() == null ? null : entry.caseId().toString());
        statement.setString(14, entry.counterpartyId() == null ? null : entry.counterpartyId().toString());
        statement.setString(15, entry.counterpartyName());
    }

    private void markUnhealthy(Exception exception) {
        this.healthy.set(false);
        this.lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        this.logger.log(Level.SEVERE, "EconomyRiskBridge audit writer failed; new transfers may fail closed", exception);
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    @Override
    public void close() {
        this.running.set(false);
        Thread currentWorker = this.worker;
        if (currentWorker == null) {
            return;
        }
        try {
            currentWorker.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (currentWorker.isAlive()) {
            currentWorker.interrupt();
            this.logger.warning("Audit writer did not drain within 5 seconds; remaining queued events were not persisted.");
        }
    }

    public record Bootstrap(
        List<IdentityRecord> identities,
        List<TrustedPair> trustedPairs,
        List<ConfirmedCluster> clusters,
        List<CommittedTransfer> committedTransfers,
        List<CaseRecord> cases,
        List<EscrowRecord> escrows,
        Map<String, Long> treasury,
        List<ShopSourceRecord> shopSources
    ) {
    }

    public record TrustedPair(UUID left, UUID right) {
    }

    public record ConfirmedCluster(UUID playerId, String clusterId) {
    }

    private sealed interface WriteEvent permits TransferWrite, StateWrite, IdentityWrite, TrustWrite, ClusterWrite,
        CaseWrite, CaseStatusWrite, EscrowPrepareWrite, EscrowStateWrite, ShopSourceWrite, BalanceEntryWrite,
        OperatorAlertWrite, OperatorAlertsDeliveredWrite {

        default void committed() {
        }
    }

    private record TransferWrite(TransferAttempt attempt, RiskDecision decision, TransferState state)
        implements WriteEvent {
    }

    private record StateWrite(UUID transactionId, TransferState state, long settledAtMillis) implements WriteEvent {
    }

    private record IdentityWrite(IdentityRecord record) implements WriteEvent {
    }

    private record TrustWrite(UUID left, UUID right, boolean add, String actor, long atMillis) implements WriteEvent {
    }

    private record ClusterWrite(UUID playerId, String clusterId, boolean add, String actor, long atMillis)
        implements WriteEvent {
    }

    private record CaseWrite(CaseRecord record) implements WriteEvent {
    }

    private record CaseStatusWrite(CaseRecord record) implements WriteEvent {
    }

    private record EscrowPrepareWrite(
        TransferAttempt attempt,
        RiskDecision decision,
        CaseRecord caseRecord,
        EscrowRecord escrow,
        CompletableFuture<Boolean> completion
    ) implements WriteEvent {

        @Override
        public void committed() {
            this.completion.complete(true);
        }
    }

    private record EscrowStateWrite(EscrowRecord escrow, TransferState transferState, CaseRecord caseRecord)
        implements WriteEvent {
    }

    private record ShopSourceWrite(ShopSourceRecord record) implements WriteEvent {
    }

    private record BalanceEntryWrite(BalanceEntry entry) implements WriteEvent {
    }

    private record OperatorAlertWrite(UUID caseId, long createdAtMillis) implements WriteEvent {
    }

    private record OperatorAlertsDeliveredWrite(List<UUID> caseIds, String operator, long deliveredAtMillis)
        implements WriteEvent {
    }

    private static final class WriterStatements implements AutoCloseable {

        private final PreparedStatement transfer;
        private final PreparedStatement state;
        private final PreparedStatement identity;
        private final PreparedStatement trustInsert;
        private final PreparedStatement trustDelete;
        private final PreparedStatement clusterInsert;
        private final PreparedStatement clusterDelete;
        private final PreparedStatement caseInsert;
        private final PreparedStatement caseStatus;
        private final PreparedStatement escrowInsert;
        private final PreparedStatement escrowState;
        private final PreparedStatement shopSource;
        private final PreparedStatement balanceEntry;
        private final PreparedStatement operatorAlert;
        private final PreparedStatement operatorAlertDelivered;

        private WriterStatements(Connection connection) throws SQLException {
            this.transfer = connection.prepareStatement("""
                INSERT INTO erb_transfers (
                    tx_id, created_at, settled_at, sender_uuid, sender_name, receiver_uuid, receiver_name,
                    currency_id, amount_micros, state, recommended_action, risk_score, reason_codes,
                    sender_ip_hash, receiver_ip_hash, sender_cluster, sender_play_seconds, receiver_play_seconds
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(tx_id) DO NOTHING
                """);
            this.state = connection.prepareStatement(
                "UPDATE erb_transfers SET state = ?, settled_at = ? WHERE tx_id = ?");
            this.identity = connection.prepareStatement("""
                INSERT INTO erb_identity (player_uuid, ip_hash, player_name, first_seen, last_seen, play_seconds)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, ip_hash) DO UPDATE SET
                    player_name = excluded.player_name,
                    first_seen = MIN(erb_identity.first_seen, excluded.first_seen),
                    last_seen = MAX(erb_identity.last_seen, excluded.last_seen),
                    play_seconds = MAX(erb_identity.play_seconds, excluded.play_seconds)
                """);
            this.trustInsert = connection.prepareStatement("""
                INSERT INTO erb_trusted_pairs (left_uuid, right_uuid, created_at, created_by)
                VALUES (?, ?, ?, ?) ON CONFLICT(left_uuid, right_uuid) DO UPDATE SET
                    created_at = excluded.created_at, created_by = excluded.created_by
                """);
            this.trustDelete = connection.prepareStatement(
                "DELETE FROM erb_trusted_pairs WHERE left_uuid = ? AND right_uuid = ?");
            this.clusterInsert = connection.prepareStatement("""
                INSERT INTO erb_confirmed_clusters (player_uuid, cluster_id, updated_at, updated_by)
                VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET
                    cluster_id = excluded.cluster_id, updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """);
            this.clusterDelete = connection.prepareStatement(
                "DELETE FROM erb_confirmed_clusters WHERE player_uuid = ?");
            this.caseInsert = connection.prepareStatement("""
                INSERT INTO erb_cases (
                    case_id, tx_id, created_at, severity, status, sender_uuid, sender_name,
                    receiver_uuid, receiver_name, currency_id, amount_micros, risk_score,
                    reason_codes, note, reviewer
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(case_id) DO NOTHING
                """);
            this.caseStatus = connection.prepareStatement(
                "UPDATE erb_cases SET status = ?, note = ?, reviewer = ? WHERE case_id = ?");
            this.escrowInsert = connection.prepareStatement("""
                INSERT INTO erb_escrows (
                    case_id, tx_id, status, sender_balance_before, held_at, resolved_at, resolver, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(case_id) DO NOTHING
                """);
            this.escrowState = connection.prepareStatement("""
                UPDATE erb_escrows SET status = ?, held_at = ?, resolved_at = ?, resolver = ?, note = ?
                WHERE case_id = ?
                """);
            this.shopSource = connection.prepareStatement("""
                INSERT INTO erb_shop_source_events (
                    created_at, epoch_day, player_uuid, cluster_key, source_key, units, individual_limit
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """);
            this.balanceEntry = connection.prepareStatement("""
                INSERT INTO erb_balance_entries (
                    entry_id, occurred_at, player_uuid, player_name, currency_id, delta_micros,
                    balance_before_micros, balance_after_micros, source_code, confidence, detail,
                    tx_id, case_id, counterparty_uuid, counterparty_name
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(entry_id) DO NOTHING
                """);
            this.operatorAlert = connection.prepareStatement("""
                INSERT INTO erb_operator_alerts (case_id, created_at, delivered_at, delivered_to)
                VALUES (?, ?, 0, '') ON CONFLICT(case_id) DO NOTHING
                """);
            this.operatorAlertDelivered = connection.prepareStatement("""
                UPDATE erb_operator_alerts SET delivered_at = ?, delivered_to = ?
                WHERE case_id = ? AND delivered_at = 0
                """);
        }

        private void write(WriteEvent event) throws SQLException {
            switch (event) {
                case TransferWrite write -> writeTransfer(write);
                case StateWrite write -> writeState(write);
                case IdentityWrite write -> writeIdentity(write);
                case TrustWrite write -> writeTrust(write);
                case ClusterWrite write -> writeCluster(write);
                case CaseWrite write -> writeCase(write.record());
                case CaseStatusWrite write -> writeCaseStatus(write.record());
                case EscrowPrepareWrite write -> writeEscrowPrepare(write);
                case EscrowStateWrite write -> writeEscrowState(write);
                case ShopSourceWrite write -> writeShopSource(write.record());
                case BalanceEntryWrite write -> writeBalanceEntry(write.entry());
                case OperatorAlertWrite write -> writeOperatorAlert(write);
                case OperatorAlertsDeliveredWrite write -> writeOperatorAlertsDelivered(write);
            }
        }

        private void writeTransfer(TransferWrite write) throws SQLException {
            TransferAttempt attempt = write.attempt();
            this.transfer.setString(1, attempt.transactionId().toString());
            this.transfer.setLong(2, attempt.createdAtMillis());
            this.transfer.setString(3, attempt.sender().uuid().toString());
            this.transfer.setString(4, attempt.sender().name());
            this.transfer.setString(5, attempt.receiver().uuid().toString());
            this.transfer.setString(6, attempt.receiver().name());
            this.transfer.setString(7, attempt.currencyId());
            this.transfer.setLong(8, attempt.amountMicros());
            this.transfer.setString(9, write.state().name());
            this.transfer.setString(10, write.decision().action().name());
            this.transfer.setInt(11, write.decision().score());
            this.transfer.setString(12, write.decision().reasonCodes());
            this.transfer.setString(13, attempt.sender().ipHash());
            this.transfer.setString(14, attempt.receiver().ipHash());
            this.transfer.setString(15, attempt.sender().clusterKey());
            this.transfer.setLong(16, attempt.sender().playSeconds());
            this.transfer.setLong(17, attempt.receiver().playSeconds());
            this.transfer.executeUpdate();
        }

        private void writeState(StateWrite write) throws SQLException {
            this.state.setString(1, write.state().name());
            this.state.setLong(2, write.settledAtMillis());
            this.state.setString(3, write.transactionId().toString());
            this.state.executeUpdate();
        }

        private void writeIdentity(IdentityWrite write) throws SQLException {
            IdentityRecord record = write.record();
            this.identity.setString(1, record.playerId().toString());
            this.identity.setString(2, record.ipHash());
            this.identity.setString(3, record.playerName());
            this.identity.setLong(4, record.firstSeenMillis());
            this.identity.setLong(5, record.lastSeenMillis());
            this.identity.setLong(6, record.playSeconds());
            this.identity.executeUpdate();
        }

        private void writeTrust(TrustWrite write) throws SQLException {
            if (write.add()) {
                this.trustInsert.setString(1, write.left().toString());
                this.trustInsert.setString(2, write.right().toString());
                this.trustInsert.setLong(3, write.atMillis());
                this.trustInsert.setString(4, write.actor());
                this.trustInsert.executeUpdate();
            } else {
                this.trustDelete.setString(1, write.left().toString());
                this.trustDelete.setString(2, write.right().toString());
                this.trustDelete.executeUpdate();
            }
        }

        private void writeCluster(ClusterWrite write) throws SQLException {
            if (write.add()) {
                this.clusterInsert.setString(1, write.playerId().toString());
                this.clusterInsert.setString(2, write.clusterId());
                this.clusterInsert.setLong(3, write.atMillis());
                this.clusterInsert.setString(4, write.actor());
                this.clusterInsert.executeUpdate();
            } else {
                this.clusterDelete.setString(1, write.playerId().toString());
                this.clusterDelete.executeUpdate();
            }
        }

        private void writeCase(CaseRecord record) throws SQLException {
            this.caseInsert.setString(1, record.caseId().toString());
            this.caseInsert.setString(2, record.transactionId().toString());
            this.caseInsert.setLong(3, record.createdAtMillis());
            this.caseInsert.setString(4, record.severity().name());
            this.caseInsert.setString(5, record.status().name());
            this.caseInsert.setString(6, record.senderId().toString());
            this.caseInsert.setString(7, record.senderName());
            this.caseInsert.setString(8, record.receiverId().toString());
            this.caseInsert.setString(9, record.receiverName());
            this.caseInsert.setString(10, record.currencyId());
            this.caseInsert.setLong(11, record.amountMicros());
            this.caseInsert.setInt(12, record.score());
            this.caseInsert.setString(13, record.reasonCodes());
            this.caseInsert.setString(14, record.note());
            this.caseInsert.setString(15, record.reviewer());
            this.caseInsert.executeUpdate();
        }

        private void writeCaseStatus(CaseRecord record) throws SQLException {
            this.caseStatus.setString(1, record.status().name());
            this.caseStatus.setString(2, record.note());
            this.caseStatus.setString(3, record.reviewer());
            this.caseStatus.setString(4, record.caseId().toString());
            this.caseStatus.executeUpdate();
        }

        private void writeEscrowPrepare(EscrowPrepareWrite write) throws SQLException {
            writeTransfer(new TransferWrite(write.attempt(), write.decision(), TransferState.ESCROW_PREPARED));
            writeCase(write.caseRecord());
            EscrowRecord escrow = write.escrow();
            this.escrowInsert.setString(1, escrow.caseId().toString());
            this.escrowInsert.setString(2, escrow.transactionId().toString());
            this.escrowInsert.setString(3, escrow.status().name());
            this.escrowInsert.setDouble(4, escrow.senderBalanceBefore());
            this.escrowInsert.setLong(5, escrow.heldAtMillis());
            this.escrowInsert.setLong(6, escrow.resolvedAtMillis());
            this.escrowInsert.setString(7, escrow.resolver());
            this.escrowInsert.setString(8, escrow.note());
            this.escrowInsert.executeUpdate();
        }

        private void writeEscrowState(EscrowStateWrite write) throws SQLException {
            EscrowRecord escrow = write.escrow();
            this.escrowState.setString(1, escrow.status().name());
            this.escrowState.setLong(2, escrow.heldAtMillis());
            this.escrowState.setLong(3, escrow.resolvedAtMillis());
            this.escrowState.setString(4, escrow.resolver());
            this.escrowState.setString(5, escrow.note());
            this.escrowState.setString(6, escrow.caseId().toString());
            this.escrowState.executeUpdate();
            writeState(new StateWrite(escrow.transactionId(), write.transferState(),
                escrow.resolvedAtMillis() == 0L ? escrow.heldAtMillis() : escrow.resolvedAtMillis()));
            if (write.caseRecord() != null) {
                writeCaseStatus(write.caseRecord());
            }
        }

        private void writeShopSource(ShopSourceRecord record) throws SQLException {
            this.shopSource.setLong(1, record.createdAtMillis());
            this.shopSource.setLong(2, record.epochDay());
            this.shopSource.setString(3, record.playerId().toString());
            this.shopSource.setString(4, record.clusterKey());
            this.shopSource.setString(5, record.sourceKey());
            this.shopSource.setLong(6, record.units());
            this.shopSource.setLong(7, record.individualLimit());
            this.shopSource.executeUpdate();
        }

        private void writeBalanceEntry(BalanceEntry entry) throws SQLException {
            bindBalanceEntry(this.balanceEntry, entry);
            this.balanceEntry.executeUpdate();
        }

        private void writeOperatorAlert(OperatorAlertWrite write) throws SQLException {
            this.operatorAlert.setString(1, write.caseId().toString());
            this.operatorAlert.setLong(2, write.createdAtMillis());
            this.operatorAlert.executeUpdate();
        }

        private void writeOperatorAlertsDelivered(OperatorAlertsDeliveredWrite write) throws SQLException {
            for (UUID caseId : write.caseIds()) {
                this.operatorAlertDelivered.setLong(1, write.deliveredAtMillis());
                this.operatorAlertDelivered.setString(2, write.operator());
                this.operatorAlertDelivered.setString(3, caseId.toString());
                this.operatorAlertDelivered.addBatch();
            }
            this.operatorAlertDelivered.executeBatch();
        }

        @Override
        public void close() throws SQLException {
            this.transfer.close();
            this.state.close();
            this.identity.close();
            this.trustInsert.close();
            this.trustDelete.close();
            this.clusterInsert.close();
            this.clusterDelete.close();
            this.caseInsert.close();
            this.caseStatus.close();
            this.escrowInsert.close();
            this.escrowState.close();
            this.shopSource.close();
            this.balanceEntry.close();
            this.operatorAlert.close();
            this.operatorAlertDelivered.close();
        }
    }
}
