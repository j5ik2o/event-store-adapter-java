package com.github.j5ik2o.event.store.adapter.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.spanner.SpannerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "googleSdkSet.google-cloud-spanner", matches = ".+")
class GoogleSdkSetTest {

  @Test
  void theTestsRunOnTheSpannerAndBigtableSdkOfTheSelectedSet() {
    assertEquals(
        System.getProperty("googleSdkSet.google-cloud-spanner"),
        SpannerOptions.class.getPackage().getImplementationVersion(),
        "Spanner SDK on the test runtime classpath");
    assertEquals(
        System.getProperty("googleSdkSet.google-cloud-bigtable"),
        BigtableDataSettings.class.getPackage().getImplementationVersion(),
        "Bigtable SDK on the test runtime classpath");
  }
}
