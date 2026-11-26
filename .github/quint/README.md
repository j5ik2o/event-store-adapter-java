# Release path model (Quint)

`release_path.qnt` models the release path: the version pull request, the merge, the release gate, the tag, the publish job, the Maven Central deployment (accepted, then `PUBLISHED` or `FAILED`), the checks after publishing and the GitHub release. `release_path_test.qnt` runs fixed paths through the model.

The model was written for the release path design of the event-store-adapter repositories (the js shape of j5ik2o/event-store-adapter-js#1158 applied to java, kotlin, scala, rs and go). The R2b part models what `.github/central-rules.py` decides before an upload:

- `RECORD_VIEW`: the precheck sees a deployment only through its record (the `central-deployment-<attempt>-<ID>` artifact, or a line in the `CENTRAL_DEPLOYMENTS` variable). A job can fail after the upload step starts and before the ID is recorded.
- `UNKNOWN_DETECTED`: such an attempt is always seen as started (`may_have_uploaded` in `central-rules.py` counts a job as uploaded unless it proves otherwise).
- `UNKNOWN_STOPS`: an attempt that may have uploaded without a record stops every later upload.
- `NONE_SKIPS_QUERY`: the rejected rule where a `=none` line skipped the status query. The instance `noneSkipsQuery` shows that rule breaks the invariants.
- `PRECHECK_LATEST_TAG`: the precheck checks that this version is still the latest tag. The model reads the tags at the moment the precheck decides, so `central-portal.py` reads them again after a wait. The instance `noLatestTagCheck` drops the check and breaks I2 (a `FAILED` version re-run after a later version is `PUBLISHED`).

Invariants are `safety` (I1 to I11). Instances whose names describe a missing safeguard (`stepRecordDropped`, `unknownIgnored`, `noneSkipsQuery` and others) are expected to violate an invariant.

## Running

```sh
bash .github/quint/run-quint.sh ci          # what CI runs: typecheck, every quint test, the R2b instances with 500 samples
bash .github/quint/run-quint.sh full 3000   # every instance; run it locally when the model changes and put the result in the pull request
```

The script uses `quint` from `PATH`, or the command in `QUINT` (CI uses `npx --yes @informalsystems/quint@0.32.0`).

When `central-rules.py` changes what it decides, change the model first, then the code.
