// -*- mode: Bluespec; -*-
// release_path.qnt の、決まった手順の経路（設計レビュー D1）。
// 反映の遅れを 0 にして、手順の結果が1つに決まるインスタンスで流す。
// 実行: quint test release_path_test.qnt --main=javaPathsTest   と   --main=kotlinPathsTest

// java：Central は2段（受け付け → PUBLISHED／FAILED）。確認の上限は 2 回
module javaPathsTest {
  import release_path(
    VERSIONS = 1.to(3), INIT_VER = 2, INIT_PRE = true, INIT_TAG = 1, LEGACY = Set(), REQUIRED_PUBLISHED = Set(),
    TAG_IS_PUBLISH = false, ASYNC_PORTAL = true, PRECHECK_SEES_DEPLOYMENTS = true, MANUAL_TAGS = false, OTHER_PRS = false,
    LOOKUP_ATTEMPTS = 5, MAX_ASSOC_DELAY = 6, CONFIRM_ATTEMPTS = 2, MAX_DELAY = 0,
    NEW_PATH = true, TAG_PUSH_ENABLED = true, OLD_PUBLISH = false, OLD_BUMP = false, PRECHECK_SEES_OTHER_DEPLOYMENTS = true,
    RECORD_VIEW = false, UNKNOWN_DETECTED = true, UNKNOWN_STOPS = true, NONE_SKIPS_QUERY = false,
    RERUN_WHOLE_PUBLISH = false, MAX_COMMITS = 6
  ).* from "./release_path"

  // 版上げの PR（PR 0）をマージすると、コミット 2 ができる
  action mergedRelease(delay: int): bool =
    init.then(openReleasePR(2)).then(mergePR(0, delay))

  // 正常の経路：PR → マージ → タグ → 公開 → PUBLISHED → 確認 → リリース
  run happyPathTest =
    mergedRelease(0)
      .then(tagJob(2))
      .expect(s.tags.get(2) == 2 and s.publishPending.contains(2))
      .then(publishJob(2, false))
      .expect(deployOf(s, 2) == Processing)
      .expect(deployOf(s, 2) != Published)    // githubRelease の条件（PUBLISHED）を満たさない＝リリースを作らない
      .then(portalResolve(2, true))
      .then(confirmPoll(2))
      .expect(s.confirm.get(2).status == Confirmed)
      .then(githubRelease(2))
      .expect(s.releases.contains(2) and safety)

  // 照会の空振り → 使い切って失敗 → 再実行 → タグ
  run lookupRetryTest =
    mergedRelease(6)
      .then(5.reps(_ => tagJob(2)))
      .expect(s.tagFailed.contains(2) and not(s.tags.keys().contains(2)))
      .then(rerunTagJob(2))
      .then(tagJob(2))
      .expect(s.tags.get(2) == 2 and safety)

  // 処理中のまま確認が時間切れ → PUBLISHED → 確認だけを再実行 → 確認できる（公開はしない）
  run timeoutThenConfirmTest =
    mergedRelease(0)
      .then(tagJob(2))
      .then(publishJob(2, false))
      .then(confirmPoll(2))
      .then(confirmPoll(2))
      .expect(s.confirm.get(2).status == TimedOut and s.confirm.get(2).whileProcessing)
      .then(portalResolve(2, true))
      .then(rerunConfirm(2))
      .then(confirmPoll(2))
      .expect(s.confirm.get(2).status == Confirmed and s.duplicateAttempts == 0 and safety)

  // FAILED → リリースを作らない → 確認はすぐ失敗 → 公開のジョブを再実行 → PUBLISHED
  run failedThenRepublishTest =
    mergedRelease(0)
      .then(tagJob(2))
      .then(publishJob(2, false))
      .then(portalResolve(2, false))
      .expect(s.publishFailed.contains(2) and deployOf(s, 2) == Failed)
      .expect(deployOf(s, 2) != Published)
      .then(confirmPoll(2))
      .expect(s.confirm.get(2).status == Rejected)
      .then(rerunPublishJob(2))
      .then(publishJob(2, false))
      .expect(deployOf(s, 2) == Processing)
      .then(portalResolve(2, true))
      .then(githubRelease(2))
      .expect(s.published.contains(2) and s.releases.contains(2) and s.duplicateAttempts == 0 and safety)

  // 公開の操作のあとでジョブが失敗 → 再実行しても、処理中の deployment があるので二度目は出さない → PUBLISHED
  run jobFailsAfterSubmitTest =
    mergedRelease(0)
      .then(tagJob(2))
      .then(publishJob(2, true))
      .expect(s.publishFailed.contains(2) and deployOf(s, 2) == Processing)
      .then(rerunPublishJob(2))
      .then(publishJob(2, false))
      .expect(s.publishFailed.contains(2) and s.duplicateAttempts == 0)
      .then(portalResolve(2, true))
      .expect(s.published.contains(2) and s.duplicateAttempts == 0 and safety)

  // 2つの版を続けて出す
  run twoReleasesTest =
    mergedRelease(0)
      .then(tagJob(2))
      .then(publishJob(2, false))
      .then(portalResolve(2, true))
      .then(openReleasePR(3))
      .then(mergePR(1, 0))
      .then(tagJob(3))
      .then(publishJob(3, false))
      .then(portalResolve(3, true))
      .expect(s.publishOrder == [2, 3] and safety)

  // 新しい版の公開は、前の版の deployment が処理中のあいだ待つ（追い越さない）
  run noOvertakeWhileProcessingTest =
    mergedRelease(0)
      .then(tagJob(2))
      .then(publishJob(2, false))
      .then(openReleasePR(3))
      .then(mergePR(1, 0))
      .then(tagJob(3))
      .then(publishJob(3, false))
      .expect(s.publishFailed.contains(3) and deployOf(s, 3) == NoDeploy)
      .then(portalResolve(2, true))
      .then(rerunPublishJob(3))
      .then(publishJob(3, false))
      .expect(deployOf(s, 3) == Processing and safety)
}

// kotlin：v5.2.0（版 1）は Central に無い。関門の設定「公開先に必要な版：5.2.0」
module kotlinPathsTest {
  import release_path(
    VERSIONS = 1.to(3), INIT_VER = 2, INIT_PRE = true, INIT_TAG = 1, LEGACY = Set(1), REQUIRED_PUBLISHED = Set(1),
    TAG_IS_PUBLISH = false, ASYNC_PORTAL = true, PRECHECK_SEES_DEPLOYMENTS = true, MANUAL_TAGS = false, OTHER_PRS = false,
    LOOKUP_ATTEMPTS = 5, MAX_ASSOC_DELAY = 0, CONFIRM_ATTEMPTS = 2, MAX_DELAY = 0,
    NEW_PATH = true, TAG_PUSH_ENABLED = true, OLD_PUBLISH = false, OLD_BUMP = false, PRECHECK_SEES_OTHER_DEPLOYMENTS = true,
    RECORD_VIEW = false, UNKNOWN_DETECTED = true, UNKNOWN_STOPS = true, NONE_SKIPS_QUERY = false,
    RERUN_WHOLE_PUBLISH = false, MAX_COMMITS = 6
  ).* from "./release_path"

  // 5.2.0 が出るまで、新しい版のタグは関門で止まる。5.2.0 が出たあと、タグのジョブの再実行で続ける
  run legacyFirstTest =
    init
      .then(openReleasePR(2))
      .then(mergePR(0, 0))
      .then(tagJob(2))
      .expect(s.tagFailed.contains(2) and not(s.tags.keys().contains(2)))
      .then(publishLegacy(1))
      .then(portalResolve(1, true))
      .then(rerunTagJob(2))
      .then(tagJob(2))
      .then(publishJob(2, false))
      .then(portalResolve(2, true))
      .expect(s.publishOrder == [1, 2] and safety)

  // 5.2.0 の再実行が FAILED（上限など）→ もう一度再実行できる。処理中のあいだは再実行しない
  run legacyFailedTest =
    init
      .then(publishLegacy(1))
      .expect(inFlightOrDone(s, 1))             // 処理中なので publishLegacy の条件を満たさない
      .then(portalResolve(1, false))
      .then(publishLegacy(1))
      .then(portalResolve(1, true))
      .expect(s.published.contains(1) and s.duplicateAttempts == 0 and safety)
}

// java で R2a だけが main にある間（N1）：版上げの PR をマージしても、タグは付かず、古い release.yml は動かない
module javaR2aOnlyTest {
  import release_path(
    VERSIONS = 1.to(3), INIT_VER = 2, INIT_PRE = true, INIT_TAG = 1, LEGACY = Set(), REQUIRED_PUBLISHED = Set(),
    TAG_IS_PUBLISH = false, ASYNC_PORTAL = true, PRECHECK_SEES_DEPLOYMENTS = true, MANUAL_TAGS = false, OTHER_PRS = false,
    LOOKUP_ATTEMPTS = 5, MAX_ASSOC_DELAY = 0, CONFIRM_ATTEMPTS = 2, MAX_DELAY = 0,
    NEW_PATH = true, TAG_PUSH_ENABLED = false, OLD_PUBLISH = true, OLD_BUMP = false, PRECHECK_SEES_OTHER_DEPLOYMENTS = true,
    RECORD_VIEW = false, UNKNOWN_DETECTED = true, UNKNOWN_STOPS = true, NONE_SKIPS_QUERY = false,
    RERUN_WHOLE_PUBLISH = false, MAX_COMMITS = 6
  ).* from "./release_path"

  run dryRunTagTest =
    init
      .then(openReleasePR(2))
      .then(mergePR(0, 0))
      .then(tagJob(2))
      .expect(s.wouldTag.contains(2) and not(s.tags.keys().contains(2))
              and s.publishPending.size() == 0 and s.deploy.keys() == Set(1) and safety)
}

// java の R2b（design-r2b.md §5）：ID を残す前に落ちた試み、Portal に届く前に落ちた試み、ほかの版の止まり、drop
module javaR2bPathsTest {
  import release_path(
    VERSIONS = 1.to(3), INIT_VER = 2, INIT_PRE = true, INIT_TAG = 1, LEGACY = Set(), REQUIRED_PUBLISHED = Set(),
    TAG_IS_PUBLISH = false, ASYNC_PORTAL = true, PRECHECK_SEES_DEPLOYMENTS = true, MANUAL_TAGS = false, OTHER_PRS = false,
    LOOKUP_ATTEMPTS = 5, MAX_ASSOC_DELAY = 0, CONFIRM_ATTEMPTS = 2, MAX_DELAY = 0,
    NEW_PATH = true, TAG_PUSH_ENABLED = true, OLD_PUBLISH = false, OLD_BUMP = false, PRECHECK_SEES_OTHER_DEPLOYMENTS = true,
    RECORD_VIEW = true, UNKNOWN_DETECTED = true, UNKNOWN_STOPS = true, NONE_SKIPS_QUERY = false,
    RERUN_WHOLE_PUBLISH = false, MAX_COMMITS = 6
  ).* from "./release_path"

  action tagged2: bool = init.then(openReleasePR(2)).then(mergePR(0, 0)).then(tagJob(2))

  // ID を残す前に落ちる → 再実行は止まる → 人が ID を入れる → 処理中を待つ → PUBLISHED → 確認とリリース。二度目の公開なし
  run crashBeforeIdTest =
    tagged2
      .then(publishJobAt(2, false, 2, 0))
      .expect(s.unknown.get(2) and deployOf(s, 2) == Processing and not(s.recorded.contains(2)))
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .expect(s.publishFailed.contains(2) and s.duplicateAttempts == 0)
      .then(supplyId(2))
      .expect(s.recorded.contains(2) and s.unknown.keys().size() == 0)
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .expect(s.publishFailed.contains(2) and s.duplicateAttempts == 0)
      .then(portalResolve(2, true))
      .then(confirmPoll(2))
      .then(githubRelease(2))
      .expect(s.releases.contains(2) and s.duplicateAttempts == 0 and s.uploadsWhileUnknown == 0 and safety)

  // Portal に届く前に落ちる → 人が none を入れる → 公開し直す
  run crashBeforeSubmitTest =
    tagged2
      .then(publishJobAt(2, false, 1, 0))
      .expect(s.unknown.keys() == Set(2) and not(s.unknown.get(2)) and deployOf(s, 2) == NoDeploy)
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .expect(deployOf(s, 2) == NoDeploy and s.publishFailed.contains(2))
      .then(supplyId(2))
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .then(portalResolve(2, true))
      .expect(s.published.contains(2) and s.duplicateAttempts == 0 and safety)

  // 版 2 の試みが ID を残す前に落ちたまま、版 3 のタグの公開は止まる（追い越さない）。人が ID を入れたあとに順に出る
  run otherRunUnknownTest =
    tagged2
      .then(publishJobAt(2, false, 2, 0))
      .then(openReleasePR(3))
      .then(mergePR(1, 0))
      .then(tagJob(3))
      .then(publishJobAt(3, false, 0, 0))
      .expect(s.publishFailed.contains(3) and deployOf(s, 3) == NoDeploy and s.uploadsWhileUnknown == 0)
      .then(supplyId(2))
      .then(portalResolve(2, true))
      .then(rerunPublishJob(3))
      .then(publishJobAt(3, false, 0, 0))
      .then(portalResolve(3, true))
      .expect(s.publishOrder == [2, 3] and s.duplicateAttempts == 0 and safety)

  // 処理中の試みに取り違えて =none を書く → 照会は状態を返すので止まる → 行を消す → 待って PUBLISHED。二度目の公開なし
  run wrongNoneTest =
    tagged2
      .then(publishJobAt(2, true, 0, 0))
      .then(wrongNone(2))
      .expect(s.noneConflict == Set(2) and s.recorded.contains(2))
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .expect(s.publishFailed.contains(2) and s.duplicateAttempts == 0)
      .then(fixNone(2))
      .then(portalResolve(2, true))
      .then(githubRelease(2))
      .expect(s.releases.contains(2) and s.duplicateAttempts == 0 and safety)

  // FAILED → Portal で drop → 照会が失敗して止まる → 人が none を入れる → 公開し直す
  run droppedFailedTest =
    tagged2
      .then(publishJobAt(2, false, 0, 0))
      .then(portalResolve(2, false))
      .then(dropDeployment(2))
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .expect(s.publishFailed.contains(2) and deployOf(s, 2) == Failed)
      .then(markNone(2))
      .then(rerunPublishJob(2))
      .then(publishJobAt(2, false, 0, 0))
      .then(portalResolve(2, true))
      .expect(s.published.contains(2) and s.duplicateAttempts == 0 and safety)
}
