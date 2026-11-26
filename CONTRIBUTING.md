# Contributing to event-store-adapter-java

[日本語](CONTRIBUTING.ja.md)

## Overview

This guide is for developers who change this repository. It is the Java implementation of [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md). The public packages are `core`, `memory`, `dynamodb`, `sqlite`, `spanner` and `bigtable`. Documentation for library users is in the [README](README.md) and `docs/`.

## Shared contract and decisions

The specification shared by all language implementations is in the hub repository: [docs/spec](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec). The shared design decisions are in [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr).

Some files are fixed copies from the hub, and CI checks them with SHA-256: `conformance/` (data version 1.5.0), `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py`. See [conformance/README.md](conformance/README.md) and [api-surface/README.md](api-surface/README.md). Do not edit these files in this repository.

## Local setup

- The code is compiled for Java 11 (the Gradle toolchain and `options.release` in `build.gradle.kts`). Tests run on the JDK selected by `-PtestJavaVersion` (default 11).
- The build is split into `core` (with Memory), `dynamodb`, `spanner`, `bigtable` and `sqlite` modules. The root project is the aggregate artifact `event-store-adapter-java`; it contains no classes and depends on the five modules. Each storage SDK is an `api` dependency of its own module only.
- Use the Gradle wrapper (`./gradlew`, Gradle 9.9.0). CI runs Gradle itself on JDK 25.
- The DynamoDB tests start a DynamoDB Local image pinned by digest through Testcontainers, so Docker is required. The Spanner tests start the Cloud Spanner emulator image `gcr.io/cloud-spanner-emulator/emulator:1.5.58`, pinned by digest, through Testcontainers, so Docker is required; a required Spanner conformance case that is not executed fails the build. The SQLite tests use the bundled JDBC driver and temporary local files, so they do not need Docker. The Bigtable tests start the Cloud Bigtable emulator image `gcr.io/google.com/cloudsdktool/google-cloud-cli`, pinned by digest, through Testcontainers, so Docker is required; a required Bigtable conformance case that is not executed fails the build. The first run pulls the image, which takes some time.
- The release tooling checks use Python 3. CI uses Python 3.15. `tools/user-docs/check.py` requires Python 3.10 or later. The release tooling tests in `.github/tests` need the packages in `.github/tests/requirements.txt` (Hypothesis and semver).

## Checks

These are local check commands. [The CI workflow](.github/workflows/ci.yml) runs the full `test` task with its Java-version matrix, including the API-surface tests; `--tests '*apisurface*'` below is an optional targeted local run.

```sh
./gradlew spotlessCheck
./gradlew test -PtestJavaVersion=11
./gradlew test --tests '*apisurface*'
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m pip install -r .github/tests/requirements.txt
python3 -m unittest discover -s .github/tests -v
```

- CI runs the tests with `-PtestJavaVersion` set to 11, 17 and 19.
- `-PgoogleSdkSet=5.1` or `-PgoogleSdkSet=5.2` runs the tests on the Spanner and Bigtable SDK versions that release 5.1.0 or 5.2.0 depends on (5.1: Spanner 6.124.0 and Bigtable 2.85.0; 5.2: Spanner 6.127.0 and Bigtable 2.88.0). Only the test runtime classpath changes, so the library is still compiled against the declared SDK. `GoogleSdkSetTest` fails when the loaded SDK is not the selected one. CI runs the `createOffloaded` tests on both sets. Example: `./gradlew test -PgoogleSdkSet=5.1 --tests '*OffloadedEventStoreTest'`.
- CI also checks the SHA-256 of `conformance/manifest.json`, `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py` with `sha256sum -c`, and checks the workflow files with actionlint v1.7.12. The expected values are in `ci.yml`.
- The conformance report is written to `build/reports/conformance/` by the tests.
- The long-running load test of `createOffloaded` is skipped unless `EVENT_STORE_SOAK_SECONDS` is set. It runs the four operations on several aggregates for that many seconds per storage, then reads every aggregate back and checks threads and heap. CI does not run it. Example: `EVENT_STORE_SOAK_SECONDS=900 ./gradlew test --tests '*OffloadedSoakTest'`. The results are the `SOAK` lines in the standard output of `build/test-results/test/TEST-*OffloadedSoakTest.xml`.

## Design history

[docs/design/next-major.md](docs/design/next-major.md) ([日本語](docs/design/next-major.ja.md)) is the design record from the time v5 was built. For current usage, check the README and the current code. Shared decisions are in the hub's [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr).

## Changes and releases

- Changes go to `main` through a pull request. The `CI Success` job summarizes the results of lint, test and release-tools.
- The version is kept in the `version` file, which the build reads.
- The Bump-Version workflow is started manually, with the inputs `level`, `force_bump`, `version` and `rehearsal`. The bump level comes from the Conventional Commits type of the commit subjects: `!` or `BREAKING CHANGE` is major, `feat` and `revert` are minor, and anything else is patch (`.github/semver-level.py`).
- Bump-Version does not push to `main`. It commits the new version to `version` on a branch `release/vX.Y.Z` and opens a pull request into `main`. It does not create a GitHub Release, and it does not set `version` back to a SNAPSHOT version.
- When a commit that changes `version` lands on `main`, the tag job of Bump-Version checks it with the release gate (`.github/release-gate.py`). The commit must be the squash merge of a pull request from `release/vX.Y.Z` of this repository, it must change only the version in `version`, and the version must be greater than the previous version and every `vX.Y.Z` tag. A commit whose parent has no `version` file is not released.
- Pushing a `vX.Y.Z` tag starts the Release workflow, which publishes to Sonatype (Maven Central). Snapshot builds are not published; use a released version.

### Current state: releases are switched off

The Release workflow does not check the release gate yet. Until it does, a release cannot go out through Bump-Version:

- The tag job does not push tags (`PUSH_RELEASE_TAG: 'false'` in `bump-version.yml`). When the gate passes, the job writes the tag it would push to the job summary and stops.
- Bump-Version fails before it creates the branch or the pull request, unless `rehearsal` is `true`.
- A rehearsal opens a draft pull request titled `version up to vX.Y.Z (rehearsal, do not merge)`. Check it, then close it without merging and delete the branch.
- Do not create `vX.Y.Z` tags by hand, including tags created on the GitHub release page. A tag runs the Release workflow of the commit it points at, and a commit from before the release gate is in the Release workflow has no checks. Do not tag such commits later either.
- If a version pull request is merged by mistake, its version stays on `main` without a tag. After releases are switched on, set `version` back to the previous SNAPSHOT version with a pull request, then run Bump-Version again with `version` set to the version you want.

A later pull request (R2b) switches releases on. It adds the release gate to the Release workflow and sets `PUSH_RELEASE_TAG` to `'true'` in the same change; `.github/tests/test_release_workflow_shape.py` fails if `PUSH_RELEASE_TAG` is `'true'` while the Release workflow has no gate. The signing and Sonatype secrets are given only to the `publish` step of the Release workflow, and the same test checks that.
- Dependency updates are made by Renovate (`renovate.json`).
