import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const requiredFiles = ["index.html", "styles.css", "components.css", "app.js", "tokens.json", "brand-spec.md", "prd-equivalence.md"];
const failures = [];
const pass = (name) => console.log(`PASS  ${name}`);
const fail = (name, detail) => { failures.push(`${name}: ${detail}`); console.error(`FAIL  ${name} — ${detail}`); };
const read = (file) => fs.readFileSync(path.join(root, file), "utf8");

for (const file of requiredFiles) {
  if (fs.existsSync(path.join(root, file))) pass(`文件存在 ${file}`);
  else fail(`文件存在 ${file}`, "缺失");
}

const index = read("index.html");
const app = read("app.js");
const css = `${read("styles.css")}\n${read("components.css")}`;
const tokens = JSON.parse(read("tokens.json"));
const shippedText = [index, app, css, read("tokens.json"), read("brand-spec.md"), read("prd-equivalence.md")].join("\n");

const requiredPageMarkers = [
  'id="view-records"', 'id="view-summary"', 'id="view-growth"', 'id="view-account"', 'id="view-menu"',
  'id="calendar-dialog"', 'id="search-dialog"', 'id="status-dialog"', 'id="onboarding-dialog"',
  'id="timer-dialog"', 'id="record-dialog"', 'id="settings-dialog"', 'id="confirm-dialog"'
];
for (const marker of requiredPageMarkers) index.includes(marker) ? pass(`页面/入口 ${marker}`) : fail("页面/入口", marker);

const requiredRecordTypes = [
  "nursing", "formula", "pumped_feed", "pump_express", "pee", "poop", "both_diaper", "sleep", "temperature",
  "memo", "diary", "bath", "walk", "cough", "rash", "vomit", "injury", "medicine", "hospital", "other",
  "height", "weight", "baby_food", "snack", "drink", "head", "chest", "foot_size", "vaccine", "custom"
];
for (const type of requiredRecordTypes) app.includes(`id: "${type}"`) ? pass(`记录类型 ${type}`) : fail("记录类型", type);

const requiredStates = ["normal", "loading", "empty", "summary-empty", "growth-empty", "recording", "saved", "error", "offline", "onboarding"];
for (const state of requiredStates) app.includes(`["${state}"`) ? pass(`状态演示 ${state}`) : fail("状态演示", state);

const interactionMarkers = [
  "localStorage.setItem", "persistSession", "finishNursing", "finishSleep", "saveRecordForm", "configMove", "configToggle",
  "renderWeekChart", "renderGrowthChart", "renderCalendar", "renderSearch", "exportText", "clearDataFlow", "openOnboarding"
];
for (const marker of interactionMarkers) app.includes(marker) ? pass(`交互实现 ${marker}`) : fail("交互实现", marker);

const fieldMarkers = ["peeAmount", "stoolAmount", "stoolConsistency", "stoolColor", "amountStep", "preparedMl", "durationMin", "correctedAge", "growthHead", "vaccineName"];
for (const marker of fieldMarkers) app.includes(marker) ? pass(`专属字段 ${marker}`) : fail("专属字段", marker);

const forbidden = [
  ["Piyo", "Log"].join(""),
  String.fromCodePoint(0x3074, 0x3088, 0x30ed, 0x30b0),
  ["Piyo", "日志"].join("")
];
for (const term of forbidden) {
  const found = shippedText.toLocaleLowerCase().includes(term.toLocaleLowerCase());
  found ? fail("禁用参考品牌", "检测到禁止名称") : pass("禁用参考品牌");
}

const externalPatterns = [/https?:\/\//i, /\/\/unpkg\.com/i, /<img[^>]+src=["']https?:/i, /<script[^>]+src=["']https?:/i, /<link[^>]+href=["']https?:/i];
for (const pattern of externalPatterns) pattern.test(shippedText) ? fail("无外链", pattern.toString()) : pass(`无外链 ${pattern}`);

index.includes('data-od-id="timeline-24h"') && index.includes('data-od-id="weekly-summary-grid"') && index.includes('data-od-id="growth-percentile-chart"')
  ? pass("关键区域可检查 data-od-id") : fail("data-od-id", "关键区域缺失");
css.includes("min-height: 48px") ? pass("触控目标声明 48px") : fail("触控目标", "未发现 48px 声明");
css.includes('html[data-theme="dark"]') ? pass("深色模式 CSS") : fail("深色模式", "缺失");
css.includes("overflow-x: hidden") ? pass("页面无横向溢出约束") : fail("横向溢出", "缺失");

for (const mode of ["light", "dark"]) {
  const requiredTokens = ["bg", "surface", "fg", "muted", "border", "accent"];
  const missing = requiredTokens.filter((key) => !tokens[mode]?.[key]);
  missing.length ? fail(`tokens ${mode}`, missing.join(", ")) : pass(`tokens ${mode}`);
}
tokens.touchTarget >= 48 ? pass("tokens 触控目标") : fail("tokens 触控目标", String(tokens.touchTarget));
tokens.canvas?.primaryWidth === 390 && tokens.canvas?.primaryHeight === 844 ? pass("390×844 主画布") : fail("主画布", JSON.stringify(tokens.canvas));

if (failures.length) {
  console.error(`\n静态检查失败：${failures.length} 项`);
  process.exit(1);
}
console.log("\n静态检查通过：关键页面、入口、状态、记录类型、字段、离线约束与品牌禁用项均已覆盖。");
