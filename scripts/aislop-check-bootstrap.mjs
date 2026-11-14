/**
 * aislop 検査の正本（j5ik2o/ci の aislop/check-aislop.mjs）を、版を固定して
 * 取り寄せて実行する小さな起動役。正本と同じく j5ik2o/ci の aislop/bootstrap.mjs
 * が正で、各リポジトリはこのファイルを scripts/aislop-check-bootstrap.mjs へ
 * 編集せずに写して使う（レビュースレッド確認の呼び出し側ファイルと同じ扱い）。
 * 写しを直す必要が出たら、正本を直してから各リポジトリへ写し直す。
 *
 * 使い方:
 *   node scripts/aislop-check-bootstrap.mjs check   検査を実行する
 *   node scripts/aislop-check-bootstrap.mjs test    正本のテストを実行する
 *
 * 版の固定は、隣の scripts/aislop-check-pin.json で行う。
 *   { "ref": "aislop-check-v1",
 *     "files": { "check-aislop.mjs": "<sha256>", "check-aislop.test.mjs": "<sha256>" } }
 * ref は j5ik2o/ci のタグ（またはコミット）で、files はその ref における
 * aislop/ 配下のファイルの SHA-256 である。取り寄せたファイルは、必ず
 * このハッシュと照合してから使う。ハッシュが合わなければ落とす。
 * 取り寄せたファイルは node_modules/.aislop-check/<ref>/ へ置き、次回からは
 * ハッシュが合う限りそれを使う（ネットワークが無くても、キャッシュがあれば
 * 動く）。置き場所が node_modules の中なのは、aislop のスキャン対象から
 * 外れるためである。リポジトリ直下へ置くと、取り寄せた正本ファイル自体が
 * スキャンの指摘を生み、検査が落ちる。
 * キャッシュへの書き込みは、プロセスごとの一時ファイルへ書いてから rename で
 * 原子的に置く。check と test などが並行しても、書き込み途中のファイルを
 * 別の実行が読むことはない（issue #21）。
 *
 * 制限時間は内側から順に、正本の scan が600秒、この起動役全体が660秒
 * （AISLOP_CHECK_RUN_TIMEOUT_MS で変更可）、呼び出し側の期限、の順に
 * 発火する。呼び出し側の外側の期限が先に発火して起動役ごと終了しても、
 * 子プロセスが残り続けないようにするためである（issue #20）。660秒は
 * 起動役の開始からの全体予算で、正本の取得（fetch、1ファイル60秒まで）
 * の時間も含む。子プロセスには残り時間だけを渡し、残りが無ければ子を
 * 起動せずに失敗する。正本の取得（fetch）にも60秒の制限時間を設ける。
 * 制限時間の超過時は、直接の子だけでなくプロセスツリーごと終了する。
 * 子プロセスは専用プロセスグループで起動し、超過時はグループ全体へ終了
 * 信号を送る（猶予の後に強制終了）。この起動役自身が SIGTERM や SIGINT を
 * 受けたときも、子のグループを終了してから死ぬ（検査側も同じ構造で、scan
 * のグループを終了する）。SIGKILL は受け取れないため、その場合は下の層の
 * 自前の制限時間が残る。
 *
 * j5ik2o/ci は公開リポジトリなので、取得に認証は要らない。
 */
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPTS_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.dirname(SCRIPTS_DIR);
const PIN_PATH = path.join(SCRIPTS_DIR, "aislop-check-pin.json");
const RAW_BASE = "https://raw.githubusercontent.com/j5ik2o/ci";

/** 起動役全体の制限時間の既定（ミリ秒）。開始からの予算で、取得の時間も含む。正本の scan の制限時間（600秒）より長く、呼び出し側の期限より短く置く。 */
export const RUN_TIMEOUT_MS = 660_000;
/** 正本の取得（fetch）の制限時間（ミリ秒）。 */
export const FETCH_TIMEOUT_MS = 60_000;
/** プロセスツリーへ終了信号を送ってから、強制終了まで待つ猶予（ミリ秒）。 */
const TREE_KILL_GRACE_MS = 3_000;

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");

/** 起動役全体の制限時間を読む。AISLOP_CHECK_RUN_TIMEOUT_MS の正の整数だけを上書きとして受ける。 */
export function childTimeoutMs(env = process.env) {
  const parsed = Number(env.AISLOP_CHECK_RUN_TIMEOUT_MS);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : RUN_TIMEOUT_MS;
}

/** 全体予算から経過時間を引いた、子プロセスへ渡せる残り時間（ミリ秒）。 */
export function remainingBudgetMs(budgetMs, startedAt, now = Date.now()) {
  return budgetMs - (now - startedAt);
}

/**
 * 照合済みの正本ファイルをキャッシュへ置く。プロセスごとの一時ファイルへ
 * 書いてから rename で原子的に置くので、並行する実行が書き込み途中の
 * ファイルを読むことはない。失敗したら一時ファイルを消してから投げる。
 */
export function writeVerifiedCache(cacheDir, name, buffer) {
  const cachePath = path.join(cacheDir, name);
  mkdirSync(cacheDir, { recursive: true });
  const tempPath = path.join(
    cacheDir,
    `.${name}.tmp-${process.pid}-${randomUUID()}`,
  );
  try {
    writeFileSync(tempPath, buffer);
    renameSync(tempPath, cachePath);
  } catch (error) {
    try {
      unlinkSync(tempPath);
    } catch {
      // 一時ファイルが無い・消せない場合は、元の失敗を優先して伝える。
    }
    throw error;
  }
  return cachePath;
}

async function fetchCanonicalFile(ref, name, expectedHash, cacheDir) {
  const cachePath = path.join(cacheDir, name);
  if (existsSync(cachePath) && sha256(readFileSync(cachePath)) === expectedHash) {
    return cachePath;
  }
  const url = `${RAW_BASE}/${ref}/aislop/${name}`;
  let buffer;
  try {
    const response = await fetch(url, {
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    buffer = Buffer.from(await response.arrayBuffer());
  } catch (error) {
    throw new Error(
      `正本 ${name} を ${url} から取得できませんでした: ${error.message}。キャッシュも無いか、ハッシュが合いません。ネットワークと pin の ref を確かめてください。`,
    );
  }
  const actualHash = sha256(buffer);
  if (actualHash !== expectedHash) {
    throw new Error(
      `正本 ${name} のハッシュが pin と合いません（pin: ${expectedHash}、取得: ${actualHash}）。正本が pin の ref から変わっている可能性があります。取得したファイルは使いません。`,
    );
  }
  return writeVerifiedCache(cacheDir, name, buffer);
}

/**
 * 正本のスクリプトを子プロセスで実行し、終了コードを返す。argv は
 * process.execPath の後ろに渡す引数列（検査なら [検査のパス]、テストなら
 * ["--test", テストのパス]）。子は専用プロセスグループで起動し、制限時間を
 * 超えたらグループ全体へ終了信号を送り、猶予の後に強制終了して、理由を
 * 出して 1 を返す。この起動役自身が SIGTERM や SIGINT を受けたときも、
 * 子のグループを終了してから同じ信号で死ぬ。起動自体に失敗した場合も
 * 1 を返す。Windows ではプロセスグループが無いため、直接の子だけを終了する。
 */
export function runPinnedCommand(argv, { cwd, env, timeoutMs }) {
  return new Promise((resolvePromise) => {
    const useGroup = process.platform !== "win32";
    const child = spawn(process.execPath, argv, {
      cwd,
      env,
      stdio: "inherit",
      detached: useGroup,
    });
    let timedOut = false;
    let settled = false;
    let killTimer = null;
    const killTree = (signal) => {
      if (useGroup && child.pid) {
        try {
          process.kill(-child.pid, signal);
          return;
        } catch {
          // グループへ送れなければ、直接の子だけでも終了する。
        }
      }
      try {
        child.kill(signal);
      } catch {
        // 終了済みなら何もしない。
      }
    };
    const onSigterm = () => forward("SIGTERM");
    const onSigint = () => forward("SIGINT");
    const forward = (signal) => {
      killTree("SIGKILL");
      process.removeListener("SIGTERM", onSigterm);
      process.removeListener("SIGINT", onSigint);
      process.kill(process.pid, signal);
    };
    const finish = (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (killTimer) clearTimeout(killTimer);
      process.removeListener("SIGTERM", onSigterm);
      process.removeListener("SIGINT", onSigint);
      resolvePromise(code);
    };
    const timer = setTimeout(() => {
      timedOut = true;
      killTree("SIGTERM");
      killTimer = setTimeout(() => killTree("SIGKILL"), TREE_KILL_GRACE_MS);
    }, timeoutMs);
    process.on("SIGTERM", onSigterm);
    process.on("SIGINT", onSigint);
    child.on("error", (error) => {
      console.error(`子プロセスを起動できませんでした: ${error.message}`);
      finish(1);
    });
    child.on("close", (status) => {
      if (timedOut) {
        console.error(
          `制限時間（${Math.round(timeoutMs / 1000)}秒）を超えたため、子プロセスを終了しました。制限時間は AISLOP_CHECK_RUN_TIMEOUT_MS で変更できます。`,
        );
        finish(1);
      } else {
        finish(status ?? 1);
      }
    });
  });
}

async function main() {
  const startedAt = Date.now();
  const mode = process.argv[2];
  if (mode !== "check" && mode !== "test") {
    console.error(
      "使い方: node scripts/aislop-check-bootstrap.mjs check|test",
    );
    return 1;
  }
  const pin = JSON.parse(readFileSync(PIN_PATH, "utf8"));
  if (
    typeof pin.ref !== "string" ||
    typeof pin.files !== "object" ||
    pin.files === null
  ) {
    throw new Error(
      "scripts/aislop-check-pin.json の形が違います（ref と files が要ります）。",
    );
  }
  const budgetMs = childTimeoutMs(process.env);
  const cacheDir = path.join(ROOT, "node_modules", ".aislop-check", pin.ref);
  const checkerPath = await fetchCanonicalFile(
    pin.ref,
    "check-aislop.mjs",
    pin.files["check-aislop.mjs"],
    cacheDir,
  );
  const env = {
    ...process.env,
    AISLOP_CHECK_ROOT: ROOT,
    AISLOP_CHECK_ACCEPTED: path.join(SCRIPTS_DIR, "aislop-accepted.json"),
    AISLOP_CHECK_CLI: path.join(
      ROOT,
      "node_modules",
      "aislop",
      "dist",
      "cli.js",
    ),
  };
  let argv;
  if (mode === "check") {
    argv = [checkerPath];
  } else {
    const testPath = await fetchCanonicalFile(
      pin.ref,
      "check-aislop.test.mjs",
      pin.files["check-aislop.test.mjs"],
      cacheDir,
    );
    argv = ["--test", testPath];
  }
  // 全体予算は取得の時間も含む。子には残り時間だけを渡し、残りが無ければ
  // 子を起動せずに失敗する（取得だけで予算を使い切った場合）。
  const timeoutMs = remainingBudgetMs(budgetMs, startedAt);
  if (timeoutMs <= 0) {
    console.error(
      `正本の取得だけで全体の制限時間（${Math.round(budgetMs / 1000)}秒）を使い切りました。子プロセスは起動しません。制限時間は AISLOP_CHECK_RUN_TIMEOUT_MS で変更できます。`,
    );
    return 1;
  }
  return await runPinnedCommand(argv, { cwd: ROOT, env, timeoutMs });
}

/** 実パスで比べる。シンボリックリンク経由の実行でも entry guard を一致させる。 */
function realPath(target) {
  try {
    return realpathSync(target);
  } catch {
    return path.resolve(target);
  }
}

if (
  process.argv[1] &&
  realPath(process.argv[1]) === realPath(fileURLToPath(import.meta.url))
) {
  try {
    process.exit(await main());
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
