package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.google.api.gax.core.CredentialsProvider;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import com.google.cloud.bigtable.admin.v2.models.GCRules;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.cloud.bigtable.data.v2.models.RowMutation;
import com.google.cloud.bigtable.data.v2.stub.metrics.NoopMetricsProvider;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Cloud Bigtable emulator per JVM. The two tables, their column families and the two
 * configuration rows are created here, outside the library, exactly as the schema document tells an
 * operator to do (BT-D1). No real credentials and no real service are used.
 */
public final class BigtableEmulator {
  public static final String IMAGE =
      "gcr.io/google.com/cloudsdktool/google-cloud-cli@sha256:8d8573471c489a409f03891d2c24d177fa531276184f7002b9a2d853d1b3ed54";
  public static final String PROJECT = "test-project";
  public static final String INSTANCE = "test-instance";
  public static final String STORE_ID = "5f0c8b8e-3c1a-4d7e-9a4b-2f6d1c0e7a11";
  private static final int PORT = 8086;

  private static BigtableEmulator shared;

  private final AtomicInteger tables = new AtomicInteger();
  private final GenericContainer<?> container;
  private final BigtableDataClient data;
  private final BigtableTableAdminClient admin;

  private BigtableEmulator(
      GenericContainer<?> container, BigtableDataClient data, BigtableTableAdminClient admin) {
    this.container = container;
    this.data = data;
    this.admin = admin;
  }

  /** Starts the emulator on first use and stops it when the JVM exits. */
  public static synchronized BigtableEmulator shared() {
    if (shared == null) {
      shared = start();
      Runtime.getRuntime().addShutdownHook(new Thread(shared::stop));
    }
    return shared;
  }

  private static BigtableEmulator start() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withCommand(
                "gcloud", "beta", "emulators", "bigtable", "start", "--host-port=0.0.0.0:" + PORT);
    container.start();
    BigtableDataClient data = null;
    BigtableTableAdminClient admin = null;
    try {
      String host = container.getHost();
      int port = container.getMappedPort(PORT);
      data = newDataClient(host, port, null);
      admin = newAdminClient(host, port, null);
      waitUntilListTablesSucceeds(admin);
      return new BigtableEmulator(container, data, admin);
    } catch (Exception failure) {
      if (data != null) data.close();
      if (admin != null) admin.close();
      container.stop();
      throw new IllegalStateException("failed to start the Bigtable emulator", failure);
    }
  }

  private static void waitUntilListTablesSucceeds(BigtableTableAdminClient admin)
      throws InterruptedException {
    RuntimeException last = null;
    for (int attempt = 0; attempt < 60; attempt++) {
      try {
        admin.listTables();
        return;
      } catch (RuntimeException failure) {
        last = failure;
        Thread.sleep(500);
      }
    }
    throw new IllegalStateException("the emulator never answered ListTables", last);
  }

  /** A client for the emulator. A non-null provider replaces the default no-credentials path. */
  public static BigtableDataClient newDataClient(
      String host, int port, CredentialsProvider credentials) {
    try {
      BigtableDataSettings.Builder builder =
          BigtableDataSettings.newBuilderForEmulator(host, port)
              .setProjectId(PROJECT)
              .setInstanceId(INSTANCE)
              .setMetricsProvider(NoopMetricsProvider.INSTANCE);
      if (credentials != null) {
        builder.stubSettings().setCredentialsProvider(credentials);
      }
      return BigtableDataClient.create(builder.build());
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  public static BigtableTableAdminClient newAdminClient(
      String host, int port, CredentialsProvider credentials) {
    try {
      BigtableTableAdminSettings.Builder builder =
          BigtableTableAdminSettings.newBuilderForEmulator(host, port)
              .setProjectId(PROJECT)
              .setInstanceId(INSTANCE);
      if (credentials != null) {
        builder.stubSettings().setCredentialsProvider(credentials);
      }
      return BigtableTableAdminClient.create(builder.build());
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private void stop() {
    data.close();
    admin.close();
    container.stop();
  }

  /** The clients handed to the library. The library never owns or closes them. */
  public BigtableDataClient data() {
    return data;
  }

  public BigtableTableAdminClient admin() {
    return admin;
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(PORT);
  }

  /** The identifiers of one journal and head table pair. */
  public static final class Tables {
    public final String journal;
    public final String head;

    Tables(String journal, String head) {
      this.journal = journal;
      this.head = head;
    }
  }

  /** Names a pair without creating anything, for tests that point at a table that is missing. */
  public static Tables tablesNamed(String journal, String head) {
    return new Tables(journal, head);
  }

  /** The configuration JSON an operator writes. A null keep count is stored as JSON null. */
  public static String configJson(String keepSnapshotCount, int envelopeLimitBytes) {
    return "{\"store_id\":\""
        + STORE_ID
        + "\",\"layout_version\":1,\"keep_snapshot_count\":"
        + (keepSnapshotCount == null ? "null" : "\"" + keepSnapshotCount + "\"")
        + ",\"retention_mode\":\"delete\",\"envelope_limit_bytes\":"
        + envelopeLimitBytes
        + "}";
  }

  /**
   * Creates the two tables with the column families of BT-2 (data families keep at most one
   * version, the configuration family has no garbage collection) and no configuration row.
   */
  public Tables createTables() {
    int n = tables.incrementAndGet();
    Tables created = new Tables("esa_journal_" + n, "esa_head_" + n);
    admin.createTable(
        CreateTableRequest.of(created.journal)
            .addFamily("event", GCRules.GCRULES.maxVersions(1))
            .addFamily("history", GCRules.GCRULES.maxVersions(1))
            .addFamily("config"));
    admin.createTable(
        CreateTableRequest.of(created.head)
            .addFamily("head", GCRules.GCRULES.maxVersions(1))
            .addFamily("snapshot", GCRules.GCRULES.maxVersions(1))
            .addFamily("config"));
    return created;
  }

  /** Both tables and the same configuration row in each. */
  public Tables createStore(String keepSnapshotCount, int envelopeLimitBytes) {
    Tables created = createTables();
    String json = configJson(keepSnapshotCount, envelopeLimitBytes);
    putConfig(created.journal, json);
    putConfig(created.head, json);
    return created;
  }

  public Tables createStore(String keepSnapshotCount) {
    return createStore(keepSnapshotCount, 1_048_576);
  }

  /** Writes the configuration row at timestamp 0. */
  public void putConfig(String table, String json) {
    put(
        table,
        BigtableKey.CONFIG_ROW_KEY,
        "config",
        "metadata",
        ByteString.copyFrom(json, StandardCharsets.UTF_8));
  }

  /** A raw cell write at timestamp 0, independent from the library. */
  public void put(String table, ByteString key, String family, String qualifier, ByteString value) {
    data.mutateRow(
        RowMutation.create(table, key)
            .setCell(family, ByteString.copyFromUtf8(qualifier), 0L, value));
  }

  /** A raw delete of a whole row, independent from the library. */
  public void deleteRow(String table, ByteString key) {
    data.mutateRow(RowMutation.create(table, key).deleteRow());
  }

  /** A raw read of one row, or null when the row does not exist. */
  public Row row(String table, ByteString key) {
    return data.readRow(table, key);
  }

  /** Every row of a table in key order, including the configuration row. */
  public List<Row> rows(String table) {
    List<Row> result = new ArrayList<>();
    data.readRows(Query.create(table)).forEach(result::add);
    return result;
  }

  /** The newest cell value of a column as a string, or null when the column has no cell. */
  public String text(String table, ByteString key, String family, String qualifier) {
    Row row = row(table, key);
    if (row == null) return null;
    List<RowCell> cells = row.getCells(family, ByteString.copyFromUtf8(qualifier));
    return cells.isEmpty() ? null : cells.get(0).getValue().toStringUtf8();
  }

  /** The raw bytes of a column's newest cell, or null. */
  public ByteString bytes(String table, ByteString key, String family, String qualifier) {
    Row row = row(table, key);
    if (row == null) return null;
    List<RowCell> cells = row.getCells(family, ByteString.copyFromUtf8(qualifier));
    return cells.isEmpty() ? null : cells.get(0).getValue();
  }

  /** How many cells (versions) a column holds. */
  public int cellCount(String table, ByteString key, String family, String qualifier) {
    Row row = row(table, key);
    return row == null ? 0 : row.getCells(family, ByteString.copyFromUtf8(qualifier)).size();
  }
}
