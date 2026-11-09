package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the five tables and the configuration row. It never creates anything. */
final class SpannerLayout {
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern POLICY =
      Pattern.compile(
          "OLDER_THAN\\(\\s*expired_at\\s*,\\s*INTERVAL\\s+(\\d+)\\s+DAY\\s*\\)",
          Pattern.CASE_INSENSITIVE);

  private SpannerLayout() {}

  private static Map<String, String> columns(String... definitions) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String definition : definitions) {
      int space = definition.indexOf(' ');
      result.put(definition.substring(0, space), definition.substring(space + 1));
    }
    return result;
  }

  private static Map<String, String> keyColumns() {
    return columns("hash_prefix BYTES(8) NOT NULL", "aid STRING(1024) NOT NULL");
  }

  private static Map<String, String> with(Map<String, String> base, String... more) {
    Map<String, String> result = new LinkedHashMap<>(base);
    result.putAll(columns(more));
    return result;
  }

  static void verify(DatabaseClient client, SpannerStorageConfig config) {
    if (client.getDialect() != Dialect.GOOGLE_STANDARD_SQL) {
      throw new ConfigurationException("spanner database must use the GoogleSQL dialect");
    }
    SpannerTableNames names = config.tableNames;
    Map<String, Map<String, String>> expectedColumns = new LinkedHashMap<>();
    expectedColumns.put(
        names.journal,
        with(
            keyColumns(),
            "seq_nr INT64 NOT NULL",
            "occurred_at_ns INT64 NOT NULL",
            "manifest STRING(MAX) NOT NULL",
            "payload BYTES(MAX) NOT NULL"));
    expectedColumns.put(
        names.head,
        with(
            keyColumns(),
            "type_name STRING(1024) NOT NULL",
            "seq_nr INT64 NOT NULL",
            "event_occurred_at_ns INT64 NOT NULL",
            "event_manifest STRING(MAX) NOT NULL",
            "event_payload BYTES(MAX) NOT NULL"));
    expectedColumns.put(
        names.snapshotCurrent,
        with(
            keyColumns(),
            "seq_nr INT64 NOT NULL",
            "manifest STRING(MAX) NOT NULL",
            "payload BYTES(MAX) NOT NULL"));
    expectedColumns.put(
        names.snapshotHistory,
        with(
            keyColumns(),
            "seq_nr INT64 NOT NULL",
            "manifest STRING(MAX) NOT NULL",
            "payload BYTES(MAX) NOT NULL",
            "expired_at TIMESTAMP"));
    expectedColumns.put(
        names.config,
        columns(
            "singleton INT64 NOT NULL",
            "store_id STRING(36) NOT NULL",
            "layout_version INT64 NOT NULL",
            "keep_snapshot_count INT64",
            "retention_mode STRING(8) NOT NULL",
            "ttl_grace_days INT64",
            "change_feed_enabled BOOL NOT NULL"));
    Map<String, List<String>> expectedKeys = new HashMap<>();
    expectedKeys.put(names.journal, Arrays.asList("hash_prefix", "aid", "seq_nr"));
    expectedKeys.put(names.head, Arrays.asList("hash_prefix", "aid"));
    expectedKeys.put(names.snapshotCurrent, Arrays.asList("hash_prefix", "aid"));
    expectedKeys.put(names.snapshotHistory, Arrays.asList("hash_prefix", "aid", "seq_nr"));
    expectedKeys.put(names.config, Arrays.asList("singleton"));
    String tableList = "'" + String.join("', '", expectedColumns.keySet()) + "'";

    try (ReadOnlyTransaction tx = client.readOnlyTransaction(TimestampBound.strong())) {
      Map<String, Map<String, String>> actualColumns = new HashMap<>();
      try (ResultSet rs =
          tx.executeQuery(
              Statement.of(
                  "SELECT TABLE_NAME, COLUMN_NAME, SPANNER_TYPE, IS_NULLABLE"
                      + " FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ''"
                      + " AND TABLE_NAME IN ("
                      + tableList
                      + ")"))) {
        while (rs.next()) {
          actualColumns
              .computeIfAbsent(rs.getString(0), k -> new HashMap<>())
              .put(
                  rs.getString(1),
                  rs.getString(2) + ("NO".equals(rs.getString(3)) ? " NOT NULL" : ""));
        }
      }
      Map<String, List<String>> actualKeys = new HashMap<>();
      try (ResultSet rs =
          tx.executeQuery(
              Statement.of(
                  "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                      + " WHERE TABLE_SCHEMA = '' AND INDEX_NAME = 'PRIMARY_KEY'"
                      + " AND TABLE_NAME IN ("
                      + tableList
                      + ") AND ORDINAL_POSITION IS NOT NULL"
                      + " ORDER BY TABLE_NAME, ORDINAL_POSITION"))) {
        while (rs.next()) {
          actualKeys.computeIfAbsent(rs.getString(0), k -> new ArrayList<>()).add(rs.getString(1));
        }
      }
      String policyExpression = null;
      boolean historyTableFound = false;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.of(
                  "SELECT ROW_DELETION_POLICY_EXPRESSION FROM INFORMATION_SCHEMA.TABLES"
                      + " WHERE TABLE_SCHEMA = '' AND TABLE_NAME = '"
                      + names.snapshotHistory
                      + "'"))) {
        if (rs.next()) {
          historyTableFound = true;
          policyExpression = rs.isNull(0) ? null : rs.getString(0);
        }
      }
      for (Map.Entry<String, Map<String, String>> table : expectedColumns.entrySet()) {
        Map<String, String> actual = actualColumns.get(table.getKey());
        if (actual == null) {
          throw new ConfigurationException("spanner table is missing: " + table.getKey());
        }
        if (!actual.equals(table.getValue())) {
          throw new ConfigurationException("spanner table columns do not match: " + table.getKey());
        }
        if (!expectedKeys.get(table.getKey()).equals(actualKeys.get(table.getKey()))) {
          throw new ConfigurationException(
              "spanner table primary key does not match: " + table.getKey());
        }
      }
      if (!historyTableFound) {
        throw new ConfigurationException("spanner table is missing: " + names.snapshotHistory);
      }
      verifyConfiguration(tx, config, policyExpression);
    }
  }

  private static void verifyConfiguration(
      ReadOnlyTransaction tx, SpannerStorageConfig config, String policyExpression) {
    SpannerRetentionPolicy policy = config.retentionPolicy();
    int rows = 0;
    try (ResultSet rs =
        tx.executeQuery(
            Statement.of(
                "SELECT singleton, store_id, layout_version, keep_snapshot_count, retention_mode,"
                    + " ttl_grace_days, change_feed_enabled FROM "
                    + config.tableNames.config))) {
      while (rs.next()) {
        rows++;
        if (rows > 1) {
          break;
        }
        if (rs.getLong(0) != 1) {
          throw new ConfigurationException("spanner configuration row must have singleton = 1");
        }
        if (!UUID.matcher(rs.getString(1)).matches()) {
          throw new ConfigurationException("spanner store_id must be a lowercase UUID");
        }
        if (rs.getLong(2) != 1) {
          throw new ConfigurationException("spanner layout_version must be 1");
        }
        OptionalInt keep = policy.keepCount();
        boolean rowHasKeep = !rs.isNull(3);
        if (rowHasKeep != keep.isPresent() || (rowHasKeep && rs.getLong(3) != keep.getAsInt())) {
          throw new ConfigurationException(
              "spanner keep_snapshot_count does not match the client retention");
        }
        boolean ttl = policy.mode().orElse(null) == RetentionMode.TTL;
        if (!rs.getString(4).equals(ttl ? "ttl" : "delete")) {
          throw new ConfigurationException(
              "spanner retention_mode does not match the client retention");
        }
        boolean rowHasGrace = !rs.isNull(5);
        if (ttl) {
          if (!rowHasGrace || rs.getLong(5) != policy.graceDays().getAsLong()) {
            throw new ConfigurationException(
                "spanner ttl_grace_days does not match the client retention");
          }
        } else if (rowHasGrace) {
          throw new ConfigurationException("spanner ttl_grace_days must be NULL");
        }
        if (rs.getBoolean(6)) {
          throw new ConfigurationException("spanner change_feed_enabled must be FALSE");
        }
      }
    }
    if (rows != 1) {
      throw new ConfigurationException("spanner configuration table must hold exactly one row");
    }
    boolean ttl = policy.mode().orElse(null) == RetentionMode.TTL;
    if (ttl) {
      Matcher matcher = policyExpression == null ? null : POLICY.matcher(policyExpression);
      if (matcher == null
          || !matcher.matches()
          || Long.parseLong(matcher.group(1)) != policy.graceDays().getAsLong()) {
        throw new ConfigurationException(
            "spanner row deletion policy does not match the ttl grace days");
      }
    } else if (policyExpression != null) {
      throw new ConfigurationException(
          "spanner row deletion policy must be absent unless the retention mode is ttl");
    }
  }
}
