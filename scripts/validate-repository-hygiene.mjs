import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const forbiddenDirectories = new Set([
  "chrome-profile",
  "cv_uploads",
  "data",
  "db",
  "local_screenshots",
  "local_uploads",
  "logs",
  "output",
  "private_screenshots",
  "resume_uploads",
  "resumes",
  "uploads",
  "user_uploads",
]);

const forbiddenExtensions = [
  ".bak",
  ".db",
  ".db-journal",
  ".db-shm",
  ".db-wal",
  ".dump",
  ".jks",
  ".key",
  ".keystore",
  ".log",
  ".p12",
  ".pem",
  ".pfx",
  ".sqlite",
  ".sqlite3",
];

const forbiddenFileNames = new Set(["config.yaml", "cookie.json", "data.json"]);
const migrationRoot = "src/main/resources/db";
const migrationPrefix = "src/main/resources/db/migration/";

function git(...args) {
  return execFileSync("git", ["-C", repoRoot, ...args], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
}

function normalizePath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//, "");
}

function violationFor(value) {
  const file = normalizePath(value);
  const lower = file.toLowerCase();
  if (!file || lower === ".env.example") return null;

  const name = basename(lower);
  if (name === ".env" || name.startsWith(".env.")) return "真实环境配置";
  if (forbiddenFileNames.has(name)) return "本地运行配置或数据文件";

  const segments = lower.split("/");
  const forbiddenDirectory = segments.slice(0, -1).find((segment) => forbiddenDirectories.has(segment));
  if (forbiddenDirectory) {
    if (lower === migrationRoot || lower === `${migrationRoot}/migration`) return null;
    if (lower.startsWith(migrationPrefix) && lower.endsWith(".sql")) return null;
    return `本地运行目录 ${forbiddenDirectory}/`;
  }

  const forbiddenExtension = forbiddenExtensions.find((extension) => lower.endsWith(extension));
  if (forbiddenExtension) return `禁止提交的文件类型 ${forbiddenExtension}`;
  return null;
}

function trackedPaths() {
  return git("ls-files", "-z").split("\0").filter(Boolean);
}

function historicalPaths() {
  return git("rev-list", "--objects", "--all")
    .split(/\r?\n/)
    .map((line) => line.slice(line.indexOf(" ") + 1))
    .filter((path) => path && !/^[0-9a-f]{40}$/.test(path));
}

function validatePaths(paths, scope) {
  const violations = new Map();
  for (const path of paths) {
    const reason = violationFor(path);
    if (reason) violations.set(normalizePath(path), reason);
  }

  if (violations.size > 0) {
    console.error(`::error::${scope}发现不应进入仓库的本地数据：`);
    for (const [path, reason] of violations) console.error(`- ${path}（${reason}）`);
    process.exitCode = 1;
  }
  return violations.size;
}

function validateExampleSecrets() {
  const envExample = readFileSync(resolve(repoRoot, ".env.example"), "utf8");
  const populated = [];

  for (const rawLine of envExample.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#") || !line.includes("=")) continue;
    const separator = line.indexOf("=");
    const key = line.slice(0, separator).trim();
    const value = line.slice(separator + 1).trim();
    if (/(?:API_KEY|PASSWORD|SECRET|TOKEN|COOKIE)$/i.test(key) && value !== "") populated.push(key);
  }

  if (populated.length > 0) {
    console.error(`::error::.env.example 中的敏感字段必须为空：${populated.join(", ")}`);
    process.exitCode = 1;
  }
  return populated.length;
}

const trackedViolationCount = validatePaths(trackedPaths(), "当前版本");
const historicalViolationCount = validatePaths(historicalPaths(), "Git 历史");
const populatedSecretCount = validateExampleSecrets();

if (process.exitCode) process.exit(process.exitCode);

console.log("Repository hygiene validation passed.");
console.log(`Tracked path violations: ${trackedViolationCount}`);
console.log(`Historical path violations: ${historicalViolationCount}`);
console.log(`Populated example secrets: ${populatedSecretCount}`);
