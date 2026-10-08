package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

final class DynamoDbTestClients {
  static final Region REGION = Region.US_EAST_1;
  static final String ACCESS_KEY = "DynamoDbLocalTest";
  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(
          AwsBasicCredentials.create(ACCESS_KEY, "DynamoDbLocalDummySecret"));

  private DynamoDbTestClients() {}

  static ClientOverrideConfiguration.Builder overrides() {
    return ClientOverrideConfiguration.builder()
        .retryStrategy(
            StandardRetryStrategy.builder().maxAttempts(1).useClientDefaults(false).build())
        .apiCallTimeout(Duration.ofSeconds(20));
  }

  static DynamoDbClient admin(URI endpoint) {
    return DynamoDbClient.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(overrides().build())
        .httpClientBuilder(Apache5HttpClient.builder())
        .build();
  }

  static NettyNioAsyncHttpClient.Builder asyncHttp() {
    return asyncHttp(null);
  }

  static NettyNioAsyncHttpClient.Builder asyncHttp(SdkEventLoopGroup borrowedEventLoop) {
    NettyNioAsyncHttpClient.Builder builder = NettyNioAsyncHttpClient.builder().maxConcurrency(8);
    return borrowedEventLoop == null
        ? builder.eventLoopGroupBuilder(SdkEventLoopGroup.builder().numberOfThreads(2))
        : builder.eventLoopGroup(borrowedEventLoop);
  }

  static DynamoDbAsyncClient adminAsync(URI endpoint, ExecutionInterceptor... interceptors) {
    return adminAsync(endpoint, null, interceptors);
  }

  static DynamoDbAsyncClient adminAsync(
      URI endpoint, SdkEventLoopGroup borrowedEventLoop, ExecutionInterceptor... interceptors) {
    return DynamoDbAsyncClient.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(
            overrides().executionInterceptors(Arrays.asList(interceptors)).build())
        .httpClientBuilder(asyncHttp(borrowedEventLoop))
        .build();
  }

  static DynamoDbClient observed(
      URI endpoint, DynamoDbRequestRecorder recorder, FaultHttpClient http) {
    return DynamoDbClient.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(overrides().addExecutionInterceptor(recorder).build())
        .httpClient(http)
        .build();
  }

  static DynamoDbAsyncClient observedAsync(
      URI endpoint,
      DynamoDbRequestRecorder recorder,
      FaultAsyncHttpClient http,
      ExecutionInterceptor... interceptors) {
    return DynamoDbAsyncClient.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(CREDENTIALS)
        .overrideConfiguration(
            overrides()
                .executionInterceptors(Arrays.asList(interceptors))
                .addExecutionInterceptor(recorder)
                .build())
        .httpClient(http)
        .build();
  }
}
