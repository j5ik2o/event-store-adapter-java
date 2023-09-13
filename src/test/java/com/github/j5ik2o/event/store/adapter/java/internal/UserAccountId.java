package com.github.j5ik2o.event.store.adapter.java.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.j5ik2o.event.store.adapter.java.AggregateId;
import javax.annotation.Nonnull;

public final class UserAccountId implements AggregateId {
  @Nonnull private final String typeName;
  @Nonnull private final String value;

  public UserAccountId(@Nonnull @JsonProperty("value") String value) {
    typeName = "user-account";
    this.value = value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    UserAccountId that = (UserAccountId) o;

    if (!typeName.equals(that.typeName)) return false;
    return value.equals(that.value);
  }

  @Override
  public int hashCode() {
    int result = typeName.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }

  @Nonnull
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Override
  public String getTypeName() {
    return typeName;
  }

  @Nonnull
  @Override
  public String getValue() {
    return value;
  }

  @Nonnull
  @Override
  public String asString() {
    return String.format("%s-%s", getTypeName(), getValue());
  }
}
