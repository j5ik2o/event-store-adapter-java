# Release path model (Quint)

`release_path.qnt` models the release path: the version pull request, the merge, the release gate, the tag, the publish job, the Maven Central deployment (accepted, then `PUBLISHED` or `FAILED`), the checks after publishing and the GitHub release. `release_path_test.qnt` runs fixed paths through the model.

The model was written for the release path design of the event-store-adapter repositories (the js shape of j5ik2o/event-store-adapter-js#1158 applied to java, kotlin, scala, rs and go). The R2b part models what `.github/central-rules.py` decides before an upload:

- `RECORD_VIEW`: the precheck sees a deployment only through its record (the `central-deployment-<attempt>-<ID>` artifact, or a line in the `CENTRAL_DEPLOYMENTS` variable). A job can fail after the upload step starts and before the ID is recorded.
- `UNKNOWN_DETECTED`: such an attempt is always seen as started (`may_have_uploaded` in `central-rules.py` counts a job as uploaded unless it proves otherwise).
- `UNKNOWN_STOPS`: an attempt that may have uploaded without a record stops every later upload.
- `NONE_SKIPS_QUERY`: the rejected rule where a `=none` line skipped the status query. The instance `noneSkipsQuery` shows that rule breaks the invariants.
- `PRECHECK_LATEST_TAG`: the precheck checks that this version is still the latest tag. The instance `noLatestTagCheck` drops the check and breaks I2 (a `FAILED` version re-run after a later version is `PUBLISHED`), which shows the check is needed. The model decides and uploads in one action, so it always reads the tags at the moment it decides; that `central-portal.py` reads the tags again after a wait is checked by `test_precheck_rereads_the_tags_after_waiting`.

- Concurrency groups (`release-tag` for the tag job, `release-publish` for the publish job): a job that arrives in a group replaces the job waiting there. Every arrival counts, the first run and every re-run (of a failed job or of a cancelled run), whatever its version or commit, so a re-run of an older version can replace the waiting job of a newer one ([#863](https://github.com/j5ik2o/event-store-adapter-java/issues/863)). `RERUN_CANCELLED`: a person re-runs a cancelled run. The instance `cancelledLeftAlone` leaves cancelled runs alone (the recovery row before #863) and breaks I8 and I8b: the latest tag stays unpublished, or a merged version above the latest tag stays untagged, with nothing left to run. The tag gate does not require the commit to be the head of `main` (a merge that does not change the version can come after it), so I8b checks every untagged version commit above the latest tag.

The model is the one of j5ik2o/event-store-adapter-scala (`.github/quint/` at `48e4256`, [scala#934](https://github.com/j5ik2o/event-store-adapter-scala/pull/934)), which is the java model at `3439740` with the changes below ([#870](https://github.com/j5ik2o/event-store-adapter-java/issues/870)). The instances of every language are kept in one file, so that the model stays the same in every repository until it moves to a shared source. The java instances (`java`, `javaReleasesOnly`, `javaR2aOnly`, `javaR2b`, `javaR2bReleasesOnly`) are unchanged.

- `jobOk`: the release job ended with success, because its wait step read `PUBLISHED` (`confirmPoll`) or its precheck read the deployment as published (`resumePublishJob`, a run or re-run that uploads nothing). `githubRelease` needs it, as the `github-release` job of `release.yml` needs the outputs of the release job. A job that fails after the upload, or whose wait times out while the deployment is still processing, leaves no confirm and no release: the recovery is to re-run the release job (the recovery row "The deployment is still in progress after 60 minutes" in CONTRIBUTING). `rerunConfirm` is only for the repo1 timeout of the `Check Maven Central` job. `supplyId` no longer starts the confirm by itself. Before this change the java model let a confirm-only re-run recover a wait that timed out while processing, which `release.yml` does not allow.
- I6c `requiredVisibleBeforeNewer`: a version newer than the required version is in flight or published only when the required version is visible on repo1. The java instances have no required version, so it binds nothing there.
- I8c `publishedReleaseRecoverable`: a published latest version without a GitHub release can still get one by the documented steps.
- `portalResolveAt`: the Portal resolves a deployment after a delay chosen by the caller (fixed paths use it to put a gap between `PUBLISHED` and repo1).
- `noLatestTagCheck` drops the latest-tag check of the model's single publish action, which stands for both the publish gate and the precheck (the model decides and uploads in one step). It shows that a latest-tag check right before the upload is needed. `release.yml` makes it three times: the gate, the precheck after its wait, and the gate again right before the upload (`Check the release gate again before the upload`). A short window stays between that last gate and the upload, but the `release-publish` concurrency group serializes publish jobs, so a newer version cannot be published first and I2 holds.

Outside the model: the deadline of the precheck, the wait and the repo1 check (`--deadline`, a time limit that can come before the attempt limit; the model's timeouts already cover an early end), the whole-diff check of the version pull request, the repo1 rule that a partial answer stops, the early-expiring records with a matching `CENTRAL_DEPLOYMENTS` line, and the `Latest` mark of the GitHub releases (a display property that does not affect I1 to I11; the step after the release reads the list again and marks the greatest version). `.github/tests/` checks them.

Invariants are `safety` (I1 to I11, with I8b `untaggedReleaseRecoverable`, I6c `requiredVisibleBeforeNewer` and I8c `publishedReleaseRecoverable`). Instances whose names describe a missing safeguard (`stepRecordDropped`, `unknownIgnored`, `noneSkipsQuery`, `cancelledLeftAlone` and others) are expected to violate an invariant.

In CI (`run-quint.sh ci`), every `quint test` runs (fixed paths, including `javaR2bPathsTest`), and `quint run` samples `javaR2b` and `javaR2bReleasesOnly` (no violation expected) and five instances that must violate an invariant (the R2b failure cases). The other instances are in `full` only.

## Running

```sh
bash .github/quint/run-quint.sh ci          # what CI runs: typecheck, every quint test, 2 java and 5 negative instances with 500 samples
bash .github/quint/run-quint.sh full 3000   # every instance; run it locally when the model changes and put the result in the pull request
```

The script uses `quint` from `PATH`, or the command in `QUINT` (CI uses `npx --yes @informalsystems/quint@0.32.0`).

quint fetches its Rust evaluator (`evaluator/v0.6.0`) from the GitHub API without authentication when `$QUINT_HOME/rust-evaluator-v0.6.0/quint_evaluator` is missing (`QUINT_HOME` defaults to `~/.quint`). That API allows 60 requests an hour per IP, which shared runners often have used up. CI therefore runs `fetch-evaluator.sh` first (copied from j5ik2o/event-store-adapter-rs at `81edc85`, [rs#375](https://github.com/j5ik2o/event-store-adapter-rs/pull/375)): it downloads the evaluator with `gh` and the job's token, checks its SHA-256, and puts it in `$QUINT_HOME`.

When `central-rules.py` changes what it decides, change the model first, then the code.
