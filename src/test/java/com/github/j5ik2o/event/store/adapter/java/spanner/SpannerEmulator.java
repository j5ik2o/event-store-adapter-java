package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Cloud Spanner emulator per JVM. The five tables and the configuration row are created
 * here, outside the library, exactly as the schema document tells an operator to do (SPN-D5).
 */
public final class SpannerEmulator {
  static final String IMAGE =
      "gcr.io/cloud-spanner-emulator/emulator:1.5.58@sha256:c6f3402f2599684f295a0fdefb6fbbbfb18a0e43e309ff5456ccb452a4570a79";
  private static final String PROJECT = "test-project";
  private static final String INSTANCE = "test-instance";
  private static final String DATABASE = "test-database";

  private static SpannerEmulator shared;

  private final AtomicInteger prefixes = new AtomicInteger();
  private final GenericContainer<?> container;
  private final Spanner spanner;
  private final DatabaseClient client;

  private SpannerEmulator(GenericContainer<?> container, Spanner spanner, DatabaseClient client) {
    this.container = container;
    this.spanner = spanner;
    this.client = client;
  }

  /** Starts the emulator on first use and stops it when the JVM exits. */
  public static synchronized SpannerEmulator shared() {
    if (shared == null) {
      shared = start();
      Runtime.getRuntime().addShutdownHook(new Thread(shared::stop));
    }
    return shared;
  }

  private static SpannerEmulator start() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(9010, 9020)
            .waitingFor(Wait.forLogMessage(".*gRPC server listening.*", 1));
    container.start();
    Spanner spanner = null;
    try {
      spanner =
          SpannerOptions.newBuilder()
              .setProjectId(PROJECT)
              .setEmulatorHost(container.getHost() + ":" + container.getMappedPort(9010))
              .setCredentials(NoCredentials.getInstance())
              .build()
              .getService();
      spanner
          .getInstanceAdminClient()
          .createInstance(
              InstanceInfo.newBuilder(InstanceId.of(PROJECT, INSTANCE))
                  .setInstanceConfigId(InstanceConfigId.of(PROJECT, "emulator-config"))
                  .setNodeCount(1)
                  .setDisplayName(INSTANCE)
                  .build())
          .get();
      spanner.getDatabaseAdminClient().createDatabase(INSTANCE, DATABASE, List.of()).get();
      return new SpannerEmulator(
          container,
          spanner,
          spanner.getDatabaseClient(DatabaseId.of(PROJECT, INSTANCE, DATABASE)));
    } catch (Exception failure) {
      if (spanner != null) spanner.close();
      container.stop();
      throw new IllegalStateException("failed to start the Spanner emulator", failure);
    }
  }

  private void stop() {
    spanner.close();
    container.stop();
  }

  /** The client handed to the library. The library never owns or closes it. */
  public DatabaseClient client() {
    return client;
  }

  /** A client for a database that does not exist on the emulator. */
  public DatabaseClient clientForMissingDatabase() {
    return spanner.getDatabaseClient(DatabaseId.of(PROJECT, INSTANCE, "no-such-database"));
  }

  /** One row of the configuration table. A null value is written as SQL NULL. */
  public static final class ConfigRow {
    final String storeId;
    final long layoutVersion;
    final Integer keepSnapshotCount;
    final String retentionMode;
    final Long ttlGraceDays;
    final boolean changeFeedEnabled;

    public ConfigRow(
        String storeId,
        long layoutVersion,
        Integer keepSnapshotCount,
        String retentionMode,
        Long ttlGraceDays,
        boolean changeFeedEnabled) {
      this.storeId = storeId;
      this.layoutVersion = layoutVersion;
      this.keepSnapshotCount = keepSnapshotCount;
      this.retentionMode = retentionMode;
      this.ttlGraceDays = ttlGraceDays;
      this.changeFeedEnabled = changeFeedEnabled;
    }

    /** A row that agrees with a client using the given retention setting. */
    public static ConfigRow matching(Integer keepSnapshotCount, String mode, Long graceDays) {
      return new ConfigRow(
          "0b6c5c2e-7b0e-4d0c-9a53-1f2c8e4d7a10", 1, keepSnapshotCount, mode, graceDays, false);
    }

    public ConfigRow withLayoutVersion(long value) {
      return new ConfigRow(
          storeId, value, keepSnapshotCount, retentionMode, ttlGraceDays, changeFeedEnabled);
    }

    public ConfigRow withKeep(Integer value) {
      return new ConfigRow(
          storeId, layoutVersion, value, retentionMode, ttlGraceDays, changeFeedEnabled);
    }

    public ConfigRow withMode(String value) {
      return new ConfigRow(
          storeId, layoutVersion, keepSnapshotCount, value, ttlGraceDays, changeFeedEnabled);
    }

    public ConfigRow withGraceDays(Long value) {
      return new ConfigRow(
          storeId, layoutVersion, keepSnapshotCount, retentionMode, value, changeFeedEnabled);
    }

    public ConfigRow withChangeFeed(boolean value) {
      return new ConfigRow(
          storeId, layoutVersion, keepSnapshotCount, retentionMode, ttlGraceDays, value);
    }

    String insert(String prefix) {
      return "INSERT INTO "
          + prefix
          + "_config (singleton, store_id, layout_version, keep_snapshot_count, retention_mode,"
          + " ttl_grace_days, change_feed_enabled) VALUES (1, '"
          + storeId
          + "', "
          + layoutVersion
          + ", "
          + (keepSnapshotCount == null ? "NULL" : keepSnapshotCount.toString())
          + ", '"
          + retentionMode
          + "', "
          + (ttlGraceDays == null ? "NULL" : ttlGraceDays.toString())
          + ", "
          + (changeFeedEnabled ? "TRUE" : "FALSE")
          + ")";
    }
  }

  /**
   * Creates the five tables under a fresh prefix.
   *
   * @param rowDeletionPolicyDays days of the row deletion policy on the history table, or null
   * @param configuration the single configuration row, or null to leave the table empty
   * @return the prefix
   */
  public String createStore(Integer rowDeletionPolicyDays, ConfigRow configuration) {
    String prefix = "esa" + prefixes.incrementAndGet();
    String policy =
        rowDeletionPolicyDays == null
            ? ""
            : ", ROW DELETION POLICY (OLDER_THAN(expired_at, INTERVAL "
                + rowDeletionPolicyDays
                + " DAY))";
    String key = "hash_prefix BYTES(8) NOT NULL, aid STRING(1024) NOT NULL";
    ddl(
        "CREATE TABLE "
            + prefix
            + "_journal ("
            + key
            + ", seq_nr INT64 NOT NULL, occurred_at_ns INT64 NOT NULL, manifest STRING(MAX) NOT NULL,"
            + " payload BYTES(MAX) NOT NULL) PRIMARY KEY (hash_prefix, aid, seq_nr)",
        "CREATE TABLE "
            + prefix
            + "_head ("
            + key
            + ", type_name STRING(1024) NOT NULL, seq_nr INT64 NOT NULL, event_occurred_at_ns INT64"
            + " NOT NULL, event_manifest STRING(MAX) NOT NULL, event_payload BYTES(MAX) NOT NULL)"
            + " PRIMARY KEY (hash_prefix, aid)",
        "CREATE TABLE "
            + prefix
            + "_snapshot_current ("
            + key
            + ", seq_nr INT64 NOT NULL, manifest STRING(MAX) NOT NULL, payload BYTES(MAX) NOT NULL)"
            + " PRIMARY KEY (hash_prefix, aid)",
        "CREATE TABLE "
            + prefix
            + "_snapshot_history ("
            + key
            + ", seq_nr INT64 NOT NULL, manifest STRING(MAX) NOT NULL, payload BYTES(MAX) NOT NULL,"
            + " expired_at TIMESTAMP) PRIMARY KEY (hash_prefix, aid, seq_nr)"
            + policy,
        "CREATE TABLE "
            + prefix
            + "_config (singleton INT64 NOT NULL, store_id STRING(36) NOT NULL, layout_version INT64"
            + " NOT NULL, keep_snapshot_count INT64, retention_mode STRING(8) NOT NULL,"
            + " ttl_grace_days INT64, change_feed_enabled BOOL NOT NULL) PRIMARY KEY (singleton)");
    if (configuration != null) {
      execute(configuration.insert(prefix));
    }
    return prefix;
  }

  /** Runs DDL against the shared database and waits for it to finish. */
  public void ddl(String... statements) {
    try {
      spanner
          .getDatabaseAdminClient()
          .updateDatabaseDdl(INSTANCE, DATABASE, Arrays.asList(statements), null)
          .get();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** Runs one DML statement in its own transaction, independent from the library. */
  public void execute(String dml) {
    client.readWriteTransaction().run(tx -> tx.executeUpdate(Statement.of(dml)));
  }

  public long count(String prefix, String table) {
    return longs("SELECT COUNT(*) FROM " + prefix + "_" + table).get(0);
  }

  public List<Long> longs(String sql) {
    List<Long> result = new ArrayList<>();
    try (ResultSet rs = client.singleUse().executeQuery(Statement.of(sql))) {
      while (rs.next()) {
        result.add(rs.getLong(0));
      }
    }
    return result;
  }

  /** The first column of the first row as a string, or null when there is no row. */
  public String string(String sql) {
    try (ResultSet rs = client.singleUse().executeQuery(Statement.of(sql))) {
      return rs.next() ? rs.getString(0) : null;
    }
  }
}
