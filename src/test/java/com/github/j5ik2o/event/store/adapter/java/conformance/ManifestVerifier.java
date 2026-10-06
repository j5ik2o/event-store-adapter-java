package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** conformance/manifest.json を実ファイルと照合する。tools/conformance/manifest.py の verify に合わせる。 */
final class ManifestVerifier {

  static final String DATA_VERSION = "1.0.0";
  private static final String MANIFEST_NAME = "manifest.json";

  private ManifestVerifier() {}

  static final class Result {
    private final boolean ok;
    private final List<String> mismatches;
    private final int fileCount;

    Result(boolean ok, List<String> mismatches, int fileCount) {
      this.ok = ok;
      this.mismatches = Collections.unmodifiableList(new ArrayList<>(mismatches));
      this.fileCount = fileCount;
    }

    boolean ok() {
      return ok;
    }

    List<String> mismatches() {
      return mismatches;
    }

    int fileCount() {
      return fileCount;
    }
  }

  static Result verify(Path root) throws IOException {
    ObjectNode expected = inventory(root);
    JsonNode actual =
        ConformanceJson.readTree(Files.readAllBytes(root.resolve(MANIFEST_NAME)), MANIFEST_NAME);
    int fileCount = expected.get("files").size();
    if (expected.equals(actual)) {
      return new Result(true, List.of(), fileCount);
    }
    List<String> mismatches = new ArrayList<>();
    if (!expected.get("format").equals(actual.get("format"))
        || !expected.get("version").equals(actual.get("version"))) {
      mismatches.add("format/version");
    }
    Map<String, String> expectedFiles = filesOf(expected);
    Map<String, String> actualFiles = filesOf(actual);
    Set<String> paths = new TreeSet<>(expectedFiles.keySet());
    paths.addAll(actualFiles.keySet());
    for (String path : paths) {
      String e = expectedFiles.get(path);
      if (e == null ? actualFiles.get(path) != null : !e.equals(actualFiles.get(path))) {
        mismatches.add(path);
      }
    }
    if (mismatches.isEmpty()) {
      mismatches.add("structure/order");
    }
    return new Result(false, mismatches, fileCount);
  }

  private static Map<String, String> filesOf(JsonNode manifest) {
    Map<String, String> files = new TreeMap<>();
    for (JsonNode entry : manifest.path("files")) {
      files.put(entry.path("path").asText(), entry.path("sha256").asText());
    }
    return files;
  }

  /** root からの相対パスを `/` 区切りで表す（manifest.py の as_posix に合わせる）。 */
  static String relativePosix(Path root, Path path) {
    StringBuilder relative = new StringBuilder();
    for (Path element : root.relativize(path)) {
      if (relative.length() > 0) {
        relative.append('/');
      }
      relative.append(element.toString());
    }
    return relative.toString();
  }

  private static ObjectNode inventory(Path root) throws IOException {
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(root)) {
      paths = walk.filter(p -> !p.equals(root)).collect(Collectors.toList());
    }
    Map<String, Path> files = new TreeMap<>();
    for (Path path : paths) {
      if (Files.isSymbolicLink(path)) {
        throw new IOException("シンボリックリンクは配布できない: " + path);
      }
      if (Files.isRegularFile(path)) {
        String relative = relativePosix(root, path);
        if (!relative.equals(MANIFEST_NAME)) {
          files.put(relative, path);
        }
      }
    }
    JsonNodeFactory factory = JsonNodeFactory.instance;
    ObjectNode manifest = factory.objectNode();
    manifest.put("format", "manifest");
    manifest.put("version", DATA_VERSION);
    ArrayNode array = manifest.putArray("files");
    for (Map.Entry<String, Path> file : files.entrySet()) {
      ObjectNode entry = array.addObject();
      entry.put("path", file.getKey());
      entry.put("sha256", sha256(Files.readAllBytes(file.getValue())));
    }
    return manifest;
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
