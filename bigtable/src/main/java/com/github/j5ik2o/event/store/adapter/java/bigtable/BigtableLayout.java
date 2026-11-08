package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.models.ColumnFamily;
import com.google.cloud.bigtable.admin.v2.models.GCRules.VersionRule;
import com.google.cloud.bigtable.admin.v2.models.Table;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Verifies the two tables and their configuration rows (BT-D1, BT-2, BT-16) and writes the two
 * configuration rows at initialization. It never creates a table. / 2
 * つの表と設定行を照合し（BT-D1、BT-2、BT-16）、初期化では 設定行 2 つだけを書きます。表は作りません。
 */
final class BigtableLayout {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)
          .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
  private static final Pattern LOWERCASE_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final List<String> JOURNAL_FAMILIES =
      Arrays.asList(BigtableRows.EVENT, BigtableRows.HISTORY, BigtableRows.CONFIG);
  private static final List<String> HEAD_FAMILIES =
      Arrays.asList(BigtableRows.HEAD, BigtableRows.SNAPSHOT, BigtableRows.CONFIG);

  private BigtableLayout() {}

  /** A parsed configuration row. */
  private static final class Recorded {
    final String storeId;
    final long layoutVersion;
    final Long keepSnapshotCount;
    final String retentionMode;
    final long envelopeLimitBytes;

    Recorded(
        String storeId,
        long layoutVersion,
        Long keepSnapshotCount,
        String retentionMode,
        long envelopeLimitBytes) {
      this.storeId = storeId;
      this.layoutVersion = layoutVersion;
      this.keepSnapshotCount = keepSnapshotCount;
      this.retentionMode = retentionMode;
      this.envelopeLimitBytes = envelopeLimitBytes;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Recorded)) return false;
      Recorded r = (Recorded) o;
      return storeId.equals(r.storeId)
          && layoutVersion == r.layoutVersion
          && Objects.equals(keepSnapshotCount, r.keepSnapshotCount)
          && retentionMode.equals(r.retentionMode)
          && envelopeLimitBytes == r.envelopeLimitBytes;
    }

    @Override
    public int hashCode() {
      return Objects.hash(storeId, layoutVersion, keepSnapshotCount, retentionMode);
    }
  }

  static void verify(
      BigtableDataClient data, BigtableTableAdminClient admin, BigtableStorageConfig config) {
    verifyColumnFamilies(admin, config.journalTableId(), JOURNAL_FAMILIES);
    verifyColumnFamilies(admin, config.headTableId(), HEAD_FAMILIES);
    Recorded journal = parse(readConfiguration(data, config.journalTableId()));
    Recorded head = parse(readConfiguration(data, config.headTableId()));
    if (!journal.equals(head)) {
      throw new ConfigurationException("the configuration rows of journal and head differ");
    }
    if (journal.layoutVersion != 1 || !"delete".equals(journal.retentionMode)) {
      throw new ConfigurationException("the configuration row has an unsupported layout");
    }
    if (!LOWERCASE_UUID.matcher(journal.storeId).matches()) {
      throw new ConfigurationException("the store_id of the configuration row is not a UUID");
    }
    Long expectedKeep =
        config.retentionPolicy().keepCount().isPresent()
            ? Long.valueOf(config.retentionPolicy().keepCount().getAsInt())
            : null;
    if (!Objects.equals(journal.keepSnapshotCount, expectedKeep)) {
      throw new ConfigurationException(
          "the retention count of the client differs from the configuration row");
    }
    if (journal.envelopeLimitBytes != config.envelopeLimitBytes()) {
      throw new ConfigurationException(
          "the envelope limit of the client differs from the configuration row");
    }
  }

  /** Writes the same configuration row into both tables, only when neither has one. */
  static void initialize(BigtableDataClient data, BigtableStorageConfig config) {
    String[] tables = {config.journalTableId(), config.headTableId()};
    for (String table : tables) {
      if (existingRow(data, table) != null) {
        throw new ConfigurationException("the configuration row already exists: " + table);
      }
    }
    Map<String, Object> json = new java.util.LinkedHashMap<>();
    json.put("store_id", UUID.randomUUID().toString());
    json.put("layout_version", 1);
    json.put(
        "keep_snapshot_count",
        config.retentionPolicy().keepCount().isPresent()
            ? Integer.toString(config.retentionPolicy().keepCount().getAsInt())
            : null);
    json.put("retention_mode", "delete");
    json.put("envelope_limit_bytes", config.envelopeLimitBytes());
    ByteString value;
    try {
      value = ByteString.copyFrom(MAPPER.writeValueAsString(json), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new StorageException("failed to write the configuration row", failure);
    }
    for (String table : tables) {
      boolean existed =
          createConfiguration(
              data,
              table,
              BigtableRows.setCell(
                  Mutation.create(), BigtableRows.CONFIG, BigtableRows.METADATA, value));
      if (existed) {
        throw new ConfigurationException("the configuration row already exists: " + table);
      }
    }
  }

  private static boolean createConfiguration(
      BigtableDataClient data, String table, Mutation creation) {
    try {
      return BigtableRows.checkAndMutate(
          data,
          table,
          BigtableKey.CONFIG_ROW_KEY,
          BigtableRows.latestCellExists(BigtableRows.CONFIG, BigtableRows.METADATA),
          null,
          creation);
    } catch (StorageException failure) {
      if (failure.getCause() instanceof NotFoundException) {
        throw new ConfigurationException("the table does not exist: " + table, failure);
      }
      throw failure;
    }
  }

  private static Row existingRow(BigtableDataClient data, String table) {
    try {
      return BigtableRows.readRow(
          data,
          table,
          BigtableKey.CONFIG_ROW_KEY,
          BigtableRows.latestCellOf(BigtableRows.CONFIG, BigtableRows.METADATA));
    } catch (StorageException failure) {
      if (failure.getCause() instanceof NotFoundException) {
        throw new ConfigurationException("the table does not exist: " + table, failure);
      }
      throw failure;
    }
  }

  private static void verifyColumnFamilies(
      BigtableTableAdminClient admin, String tableId, List<String> families) {
    Table table;
    try {
      table = admin.getTable(tableId);
    } catch (NotFoundException failure) {
      throw new ConfigurationException("the table does not exist: " + tableId, failure);
    } catch (ApiException failure) {
      throw new StorageException("failed to read the table: " + tableId, failure);
    }
    Map<String, ColumnFamily> present = new HashMap<>();
    for (ColumnFamily family : table.getColumnFamilies()) {
      present.put(family.getId(), family);
    }
    for (String name : families) {
      ColumnFamily family = present.get(name);
      if (family == null) {
        throw new ConfigurationException(
            "the column family does not exist: " + tableId + ":" + name);
      }
      if (name.equals(BigtableRows.CONFIG)) {
        continue;
      }
      // BT-2: the data families keep at most one version and have no age-based GC.
      if (!(family.getGCRule() instanceof VersionRule)
          || ((VersionRule) family.getGCRule()).getMaxVersions() != 1) {
        throw new ConfigurationException(
            "the data column family must keep exactly one version: " + tableId + ":" + name);
      }
    }
  }

  private static ByteString readConfiguration(BigtableDataClient data, String table) {
    Row row = existingRow(data, table);
    ByteString value =
        row == null ? null : BigtableRows.value(row, BigtableRows.CONFIG, BigtableRows.METADATA);
    if (value == null) {
      throw new ConfigurationException("the configuration row is missing: " + table);
    }
    return value;
  }

  private static Recorded parse(ByteString bytes) {
    JsonNode root;
    try {
      root = MAPPER.readTree(bytes.toByteArray());
    } catch (IOException failure) {
      throw invalid(failure);
    }
    if (root == null || !root.isObject()) {
      throw invalid(null);
    }
    ObjectNode object = (ObjectNode) root;
    String storeId = text(object, "store_id");
    long layoutVersion = integer(object, "layout_version");
    JsonNode keep = object.get("keep_snapshot_count");
    Long keepCount;
    if (keep == null) {
      throw invalid(null);
    } else if (keep.isNull()) {
      keepCount = null;
    } else if (keep.isTextual()) {
      keepCount = decimalPositive(keep.asText());
    } else {
      throw invalid(null);
    }
    String mode = text(object, "retention_mode");
    long limit = integer(object, "envelope_limit_bytes");
    return new Recorded(storeId, layoutVersion, keepCount, mode, limit);
  }

  private static String text(ObjectNode object, String name) {
    JsonNode node = object.get(name);
    if (node == null || !node.isTextual()) {
      throw invalid(null);
    }
    return node.asText();
  }

  private static long integer(ObjectNode object, String name) {
    JsonNode node = object.get(name);
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid(null);
    }
    return node.asLong();
  }

  /** A positive decimal integer without a sign, leading zeros or an exponent. */
  private static Long decimalPositive(String text) {
    if (text.isEmpty() || text.charAt(0) == '0') {
      throw invalid(null);
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        throw invalid(null);
      }
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException failure) {
      throw invalid(failure);
    }
  }

  private static ConfigurationException invalid(Throwable cause) {
    return new ConfigurationException("the configuration row is not valid", cause);
  }
}
