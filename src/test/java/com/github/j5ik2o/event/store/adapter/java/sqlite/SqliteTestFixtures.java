package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for SQLite tests. Every test uses a real file in a temporary directory. */
final class SqliteTestFixtures {
  static final AggregateId AID = AggregateId.of("Order", "1");

  private SqliteTestFixtures() {}

  static SqliteStorageConfig settings(RetentionPolicy policy) {
    return SqliteStorageConfig.builder()
        .retentionPolicy(policy)
        .lockWait(Duration.ofSeconds(10))
        .build();
  }

  static SqliteStorageConfig settings(RetentionPolicy policy, Duration lockWait) {
    return SqliteStorageConfig.builder().retentionPolicy(policy).lockWait(lockWait).build();
  }

  static Path newFile(Path directory) {
    return directory.resolve("store.db");
  }

  /** Initializes a new file and returns its path. */
  static Path initialized(Path directory, RetentionPolicy policy) {
    Path file = newFile(directory);
    SqliteStorage.initialize(file, settings(policy));
    return file;
  }

  static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  static EventStore<String, String> store(SqliteStorage storage) {
    return SqliteEventStore.create(storage, config());
  }

  static EventEnvelope<String> event(long seqNr) {
    return event(AID, seqNr, Instant.EPOCH);
  }

  static EventEnvelope<String> event(AggregateId id, long seqNr) {
    return event(id, seqNr, Instant.EPOCH);
  }

  static EventEnvelope<String> event(AggregateId id, long seqNr, Instant occurredAt) {
    return EventEnvelope.<String>builder()
        .aggregateId(id)
        .seqNr(seqNr)
        .occurredAt(occurredAt)
        .manifest("event")
        .payload("event-" + seqNr)
        .build();
  }

  static SnapshotEnvelope<String> snapshot(long seqNr) {
    return SnapshotEnvelope.<String>builder()
        .seqNr(seqNr)
        .manifest("snapshot")
        .aggregate("snapshot-" + seqNr)
        .build();
  }

  /** Opens a plain JDBC connection to the file, independent from the library. */
  static Connection raw(Path file) {
    try {
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout = 0");
      }
      return connection;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static long count(Path file, String table) {
    try (Connection c = raw(file);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<Long> longs(Path file, String sql, Object... params) {
    try (Connection c = raw(file);
        PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      List<Long> result = new ArrayList<>();
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getLong(1));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String string(Path file, String sql, Object... params) {
    try (Connection c = raw(file);
        PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void execute(Path file, String sql) {
    try (Connection c = raw(file);
        Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
