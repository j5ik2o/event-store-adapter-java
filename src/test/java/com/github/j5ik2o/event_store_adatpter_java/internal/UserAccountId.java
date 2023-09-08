package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;

public class UserAccountId implements AggregateId {
  private final String typeName;
  private final String value;

  public UserAccountId(@JsonProperty("value") String value) {
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

  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Override
  public String getTypeName() {
    return typeName;
  }

  @Override
  public String getValue() {
    return value;
  }

  @Override
  public String asString() {
    return String.format("%s-%s", getTypeName(), getValue());
  }
}
