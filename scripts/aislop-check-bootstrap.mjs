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
 * 取り寄せたファイルは .aislop-check/<ref>/ へ置き、次回からはハッシュが
 * 合う限りそれを使う（ネットワークが無くても、キャッシュがあれば動く）。
 * .aislop-check/ は追跡しない（.gitignore へ入れる）。
 *
 * j5ik2o/ci は公開リポジトリなので、取得に認証は要らない。
 */
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPTS_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.dirname(SCRIPTS_DIR);
const PIN_PATH = path.join(SCRIPTS_DIR, "aislop-check-pin.json");
const RAW_BASE = "https://raw.githubusercontent.com/j5ik2o/ci";

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");

async function fetchCanonicalFile(ref, name, expectedHash, cacheDir) {
  const cachePath = path.join(cacheDir, name);
  if (existsSync(cachePath) && sha256(readFileSync(cachePath)) === expectedHash) {
    return cachePath;
  }
  const url = `${RAW_BASE}/${ref}/aislop/${name}`;
  let buffer;
  try {
    const response = await fetch(url);
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
  mkdirSync(cacheDir, { recursive: true });
  writeFileSync(cachePath, buffer);
  return cachePath;
}

async function main() {
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
  const cacheDir = path.join(ROOT, ".aislop-check", pin.ref);
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
  if (mode === "check") {
    const result = spawnSync(process.execPath, [checkerPath], {
      cwd: ROOT,
      env,
      stdio: "inherit",
    });
    return result.status ?? 1;
  }
  const testPath = await fetchCanonicalFile(
    pin.ref,
    "check-aislop.test.mjs",
    pin.files["check-aislop.test.mjs"],
    cacheDir,
  );
  const result = spawnSync(process.execPath, ["--test", testPath], {
    cwd: ROOT,
    env,
    stdio: "inherit",
  });
  return result.status ?? 1;
}

try {
  process.exit(await main());
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
