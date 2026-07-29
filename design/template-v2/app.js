(() => {
  "use strict";

  const STORAGE_KEY = "leji-dense-template-v2";
  const SESSION_KEY = "leji-dense-session-v2";
  const DEVICE_KEY = "leji-dense-device-v2";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const pad = (n) => String(n).padStart(2, "0");
  const escapeHTML = (value = "") => String(value).replace(/[&<>'"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[c]);
  const uid = (prefix) => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;

  const ICONS = {
    nursing: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="9" cy="8" r="3"/><circle cx="15.5" cy="10.5" r="2.5"/><path d="M4 20c.2-4.2 2-6.4 5.2-6.4 2.6 0 4.3 1.4 5 4.2M11.5 20c.4-3.2 1.8-4.7 4.2-4.7 2.5 0 3.8 1.6 4.3 4.7"/></svg>',
    bottle: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3ZM8 13h8M10 6h4"/></svg>',
    pump: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M7 4h7v5H7zM10.5 9v3M7 12h7v8H7zM16 7h3v11h-3M14 15h2"/></svg>',
    pee: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M8 4c2.3 3.2 3.4 5.5 3.4 7.3A3.4 3.4 0 0 1 4.6 11C4.6 9.4 5.7 7 8 4ZM16 7c2.2 3 3.2 5.1 3.2 6.8a3.2 3.2 0 0 1-6.4 0C12.8 12.1 13.8 10 16 7Z"/></svg>',
    poop: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9.2 8.1c.1-2.1 1.5-3.5 3.6-3.8.2 1.5.9 2.4 2.1 2.9 2.2.1 3.6 1.3 3.9 3.3 1.8.4 2.7 1.7 2.5 3.7-.3 2.5-2.1 3.9-5.3 3.9H8.1c-2.9 0-4.5-1.3-4.5-3.7 0-2 .9-3.2 2.9-3.6.2-1.4.8-2.5 1.7-3.3.3-.3.6-.5 1-.6Z"/><circle cx="10.2" cy="14.2" r="0.55" fill="currentColor" stroke="none"/><circle cx="13.8" cy="14.2" r="0.55" fill="currentColor" stroke="none"/><path d="M11 15.6c.4.45 1.6.45 2 0"/></svg>',
    sleep: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M20 15.2A8.2 8.2 0 0 1 8.8 4 8.2 8.2 0 1 0 20 15.2Z"/></svg>',
    temperature: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M14 14.8V5a3 3 0 0 0-6 0v9.8a5 5 0 1 0 6 0ZM11 7v9"/></svg>',
    note: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M5 4h14v16H5zM8 8h8M8 12h8M8 16h5"/></svg>',
    bath: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M3 12h18M5 12v5a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-5M8 9V6a3 3 0 0 1 6 0"/></svg>',
    walk: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="5" r="2"/><path d="m9 21 2-7 2-4 3 4M13 14l2 7M8 12l3-3 3 2"/></svg>',
    health: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 8v5M12 17h.01"/><circle cx="12" cy="12" r="9"/></svg>',
    medicine: '<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="4" y="7" width="16" height="10" rx="5"/><path d="m9 8 6 8"/></svg>',
    hospital: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M5 4h14v16H5zM12 8v8M8 12h8"/></svg>',
    growth: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="m4 18 5-5 3 3 7-8M15 8h4v4"/></svg>',
    food: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M7 3v8M4 3v5c0 2 1 3 3 3s3-1 3-3V3M7 11v10M16 3v18M16 3c3 2 4 5 4 8h-4"/></svg>',
    vaccine: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="m5 19 9-9M12 5l7 7M15 2l7 7M4 20l-2 2M8 16l-2-2"/></svg>',
    other: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>',
    search: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg>'
  };

  const TYPES = [
    { id: "nursing", name: "母乳", mode: "nursing", icon: "nursing", color: "nursing", group: "feed", tip: "左右计时" },
    { id: "formula", name: "配方奶", mode: "milk", icon: "bottle", color: "milk", group: "feed", tip: "奶量" },
    { id: "pumped_feed", name: "母乳瓶喂", mode: "milk", icon: "bottle", color: "nursing", group: "feed", tip: "奶量" },
    { id: "pee", name: "尿尿", mode: "pee", icon: "pee", color: "pee", group: "diaper", tip: "小中大" },
    { id: "poop", name: "便便", mode: "poop", icon: "poop", color: "poop", group: "diaper", tip: "三组分档" },
    { id: "both_diaper", name: "尿+便", mode: "both", icon: "poop", color: "poop", group: "diaper", tip: "完整分档" },
    { id: "sleep", name: "睡眠", mode: "sleep", icon: "sleep", color: "sleep", group: "sleep", tip: "计时/手动" },
    { id: "temperature", name: "体温", mode: "temperature", icon: "temperature", color: "temperature", group: "temperature", tip: "℃/℉" },
    { id: "memo", name: "备注", mode: "text", icon: "note", color: "care", group: "care", tip: "文字/照片" },
    { id: "diary", name: "日记", mode: "diary", icon: "note", color: "care", group: "care", tip: "正文/照片" },
    { id: "bath", name: "洗澡", mode: "simple", icon: "bath", color: "wake", group: "care", tip: "一键记录" },
    { id: "walk", name: "散步", mode: "interval", icon: "walk", color: "growth", group: "care", tip: "起止时间" },
    { id: "cough", name: "咳嗽", mode: "symptom", icon: "health", color: "temperature", group: "care", tip: "程度/备注" },
    { id: "rash", name: "发疹", mode: "symptom", icon: "health", color: "temperature", group: "care", tip: "程度/备注" },
    { id: "vomit", name: "呕吐", mode: "symptom", icon: "health", color: "temperature", group: "care", tip: "程度/备注" },
    { id: "injury", name: "受伤", mode: "symptom", icon: "health", color: "temperature", group: "care", tip: "程度/备注" },
    { id: "medicine", name: "用药", mode: "medicine", icon: "medicine", color: "care", group: "care", tip: "名称/剂量" },
    { id: "hospital", name: "就医", mode: "hospital", icon: "hospital", color: "wake", group: "care", tip: "原因/医嘱" },
    { id: "other", name: "其他", mode: "other", icon: "other", color: "care", group: "care", tip: "自由文本" },
    { id: "height", name: "身高", mode: "measure", icon: "growth", color: "growth", group: "growth", unit: "cm", tip: "成长测量" },
    { id: "weight", name: "体重", mode: "measure", icon: "growth", color: "growth", group: "growth", unit: "kg", tip: "成长测量" },
    { id: "baby_food", name: "辅食", mode: "food", icon: "food", color: "milk", group: "feed", tip: "内容/备注" },
    { id: "snack", name: "点心", mode: "food", icon: "food", color: "milk", group: "feed", tip: "内容/备注" },
    { id: "drink", name: "饮料", mode: "food", icon: "bottle", color: "milk", group: "feed", tip: "内容/量" },
    { id: "head", name: "头围", mode: "measure", icon: "growth", color: "growth", group: "growth", unit: "cm", tip: "成长测量" },
    { id: "chest", name: "胸围", mode: "measure", icon: "growth", color: "growth", group: "growth", unit: "cm", tip: "成长测量" },
    { id: "foot_size", name: "足长", mode: "measure", icon: "growth", color: "growth", group: "growth", unit: "cm", tip: "成长测量" },
    { id: "vaccine", name: "疫苗", mode: "vaccine", icon: "vaccine", color: "wake", group: "care", tip: "手记" },
    { id: "custom", name: "自定义", mode: "other", icon: "other", color: "care", group: "care", tip: "最多10项" }
  ];
  const TYPE_MAP = Object.fromEntries(TYPES.map((type) => [type.id, type]));
  const QUICK_DEFAULT = ["nursing", "formula", "sleep", "pee", "poop", "memo"];
  const STATUS = [
    ["normal", "正常", "显示本机记录"], ["loading", "加载中", "骨架状态"], ["empty", "记录空", "首页无记录"], ["summary-empty", "汇总空", "无趋势数据"], ["growth-empty", "成长空", "无测量数据"],
    ["recording", "记录中", "跨页计时条"], ["saved", "保存成功", "Toast 反馈"], ["error", "错误", "可操作重试"], ["offline", "离线", "仍可本机记录"], ["onboarding", "启动引导", "三步创建宝宝"]
  ];
  const THEME_COLORS = { coral: "oklch(71.4% 0.136 10.2)", mint: "oklch(78% 0.10 165)", berry: "oklch(66% 0.14 340)", sky: "oklch(75% 0.10 220)" };

  function localISO(date = new Date()) { const copy = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return copy.toISOString().slice(0, 10); }
  function offsetISO(days) { const date = new Date(); date.setHours(12, 0, 0, 0); date.setDate(date.getDate() + days); return localISO(date); }
  function timeNow() { const now = new Date(); return `${pad(now.getHours())}:${pad(now.getMinutes())}`; }
  function minutesBetween(start, end) { const [sh, sm] = start.split(":").map(Number); const [eh, em] = end.split(":").map(Number); let value = eh * 60 + em - sh * 60 - sm; if (value <= 0) value += 1440; return value; }
  function durationText(minutes) { const h = Math.floor(minutes / 60); const m = minutes % 60; return h ? `${h}小时${m ? `${m}分` : ""}` : `${m}分钟`; }
  function seedState() {
    const today = offsetISO(0), yesterday = offsetISO(-1), three = offsetISO(-3), week = offsetISO(-6);
    return {
      version: 2, activeBabyId: "mumu", babies: [
        { id: "mumu", name: "木木", sex: "女宝", birthday: "2026-05-04", dueDate: "", theme: "coral" },
        { id: "anan", name: "安安", sex: "男宝", birthday: "2025-08-19", dueDate: "2025-09-10", theme: "sky" }
      ],
      records: {
        mumu: [
          { id: "r1", date: today, time: "02:15", type: "formula", note: "拍嗝顺利", payload: { amountMl: 90, preparedMl: 100, durationMin: 12 } },
          { id: "r2", date: today, time: "05:48", type: "pee", note: "", payload: { peeAmount: 2 } },
          { id: "r3", date: today, time: "06:30", type: "sleep", note: "安稳", payload: { start: "06:30", end: "08:40", durationMin: 130, nap: false } },
          { id: "r4", date: today, time: "08:42", type: "nursing", note: "状态平稳", payload: { leftSec: 720, rightSec: 480, order: "left", amountMl: 0 } },
          { id: "r5", date: today, time: "09:26", type: "both_diaper", note: "皮肤状态正常", payload: { peeAmount: 2, stoolAmount: 3, stoolConsistency: 2, stoolColor: 2 } },
          { id: "r6", date: today, time: "10:08", type: "memo", note: "上午晒了十分钟太阳", payload: { body: "上午晒了十分钟太阳", photos: 0 } },
          { id: "r7", date: yesterday, time: "13:20", type: "sleep", note: "午睡", payload: { start: "13:20", end: "14:55", durationMin: 95, nap: true } },
          { id: "r8", date: yesterday, time: "21:10", type: "nursing", note: "", payload: { leftSec: 540, rightSec: 660, order: "right", amountMl: 0 } },
          { id: "r9", date: three, time: "10:30", type: "pumped_feed", note: "", payload: { amountMl: 80 } },
          { id: "r10", date: week, time: "19:40", type: "temperature", note: "洗澡后", payload: { celsius: 36.7, unit: "c" } }
        ],
        anan: [
          { id: "a1", date: today, time: "07:50", type: "formula", note: "", payload: { amountMl: 150 } },
          { id: "a2", date: today, time: "09:10", type: "sleep", note: "午前小睡", payload: { start: "09:10", end: "10:05", durationMin: 55, nap: true } },
          { id: "a3", date: today, time: "10:18", type: "pee", note: "", payload: { peeAmount: 2 } }
        ]
      },
      growth: {
        mumu: [
          { id: "g1", date: "2026-05-04", weight: 3.35, height: 50.2, head: 34.1, note: "出生记录" },
          { id: "g2", date: "2026-06-05", weight: 4.82, height: 56.5, head: 37.1, note: "满月测量" },
          { id: "g3", date: "2026-07-18", weight: 5.8, height: 60.4, head: 39.2, note: "社区体检" }
        ],
        anan: [
          { id: "ga1", date: "2025-08-19", weight: 3.6, height: 51.1, head: 34.5, note: "出生" },
          { id: "ga2", date: "2026-01-20", weight: 7.3, height: 67.4, head: 43, note: "家庭测量" }
        ]
      },
      settings: { dark: false, time24: true, relativeTime: false, reduceMotion: false, units: "metric", timerEnabled: true, recordAt: "end", amountStep: 5, reminders: false, reminderHours: 3, correctedAge: false },
      quickItems: [...QUICK_DEFAULT], hiddenItems: []
    };
  }
  function blankState() { const seed = seedState(); return { ...seed, activeBabyId: "", babies: [], records: {}, growth: {}, quickItems: [...QUICK_DEFAULT] }; }
  function loadState() { try { const saved = JSON.parse(localStorage.getItem(STORAGE_KEY)); if (saved?.version === 2) return { ...seedState(), ...saved, settings: { ...seedState().settings, ...(saved.settings || {}) } }; } catch (_) {} return seedState(); }
  function loadSession() { try { return JSON.parse(localStorage.getItem(SESSION_KEY)) || { nursing: null, sleep: null }; } catch (_) { return { nursing: null, sleep: null }; } }
  function persist() { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
  function persistSession() { localStorage.setItem(SESSION_KEY, JSON.stringify(session)); }
  function getDeviceId() { let id = localStorage.getItem(DEVICE_KEY); if (!id) { id = `LEJI-${Math.random().toString(36).slice(2, 6).toUpperCase()}-${Date.now().toString(36).slice(-4).toUpperCase()}`; localStorage.setItem(DEVICE_KEY, id); } return id; }

  let state = loadState();
  let session = loadSession();
  let activeView = "records", selectedDate = localISO(), summaryRange = "week", summaryCategory = "all", growthMetric = "weight", demoStatus = "normal";
  let calendarCursor = new Date(), timerInterval = null, confirmHandler = null;

  function activeBaby() { return state.babies.find((b) => b.id === state.activeBabyId) || state.babies[0] || { id: "pending", name: "宝宝", birthday: localISO(), sex: "未设置", dueDate: "", theme: "coral" }; }
  function allRecords(babyId = activeBaby().id) { return state.records[babyId] || []; }
  function dateRecords(date = selectedDate) { return allRecords().filter((r) => r.date === date).sort((a, b) => b.time.localeCompare(a.time)); }
  function typeOf(id) { return TYPE_MAP[id] || TYPE_MAP.other; }
  function typeIcon(typeId, extra = "") { const type = typeOf(typeId); return `<span class="type-icon ${extra}" style="--type-color:var(--${type.color})">${ICONS[type.icon] || ICONS.other}</span>`; }
  function dateDiffDays(a, b) { return Math.round((new Date(`${a}T12:00:00`) - new Date(`${b}T12:00:00`)) / 86400000); }
  function babyAge(baby = activeBaby()) { const days = Math.max(0, dateDiffDays(localISO(), baby.birthday)); if (days < 60) return `生后 ${days} 日`; const months = Math.max(0, Math.floor(days / 30.44)); return `${months}个月 · 生后${days}日`; }
  function displayDate(iso) { const date = new Date(`${iso}T12:00:00`); const days = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]; return { title: iso === localISO() ? "今天" : iso === offsetISO(-1) ? "昨天" : `${date.getMonth() + 1}月${date.getDate()}日`, sub: `${date.getMonth() + 1}月${date.getDate()}日 · ${days[date.getDay()]}` }; }
  function displayTime(time) { if (state.settings.relativeTime && selectedDate === localISO()) { const [h, m] = time.split(":").map(Number); const then = new Date(); then.setHours(h, m, 0, 0); const diff = Math.floor((Date.now() - then) / 60000); if (diff >= 0 && diff < 60) return `${Math.max(1, diff)}分前`; if (diff < 1440) return `${Math.floor(diff / 60)}小时前`; } if (state.settings.time24) return time; const [h, m] = time.split(":").map(Number); return `${h >= 12 ? "下午" : "上午"}${h % 12 || 12}:${pad(m)}`; }
  function recordDetail(record) {
    const p = record.payload || {}, type = typeOf(record.type);
    if (record.type === "nursing") return `左${Math.round((p.leftSec || 0) / 60)}分 · 右${Math.round((p.rightSec || 0) / 60)}分${p.amountMl ? ` · ${p.amountMl}ml` : ""}`;
    if (["formula", "pumped_feed", "pump_express"].includes(record.type)) return `${p.amountMl || 0}ml${p.durationMin ? ` · ${p.durationMin}分` : ""}`;
    if (record.type === "pee") return `尿量${["", "小", "中", "大"][p.peeAmount || 2]}`;
    if (record.type === "poop") return `量${p.stoolAmount || 3} · 软硬${p.stoolConsistency || 3} · ${["未选", "白", "黄", "橙", "褐", "绿", "红", "黑"][p.stoolColor || 0]}`;
    if (record.type === "both_diaper") return `尿${["", "小", "中", "大"][p.peeAmount || 2]} · 便量${p.stoolAmount || 3}/软硬${p.stoolConsistency || 3}`;
    if (record.type === "sleep") return `${p.start || record.time}–${p.end || "—"} · ${durationText(p.durationMin || 0)}${p.anomaly ? " · !" : ""}`;
    if (record.type === "temperature") return `${(p.celsius || 0).toFixed(1)}℃`;
    if (["memo", "diary"].includes(record.type)) return p.body || record.note || type.name;
    if (record.type === "walk") return `${p.start || record.time}–${p.end || "—"} · ${durationText(p.durationMin || 0)}`;
    if (["cough", "rash", "vomit", "injury"].includes(record.type)) return `${p.severity || "一般"}${p.description ? ` · ${p.description}` : ""}`;
    if (record.type === "medicine") return `${p.name || "未填药名"}${p.dose ? ` · ${p.dose}` : ""}`;
    if (record.type === "hospital") return p.reason || record.note || "就医记录";
    if (["height", "weight", "head", "chest", "foot_size"].includes(record.type)) return `${p.value || 0}${p.unit || type.unit}`;
    if (["baby_food", "snack", "drink"].includes(record.type)) return `${p.content || type.name}${p.amount ? ` · ${p.amount}` : ""}`;
    if (record.type === "vaccine") return `${p.name || "疫苗手记"}${p.batch ? ` · ${p.batch}` : ""}`;
    if (["other", "custom"].includes(record.type)) return p.detail || p.title || record.note || type.name;
    return record.note || type.name;
  }
  let toastTimer = null;
  function showToast(message) {
    const region = $("#toast-region");
    if (!region) return;
    // Replace any existing toast so rapid actions don't stack a permanent pile of bars.
    region.replaceChildren();
    if (toastTimer) { clearTimeout(toastTimer); toastTimer = null; }
    const toast = document.createElement("div");
    toast.className = "toast";
    toast.textContent = message;
    region.append(toast);
    toastTimer = setTimeout(() => {
      toast.remove();
      toastTimer = null;
    }, 2400);
  }
  function openDialog(dialog) { const current = $("dialog[open]"); if (current && current !== dialog) current.close(); if (!dialog.open) dialog.showModal(); document.body.classList.add("dialog-open"); }
  function closeDialog(dialog) { if (!dialog?.open) return; dialog.close(); }
  document.addEventListener("close", () => { if (!$("dialog[open]")) document.body.classList.remove("dialog-open"); }, true);

  function applySettings() { document.documentElement.dataset.theme = state.settings.dark ? "dark" : "light"; document.documentElement.classList.toggle("reduce-motion", state.settings.reduceMotion); }
  function renderHeader() { const baby = activeBaby(), date = displayDate(selectedDate); $("#baby-name").textContent = baby.name; $("#baby-age").textContent = babyAge(baby); $("#baby-dot").style.setProperty("--baby-color", THEME_COLORS[baby.theme] || THEME_COLORS.coral); $("#date-title").textContent = date.title; $("#date-subtitle").textContent = date.sub; $("#next-day").disabled = selectedDate >= localISO(); document.title = `乐记 · ${baby.name} · ${date.title}`; }
  function totals(records) {
    const feedMl = records.filter((r) => ["formula", "pumped_feed", "drink"].includes(r.type)).reduce((s, r) => s + Number(r.payload?.amountMl || 0), 0);
    const nursingMin = Math.round(records.filter((r) => r.type === "nursing").reduce((s, r) => s + Number(r.payload?.leftSec || 0) + Number(r.payload?.rightSec || 0), 0) / 60);
    const sleepMin = records.filter((r) => r.type === "sleep").reduce((s, r) => s + Number(r.payload?.durationMin || 0), 0);
    const pee = records.filter((r) => ["pee", "both_diaper"].includes(r.type)).length;
    const poop = records.filter((r) => ["poop", "both_diaper"].includes(r.type)).length;
    return { feedMl, nursingMin, sleepMin, pee, poop };
  }
  function renderTodaySummary(records) { const t = totals(records); const items = [["formula", `${t.feedMl}ml`, "奶量"], ["nursing", `${t.nursingMin}分`, "母乳"], ["sleep", durationText(t.sleepMin), "睡眠"], ["pee", `${t.pee}次`, "尿尿"], ["poop", `${t.poop}次`, "便便"]]; $("#today-summary-grid").innerHTML = items.map(([id, value, label]) => `<div class="summary-item">${typeIcon(id)}<strong>${escapeHTML(value)}</strong><span>${label}</span></div>`).join(""); }
  function renderRail(records) {
    const holder = $("#day-rail"); let html = '<div class="rail-line" id="rail-line"></div>';
    for (let hour = 0; hour <= 24; hour += 2) html += `<span class="rail-hour" style="top:${(hour / 24) * 100}%">${pad(hour)}</span>`;
    records.forEach((record) => { const [h, m] = record.time.split(":").map(Number), top = ((h * 60 + m) / 1440) * 100, type = typeOf(record.type); html += `<i class="rail-event" style="top:${top}%;--event-color:var(--${type.color})" title="${escapeHTML(`${record.time} ${type.name}`)}"></i>`; if (record.type === "sleep") { const duration = record.payload?.durationMin || 0; html += `<i class="rail-sleep" style="top:${top}%;height:${Math.max(3, (duration / 1440) * 100)}%"></i>`; } });
    if (selectedDate === localISO()) { const now = new Date(), top = ((now.getHours() * 60 + now.getMinutes()) / 1440) * 100; html += `<div class="rail-now" style="top:${top}%"></div>`; }
    holder.innerHTML = html;
  }
  function recordRow(record) { const type = typeOf(record.type), detail = recordDetail(record); return `<li class="record-row" data-record-id="${record.id}"><time class="record-time">${escapeHTML(displayTime(record.time))}<small>${record.date === localISO() ? "今日" : record.date.slice(5)}</small></time>${typeIcon(record.type)}<span class="record-copy"><strong>${escapeHTML(type.name)}${record.payload?.anomaly ? "<em>!</em>" : ""}</strong><small>${escapeHTML(detail)}${record.note && !detail.includes(record.note) ? ` · ${escapeHTML(record.note)}` : ""}</small></span><button class="row-button" type="button" data-edit-record="${record.id}" aria-label="编辑${escapeHTML(type.name)}"><svg aria-hidden="true" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg></button></li>`; }
  function stateMarkup(kind) { if (kind === "loading") return '<div class="skeleton" role="status" aria-label="加载中"><span></span><span></span><span></span><span></span></div>'; if (kind === "error") return `<div class="state-block"><span class="state-symbol">${ICONS.health}</span><h3>记录暂时没有显示</h3><p>本地数据没有丢失，可以重新加载。</p><button class="secondary-button" type="button" data-retry-state>重新加载</button></div>`; return `<div class="state-block"><span class="state-symbol">${ICONS.note}</span><h3>这一天还没有记录</h3><p>点底部快捷项目，或使用加号添加第一条。</p><button class="primary-button" type="button" data-open="record-sheet">添加记录</button></div>`; }
  function renderRecords() {
    const actual = dateRecords(), forcedEmpty = demoStatus === "empty", records = forcedEmpty ? [] : actual, blocking = ["loading", "error"].includes(demoStatus), naturallyEmpty = !records.length;
    renderTodaySummary(records); $("#records-state").innerHTML = blocking ? stateMarkup(demoStatus) : naturallyEmpty ? stateMarkup("empty") : ""; $("#timeline-layout").hidden = blocking || naturallyEmpty;
    if (!blocking && !naturallyEmpty) { renderRail(records); $("#record-list").innerHTML = records.map(recordRow).join(""); }
    $("#record-count").textContent = `${records.length} 条`;
    $("#offline-banner").hidden = demoStatus !== "offline" && navigator.onLine;
  }
  function renderQuick() { let ids = (state.quickItems || QUICK_DEFAULT).filter((id) => !(state.hiddenItems || []).includes(id)); if (!state.settings.timerEnabled) ids = ids.filter((id) => id !== "nursing"); ids = ids.slice(0, 6); $("#quick-items").innerHTML = ids.map((id) => { const type = typeOf(id); const label = id === "sleep" && session.sleep ? "醒来" : type.name; return `<button class="quick-item" type="button" data-quick="${id}">${typeIcon(id)}<span>${escapeHTML(label)}</span></button>`; }).join(""); }
  function rangeRecords() { const anchor = new Date(`${selectedDate}T12:00:00`), days = summaryRange === "day" ? 1 : summaryRange === "week" ? 7 : 30, start = new Date(anchor); start.setDate(start.getDate() - days + 1); return allRecords().filter((r) => { const d = new Date(`${r.date}T12:00:00`); return d >= start && d <= anchor; }); }
  function categoryMatches(record) { if (summaryCategory === "all") return true; if (summaryCategory === "feed") return typeOf(record.type).group === "feed"; return typeOf(record.type).group === summaryCategory; }
  function renderWeekChart(records) {
    const end = new Date(`${selectedDate}T12:00:00`), days = []; for (let i = 6; i >= 0; i--) { const d = new Date(end); d.setDate(d.getDate() - i); days.push(localISO(d)); }
    let html = '<div class="week-axis">'; for (let h = 0; h <= 24; h += 3) html += `<span style="top:${(h / 24) * 100}%">${h}</span>`; html += "</div>";
    days.forEach((day) => { const date = new Date(`${day}T12:00:00`), dayRecords = records.filter((r) => r.date === day && categoryMatches(r)); html += `<div class="week-day"><strong>${date.getMonth() + 1}/${date.getDate()}</strong>`; dayRecords.forEach((r) => { const [h, m] = r.time.split(":").map(Number), top = ((h * 60 + m) / 1440) * 100, type = typeOf(r.type); if (r.type === "sleep") { const height = Math.max(1.5, ((r.payload?.durationMin || 0) / 1440) * 100); html += `<i class="sleep-block ${r.payload?.nap ? "nap" : ""}" style="top:${top}%;height:${height}%"></i>`; } else { const shape = r.type === "temperature" ? "triangle" : ["pee", "poop", "both_diaper"].includes(r.type) ? "square" : ""; html += `<i class="event-dot ${shape}" style="top:${top}%;--dot-color:var(--${type.color})"></i>`; } }); html += "</div>"; });
    $("#week-chart").innerHTML = html;
  }
  function renderSummary() {
    const empty = demoStatus === "summary-empty", records = empty ? [] : rangeRecords(), t = totals(records), temp = records.filter((r) => r.type === "temperature").at(-1)?.payload?.celsius;
    $("#summary-range-label").textContent = summaryRange === "day" ? "当日" : summaryRange === "week" ? "近7天" : "近30天";
    $("#summary-kpis").innerHTML = [["喂养", `${t.feedMl}ml`, `母乳${t.nursingMin}分`], ["睡眠", durationText(t.sleepMin), `${records.filter((r) => r.type === "sleep").length}段`], ["排泄", `${t.pee + t.poop}次`, `尿${t.pee} · 便${t.poop}`]].map(([a,b,c]) => `<div class="kpi"><span>${a}</span><strong>${b}</strong><small>${c}</small></div>`).join("");
    $("#summary-chart-panel").hidden = empty || !records.length; $("#summary-detail-panels").hidden = empty || !records.length; $("#summary-empty").hidden = !empty && records.length > 0;
    if (empty || !records.length) return;
    renderWeekChart(records); const buckets = (filter, value) => { const out = Array(7).fill(0); records.filter(filter).forEach((r) => { const diff = Math.min(6, Math.max(0, dateDiffDays(selectedDate, r.date))); out[6 - diff] += value(r); }); return out; };
    const panels = [
      ["喂养", buckets((r) => typeOf(r.type).group === "feed", (r) => Number(r.payload?.amountMl || 1)), `${t.feedMl}ml / ${t.nursingMin}分`, "milk"],
      ["睡眠", buckets((r) => r.type === "sleep", (r) => Number(r.payload?.durationMin || 0)), durationText(t.sleepMin), "sleep"],
      ["排泄", buckets((r) => ["pee","poop","both_diaper"].includes(r.type), () => 1), `尿${t.pee} · 便${t.poop}`, "pee"],
      ["体温", buckets((r) => r.type === "temperature", (r) => Number(r.payload?.celsius || 0)), temp ? `${temp.toFixed(1)}℃` : "暂无", "temperature"]
    ];
    $("#summary-detail-panels").innerHTML = panels.map(([name, values, value, color]) => { const max = Math.max(...values, 1); return `<div class="summary-detail"><div><strong>${name}</strong><small>与上一周期对比</small></div><span class="mini-bars" style="--bar-color:var(--${color})">${values.map((v) => `<i style="height:${Math.max(2, (v/max)*100)}%"></i>`).join("")}</span><span>${value}</span></div>`; }).join("");
  }
  function monthAgeAt(date, baby = activeBaby()) { const origin = state.settings.correctedAge && baby.dueDate ? baby.dueDate : baby.birthday; return Math.max(0, dateDiffDays(date, origin) / 30.44); }
  function band(metric, age) { if (metric === "weight") { const p50 = 3.3 + Math.min(age, 12) * .55; return [p50 * .82, p50, p50 * 1.18]; } if (metric === "height") { const p50 = 50 + Math.min(age, 12) * 2.1; return [p50 * .92, p50, p50 * 1.08]; } const p50 = 34 + Math.min(age, 12) * .72; return [p50 * .93, p50, p50 * 1.07]; }
  function renderGrowthChart(entries) {
    const cfg = { weight: [2, 12, "kg"], height: [45, 82, "cm"], head: [30, 50, "cm"] }[growthMetric], [min, max] = cfg, x = (age) => 32 + (Math.min(12, age) / 12) * 296, y = (value) => 244 - ((value - min) / (max - min)) * 205;
    let grid = ""; for (let i = 0; i <= 6; i++) { const gx = 32 + i * 49.33; grid += `<path class="growth-grid-line" d="M${gx} 28V244"/><text class="growth-axis-label" x="${gx}" y="260" text-anchor="middle">${i*2}月</text>`; } for (let i = 0; i <= 5; i++) { const gy = 39 + i * 41; const val = max - ((max - min) / 5) * i; grid += `<path class="growth-grid-line" d="M32 ${gy}H328"/><text class="growth-axis-label" x="27" y="${gy+3}" text-anchor="end">${val.toFixed(growthMetric === "weight" ? 1 : 0)}</text>`; } $("#growth-grid").innerHTML = grid;
    const ages = [0,2,4,6,8,10,12], paths = [0,1,2].map((idx) => ages.map((age, i) => `${i ? "L" : "M"}${x(age).toFixed(1)} ${y(band(growthMetric, age)[idx]).toFixed(1)}`).join(" ")); $("#growth-reference").innerHTML = `<path class="ref-line" d="${paths[0]}"/><path class="ref-line p50" d="${paths[1]}"/><path class="ref-line" d="${paths[2]}"/>`;
    const points = entries.filter((e) => Number(e[growthMetric])).map((e) => ({ x: x(monthAgeAt(e.date)), y: y(Number(e[growthMetric])), value: Number(e[growthMetric]) })); $("#growth-line").setAttribute("d", points.map((p,i) => `${i ? "L" : "M"}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(" ")); $("#growth-points").innerHTML = points.map((p) => `<circle class="growth-point" cx="${p.x}" cy="${p.y}" r="4"/>`).join("");
  }
  function renderGrowth() {
    const empty = demoStatus === "growth-empty", entries = empty ? [] : [...(state.growth[activeBaby().id] || [])].sort((a,b) => a.date.localeCompare(b.date)), metricEntries = entries.filter((e) => Number(e[growthMetric])), latest = metricEntries.at(-1), previous = metricEntries.at(-2), labels = { weight: "体重", height: "身高", head: "头围" }, unit = growthMetric === "weight" ? "kg" : "cm";
    $("#corrected-row").hidden = !activeBaby().dueDate; $("#corrected-copy").textContent = activeBaby().dueDate ? `预产期 ${activeBaby().dueDate}` : "需设置预产期"; $("#corrected-toggle").checked = state.settings.correctedAge; $("#growth-label").textContent = `最新${labels[growthMetric]}`; $("#growth-value").textContent = latest ? `${latest[growthMetric]}${unit}` : "—"; $("#growth-change").textContent = latest && previous ? `较上次 ${latest[growthMetric] - previous[growthMetric] >= 0 ? "+" : ""}${(latest[growthMetric] - previous[growthMetric]).toFixed(growthMetric === "weight" ? 2 : 1)}${unit}` : "暂无变化";
    $("#growth-count").textContent = `${entries.length} 次`; $("#growth-history").innerHTML = [...entries].reverse().map((e) => `<div class="history-row"><span>${e.date.slice(5)}</span><span>${e.weight ? `${e.weight}kg` : "—"}</span><span>${e.height ? `${e.height}cm` : "—"}</span><span>${e.head ? `${e.head}cm` : "—"}</span></div>`).join(""); $(".growth-panel").hidden = empty || !entries.length; $(".history-section").hidden = empty || !entries.length; $("#growth-empty").hidden = !empty && entries.length > 0; if (!empty && entries.length) renderGrowthChart(entries);
  }
  function renderAccount() { const baby = activeBaby(); $("#device-id").textContent = getDeviceId(); $("#account-baby").innerHTML = `<span class="account-avatar" style="--baby-color:${THEME_COLORS[baby.theme] || THEME_COLORS.coral}">${escapeHTML(baby.name.slice(0,1))}</span><div><strong>${escapeHTML(baby.name)}</strong><small>${escapeHTML(`${baby.birthday} · ${baby.sex} · ${babyAge(baby)}`)}</small></div><button class="secondary-button compact" type="button" data-settings="baby">管理</button>`; }
  function renderMenu() {
    const groups = [
      ["查找", [["search","搜索记录","按类型、内容或备注查找","open:search-dialog"],["health","状态演示","正常、空态、错误、离线等","open:status-dialog"]]],
      ["宝宝与记录", [["growth","宝宝管理","资料、预产期、主题色","settings:baby"],["note","项目显隐与排序","同步影响首页快捷带","settings:items"]]],
      ["喂养与提醒", [["nursing","喂奶设置","计时入口、记录时刻、奶量步进","settings:feeding"],["health","本机提醒","间隔示例，不申请系统权限","settings:reminders"]]],
      ["显示", [["other","显示设置","深色、时间、单位、减少动态","settings:display"]]],
      ["数据与关于", [["note","TXT / PDF 导出","TXT 可下载 · PDF Stub","settings:export"],["health","清除全部数据","两步确认后清除本机数据","clear"],["other","关于乐记","版本、包名与隐私说明","settings:about"]]]
    ];
    $("#menu-groups").innerHTML = groups.map(([title, rows]) => `<section class="menu-group"><h2>${title}</h2>${rows.map(([icon,title,sub,action]) => { const attrs = action.startsWith("open:") ? `data-open="${action.slice(5)}"` : action.startsWith("settings:") ? `data-settings="${action.slice(9)}"` : action === "clear" ? "data-clear-all" : ""; return `<button class="menu-row ${action === "clear" ? "danger" : ""}" type="button" ${attrs}><span class="menu-icon">${ICONS[icon] || ICONS.other}</span><span><strong>${title}</strong><small>${sub}</small></span><svg aria-hidden="true" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg></button>`; }).join("")}</section>`).join("");
  }
  function renderSession() { syncNursing(); const nursing = session.nursing && (session.nursing.leftSec || session.nursing.rightSec || session.nursing.runningSide), sleep = session.sleep?.startedAt, bar = $("#session-bar"); if (!nursing && !sleep) { bar.hidden = true; return; } bar.hidden = false; if (sleep) { const sec = Math.max(0, Math.floor((Date.now() - session.sleep.startedAt) / 1000)); $("#session-label").textContent = "睡眠记录中"; $("#session-detail").textContent = formatTimer(sec); } else { const total = session.nursing.leftSec + session.nursing.rightSec; $("#session-label").textContent = session.nursing.runningSide ? `母乳${session.nursing.runningSide === "left" ? "左侧" : "右侧"}计时中` : "母乳计时已暂停"; $("#session-detail").textContent = `${formatTimer(total)} · 左${formatTimer(session.nursing.leftSec)} / 右${formatTimer(session.nursing.rightSec)}`; } }
  function renderAll() { applySettings(); renderHeader(); renderRecords(); renderQuick(); renderSummary(); renderGrowth(); renderAccount(); renderMenu(); renderSession(); }
  function setView(view) { if (!$("#view-" + view)) return; activeView = view; $$(".view").forEach((el) => { el.hidden = el.dataset.view !== view; el.classList.toggle("is-active", el.dataset.view === view); }); $$(".nav-item").forEach((el) => { const on = el.dataset.viewTarget === view; el.classList.toggle("is-active", on); on ? el.setAttribute("aria-current", "page") : el.removeAttribute("aria-current"); }); const isRecords = view === "records"; $("#quick-band").hidden = !isRecords; $("#record-fab").hidden = !isRecords; document.documentElement.scrollTop = 0; }

  function renderRecordSheet() { $("#record-type-list").innerHTML = TYPES.map((type) => `<button class="record-type-button" type="button" data-record-type="${type.id}">${typeIcon(type.id)}<strong>${type.name}</strong><small>${type.tip}</small></button>`).join(""); }
  function choice(name, value, label, checked, icon = "") { return `<label class="choice"><input type="radio" name="${name}" value="${value}" ${checked ? "checked" : ""}><span>${icon}${label}</span></label>`; }
  function baseFields(record) { return `<div class="field-row"><label class="field"><span>日期</span><input name="date" type="date" value="${record?.date || selectedDate}" required></label><label class="field"><span>时间</span><input name="time" type="time" value="${record?.time || timeNow()}" required></label></div><label class="field"><span>备注（可选）</span><textarea name="note" rows="2" maxlength="200" placeholder="补充这条记录">${escapeHTML(record?.note || "")}</textarea></label>`; }
  function stoolFields(payload = {}) { const colors = [[0,"未选","var(--bg)"],[1,"白","oklch(96% 0 0)"],[2,"黄","oklch(84% .15 88)"],[3,"橙","oklch(75% .17 58)"],[4,"褐","oklch(55% .10 60)"],[5,"绿","oklch(68% .14 145)"],[6,"红","oklch(62% .19 25)"],[7,"黑","oklch(28% 0 0)"]]; return `<fieldset class="field"><legend>便量 1–4</legend><div class="choice-row" style="--count:4">${[1,2,3,4].map((n) => choice("stoolAmount",n,["一点","偏少","正常","偏多"][n-1],Number(payload.stoolAmount || 3)===n,`<i class="stool-shape s${n}"></i>`)).join("")}</div></fieldset><fieldset class="field"><legend>软硬 1–4</legend><div class="choice-row" style="--count:4">${[1,2,3,4].map((n) => choice("stoolConsistency",n,["稀","偏软","正常","偏硬"][n-1],Number(payload.stoolConsistency || 3)===n,`<i class="stool-shape s${5-n}"></i>`)).join("")}</div></fieldset><fieldset class="field"><legend>颜色 0–7</legend><div class="choice-row" style="--count:4">${colors.map(([n,label,color]) => choice("stoolColor",n,label,Number(payload.stoolColor || 0)===n,`<i class="choice-icon" style="--choice-color:${color}"></i>`)).join("")}</div></fieldset>`; }
  function peeFields(payload = {}) { return `<fieldset class="field"><legend>尿尿量</legend><div class="choice-row">${[1,2,3].map((n) => choice("peeAmount",n,["小","中","大"][n-1],Number(payload.peeAmount || 2)===n,`<span style="font-size:${10+n*3}px;color:var(--pee)">●</span>`)).join("")}</div></fieldset>`; }
  function buildRecordFields(type, record) {
    const p = record?.payload || {}, step = state.settings.amountStep || 5; let html = "";
    if (type.mode === "nursing") html = `<div class="field-row"><label class="field"><span>左侧（秒）</span><input name="leftSec" type="number" min="0" value="${p.leftSec || 0}"></label><label class="field"><span>右侧（秒）</span><input name="rightSec" type="number" min="0" value="${p.rightSec || 0}"></label></div><fieldset class="field"><legend>开始侧</legend><div class="choice-row">${choice("order","left","左侧",(p.order||"left")==="left")}${choice("order","right","右侧",p.order==="right")}</div></fieldset><label class="field"><span>估算奶量 ml（可选）</span><input name="amountMl" type="number" min="0" max="500" value="${p.amountMl || 0}"></label>`;
    if (type.mode === "milk") { const value = p.amountMl || (type.id === "pump_express" ? 60 : 90), quick = [value-step,value,value+step,value+step*2].map((v) => Math.max(5, Math.round(v/step)*step)); html = `<label class="field"><span>奶量（当前步进 ${step}ml，可手输任意正整数）</span><div class="amount-stepper"><button type="button" data-amount-adjust="-${step}">−</button><input id="amount-input" name="amountMl" type="number" min="1" max="500" value="${value}" required><button type="button" data-amount-adjust="${step}">+</button></div><span class="quick-amounts">${quick.map((v) => `<button type="button" data-amount-value="${v}">${v}ml</button>`).join("")}</span></label>${type.id === "formula" ? `<div class="field-row"><label class="field"><span>冲调量 ml（可选）</span><input name="preparedMl" type="number" min="0" max="500" value="${p.preparedMl || ""}"></label><label class="field"><span>喂养时长 分（可选）</span><input name="durationMin" type="number" min="0" max="180" value="${p.durationMin || ""}"></label></div>` : ""}`; }
    if (type.mode === "pee") html = peeFields(p);
    if (type.mode === "poop") html = stoolFields(p);
    if (type.mode === "both") html = peeFields(p) + stoolFields(p);
    if (type.mode === "sleep") html = `<div class="field-row"><label class="field"><span>睡下</span><input name="start" type="time" value="${p.start || record?.time || timeNow()}" required></label><label class="field"><span>醒来</span><input name="end" type="time" value="${p.end || timeNow()}" required></label></div><fieldset class="field"><legend>睡眠类型</legend><div class="choice-row">${choice("nap","false","夜间/长睡",!p.nap)}${choice("nap","true","午睡",Boolean(p.nap))}</div></fieldset>`;
    if (type.mode === "temperature") html = `<div class="field-row"><label class="field"><span>体温</span><input name="temperature" type="number" min="34" max="108" step="0.1" value="${p.unit === "f" ? ((p.celsius || 36.5)*9/5+32).toFixed(1) : (p.celsius || 36.5)}" required></label><fieldset class="field"><legend>单位</legend><div class="choice-row">${choice("unit","c","℃",p.unit !== "f")}${choice("unit","f","℉",p.unit === "f")}</div></fieldset></div><div class="medical-note" id="fever-note" hidden>月龄小于3个月且体温≥38℃，建议及时就医。本提示不构成医疗建议。</div>`;
    if (["text","diary"].includes(type.mode)) html = `<label class="field"><span>${type.mode === "diary" ? "日记正文" : "内容"}</span><textarea name="body" rows="5" maxlength="800" required>${escapeHTML(p.body || record?.note || "")}</textarea></label><div class="photo-placeholder"><span id="photo-count">${p.photos || 0} 张照片占位</span><button type="button" data-add-photo>添加照片</button></div>`;
    if (type.mode === "interval") html = `<div class="field-row"><label class="field"><span>开始</span><input name="start" type="time" value="${p.start || record?.time || timeNow()}" required></label><label class="field"><span>结束</span><input name="end" type="time" value="${p.end || timeNow()}" required></label></div>`;
    if (type.mode === "symptom") html = `<fieldset class="field"><legend>程度</legend><div class="choice-row">${["轻微","一般","明显"].map((v) => choice("severity",v,v,(p.severity||"一般")===v)).join("")}</div></fieldset><label class="field"><span>症状描述</span><textarea name="description" rows="3" maxlength="200">${escapeHTML(p.description || "")}</textarea></label>`;
    if (type.mode === "medicine") html = `<div class="field-row"><label class="field"><span>药品名称</span><input name="medicineName" type="text" maxlength="50" value="${escapeHTML(p.name || "")}" required></label><label class="field"><span>剂量</span><input name="dose" type="text" maxlength="30" value="${escapeHTML(p.dose || "")}" placeholder="如 0.5ml"></label></div>`;
    if (type.mode === "hospital") html = `<label class="field"><span>就诊原因</span><input name="reason" type="text" maxlength="100" value="${escapeHTML(p.reason || "")}" required></label><label class="field"><span>医嘱</span><textarea name="advice" rows="3" maxlength="300">${escapeHTML(p.advice || "")}</textarea></label>`;
    if (type.mode === "other") html = `<label class="field"><span>标题</span><input name="customTitle" type="text" maxlength="30" value="${escapeHTML(p.title || (type.id === "custom" ? "自定义项目" : ""))}" required></label><label class="field"><span>详情</span><textarea name="customDetail" rows="3" maxlength="200">${escapeHTML(p.detail || "")}</textarea></label>`;
    if (type.mode === "measure") html = `<label class="field"><span>${type.name}（${type.unit}）</span><input name="measureValue" type="number" min="0.1" max="250" step="0.1" value="${p.value || ""}" required></label>`;
    if (type.mode === "food") html = `<label class="field"><span>内容</span><input name="foodContent" type="text" maxlength="80" value="${escapeHTML(p.content || "")}" required></label><label class="field"><span>量（可选）</span><input name="foodAmount" type="text" maxlength="30" value="${escapeHTML(p.amount || "")}" placeholder="如 半碗"></label>`;
    if (type.mode === "vaccine") html = `<label class="field"><span>疫苗名称</span><input name="vaccineName" type="text" maxlength="80" value="${escapeHTML(p.name || "")}" required></label><label class="field"><span>批次/针次（可选）</span><input name="vaccineBatch" type="text" maxlength="50" value="${escapeHTML(p.batch || "")}"></label>`;
    if (type.id === "growth_measure") html = `<div class="field-row"><label class="field"><span>体重 kg</span><input name="growthWeight" type="number" min="0.5" max="100" step="0.01" required></label><label class="field"><span>身高 cm</span><input name="growthHeight" type="number" min="20" max="220" step="0.1" required></label></div><label class="field"><span>头围 cm</span><input name="growthHead" type="number" min="15" max="80" step="0.1" required></label>`;
    return html + baseFields(record);
  }
  function openRecord(typeId, recordId = "") {
    const type = typeId === "growth_measure" ? { id: "growth_measure", name: "成长测量", mode: "growth", icon: "growth", color: "growth", group: "growth" } : typeOf(typeId), record = recordId ? allRecords().find((r) => r.id === recordId) : null;
    if (!record && type.id === "nursing") { openTimer(); return; }
    const dialog = $("#record-dialog"); dialog.dataset.type = type.id; dialog.dataset.recordId = record?.id || ""; dialog.dataset.photos = String(record?.payload?.photos || 0); $("#record-dialog-kicker").textContent = record ? "编辑记录" : type.tip || "添加记录"; $("#record-dialog-title").textContent = type.name; $("#record-fields").innerHTML = buildRecordFields(type, record); $("#delete-record").hidden = !record; openDialog(dialog); checkFever();
  }
  function formPayload(type, form, existing = {}) {
    const fd = new FormData(form), val = (name) => fd.get(name), num = (name) => Number(val(name) || 0), p = { ...existing };
    if (type.mode === "nursing") Object.assign(p,{ leftSec:num("leftSec"), rightSec:num("rightSec"), order:val("order"), amountMl:num("amountMl") });
    if (type.mode === "milk") Object.assign(p,{ amountMl:num("amountMl"), preparedMl:num("preparedMl"), durationMin:num("durationMin") });
    if (["pee","both"].includes(type.mode)) p.peeAmount=num("peeAmount")||2;
    if (["poop","both"].includes(type.mode)) Object.assign(p,{ stoolAmount:num("stoolAmount")||3, stoolConsistency:num("stoolConsistency")||3, stoolColor:num("stoolColor") });
    if (type.mode === "sleep") { Object.assign(p,{ start:val("start"), end:val("end"), nap:val("nap")==="true" }); p.durationMin=minutesBetween(p.start,p.end); p.anomaly=p.durationMin>720; }
    if (type.mode === "temperature") { const raw=num("temperature"), unit=val("unit"); Object.assign(p,{ unit, celsius:unit==="f"?(raw-32)*5/9:raw }); }
    if (["text","diary"].includes(type.mode)) Object.assign(p,{ body:val("body")?.trim(), photos:Number($("#record-dialog").dataset.photos||0) });
    if (type.mode === "interval") Object.assign(p,{ start:val("start"), end:val("end"), durationMin:minutesBetween(val("start"),val("end")) });
    if (type.mode === "symptom") Object.assign(p,{ severity:val("severity"), description:val("description")?.trim() });
    if (type.mode === "medicine") Object.assign(p,{ name:val("medicineName")?.trim(), dose:val("dose")?.trim() });
    if (type.mode === "hospital") Object.assign(p,{ reason:val("reason")?.trim(), advice:val("advice")?.trim() });
    if (type.mode === "other") Object.assign(p,{ title:val("customTitle")?.trim(), detail:val("customDetail")?.trim() });
    if (type.mode === "measure") Object.assign(p,{ value:num("measureValue"), unit:type.unit });
    if (type.mode === "food") Object.assign(p,{ content:val("foodContent")?.trim(), amount:val("foodAmount")?.trim() });
    if (type.mode === "vaccine") Object.assign(p,{ name:val("vaccineName")?.trim(), batch:val("vaccineBatch")?.trim() });
    return p;
  }
  function saveRecordForm(event) {
    event.preventDefault(); const dialog = $("#record-dialog"), typeId = dialog.dataset.type, fd = new FormData(event.target), recordId = dialog.dataset.recordId;
    if (typeId === "growth_measure") { const entry = { id:uid("growth"), date:fd.get("date"), weight:Number(fd.get("growthWeight")), height:Number(fd.get("growthHeight")), head:Number(fd.get("growthHead")), note:fd.get("note")?.trim() }; (state.growth[activeBaby().id] ||= []).push(entry); (state.records[activeBaby().id] ||= []).push({ id:uid("weight"), date:entry.date, time:fd.get("time"), type:"weight", note:entry.note, payload:{ value:entry.weight, unit:"kg", growthId:entry.id } }); persist(); closeDialog(dialog); demoStatus="normal"; renderAll(); setView("growth"); showToast("成长测量已保存"); return; }
    const type = typeOf(typeId), records = (state.records[activeBaby().id] ||= []), old = records.find((r) => r.id === recordId), payload = formPayload(type, event.target, old?.payload || {}), item = { id:old?.id || uid(typeId), date:fd.get("date"), time:fd.get("time"), type:typeId, note:fd.get("note")?.trim() || "", payload };
    if (old) Object.assign(old,item); else records.push(item);
    if (type.mode === "measure") { let entry = (state.growth[activeBaby().id] ||= []).find((g) => g.date === item.date); if (!entry) { entry={id:uid("growth"),date:item.date}; state.growth[activeBaby().id].push(entry); } entry[type.id] = payload.value; }
    demoStatus="normal"; persist(); closeDialog(dialog); renderAll(); if (payload.anomaly) showToast("睡眠区间较长，已标记 ! 并保存"); else showToast(old ? "记录已更新" : "记录已保存");
  }
  function quickRecord(typeId) { if (typeId === "pee") { addSimple("pee",{peeAmount:2},"已记录尿尿 · 中"); return; } if (typeId === "bath") { addSimple("bath",{},"洗澡记录已保存"); return; } if (typeId === "sleep") { session.sleep ? finishSleep() : startSleep(); return; } openRecord(typeId); }
  function addSimple(type, payload, toast) { (state.records[activeBaby().id] ||= []).push({ id:uid(type), date:selectedDate, time:timeNow(), type, note:"", payload }); persist(); demoStatus="normal"; renderAll(); showToast(toast); }

  function formatTimer(seconds) { return `${pad(Math.floor(seconds/60))}:${pad(seconds%60)}`; }
  function syncNursing() { const n=session.nursing; if (!n?.runningSide || !n.lastTick) return; const elapsed=Math.max(0,Math.floor((Date.now()-n.lastTick)/1000)); if (elapsed) { n[`${n.runningSide}Sec`] = Number(n[`${n.runningSide}Sec`]||0)+elapsed; n.lastTick=Date.now(); persistSession(); } }
  function openTimer() { syncNursing(); session.nursing ||= { leftSec:0,rightSec:0,runningSide:null,lastTick:null,lastSide:"right",startedAt:Date.now() }; persistSession(); updateTimerUI(); openDialog($("#timer-dialog")); }
  function startTimer(side) { syncNursing(); session.nursing ||= { leftSec:0,rightSec:0,lastSide:"right",startedAt:Date.now() }; if (session.nursing.runningSide === side) { pauseTimer(); return; } session.nursing.runningSide=side; session.nursing.lastTick=Date.now(); session.nursing.lastSide=side; persistSession(); updateTimerUI(); renderSession(); }
  function pauseTimer() { syncNursing(); if (!session.nursing) return; session.nursing.runningSide=null; session.nursing.lastTick=null; persistSession(); updateTimerUI(); renderSession(); }
  function updateTimerUI() { syncNursing(); const n=session.nursing || {leftSec:0,rightSec:0}; $("#timer-left").textContent=formatTimer(n.leftSec||0); $("#timer-right").textContent=formatTimer(n.rightSec||0); $("#last-side").textContent=`上次结束：${n.lastSide==="left"?"左侧":"右侧"}`; $$('[data-timer-side]').forEach((b)=>{const on=n.runningSide===b.dataset.timerSide;b.classList.toggle("is-running",on);$("small",b).textContent=on?"正在计时":n[`${b.dataset.timerSide}Sec`]?"已暂停":"点按开始";}); $("#timer-pause").disabled=!n.runningSide; }
  function finishNursing() { syncNursing(); const n=session.nursing; if (!n || !(n.leftSec+n.rightSec)) { showToast("请先开始左侧或右侧计时"); return; } const started=new Date(n.startedAt||Date.now()), recordTime=state.settings.recordAt==="start"?`${pad(started.getHours())}:${pad(started.getMinutes())}`:timeNow(); (state.records[activeBaby().id] ||= []).push({id:uid("nursing"),date:selectedDate,time:recordTime,type:"nursing",note:"",payload:{leftSec:n.leftSec,rightSec:n.rightSec,order:n.lastSide||"left",amountMl:0}}); session.nursing=null; persist();persistSession();closeDialog($("#timer-dialog"));renderAll();showToast("母乳记录已保存"); }
  function startSleep() { if (session.nursing?.runningSide) { showToast("请先暂停母乳计时"); return; } session.sleep={startedAt:Date.now()};persistSession();renderAll();showToast("已开始睡眠计时"); }
  function finishSleep() { if (!session.sleep) return; const start=new Date(session.sleep.startedAt), end=new Date(), minutes=Math.max(1,Math.round((end-start)/60000)), startTime=`${pad(start.getHours())}:${pad(start.getMinutes())}`; (state.records[activeBaby().id] ||= []).push({id:uid("sleep"),date:localISO(start),time:startTime,type:"sleep",note:"",payload:{start:startTime,end:timeNow(),durationMin:minutes,nap:start.getHours()>=6&&start.getHours()<18,anomaly:minutes>720}});session.sleep=null;persist();persistSession();renderAll();showToast("睡眠记录已保存"); }

  function settingsSwitch(id,label,copy,checked) { return `<label class="switch-row"><span><strong>${label}</strong><small>${copy}</small></span><input id="${id}" type="checkbox" role="switch" ${checked?"checked":""}><i aria-hidden="true"></i></label>`; }
  function openSettings(section, adding = false) { const dialog=$("#settings-dialog"), baby=activeBaby();dialog.dataset.section=section;dialog.dataset.adding=String(adding);let title="设置",content="";
    if(section==="baby"){title=adding?"添加宝宝":"宝宝设置";content=`<label class="field"><span>昵称</span><input name="babyName" type="text" maxlength="12" value="${adding?"":escapeHTML(baby.name)}" required></label><div class="field-row"><label class="field"><span>出生日期</span><input name="birthday" type="date" value="${adding?localISO():baby.birthday}" required></label><label class="field"><span>预产期（可选）</span><input name="dueDate" type="date" value="${adding?"":baby.dueDate}"></label></div><fieldset class="field"><legend>性别</legend><div class="choice-row">${["女宝","男宝","未设置"].map(v=>choice("sex",v,v,(adding?"未设置":baby.sex)===v)).join("")}</div></fieldset><fieldset class="field"><legend>档案识别色（主界面仍保持珊瑚）</legend><div class="theme-choices">${Object.entries(THEME_COLORS).map(([key,color])=>`<label class="theme-choice"><input type="radio" name="theme" value="${key}" ${(adding?"coral":baby.theme)===key?"checked":""}><span style="--theme-color:${color}">${{coral:"珊瑚",mint:"薄荷",berry:"莓果",sky:"天空"}[key]}</span></label>`).join("")}</div></fieldset>${adding?"":'<button class="secondary-button full" type="button" data-add-baby>添加另一个宝宝</button>'}`;if(!adding){const babyList=state.babies.map(item=>{const current=item.id===state.activeBabyId;return `<button class="menu-row${current?" is-current":""}" type="button" data-switch-baby="${item.id}" ${current?"aria-current=\"true\"":""}><span class="account-avatar" style="width:32px;height:32px;font-size:12px;--baby-color:${THEME_COLORS[item.theme]||THEME_COLORS.coral}">${escapeHTML(item.name.slice(0,1))}</span><span><strong>${escapeHTML(item.name)}</strong><small>${escapeHTML(babyAge(item))}${current?" · 当前":""}</small></span>${current?'<span class="current-check" aria-hidden="true">✓</span>':'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg>'}</button>`;}).join("");content=`<p class="settings-section-title">切换宝宝</p><div class="config-list">${babyList}</div><p class="settings-section-title">当前宝宝资料</p>${content}`;}}
    if(section==="items"){title="项目显隐与排序";const order=[...(state.quickItems||QUICK_DEFAULT),...TYPES.map(t=>t.id).filter(id=>!(state.quickItems||[]).includes(id))];content=`<p class="settings-note">前六个未隐藏项目显示在首页快捷带；全部项目仍可从“更多”进入。</p><div class="config-list">${order.map((id,index)=>{const t=typeOf(id),hidden=(state.hiddenItems||[]).includes(id);return `<div class="config-row" data-config-id="${id}">${typeIcon(id)}<strong>${t.name}</strong><span class="config-actions"><button type="button" data-config-move="up" ${index===0?"disabled":""}>↑</button><button type="button" data-config-move="down" ${index===order.length-1?"disabled":""}>↓</button><button type="button" data-config-toggle>${hidden?"显":"隐"}</button></span></div>`;}).join("")}</div>`;}
    if(section==="feeding"){title="喂奶设置";content=`${settingsSwitch("setting-timer","母乳计时入口","关闭后首页不显示母乳快捷入口",state.settings.timerEnabled)}<fieldset class="field"><legend>母乳记录时刻</legend><div class="choice-row">${choice("recordAt","start","开始时",state.settings.recordAt==="start")}${choice("recordAt","end","结束时",state.settings.recordAt!=="start")}</div></fieldset><fieldset class="field"><legend>配方奶/挤出乳步进</legend><div class="choice-row">${[5,10,15].map(n=>choice("amountStep",n,`${n}ml`,Number(state.settings.amountStep)===n)).join("")}</div></fieldset><p class="settings-note">改动会在下一次打开奶量表单时立即生效；仍可手输任意正整数。</p>`;}
    if(section==="display"){title="显示设置";content=`${settingsSwitch("setting-dark","深色模式","夜间降低大面积亮度",state.settings.dark)}${settingsSwitch("setting-relative","相对时间","优先显示几分钟前/几小时前",state.settings.relativeTime)}${settingsSwitch("setting-time24","24 小时制","关闭后显示上午/下午",state.settings.time24)}${settingsSwitch("setting-motion","减少动态","停用非必要过渡",state.settings.reduceMotion)}<fieldset class="field"><legend>测量单位</legend><div class="choice-row">${choice("units","metric","公制",state.settings.units==="metric")}${choice("units","imperial","英制",state.settings.units==="imperial")}</div></fieldset>`;}
    if(section==="reminders"){title="喂奶提醒";content=`${settingsSwitch("setting-reminders","下次喂奶提醒","原型仅保存本机偏好，不发系统通知",state.settings.reminders)}<label class="field"><span>提醒间隔（小时）</span><input name="reminderHours" type="number" min="1" max="12" step="0.5" value="${state.settings.reminderHours}"></label><p class="settings-note">不申请通知权限；家庭成员不会收到逐条推送。</p>`;}
    if(section==="export"){title="导出";content=`<button class="secondary-button full" type="button" data-export-preview>生成 TXT 预览</button><pre class="export-preview" id="export-preview" hidden></pre><button class="secondary-button full" type="button" data-export-download hidden>下载 TXT 文件</button><button class="secondary-button full" type="button" data-pdf-stub>PDF 导出（后续版本）</button><p class="settings-note">TXT 来自当前宝宝的真实本地记录；PDF 仅为明确 Stub。</p>`;}
    if(section==="about"){title="关于乐记";content=`<div class="local-notice"><strong>乐记 · 第二套视觉模板</strong><p>版本 2.0.0-prototype · 包名 com.lezi.babylog</p></div><p class="settings-note">无广告 · 无内购 · 默认本机保存 · 图表仅作家庭记录参考。</p>`;}
    $("#settings-title").textContent=title;$("#settings-content").innerHTML=content;$("#settings-form .form-actions").hidden=["items","export","about"].includes(section);openDialog(dialog); }
  function saveSettings(event){event.preventDefault();const section=$("#settings-dialog").dataset.section,fd=new FormData(event.target);
    if(section==="baby"){const adding=$("#settings-dialog").dataset.adding==="true",id=adding?uid("baby"):activeBaby().id,baby=adding?{id}:activeBaby();Object.assign(baby,{name:fd.get("babyName")?.trim(),birthday:fd.get("birthday"),dueDate:fd.get("dueDate"),sex:fd.get("sex"),theme:fd.get("theme")});if(adding){state.babies.push(baby);state.records[id]=[];state.growth[id]=[];state.activeBabyId=id;}}
    if(section==="feeding")Object.assign(state.settings,{timerEnabled:$("#setting-timer").checked,recordAt:fd.get("recordAt"),amountStep:Number(fd.get("amountStep"))});
    if(section==="display")Object.assign(state.settings,{dark:$("#setting-dark").checked,relativeTime:$("#setting-relative").checked,time24:$("#setting-time24").checked,reduceMotion:$("#setting-motion").checked,units:fd.get("units")});
    if(section==="reminders")Object.assign(state.settings,{reminders:$("#setting-reminders").checked,reminderHours:Number(fd.get("reminderHours"))||3});
    persist();closeDialog($("#settings-dialog"));renderAll();showToast("设置已保存到本机"); }
  function configMove(id,direction){const full=[...(state.quickItems||QUICK_DEFAULT),...TYPES.map(t=>t.id).filter(x=>!(state.quickItems||[]).includes(x))],idx=full.indexOf(id),next=idx+(direction==="up"?-1:1);if(idx<0||next<0||next>=full.length)return;[full[idx],full[next]]=[full[next],full[idx]];state.quickItems=full;persist();openSettings("items");renderQuick();}
  function configToggle(id){state.hiddenItems||=[];state.hiddenItems.includes(id)?state.hiddenItems=state.hiddenItems.filter(x=>x!==id):state.hiddenItems.push(id);persist();openSettings("items");renderQuick();}
  function exportText(){const baby=activeBaby(),rows=[`乐记 · ${baby.name}的记录`,`导出时间：${new Date().toLocaleString("zh-CN")}`,`宝宝：${baby.name} · ${baby.birthday} · ${baby.sex}`,`本机 ID：${getDeviceId()}`,"","--- 记录 ---",""];[...allRecords()].sort((a,b)=>`${a.date}${a.time}`.localeCompare(`${b.date}${b.time}`)).forEach(r=>{rows.push(`[${r.date} ${r.time}] ${typeOf(r.type).name}`);rows.push(`  ${recordDetail(r)}`);if(r.note)rows.push(`  备注：${r.note}`);rows.push("");});if(!allRecords().length)rows.push("暂无记录。");return rows.join("\n");}

  function renderCalendar(){const year=calendarCursor.getFullYear(),month=calendarCursor.getMonth(),first=new Date(year,month,1).getDay(),count=new Date(year,month+1,0).getDate(),data=new Set(allRecords().map(r=>r.date));$("#calendar-month").textContent=`${year}年${month+1}月`;let html=["日","一","二","三","四","五","六"].map(d=>`<span class="calendar-dow">${d}</span>`).join("");for(let i=0;i<first;i++)html+='<button class="calendar-day" type="button" disabled></button>';for(let d=1;d<=count;d++){const iso=`${year}-${pad(month+1)}-${pad(d)}`;html+=`<button class="calendar-day ${iso===localISO()?"today":""} ${iso===selectedDate?"selected":""} ${data.has(iso)?"has-data":""}" type="button" data-calendar-date="${iso}" ${iso>localISO()?"disabled":""}>${d}</button>`;}$("#calendar-grid").innerHTML=html;}
  function renderSearch(){const q=$("#search-input").value.trim().toLowerCase(),all=$("#search-all-babies").checked,ids=all?state.babies.map(b=>b.id):[activeBaby().id],matches=[];if(q)ids.forEach(id=>(state.records[id]||[]).forEach(r=>{const baby=state.babies.find(b=>b.id===id)||activeBaby(),hay=`${typeOf(r.type).name} ${recordDetail(r)} ${r.note} ${baby.name}`.toLowerCase();if(hay.includes(q))matches.push({r,baby});}));matches.sort((a,b)=>`${b.r.date}${b.r.time}`.localeCompare(`${a.r.date}${a.r.time}`));$("#search-count").textContent=q?`${matches.length} 条结果`:"输入关键词开始查找";$("#search-results").innerHTML=!q?'<p class="search-empty">可搜索记录类型、字段内容、日记或备注。</p>':matches.length?matches.map(({r,baby})=>`<button class="search-result" type="button" data-search-record="${r.id}" data-search-baby="${baby.id}">${typeIcon(r.type)}<span><strong>${typeOf(r.type).name} · ${escapeHTML(baby.name)}</strong><small>${r.date} ${r.time} · ${escapeHTML(recordDetail(r))}</small></span></button>`).join(""):'<p class="search-empty">没有匹配记录，试试更短的关键词。</p>';}
  function openConfirm(title,copy,action,extra=""){ $("#confirm-title").textContent=title;$("#confirm-copy").textContent=copy;$("#confirm-extra").innerHTML=extra;confirmHandler=action;openDialog($("#confirm-dialog"));}
  function clearDataFlow(){openConfirm("清除全部本机数据？","第一步：继续后仍需输入“清除”才能执行。",()=>{openConfirm("最后确认","第二步：输入“清除”以删除宝宝、记录、成长数据与设置。",()=>{if($("#clear-word")?.value.trim()!=="清除"){showToast("请输入“清除”");return;}localStorage.removeItem(STORAGE_KEY);localStorage.removeItem(SESSION_KEY);state=blankState();session={nursing:null,sleep:null};persist();persistSession();closeDialog($("#confirm-dialog"));renderAll();openOnboarding(0);showToast("本机数据已清除");},'<label class="field"><span>确认文字</span><input id="clear-word" type="text" placeholder="清除" autocomplete="off"></label>');});}
  function openOnboarding(step=0,temp={}){const dialog=$("#onboarding-dialog");dialog.dataset.step=String(step);dialog.dataset.temp=JSON.stringify(temp);let html="";if(step===0)html=`<div class="onboarding-screen"><span class="onboarding-mark">乐</span><h1 id="onboarding-title">从今天开始记</h1><p>喂养、睡眠、排泄与成长记录默认只保存在这台设备。</p><div class="onboarding-art" aria-hidden="true"></div><div class="onboarding-actions"><button class="primary-button full" type="button" data-onboarding-next>开始创建宝宝</button><button class="secondary-button full" type="button" data-onboarding-join>加入家庭（后续版本）</button>${state.babies.length?'<button class="secondary-button full" type="button" data-close>继续使用现有数据</button>':""}</div></div>`;if(step===1)html=`<div class="onboarding-screen"><span class="onboarding-mark">1</span><h1 id="onboarding-title">宝宝资料</h1><p>昵称和生日用于区分档案与计算日龄。</p><form id="onboarding-profile"><label class="field"><span>昵称</span><input name="name" type="text" maxlength="12" required></label><label class="field"><span>生日</span><input name="birthday" type="date" max="${localISO()}" value="${localISO()}" required></label><label class="field"><span>预产期（可选）</span><input name="dueDate" type="date"></label><fieldset class="field"><legend>性别</legend><div class="choice-row">${["女宝","男宝","未设置"].map(v=>choice("sex",v,v,v==="未设置")).join("")}</div></fieldset><button class="primary-button full" type="submit">下一步</button></form></div>`;if(step===2)html=`<div class="onboarding-screen"><span class="onboarding-mark">2</span><h1 id="onboarding-title">选择识别色</h1><p>识别色用于宝宝头像和档案提示；乐记主界面保持珊瑚粉。</p><form id="onboarding-theme-form"><div class="theme-choices">${Object.entries(THEME_COLORS).map(([key,color])=>`<label class="theme-choice"><input type="radio" name="theme" value="${key}" ${key==="coral"?"checked":""}><span style="--theme-color:${color}">${{coral:"珊瑚",mint:"薄荷",berry:"莓果",sky:"天空"}[key]}</span></label>`).join("")}</div><div class="onboarding-art" aria-hidden="true"></div><button class="primary-button full" type="submit">进入乐记</button></form></div>`;$("#onboarding-content").innerHTML=html;openDialog(dialog);}
  function finishOnboarding(theme){const temp=JSON.parse($("#onboarding-dialog").dataset.temp||"{}"),id=uid("baby"),baby={id,name:temp.name,birthday:temp.birthday,dueDate:temp.dueDate||"",sex:temp.sex||"未设置",theme};state.babies.push(baby);state.records[id]=[];state.growth[id]=[];state.activeBabyId=id;persist();closeDialog($("#onboarding-dialog"));selectedDate=localISO();demoStatus="normal";renderAll();setView("records");showToast(`欢迎，${baby.name}`);}
  function applyStatus(value){demoStatus=value;closeDialog($("#status-dialog"));if(value==="summary-empty")setView("summary");else if(value==="growth-empty")setView("growth");else if(value==="onboarding"){setView("records");openOnboarding(0);}else if(value==="recording"){setView("records");if(!session.sleep&&!session.nursing)startSleep();}else{setView("records");if(value==="saved")showToast("保存成功");}renderAll();showToast(`已切换：${STATUS.find(s=>s[0]===value)?.[1]||value}`);}

  document.addEventListener("click",(event)=>{
    const open=event.target.closest("[data-open]");if(open){const dialog=$("#"+open.dataset.open);if(dialog){if(dialog.id==="calendar-dialog")renderCalendar();if(dialog.id==="search-dialog")renderSearch();if(dialog.id==="status-dialog"){const input=$(`input[name="demo-status"][value="${demoStatus}"]`);if(input)input.checked=true;}openDialog(dialog);}return;}
    const close=event.target.closest("[data-close]");if(close){closeDialog(close.closest("dialog"));return;}
    const view=event.target.closest("[data-view-target]");if(view){setView(view.dataset.viewTarget);return;}
    const record=event.target.closest("[data-record-type]");if(record){closeDialog($("#record-sheet"));openRecord(record.dataset.recordType);return;}
    const quick=event.target.closest("[data-quick]");if(quick){quickRecord(quick.dataset.quick);return;}
    const edit=event.target.closest("[data-edit-record]");if(edit){const item=allRecords().find(r=>r.id===edit.dataset.editRecord);if(item)openRecord(item.type,item.id);return;}
    const settings=event.target.closest("[data-settings]");if(settings){openSettings(settings.dataset.settings);return;}
    const family=event.target.closest("[data-family-stub]");if(family){showToast("家庭同步将在后续版本开放");return;}
    const retry=event.target.closest("[data-retry-state]");if(retry){demoStatus="normal";renderAll();showToast("已重新载入本机记录");return;}
    const growth=event.target.closest("[data-growth-add]");if(growth){openRecord("growth_measure");return;}
    const amount=event.target.closest("[data-amount-adjust]");if(amount){const input=$("#amount-input");input.value=Math.min(500,Math.max(1,Number(input.value)+Number(amount.dataset.amountAdjust)));return;}
    const amountValue=event.target.closest("[data-amount-value]");if(amountValue){$("#amount-input").value=amountValue.dataset.amountValue;return;}
    const photo=event.target.closest("[data-add-photo]");if(photo){const dialog=$("#record-dialog"),count=Number(dialog.dataset.photos||0)+1;dialog.dataset.photos=String(count);$("#photo-count").textContent=`${count} 张照片占位`;return;}
    const timerSide=event.target.closest("[data-timer-side]");if(timerSide){startTimer(timerSide.dataset.timerSide);return;}
    const addBaby=event.target.closest("[data-add-baby]");if(addBaby){openSettings("baby",true);return;}
    const switchBaby=event.target.closest("[data-switch-baby]");if(switchBaby){
      event.preventDefault();
      event.stopPropagation();
      const nextId=switchBaby.dataset.switchBaby;
      // Already current: do not re-render/toast (avoids ghost-click loops when DOM is rebuilt under the cursor).
      if(!nextId||nextId===state.activeBabyId)return;
      state.activeBabyId=nextId;
      selectedDate=localISO();
      persist();
      renderAll();
      showToast(`已切换到 ${activeBaby().name}`);
      // Rebuild settings after the click finishes so replacing the pressed button cannot re-fire.
      queueMicrotask(()=>openSettings("baby"));
      return;
    }
    const config=event.target.closest("[data-config-move]");if(config){configMove(config.closest("[data-config-id]").dataset.configId,config.dataset.configMove);return;}
    const toggle=event.target.closest("[data-config-toggle]");if(toggle){configToggle(toggle.closest("[data-config-id]").dataset.configId);return;}
    const preview=event.target.closest("[data-export-preview]");if(preview){$("#export-preview").textContent=exportText();$("#export-preview").hidden=false;$("[data-export-download]").hidden=false;return;}
    const download=event.target.closest("[data-export-download]");if(download){const blob=new Blob([exportText()],{type:"text/plain;charset=utf-8"}),url=URL.createObjectURL(blob),a=document.createElement("a");a.href=url;a.download=`乐记-${activeBaby().name}-${localISO()}.txt`;a.click();URL.revokeObjectURL(url);showToast("TXT 文件已开始下载");return;}
    const pdf=event.target.closest("[data-pdf-stub]");if(pdf){showToast("PDF 导出将在后续版本开放");return;}
    const clear=event.target.closest("[data-clear-all]");if(clear){clearDataFlow();return;}
    const date=event.target.closest("[data-calendar-date]");if(date){selectedDate=date.dataset.calendarDate;closeDialog($("#calendar-dialog"));renderAll();return;}
    const search=event.target.closest("[data-search-record]");if(search){state.activeBabyId=search.dataset.searchBaby;const item=allRecords().find(r=>r.id===search.dataset.searchRecord);if(item){selectedDate=item.date;closeDialog($("#search-dialog"));renderAll();setView("records");openRecord(item.type,item.id);}return;}
    const next=event.target.closest("[data-onboarding-next]");if(next){openOnboarding(1);return;}
    const join=event.target.closest("[data-onboarding-join]");if(join){showToast("家庭同步将在后续版本开放");return;}
  });

  $("#previous-day").addEventListener("click",()=>{const d=new Date(`${selectedDate}T12:00:00`);d.setDate(d.getDate()-1);selectedDate=localISO(d);renderAll();});
  $("#next-day").addEventListener("click",()=>{const d=new Date(`${selectedDate}T12:00:00`);d.setDate(d.getDate()+1);selectedDate=localISO(d);renderAll();});
  $("#theme-toggle").addEventListener("click",()=>{state.settings.dark=!state.settings.dark;persist();renderAll();showToast(state.settings.dark?"已开启深色模式":"已切换为浅色模式");});
  $("#summary-range").addEventListener("click",e=>{const b=e.target.closest("[data-range]");if(!b)return;summaryRange=b.dataset.range;$$('[data-range]',e.currentTarget).forEach(x=>x.classList.toggle("is-active",x===b));renderSummary();});
  $("#summary-category").addEventListener("click",e=>{const b=e.target.closest("[data-category]");if(!b)return;summaryCategory=b.dataset.category;$$('[data-category]',e.currentTarget).forEach(x=>x.classList.toggle("is-active",x===b));renderSummary();});
  $("#growth-metric").addEventListener("click",e=>{const b=e.target.closest("[data-metric]");if(!b)return;growthMetric=b.dataset.metric;$$('[data-metric]',e.currentTarget).forEach(x=>x.classList.toggle("is-active",x===b));renderGrowth();});
  $("#corrected-toggle").addEventListener("change",e=>{state.settings.correctedAge=e.target.checked;persist();renderGrowth();});
  $("#record-form").addEventListener("submit",saveRecordForm);
  $("#delete-record").addEventListener("click",()=>{const id=$("#record-dialog").dataset.recordId;openConfirm("删除这条记录？","删除后会立即更新首页、汇总与成长视图。",()=>{state.records[activeBaby().id]=allRecords().filter(r=>r.id!==id);persist();closeDialog($("#confirm-dialog"));renderAll();showToast("记录已删除");});});
  $("#timer-pause").addEventListener("click",pauseTimer);$("#timer-reset").addEventListener("click",()=>{session.nursing={leftSec:0,rightSec:0,runningSide:null,lastTick:null,lastSide:"right",startedAt:Date.now()};persistSession();updateTimerUI();renderSession();});$("#timer-save").addEventListener("click",finishNursing);
  $("#session-open").addEventListener("click",()=>{session.sleep?showToast("睡眠计时正在运行"):openTimer();});$("#session-finish").addEventListener("click",()=>{session.sleep?finishSleep():finishNursing();});
  $("#search-input").addEventListener("input",renderSearch);$("#search-all-babies").addEventListener("change",renderSearch);
  $("#settings-form").addEventListener("submit",saveSettings);
  $("#calendar-prev").addEventListener("click",()=>{calendarCursor.setMonth(calendarCursor.getMonth()-1);renderCalendar();});$("#calendar-next").addEventListener("click",()=>{calendarCursor.setMonth(calendarCursor.getMonth()+1);renderCalendar();});$("#calendar-today").addEventListener("click",()=>{selectedDate=localISO();calendarCursor=new Date();closeDialog($("#calendar-dialog"));renderAll();});
  $("#status-grid").innerHTML=STATUS.map(([id,label,copy])=>`<label class="status-option"><input type="radio" name="demo-status" value="${id}" ${id==="normal"?"checked":""}><span><strong>${label}</strong><small>${copy}</small></span></label>`).join("");
  $("#status-form").addEventListener("submit",e=>{e.preventDefault();applyStatus(new FormData(e.target).get("demo-status"));});
  $("#confirm-action").addEventListener("click",()=>{const fn=confirmHandler;if(fn)fn();});
  $("#onboarding-content").addEventListener("submit",e=>{e.preventDefault();if(e.target.id==="onboarding-profile"){const fd=new FormData(e.target);openOnboarding(2,{name:fd.get("name")?.trim(),birthday:fd.get("birthday"),dueDate:fd.get("dueDate"),sex:fd.get("sex")});}if(e.target.id==="onboarding-theme-form")finishOnboarding(new FormData(e.target).get("theme"));});
  $("#record-fields").addEventListener("input",e=>{if(e.target.name==="temperature")checkFever();});
  function checkFever(){const input=$('[name="temperature"]'),unit=$('[name="unit"]:checked'),note=$("#fever-note");if(!input||!unit||!note)return;const raw=Number(input.value),c=unit.value==="f"?(raw-32)*5/9:raw,months=monthAgeAt(localISO());note.hidden=!(months<3&&c>=38);}
  window.addEventListener("offline",()=>{$("#offline-banner").hidden=false;});window.addEventListener("online",()=>{if(demoStatus!=="offline")$("#offline-banner").hidden=true;});

  renderRecordSheet(); renderAll(); setView(activeView);
  timerInterval=setInterval(()=>{if(session.nursing?.runningSide||session.sleep){renderSession();if($("#timer-dialog").open)updateTimerUI();renderQuick();}},1000);
})();
