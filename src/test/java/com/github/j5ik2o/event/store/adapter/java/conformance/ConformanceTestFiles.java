package com.github.j5ik2o.event.store.adapter.java.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ConformanceTestFiles {

  static final Path REAL_ROOT = Paths.get("conformance");

  private ConformanceTestFiles() {}

  /** conformance/ を一時ディレクトリへそのまま写す。conformance/ 自体は書き換えない。 */
  static Path copyOfRealData(Path tempDir) throws IOException {
    Path target = tempDir.resolve("conformance");
    List<Path> sources;
    try (Stream<Path> walk = Files.walk(REAL_ROOT)) {
      sources = walk.collect(Collectors.toList());
    }
    for (Path source : sources) {
      Path dest = target.resolve(REAL_ROOT.relativize(source).toString());
      if (Files.isDirectory(source)) {
        Files.createDirectories(dest);
      } else {
        Files.copy(source, dest);
      }
    }
    return target;
  }

  static void replaceInFile(Path file, String from, String to) throws IOException {
    String content = Files.readString(file);
    if (!content.contains(from)) {
      throw new IllegalStateException(file + " に " + from + " がない");
    }
    Files.writeString(file, content.replace(from, to));
  }
}
