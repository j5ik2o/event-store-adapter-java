package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/** A contract violation. / 契約違反。 */
public final class ContractViolationException extends EventStoreException {

  private final String rule;
  private final OptionalLong seqNr;

  /**
   * Creates an exception. / 例外を作ります。
   *
   * @param rule rule number / 規則番号
   * @param seqNr related seq_nr, if any / 関係する seq_nr（あるときだけ）
   * @param detail detail / 詳細
   */
  public ContractViolationException(
      @Nonnull String rule, @Nonnull OptionalLong seqNr, @Nonnull String detail) {
    super(buildMessage(rule, seqNr, detail), null);
    this.rule = rule;
    this.seqNr = seqNr;
  }

  private static String buildMessage(String rule, OptionalLong seqNr, String detail) {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(seqNr, "seqNr");
    Objects.requireNonNull(detail, "detail");
    String base = rule + ": " + detail;
    return seqNr.isPresent() ? base + " (seq_nr=" + seqNr.getAsLong() + ")" : base;
  }

  /**
   * Returns the rule number. / 規則番号を返します。
   *
   * @return rule / 規則番号
   */
  @Nonnull
  public String rule() {
    return rule;
  }

  /**
   * Returns the related seq_nr. / 関係する seq_nr を返します。
   *
   * @return seq_nr, empty if none / seq_nr。なければ空
   */
  @Nonnull
  public OptionalLong seqNr() {
    return seqNr;
  }

  @Override
  @Nonnull
  public ErrorCategory category() {
    return ErrorCategory.CONTRACT_VIOLATION;
  }
}
