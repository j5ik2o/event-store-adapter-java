# Contributing to event-store-adapter-java

[日本語](CONTRIBUTING.ja.md)

## Overview

This guide is for developers who change this repository. It is the Java implementation of [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md). The public packages are `core`, `memory`, `dynamodb`, `sqlite`, `spanner` and `bigtable`. Documentation for library users is in the [README](README.md) and `docs/`.

## Shared contract and decisions

The specification shared by all language implementations is in the hub repository: [docs/spec](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec). The shared design decisions are in [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr).

Some files are fixed copies from the hub, and CI checks them with SHA-256: `conformance/` (data version 1.3.0), `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py`. See [conformance/README.md](conformance/README.md) and [api-surface/README.md](api-surface/README.md). Do not edit these files in this repository.

## Local setup

- The code is compiled for Java 11 (the Gradle toolchain and `options.release` in `build.gradle.kts`). Tests run on the JDK selected by `-PtestJavaVersion` (default 11).
- Use the Gradle wrapper (`./gradlew`, Gradle 9.8.2). CI runs Gradle itself on JDK 25.
- The DynamoDB tests start a DynamoDB Local image pinned by digest through Testcontainers, so Docker is required. The Spanner tests start the Cloud Spanner emulator image `gcr.io/cloud-spanner-emulator/emulator:1.5.58`, pinned by digest, through Testcontainers, so Docker is required; a required Spanner conformance case that is not executed fails the build. The SQLite tests use the bundled JDBC driver and temporary local files, so they do not need Docker. The Bigtable tests start the Cloud Bigtable emulator image `gcr.io/google.com/cloudsdktool/google-cloud-cli`, pinned by digest, through Testcontainers, so Docker is required; a required Bigtable conformance case that is not executed fails the build. The first run pulls the image, which takes some time.
- The release tooling checks use Python 3. CI uses Python 3.15. `tools/user-docs/check.py` requires Python 3.10 or later.

## Checks

These are local check commands. [The CI workflow](.github/workflows/ci.yml) runs the full `test` task with its Java-version matrix, including the API-surface tests; `--tests '*apisurface*'` below is an optional targeted local run.

```sh
./gradlew spotlessCheck
./gradlew test -PtestJavaVersion=11
./gradlew test --tests '*apisurface*'
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
```

- CI runs the tests with `-PtestJavaVersion` set to 11, 17 and 19.
- CI also checks the SHA-256 of `conformance/manifest.json`, `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py` with `sha256sum -c`, and checks the workflow files with actionlint v1.7.12. The expected values are in `ci.yml`.
- The conformance report is written to `build/reports/conformance/` by the tests.

## Design history

[docs/design/next-major.md](docs/design/next-major.md) ([日本語](docs/design/next-major.ja.md)) is the design record from the time v5 was built. For current usage, check the README and the current code. Shared decisions are in the hub's [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr).

## Changes and releases

- Changes go to `main` through a pull request. The `CI Success` job summarizes the results of lint, test and release-tools.
- The version is kept in the `version` file, which the build reads.
- The Bump-Version workflow is started manually, with the inputs `level`, `force_bump` and `version`. The bump level comes from the Conventional Commits type of the commit subjects: `!` or `BREAKING CHANGE` is major, `feat` and `revert` are minor, and anything else is patch (`.github/semver-level.py`).
- Bump-Version pushes a `vX.Y.Z` tag, creates a GitHub Release, and sets `version` to the next SNAPSHOT version.
- Pushing a `vX.Y.Z` tag starts the Release workflow, which publishes to Sonatype (Maven Central). When CI succeeds on `main`, the Snapshot workflow publishes the snapshot.
- Dependency updates are made by Renovate (`renovate.json`).
