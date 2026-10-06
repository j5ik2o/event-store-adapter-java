package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestVerifierTest {

  @Test
  void realDataPassesAndCoversTwentyTwoFiles() throws IOException {
    ManifestVerifier.Result result = ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT);

    assertTrue(result.ok(), "mismatches: " + result.mismatches());
    assertTrue(result.mismatches().isEmpty());
    assertEquals(22, result.fileCount());
  }

  @Test
  void unmodifiedCopyPasses(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);

    ManifestVerifier.Result result = ManifestVerifier.verify(copy);

    assertTrue(result.ok(), "mismatches: " + result.mismatches());
    assertTrue(result.mismatches().isEmpty());
  }

  @Test
  void tamperedCopyFailsAndReportsTheFile(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    Files.write(copy.resolve("values/aid.json"), new byte[] {' '}, StandardOpenOption.APPEND);

    ManifestVerifier.Result result = ManifestVerifier.verify(copy);

    assertFalse(result.ok());
    assertTrue(result.mismatches().contains("values/aid.json"), result.mismatches().toString());
  }
}
