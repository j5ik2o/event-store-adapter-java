package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @Test
  void emptyManifestIsReportedAsMismatchWithoutThrowing(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    Files.write(copy.resolve("manifest.json"), new byte[0]);

    ManifestVerifier.Result result = ManifestVerifier.verify(copy);

    assertFalse(result.ok());
    assertFalse(result.mismatches().isEmpty());
    assertNull(result.manifestVersion());
    assertFalse(result.versionMatches());
  }

  @Test
  void realDataReportsTheActualManifestVersion() throws IOException {
    ManifestVerifier.Result result = ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT);

    assertEquals("1.0.0", result.manifestVersion());
    assertTrue(result.versionMatches());
  }

  @Test
  void differentManifestVersionIsReportedSeparatelyFromFormat(@TempDir Path tempDir)
      throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(
        copy.resolve("manifest.json"), "\"version\": \"1.0.0\"", "\"version\": \"9.9.9\"");

    ManifestVerifier.Result result = ManifestVerifier.verify(copy);

    assertFalse(result.ok());
    assertEquals("9.9.9", result.manifestVersion());
    assertFalse(result.versionMatches());
    assertTrue(result.mismatches().contains("version"), result.mismatches().toString());
    assertFalse(result.mismatches().contains("format"), result.mismatches().toString());
  }
}
