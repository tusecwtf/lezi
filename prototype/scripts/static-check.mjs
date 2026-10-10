import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const files = ["index.html", "styles.css", "components.css", "app.js", "brand-spec.md", "prd-alignment.md"];
const source = Object.fromEntries(files.map((file) => [file, readFileSync(resolve(root, file), "utf8")]));
const failures = [];
const checks = [];

function check(name, condition, detail = "") {
  checks.push(name);
  if (!condition) failures.push(`${name}${detail ? `：${detail}` : ""}`);
}

const html = source["index.html"];
const css = `${source["styles.css"]}\n${source["components.css"]}`;
const js = source["app.js"];
const allText = files.map((file) => source[file]).join("\n");

const ids = [...html.matchAll(/(?:^|\s)id="([^"]+)"/g)].map((match) => match[1]);
const idSet = new Set(ids);
const duplicateIds = ids.filter((id, index) => ids.indexOf(id) !== index);
const buttons = [...html.matchAll(/<button\b[^>]*>/g)].map((match) => match[0]);
const openTargets = [...html.matchAll(/\bdata-open="([^"]+)"/g)].map((match) => match[1]);
const staticJsIds = [...js.matchAll(/\$\("#([a-zA-Z0-9_-]+)"\)/g)].map((match) => match[1]);
const getElementIds = [...js.matchAll(/getElementById\(["']([a-zA-Z0-9_-]+)["']\)/g)].map((match) => match[1]);
const missingOpenTargets = [...new Set(openTargets.filter((id) => !idSet.has(id)))];
const missingJsIds = [...new Set([...staticJsIds, ...getElementIds].filter((id) => !idSet.has(id)))];
const ariaRefs = [...html.matchAll(/aria-labelledby="([^"]+)"/g)].flatMap((match) => match[1].split(/\s+/));
const missingAria = [...new Set(ariaRefs.filter((id) => id && !idSet.has(id)))];
const externalAssetPattern = /(?:src|href)\s*=\s*["']https?:|@import\s+url|url\(\s*["']?https?:/i;
const forbiddenBrand = /PiyoLog|ぴよログ|暖芽|lorem ipsum/i;
const keyLabels = ["记录", "汇总", "成长", "账户", "菜单"];
const cssBalance = [...css].reduce((count, character) => count + (character === "{" ? 1 : character === "}" ? -1 : 0), 0);

const poopColorOptions = (html.match(/id="poop-color"[\s\S]*?<\/select>/) || [""])[0];
const poopOnlyColorOptions = (html.match(/id="poop-only-color"[\s\S]*?<\/select>/) || [""])[0];
const poopColorCount = (poopColorOptions.match(/<option/g) || []).length;
const poopOnlyColorCount = (poopOnlyColorOptions.match(/<option/g) || []).length;

check("六个指定文件均可读取", files.every((file) => source[file].length > 0));
check("HTML 使用中文语言声明", /<html\b[^>]*lang="zh-CN"/.test(html));
check("移动端 viewport 已声明", /name="viewport"/.test(html));
check("无外链脚本、样式、字体或图片", !files.some((file) => externalAssetPattern.test(source[file])));
check("HTML id 唯一", duplicateIds.length === 0, duplicateIds.join(", "));
check("所有按钮声明 type", buttons.every((button) => /\btype="(?:button|submit|reset)"/.test(button)));
check("data-open 均指向真实对话框", missingOpenTargets.length === 0, missingOpenTargets.join(", "));
check("JS 静态 id 引用均存在", missingJsIds.length === 0, missingJsIds.join(", "));
check("aria-labelledby 目标均存在", missingAria.length === 0, missingAria.join(", "));
check("五栏导航标签齐全", keyLabels.every((label) => new RegExp(`>${label}<`).test(html)));
check("记录页 records-title 存在", idSet.has("records-title"));
check("关键记录对话框齐全", [
  "breastfeed-dialog", "bottle-dialog", "expressed-feed-dialog", "sleep-dialog", "diaper-dialog",
  "growth-dialog", "pump-dialog", "poop-dialog", "temperature-dialog", "memo-dialog", "diary-dialog"
].every((id) => idSet.has(id)));
check("搜索、设置、状态、家庭与工具对话框齐全", [
  "search-dialog", "settings-dialog", "status-dialog", "family-dialog", "calendar-dialog",
  "export-dialog", "about-dialog", "record-config-dialog", "clear-data-dialog", "onboarding-dialog"
].every((id) => idSet.has(id)));
check("active-session 条存在", idSet.has("active-session-bar") && idSet.has("session-bar-label") && idSet.has("session-bar-primary"));
check("今日摘要五格 id 齐全", ["glance-feed-ml", "glance-bf-min", "glance-sleep", "glance-pee", "glance-poop"].every((id) => idSet.has(id)));
check("四类汇总趋势容器存在", ["trend-feed-chart", "trend-sleep-chart", "trend-diaper-chart", "trend-temp-chart"].every((id) => idSet.has(id)));
check("汇总/成长空状态存在", idSet.has("summary-empty-state") && idSet.has("growth-empty-state"));
check("WHO 与修正月龄存在", idSet.has("who-ref-info") && idSet.has("corrected-age-toggle") && /WHO 示例数据源/.test(html));
check("设备 ID 与本机优先文案", idSet.has("device-id-value") && /数据仅保存在本机/.test(html) && /无需登录/.test(html));
check("家庭三入口文案正确", /新建家庭/.test(html) && /输入邀请码/.test(html) && /扫码加入/.test(html));
check("家庭 stub 反馈文案在 JS", /家庭同步将在后续版本开放/.test(js));
check("便便 8 色选项", poopColorCount === 8 && poopOnlyColorCount === 8, `diaper=${poopColorCount}, poop=${poopOnlyColorCount}`);
check("编辑类型字段容器", idSet.has("edit-type-fields"));
check("清除两步确认输入", idSet.has("clear-confirm-input") && /清除/.test(html));
check("状态演示枚举齐全", ["loading", "empty", "summary-empty", "growth-empty", "recording", "saved", "error", "offline", "onboarding"].every((value) => html.includes(`value="${value}"`)));
check("会话持久化键存在", /leji-session-v1/.test(js) && /SESSION_KEY/.test(js));
check("记录排序上移下移", /data-config-move/.test(js) && /上移|下移/.test(js));
check("可访问状态容器存在", /aria-live="polite"/.test(html) && /aria-current="page"/.test(html));
check("触控基线为 48px", /min-height:\s*48px/.test(css));
check("CSS 花括号平衡", cssBalance === 0, `差值 ${cssBalance}`);
check("单文件不超过 1300 行", files.every((file) => source[file].split("\n").length <= 1300), files.filter((file) => source[file].split("\n").length > 1300).join(", "));
check("无禁用品牌字符串", !forbiddenBrand.test(allText));
check("品牌名为乐记", /乐记/.test(html) && !/PiyoLog/.test(allText));
check("便量 4 档", (html.match(/name="poop-only-amount"/g) || []).length === 4 && (html.match(/name="poop-amount"/g) || []).length === 4);
check("稠度 4 档", ((html.match(/id="poop-only-texture"[\s\S]*?<\/select>/) || [""])[0].match(/<option/g) || []).length === 4);
// Retired historical one-click pee/bath assertion: docs/spec/product.md §1
// requires every new record to be confirmed in a Composer before persistence.
// The frozen visual demo is not a behavior contract or Android acceptance gate.
check("日记与编辑字段", idSet.has("diary-dialog") && idSet.has("edit-type-fields") && /fillEditTypeFields/.test(js));
check("无 TODO/FIXME 残留", !/\bTODO\b|\bFIXME\b/.test(`${html}\n${js}\n${css}`));

// Reopening the sheet must atomically reset visibility and aria-expanded.
const recordSheetPrepare = (js.match(/if\s*\(\s*id\s*===\s*["']record-sheet["']\s*\)\s*\{[\s\S]*?\n\s*\}/) || [""])[0];
check(
  "打开 record-sheet 时重置更多类型折叠状态",
  /more-types-wrap/.test(recordSheetPrepare)
    && /more-types-toggle/.test(recordSheetPrepare)
    && /\.hidden\s*=\s*true/.test(recordSheetPrepare)
    && /aria-expanded["']\s*,\s*["']false["']/.test(recordSheetPrepare),
  "prepareDialog('record-sheet') 须原子设置 more-types-wrap.hidden=true 与 more-types-toggle aria-expanded=false"
);

if (failures.length) {
  console.error(`静态检查失败（${failures.length}/${checks.length}）：`);
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`静态检查通过：${checks.length} 项`);
console.log(`HTML ${html.split("\n").length} 行 · CSS ${css.split("\n").length} 行 · JS ${js.split("\n").length} 行`);
