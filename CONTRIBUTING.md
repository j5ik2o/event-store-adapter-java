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
- The `release-bundle` job builds the Maven Central bundle with `.github/central-bundle.sh` (the script the Release workflow uses) and a throwaway signing key, and checks its 180 files and 30 signatures. The `release-model` job checks the Quint model of the release path (`bash .github/quint/run-quint.sh ci`; see [.github/quint/README.md](.github/quint/README.md)). Neither uses a secret, and neither publishes anything.
- The long-running load test of `createOffloaded` is skipped unless `EVENT_STORE_SOAK_SECONDS` is set. It runs the four operations on several aggregates for that many seconds per storage, then reads every aggregate back and checks threads and heap. CI does not run it. Example: `EVENT_STORE_SOAK_SECONDS=900 ./gradlew test --tests '*OffloadedSoakTest'`. The results are the `SOAK` lines in the standard output of `build/test-results/test/TEST-*OffloadedSoakTest.xml`.

## Design history

[docs/design/next-major.md](docs/design/next-major.md) ([日本語](docs/design/next-major.ja.md)) is the design record from the time v5 was built. For current usage, check the README and the current code. Shared decisions are in the hub's [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr).

## Changes and releases

- Changes go to `main` through a pull request. The `CI Success` job summarizes the results of lint, test, google-sdk-sets, aislop, release-tools, release-bundle and release-model.
- The version is kept in the `version` file, which the build reads.
- The Bump-Version workflow is started manually, with the inputs `level`, `force_bump`, `version` and `rehearsal`. The bump level comes from the Conventional Commits type of the commit subjects: `!` or `BREAKING CHANGE` is major, `feat` and `revert` are minor, and anything else is patch (`.github/semver-level.py`).
- Bump-Version does not push to `main`. It commits the new version to `version` on a branch `release/vX.Y.Z` and opens a pull request into `main`. It does not create a GitHub Release, and it does not set `version` back to a SNAPSHOT version.
- When a commit that changes `version` lands on `main`, the tag job of Bump-Version checks it with the release gate (`.github/release-gate.py`). The commit must be the squash merge of a pull request from `release/vX.Y.Z` of this repository, it must change only the version in `version`, and the version must be greater than the previous version and every `vX.Y.Z` tag. A commit whose parent has no `version` file is not released.
- Pushing a `vX.Y.Z` tag starts the Release workflow, which checks the tagged commit against the release gate again and publishes the version to Maven Central (see [Releasing](#releasing)). Snapshot builds are not published; use a released version.
- Dependency updates are made by Renovate (`renovate.json`).

### Releasing

1. Run Bump-Version (`rehearsal: false`). Set `version` to choose the version, or leave it empty to use the bump level.
2. Check the pull request `version up to vX.Y.Z`. It must change only `version`, and Check unresolved comments must pass. Merge it with a squash merge when the release should go out. To cancel, close it and delete the branch.
3. The tag job (`Tag the merged version`) checks the merged commit against the release gate and pushes `vX.Y.Z`.
4. The Release workflow runs the job `Publish to Maven Central`:
   1. It checks the tagged commit against the release gate.
   2. It checks that Gradle builds the version of the tag.
   3. It checks Maven Central and the earlier release runs (`.github/central-portal.py precheck`). It waits while a deployment is in progress (up to 60 minutes), and it stops when a version is already published or an earlier attempt is unaccounted for.
   4. It builds the bundle (180 files) and uploads it to the Central Portal (`publishingType=AUTOMATIC`).
   5. It records the deployment ID as the artifact `central-deployment-<attempt>-<ID>` (kept for 90 days).
   6. It waits until the deployment is `PUBLISHED` (up to 60 minutes).
5. When the deployment is `PUBLISHED`, `Create the GitHub release` creates the GitHub release and `Check Maven Central` waits until repo1 serves the six POMs (up to 60 minutes).

A rehearsal (`rehearsal: true`) opens a draft pull request titled `version up to vX.Y.Z (rehearsal, do not merge)`. Close it without merging and delete the branch.

The signing secrets are given only to the bundle step, and the Sonatype secrets only to the precheck, upload and wait steps. `release.yml` has no personal access token.

Rules that keep the checks working:

- Do not create `vX.Y.Z` tags by hand, including tags created on the GitHub release page. A tag runs the Release workflow of the commit it points at. Do not tag commits from before the release gate was added to the Release workflow.
- Do not delete Release runs or their `central-` artifacts, and keep the artifact retention at 90 days. The precheck reads them to find deployments of earlier attempts.
- Do not rename the jobs or steps of `release.yml`. The precheck finds the upload in earlier runs by the names in `.github/central-rules.py` (`RELEASE_JOB`, `UPLOAD_STEP`, `PRE_UPLOAD_STEPS`). If a name has to change, keep the old name in those sets as well.
- A pull request that changes `release.yml` changes `.github/tests/fixtures/release-workflow.yml` (the reviewed copy) in the same way. `.github/tests/test_release_workflow_shape.py` allows `PUSH_RELEASE_TAG: 'true'` only while `release.yml` is the same as that copy, line for line; only the versions in `uses:` lines may differ.
- A failed or cancelled run can be re-run for 30 days after it started.

To stop releases, merge a pull request that sets `PUSH_RELEASE_TAG` to `'false'` in `bump-version.yml`. While it is `'false'`, the tag job writes the tag it would push to the job summary and stops. A version pull request merged during that time leaves its version on `main` without a tag: set `version` back to the previous SNAPSHOT version with a pull request, then run Bump-Version again with `version` set to the version you want.

### When a release stops

Most stops are recovered by re-running the failed jobs of the run (Re-run failed jobs). Each re-run checks Maven Central and the earlier attempts again before it uploads, so it never uploads a version twice.

Some stops need a line in the repository variable `CENTRAL_DEPLOYMENTS` (Settings, Secrets and variables, Actions, Variables). Write one line per item:

- `<run_id>/<attempt>=<deployment ID>`: the deployment that attempt `<attempt>` of run `<run_id>` uploaded.
- `<run_id>/<attempt>=none`: that attempt uploaded nothing, or its deployment was dropped in the Portal.
- `tag/vX.Y.Z=checked`: you checked in the Portal that no deployment of that version is in progress.

When the precheck stops for a missing ID, its error message lists every line to add, for every attempt from `<run_id>/1` to `<run_id>/<run_attempt>` that lacks one. The deployment of an attempt is named `event-store-adapter-java-<version>-run<run_id>-<attempt>` in the Portal.

| What happened | What the workflow does | How to recover |
|---|---|---|
| The tag is not on the squash merge of a version pull request, the commit changes code with the version, the version is lower, or the tag does not match the version | The release gate step fails. Nothing is built or uploaded | Delete the tag. Fix `version` with a pull request |
| Gradle builds another version than the tag | `Check the version Gradle builds` fails | Fix `version` with a pull request and release the next version |
| A query to GitHub, the Portal or repo1 fails (5xx, timeout) | The precheck stops | Re-run the failed jobs |
| A deployment of another run is in progress | The precheck waits up to 60 minutes, then fails | Re-run the failed jobs |
| An attempt may have uploaded but left no deployment ID (including step lists that are cut, runs without jobs, and attempts without a release job) | The precheck stops and lists the lines to add | Find each ID in the job summary or log of the attempt, or in the Portal by its name. Add `<run_id>/<attempt>=<ID>` or `=none` for every listed attempt, then re-run |
| The upload does not return 201 and a deployment ID | The upload step fails. The next attempt stops as in the row above | As in the row above |
| The bundle does not have the expected files | The bundle step fails. Nothing is uploaded | Fix it with a pull request and release the next version. A re-run uses the scripts of the tagged commit, so it does not pick up the fix |
| The signing key is missing or wrong | Signing fails in the bundle step. Nothing is uploaded | Fix the secrets and re-run |
| The deployment is `FAILED` | The wait step fails with the Portal errors. No GitHub release is created | Re-run the failed jobs (the precheck sees `FAILED` and uploads again). If the content is wrong, fix it in the next version |
| A `FAILED` deployment was dropped in the Portal | The precheck stops: the ID is not found | After the drop, add `<run_id>/<attempt>=none` and re-run |
| The deployment is still in progress after 60 minutes | The wait step fails | Re-run the failed jobs. The precheck waits for the same deployment and does not upload again |
| repo1 does not serve the version after 60 minutes | `Check Maven Central` fails | Re-run only `Check Maven Central` |
| Creating the GitHub release fails | `Create the GitHub release` fails | Re-run only `Create the GitHub release` |
| A newer tag exists (the version was overtaken) | The precheck stops; the version is not published | Leave it: the version is skipped. If the message names a cancelled run of the newer tag, re-run all jobs of that run |
| The waiting publish job was replaced by a later arrival in the `release-publish` group (another tag push, or a re-run of another version) | That run is cancelled before it starts; its version is not published | If its version is the latest `vX.Y.Z` tag, re-run all jobs of the cancelled run (its cancelled job is not counted as an upload). Otherwise leave it: a re-run stops at the precheck because a newer tag exists |
| The tag job (`Tag the merged version`) was replaced by a later arrival in the `release-tag` group | That run is cancelled; the version gets no tag | If its version is greater than the latest `vX.Y.Z` tag, re-run the cancelled run, also when other pull requests were merged after it (the release gate does not require the head of `main`). Otherwise leave it: the release gate refuses it |
| A line of `CENTRAL_DEPLOYMENTS` is malformed, gives another ID than the artifact, or says `none` for a deployment the Portal still knows | The precheck stops | Correct or remove the line and re-run |
| A Release run or a `central-` artifact was deleted, or a tag push started no Release run (more than three tags in one push, or an outage) | The precheck stops: a tag has no Release run | Check in the Portal that no deployment of that version is in progress, add `tag/vX.Y.Z=checked` and re-run. Do not move the tag |
| A job or step of `release.yml` was renamed | The precheck stops on the earlier runs | Do not rename them. If you have to, keep the old names in `.github/central-rules.py` |
| The artifact retention was set below 90 days | The precheck stops | Set it back to 90 days |
| More than 30 days passed, so the run cannot be re-run | | Treat the version as skipped and release the next one. Before that, add lines for every attempt that is still unaccounted for |
| A run is older than 80 days | The precheck does not count it | |
