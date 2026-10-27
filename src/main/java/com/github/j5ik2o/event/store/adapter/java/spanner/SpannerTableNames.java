package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import java.util.regex.Pattern;

/** The five table names derived from one prefix. / 1 つの接頭辞から導く 5 つの表の名前。 */
final class SpannerTableNames {
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final int MAX_IDENTIFIER_LENGTH = 128;

  final String journal;
  final String head;
  final String snapshotCurrent;
  final String snapshotHistory;
  final String config;

  private SpannerTableNames(String prefix) {
    this.journal = prefix + "_journal";
    this.head = prefix + "_head";
    this.snapshotCurrent = prefix + "_snapshot_current";
    this.snapshotHistory = prefix + "_snapshot_history";
    this.config = prefix + "_config";
  }

  static SpannerTableNames of(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      throw new ConfigurationException("table prefix must be a non-empty string");
    }
    SpannerTableNames names = new SpannerTableNames(prefix);
    for (String name :
        new String[] {
          names.journal, names.head, names.snapshotCurrent, names.snapshotHistory, names.config
        }) {
      if (name.length() > MAX_IDENTIFIER_LENGTH || !IDENTIFIER.matcher(name).matches()) {
        throw new ConfigurationException(
            "table prefix must form SQL identifiers of at most 128 characters");
      }
    }
    return names;
  }
}
