package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.time.Clock;

/** Immutable settings for three externally provisioned tables. / 外部で用意する3テーブルの不変設定。 */
public final class DynamoDbTableConfig {
  private final String journalTableName;
  private final String snapshotTableName;
  private final String headTableName;
  private final String snapshotAidIndexName;
  private final RetentionPolicy retentionPolicy;
  private final int configurationReadRetryLimit;
  private final Clock clock;

  private DynamoDbTableConfig(Builder builder) {
    journalTableName = builder.journalTableName;
    snapshotTableName = builder.snapshotTableName;
    headTableName = builder.headTableName;
    snapshotAidIndexName = builder.snapshotAidIndexName;
    retentionPolicy = builder.retentionPolicy;
    configurationReadRetryLimit = builder.configurationReadRetryLimit;
    clock = builder.clock;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String journalTableName() {
    return journalTableName;
  }

  public String snapshotTableName() {
    return snapshotTableName;
  }

  public String headTableName() {
    return headTableName;
  }

  public String snapshotAidIndexName() {
    return snapshotAidIndexName;
  }

  public RetentionPolicy retentionPolicy() {
    return retentionPolicy;
  }

  public int configurationReadRetryLimit() {
    return configurationReadRetryLimit;
  }

  public Clock clock() {
    return clock;
  }

  /** Builder. / ビルダー。 */
  public static final class Builder {
    private String journalTableName;
    private String snapshotTableName;
    private String headTableName;
    private String snapshotAidIndexName;
    private RetentionPolicy retentionPolicy = RetentionPolicy.none();
    private int configurationReadRetryLimit = 10;
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    public Builder journalTableName(String v) {
      journalTableName = v;
      return this;
    }

    public Builder snapshotTableName(String v) {
      snapshotTableName = v;
      return this;
    }

    public Builder headTableName(String v) {
      headTableName = v;
      return this;
    }

    public Builder snapshotAidIndexName(String v) {
      snapshotAidIndexName = v;
      return this;
    }

    public Builder retentionPolicy(RetentionPolicy v) {
      retentionPolicy = v;
      return this;
    }

    /** Retry count excluding the initial request. / 初回を除く再要求回数。 */
    public Builder configurationReadRetryLimit(int v) {
      configurationReadRetryLimit = v;
      return this;
    }

    public Builder clock(Clock v) {
      clock = v;
      return this;
    }

    public DynamoDbTableConfig build() {
      requiredName(journalTableName, "journalTableName");
      requiredName(snapshotTableName, "snapshotTableName");
      requiredName(headTableName, "headTableName");
      requiredName(snapshotAidIndexName, "snapshotAidIndexName");
      if (journalTableName.equals(snapshotTableName)
          || journalTableName.equals(headTableName)
          || snapshotTableName.equals(headTableName)) {
        throw new ConfigurationException("journal, snapshot and head table names must be distinct");
      }
      if (retentionPolicy == null || clock == null) {
        throw new ConfigurationException("retentionPolicy and clock are required");
      }
      if (configurationReadRetryLimit < 0) {
        throw new ConfigurationException("configurationReadRetryLimit must be non-negative");
      }
      return new DynamoDbTableConfig(this);
    }

    private static void requiredName(String value, String name) {
      if (value == null || value.isBlank()) throw new ConfigurationException(name + " is required");
    }
  }
}
