package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpResponse;

final class HttpReply {
  private final int status;
  private final byte[] body;

  HttpReply(int status, JsonNode body) {
    this.status = status;
    this.body = DynamoDbJson.bytes(body);
  }

  byte[] body() {
    return body.clone();
  }

  SdkHttpResponse headers() {
    return SdkHttpResponse.builder()
        .statusCode(status)
        .putHeader("Content-Type", "application/x-amz-json-1.0")
        .putHeader("Content-Length", Integer.toString(body.length))
        .build();
  }

  HttpExecuteResponse sync() {
    return HttpExecuteResponse.builder()
        .response(headers())
        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body())))
        .build();
  }
}
