package com.github.j5ik2o.event.store.adapter.java.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nonnull;

/** JSON payload serializer. / JSON の payload シリアライザ。 */
public final class JsonPayloadSerializer {

  private JsonPayloadSerializer() {}

  /**
   * Creates a serializer with the default mapper. / 既定の ObjectMapper でシリアライザを作ります。
   *
   * @param type type / 型
   * @param <T> value type / 値の型
   * @return serializer / シリアライザ
   */
  @Nonnull
  public static <T> PayloadSerializer<T> of(@Nonnull Class<T> type) {
    return of(new ObjectMapper().findAndRegisterModules(), type);
  }

  /**
   * Creates a serializer with a mapper and a class. / ObjectMapper と Class でシリアライザを作ります。
   *
   * @param mapper mapper / ObjectMapper
   * @param type type / 型
   * @param <T> value type / 値の型
   * @return serializer / シリアライザ
   */
  @Nonnull
  public static <T> PayloadSerializer<T> of(@Nonnull ObjectMapper mapper, @Nonnull Class<T> type) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(type, "type");
    return new Impl<>(mapper, mapper.readerFor(type));
  }

  /**
   * Creates a serializer with a mapper and a type reference. / ObjectMapper と TypeReference
   * でシリアライザを作ります。
   *
   * @param mapper mapper / ObjectMapper
   * @param type type reference / 型参照
   * @param <T> value type / 値の型
   * @return serializer / シリアライザ
   */
  @Nonnull
  public static <T> PayloadSerializer<T> of(
      @Nonnull ObjectMapper mapper, @Nonnull TypeReference<T> type) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(type, "type");
    return new Impl<>(mapper, mapper.readerFor(type));
  }

  private static final class Impl<T> implements PayloadSerializer<T> {
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    private Impl(ObjectMapper mapper, ObjectReader reader) {
      this.mapper = mapper;
      this.reader = reader;
    }

    @Override
    @Nonnull
    public byte[] serialize(@Nonnull T value) {
      try {
        return mapper.writeValueAsBytes(value);
      } catch (JsonProcessingException e) {
        throw new SerializationException("failed to serialize payload", e);
      }
    }

    @Override
    @Nonnull
    public T deserialize(@Nonnull byte[] bytes) {
      try {
        return reader.readValue(bytes);
      } catch (IOException e) {
        throw new SerializationException("failed to deserialize payload", e);
      }
    }
  }
}
