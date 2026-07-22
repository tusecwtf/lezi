(() => {
  "use strict";
  const STORAGE_KEY = "leji-prototype-v1"; const SESSION_KEY = "leji-session-v1"; const DEVICE_KEY = "leji-device-id";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const pad = (value) => String(value).padStart(2, "0");
  const icons = {
    breastfeed:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 3c3.4 4.1 5.2 7.2 5.2 10a5.2 5.2 0 0 1-10.4 0c0-2.8 1.8-5.9 5.2-10Z"></path></svg>', bottle:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3ZM9 12h8"></path></svg>', expressed:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3ZM9 14h8"></path></svg>', pump:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3ZM9 14h8"></path></svg>', sleep:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z"></path></svg>', diaper:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M7 4v4.5a5 5 0 0 0 10 0V4M5 7h14M7 17c2.7 1.3 7.3 1.3 10 0"></path></svg>', poop:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M7 4v4.5a5 5 0 0 0 10 0V4M5 7h14M7 17c2.7 1.3 7.3 1.3 10 0M10 10h4"></path></svg>', both:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M7 4v4.5a5 5 0 0 0 10 0V4M5 7h14M7 17c2.7 1.3 7.3 1.3 10 0M10 10h4"></path></svg>', temperature:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 9V2M12 9a4 4 0 1 0 0 8M12 17v5M8 13h8"></path></svg>', memo:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 4h16v16H4zM8 8h8M8 12h8M8 16h5"></path></svg>', diary:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 4h16v16H4zM8 8h8M8 12h8M8 16h5"></path></svg>', bath:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 12h16M6 12v6a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-6M9 4a3 3 0 0 1 6 0v8"></path></svg>', walk:'<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="5" r="2"></circle><path d="M10 22l2-7 2 7M8 13l4-3 4 3M12 10v4"></path></svg>', symptom:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 8v4M12 16h.01M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z"></path></svg>', medication:'<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="3"></rect><path d="M12 8v8M8 12h8"></path></svg>', doctor:'<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"></circle><path d="M6 20v-2a6 6 0 0 1 12 0v2M16 11h4M18 9v4"></path></svg>', growth:'<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 18 9 13l3 3 7-8M15 8h4v4"></path></svg>', other:'<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle></svg>'
  };
  const statusLabels = {
    normal: "正常", loading: "加载中", empty: "记录空", "summary-empty": "汇总空", "growth-empty": "成长空", recording: "记录中", saved: "保存成功", error: "错误", offline: "离线", onboarding: "启动引导"
  };
  const recordTypes = [
    { id:"breastfeed", name:"母乳", kind:"feed", color:"blue" }, { id:"bottle", name:"配方奶", kind:"feed", color:"neutral" }, { id:"expressed", name:"喂挤出乳", kind:"feed", color:"blue" }, { id:"pump", name:"挤奶", kind:"feed", color:"blue" }, { id:"diaper", name:"小便", kind:"care", color:"yellow" }, { id:"poop", name:"便便", kind:"care", color:"cream" }, { id:"both", name:"都有", kind:"care", color:"yellow" }, { id:"sleep", name:"睡眠", kind:"sleep", color:"cream" }, { id:"temperature", name:"体温", kind:"care", color:"red" }, { id:"memo", name:"备忘", kind:"care", color:"neutral" }, { id:"diary", name:"日记", kind:"care", color:"neutral" }, { id:"bath", name:"洗澡", kind:"care", color:"blue" }, { id:"walk", name:"散步", kind:"care", color:"green" }, { id:"symptom", name:"症状", kind:"care", color:"red" }, { id:"medication", name:"用药", kind:"care", color:"neutral" }, { id:"doctor", name:"就医", kind:"care", color:"blue" }, { id:"other", name:"其他", kind:"care", color:"neutral" }
  ];
  const defaultQuickItems = ["breastfeed", "bottle", "expressed", "diaper", "poop", "sleep", "temperature", "memo"];
  const POOP_COLORS = ["黄色", "黄绿色", "绿色", "褐色", "黑色", "红色/血丝", "白色/灰白", "其他"]; const POOP_TEXTURES = ["水样", "稀软", "糊状", "成形"];
  const POOP_AMOUNTS = ["微量", "少量", "适中", "较多"]; const FAMILY_STUB_MSG = "家庭同步将在后续版本开放";
  function localISO(date = new Date()) {
    const copy = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return copy.toISOString().slice(0, 10);
  }
  function isoOffset(offset) {
    const date = new Date(); date.setHours(12, 0, 0, 0); date.setDate(date.getDate() + offset); return localISO(date);
  }
  function timeNow() {
    const now = new Date(); return `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  }
  function makeSeed() {
    const today = isoOffset(0); const yesterday = isoOffset(-1); const threeDaysAgo = isoOffset(-3);
    const eightDaysAgo = isoOffset(-8);
    return {
      version: 1, activeBabyId: "mumu", babies: [
        { id: "mumu", name: "木木", birthday: "2026-05-04", gender: "女宝", dueDate: "" }, { id: "anan", name: "安安", birthday: "2025-08-19", gender: "男宝", dueDate: "2025-09-10" }
      ], records: {
        mumu: [
          { id: "m-feed-1", date: today, time: "08:42", type: "breastfeed", kind: "feed", title: "母乳喂养", detail: "左侧 12 分钟 · 右侧 8 分钟", notes: "状态平稳", meta: { left: 720, right: 480, duration: 1200 } }, { id: "m-diaper-2", date: today, time: "09:26", type: "diaper", kind: "care", title: "换尿布", detail: "尿湿 + 便便 · 适中 · 黄色糊状", notes: "皮肤状态正常", meta: { wet: true, poop: true, amount: "适中", color: "黄色", texture: "糊状" } }, { id: "m-sleep-1", date: today, time: "06:30", type: "sleep", kind: "sleep", title: "睡眠", detail: "2 小时 10 分钟 · 安稳", notes: "", meta: { start: "06:30", end: "08:40", duration: 130 } }, { id: "m-bottle-1", date: today, time: "02:15", type: "bottle", kind: "feed", title: "配方奶", detail: "配方奶 · 90 ml", notes: "拍嗝顺利", meta: { milkType: "配方奶", amount: 90 } }, { id: "m-diaper-1", date: today, time: "05:48", type: "diaper", kind: "care", title: "小便", detail: "尿湿", notes: "", meta: { wet: true, poop: false } }, { id: "m-feed-y", date: yesterday, time: "21:10", type: "breastfeed", kind: "feed", title: "母乳喂养", detail: "左侧 9 分钟 · 右侧 11 分钟", notes: "", meta: { left: 540, right: 660, duration: 1200 } }, { id: "m-sleep-y", date: yesterday, time: "13:20", type: "sleep", kind: "sleep", title: "睡眠", detail: "1 小时 35 分钟 · 安稳", notes: "", meta: { start: "13:20", end: "14:55", duration: 95 } }, { id: "m-feed-3", date: threeDaysAgo, time: "10:30", type: "bottle", kind: "feed", title: "配方奶", detail: "母乳 · 80 ml", notes: "", meta: { milkType: "母乳", amount: 80 } }, { id: "m-old", date: eightDaysAgo, time: "09:05", type: "diaper", kind: "care", title: "小便", detail: "尿湿", notes: "", meta: { wet: true, poop: false } }
        ], anan: [
          { id: "a-feed-1", date: today, time: "07:50", type: "bottle", kind: "feed", title: "配方奶", detail: "配方奶 · 150 ml", notes: "", meta: { milkType: "配方奶", amount: 150 } }, { id: "a-sleep-1", date: today, time: "09:10", type: "sleep", kind: "sleep", title: "睡眠", detail: "55 分钟 · 一般", notes: "午前小睡", meta: { start: "09:10", end: "10:05", duration: 55 } }, { id: "a-diaper-1", date: today, time: "10:18", type: "diaper", kind: "care", title: "小便", detail: "尿湿", notes: "", meta: { wet: true, poop: false } }
        ]
      }, growth: {
        mumu: [
          { id: "g-m-1", date: "2026-05-04", weight: 3.35, height: 50.2, head: 34.1, notes: "出生记录" }, { id: "g-m-2", date: "2026-06-05", weight: 4.82, height: 56.5, head: 37.1, notes: "满月后测量" }, { id: "g-m-3", date: "2026-07-18", weight: 5.8, height: 60.4, head: 39.2, notes: "社区体检" }
        ], anan: [
          { id: "g-a-1", date: "2025-08-19", weight: 3.6, height: 51.1, head: 34.5, notes: "出生记录" }, { id: "g-a-2", date: "2026-01-20", weight: 7.3, height: 67.4, head: 43.0, notes: "家庭测量" }, { id: "g-a-3", date: "2026-07-15", weight: 9.4, height: 76.2, head: 46.0, notes: "社区体检" }
        ]
      }, settings: {
        dark: false, time24: true, reminders: false, reduceMotion: false, units: "metric", relativeTime: false, bfTimer: true, feedInterval: 3, correctedAge: false
      }, quickItems: [...defaultQuickItems]
    };
  }
  function loadState() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
      if (saved && saved.version === 1 && Array.isArray(saved.babies)) {
        saved.settings = { ...makeSeed().settings, ...(saved.settings || {}) };
        if (!saved.quickItems) saved.quickItems = [...defaultQuickItems];
        return saved;
      }
    } catch (error) {
      console.warn("乐记本地数据读取失败，将载入演示数据。", error);
    }
    return makeSeed();
  }
  function loadSession() {
    try {
      const saved = JSON.parse(localStorage.getItem(SESSION_KEY));
      if (saved && typeof saved === "object") return saved;
    } catch (_) {  }
    return { breastfeed: null, sleep: null };
  }
  function getDeviceId() {
    let id = localStorage.getItem(DEVICE_KEY);
    if (!id) {
      id = `LEJI-${Math.random().toString(36).slice(2, 6).toUpperCase()}-${Date.now().toString(36).toUpperCase().slice(-4)}`;
      localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  }
  let state = loadState(); let session = loadSession(); let activeView = "records"; let dayOffset = 0; let summaryRange = "day";
  let growthMetric = "weight"; let demoStatus = "normal"; let selectedRecordId = null; let timerSeconds = { left: 0, right: 0 };
  let runningSide = null; let selectedTimerSide = "left"; let timerInterval = null; let sessionTick = null;
  let calendarMonth = new Date().getMonth(); let calendarYear = new Date().getFullYear(); let diaryPhotoCount = 0;
  let clearStep = 1; let forceSummaryEmpty = false; let forceGrowthEmpty = false;
  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }
  function persistSession() {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }
  function escapeHTML(value = "") {
    return String(value).replace(/[&<>'"]/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
    })[character]);
  }
  function activeBaby() {
    return state.babies.find((baby) => baby.id === state.activeBabyId) || state.babies[0];
  }
  function selectedDate() {
    return isoOffset(dayOffset);
  }
  function allActiveRecords() {
    return state.records[state.activeBabyId] || [];
  }
  function recordsForSelectedDate() {
    return allActiveRecords()
      .filter((record) => record.date === selectedDate())
      .sort((a, b) => b.time.localeCompare(a.time));
  }
  function rangeRecords(range = summaryRange, offsetDays = 0) {
    const days = range === "day" ? 1 : range === "week" ? 7 : 30; const end = new Date();
    end.setHours(0, 0, 0, 0); end.setDate(end.getDate() - offsetDays); const start = new Date(end);
    start.setDate(start.getDate() - days + 1);
    return allActiveRecords().filter((record) => {
      const recordDate = new Date(`${record.date}T12:00:00`); return recordDate >= start && recordDate <= end;
    });
  }
  function ageText(birthday, useCorrected = false) {
    let birth = new Date(`${birthday}T12:00:00`); const baby = activeBaby();
    if (useCorrected && baby.dueDate && baby.dueDate > baby.birthday) {
      birth = new Date(`${baby.dueDate}T12:00:00`);
    }
    const now = new Date(); let months = (now.getFullYear() - birth.getFullYear()) * 12 + now.getMonth() - birth.getMonth();
    if (now.getDate() < birth.getDate()) months -= 1;
    if (months < 1) {
      const days = Math.max(0, Math.floor((now - birth) / 86400000)); return `${days}天`;
    }
    if (months < 24) return `${months}个月`;
    return `${Math.floor(months / 12)}岁${months % 12 ? `${months % 12}个月` : ""}`;
  }
  function longDate(iso) {
    const date = new Date(`${iso}T12:00:00`); return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
  }
  function dateLabel() {
    if (dayOffset === 0) return `今天 · ${new Date().getMonth() + 1}月${new Date().getDate()}日`;
    if (dayOffset === -1) return "昨天";
    const date = new Date(`${selectedDate()}T12:00:00`); return `${date.getMonth() + 1}月${date.getDate()}日`;
  }
  function displayTime(time) {
    if (state.settings.relativeTime && dayOffset === 0) {
      const [hours, minutes] = time.split(":").map(Number); const then = new Date(); then.setHours(hours, minutes, 0, 0);
      const diff = Math.round((Date.now() - then.getTime()) / 60000);
      if (diff >= 0 && diff < 60) return `${diff || 1} 分钟前`;
      if (diff >= 60 && diff < 24 * 60) return `${Math.floor(diff / 60)} 小时前`;
    }
    if (state.settings.time24) return time;
    const [hours, minutes] = time.split(":").map(Number);
    return `${hours >= 12 ? "下午" : "上午"}${hours % 12 || 12}:${pad(minutes)}`;
  }
  function minutesBetween(start, end) {
    const [startHour, startMinute] = start.split(":").map(Number); const [endHour, endMinute] = end.split(":").map(Number);
    let minutes = endHour * 60 + endMinute - startHour * 60 - startMinute;
    if (minutes <= 0) minutes += 1440;
    return minutes;
  }
  function durationLabel(minutes) {
    const hours = Math.floor(minutes / 60); const rest = minutes % 60;
    if (!hours) return `${rest} 分钟`;
    if (!rest) return `${hours} 小时`;
    return `${hours} 小时 ${rest} 分钟`;
  }
  function shortDuration(minutes) {
    const hours = Math.floor(minutes / 60); const rest = minutes % 60;
    return hours ? `${hours}h${rest ? `${rest}m` : ""}` : `${rest}m`;
  }
  function newId(prefix) {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  }
  function showToast(message) {
    const toast = document.createElement("div");
    toast.className = "toast"; toast.textContent = message; $("#toast-region").append(toast);
    window.setTimeout(() => toast.remove(), 2600);
  }
  function formatTimer(seconds) {
    return `${pad(Math.floor(seconds / 60))}:${pad(seconds % 60)}`;
  }
  function restoreBreastfeedFromSession() {
    const bf = session.breastfeed;
    if (!bf) return;
    timerSeconds = { left: bf.left || 0, right: bf.right || 0 }; selectedTimerSide = bf.selectedSide || "left";
    runningSide = null;
    if (bf.runningSide && bf.tickStartedAt) {
      const elapsed = Math.floor((Date.now() - bf.tickStartedAt) / 1000);
      timerSeconds[bf.runningSide] = (bf[bf.runningSide] || 0) + Math.max(0, elapsed); runningSide = bf.runningSide;
      window.clearInterval(timerInterval);
      timerInterval = window.setInterval(() => {
        timerSeconds[runningSide] += 1; saveBreastfeedSession(); updateTimerUI(); renderSessionBar();
      }, 1000);
    }
  }
  function saveBreastfeedSession() {
    const active = runningSide || timerSeconds.left || timerSeconds.right;
    if (!active) {
      session.breastfeed = null;
    } else {
      session.breastfeed = {
        left: timerSeconds.left, right: timerSeconds.right, runningSide, selectedSide: selectedTimerSide, tickStartedAt: runningSide ? Date.now() : null, startedAt: session.breastfeed?.startedAt || Date.now()
      };
    }
    persistSession();
  }
  function updateTimerUI() {
    $("#left-timer").textContent = formatTimer(timerSeconds.left);
    $("#right-timer").textContent = formatTimer(timerSeconds.right);
    $$("[data-timer-side]").forEach((button) => {
      const side = button.dataset.timerSide; const running = runningSide === side;
      button.classList.toggle("is-selected", side === selectedTimerSide); button.classList.toggle("is-running", running);
      button.setAttribute("aria-pressed", String(side === selectedTimerSide));
      $(`#${side}-timer-state`).textContent = running ? "正在计时" : timerSeconds[side] ? "已暂停" : "点按开始";
    }); $("#timer-pause").disabled = !runningSide; $("#timer-pause").textContent = runningSide ? "暂停" : "继续"; renderSessionBar();
  }
  function startTimer(side) {
    selectedTimerSide = side;
    if (runningSide === side) {
      pauseTimer(); return;
    }
    runningSide = side;
    if (!session.breastfeed?.startedAt) {
      session.breastfeed = session.breastfeed || {}; session.breastfeed.startedAt = Date.now();
    }
    window.clearInterval(timerInterval);
    timerInterval = window.setInterval(() => {
      timerSeconds[runningSide] += 1; saveBreastfeedSession(); updateTimerUI();
    }, 1000); saveBreastfeedSession(); updateTimerUI();
  }
  function pauseTimer() {
    runningSide = null; window.clearInterval(timerInterval); timerInterval = null; saveBreastfeedSession(); updateTimerUI();
  }
  function resetTimer() {
    pauseTimer(); timerSeconds = { left: 0, right: 0 }; session.breastfeed = null; persistSession(); updateTimerUI();
  }
  function sleepElapsedSeconds() {
    if (!session.sleep?.startedAt) return 0;
    return Math.max(0, Math.floor((Date.now() - session.sleep.startedAt) / 1000));
  }
  function startSleepSession() {
    session.sleep = { startedAt: Date.now(), running: true }; persistSession(); renderSessionBar();
    updateSleepTimerButton(); showToast("已开始睡眠计时");
  }
  function endSleepSession() {
    if (!session.sleep?.startedAt) return;
    const seconds = sleepElapsedSeconds(); const minutes = Math.max(1, Math.round(seconds / 60)); const end = timeNow();
    const startDate = new Date(session.sleep.startedAt);
    const start = `${pad(startDate.getHours())}:${pad(startDate.getMinutes())}`;
    addRecord({
      time: start, type: "sleep", kind: "sleep", title: "睡眠", detail: `${durationLabel(minutes)} · 安稳`, notes: "", meta: { start, end, duration: minutes, quality: "安稳", fromTimer: true }
    }); session.sleep = null; persistSession(); renderSessionBar(); updateSleepTimerButton(); showToast("睡眠记录已保存");
  }
  function updateSleepTimerButton() {
    const btn = $("#sleep-timer-btn");
    if (!btn) return;
    if (session.sleep?.running) {
      btn.textContent = `醒来并保存（已 ${formatTimer(sleepElapsedSeconds())}）`; btn.classList.add("is-recording");
    } else {
      btn.textContent = "开始睡眠计时"; btn.classList.remove("is-recording");
    }
  }
  function activeSessionKind() {
    if (session.sleep?.running) return "sleep";
    if (session.breastfeed && (session.breastfeed.runningSide || session.breastfeed.left || session.breastfeed.right)) return "breastfeed";
    return null;
  }
  function renderSessionBar() {
    const bar = $("#active-session-bar"); const kind = activeSessionKind();
    if (!kind) {
      bar.hidden = true; return;
    }
    bar.hidden = false;
    if (kind === "sleep") {
      $("#session-bar-label").textContent = "睡眠记录中"; $("#session-bar-time").textContent = formatTimer(sleepElapsedSeconds());
      $("#session-bar-secondary").textContent = "继续"; $("#session-bar-secondary").hidden = true;
      $("#session-bar-primary").textContent = "醒来并保存";
    } else {
      const total = timerSeconds.left + timerSeconds.right;
      const sideLabel = runningSide === "left" ? "左侧" : runningSide === "right" ? "右侧" : "已暂停";
      $("#session-bar-label").textContent = `母乳${sideLabel}`;
      $("#session-bar-time").textContent = formatTimer(total); $("#session-bar-secondary").hidden = false;
      $("#session-bar-secondary").textContent = runningSide ? "暂停" : "继续"; $("#session-bar-primary").textContent = "结束保存";
    }
  }
  function renderHeader() {
    const baby = activeBaby(); const records = recordsForSelectedDate(); const last = records[0];
    $("#active-baby-avatar").textContent = baby.name.slice(0, 1); $("#active-baby-name").textContent = baby.name;
    $("#hero-demo-label").textContent = `本地演示 · ${dayOffset === 0 ? "今日" : dateLabel()}`;
    $("#date-label").textContent = dateLabel(); $("#next-day").disabled = dayOffset >= 0;
    $("#profile-avatar").textContent = baby.name.slice(0, 1); $("#profile-name").textContent = baby.name;
    $("#profile-detail").textContent = `${longDate(baby.birthday)}出生 · ${baby.gender}`;
    $("#summary-subtitle").textContent = `${baby.name}的记录，由本地数据实时计算。`;
    $("#device-id-value").textContent = getDeviceId(); document.title = `乐记 · ${baby.name}的今天`;
  }
  function renderRail(records) {
    const lanes = { sleep: [], feed: [], care: [] };
    records.forEach((record) => {
      if (record.kind === "sleep") lanes.sleep.push(record);
      else if (record.kind === "feed") lanes.feed.push(record);
      else lanes.care.push(record);
    });
    Object.entries(lanes).forEach(([lane, laneRecords]) => {
      const holder = $(`#rail-events-${lane}`);
      if (!holder) return;
      holder.innerHTML = laneRecords.map((record) => {
        const [hours, minutes] = record.time.split(":").map(Number); const position = ((hours * 60 + minutes) / 1440) * 100;
        return `<span class="rail-event ${lane}" style="left:${position}%" title="${escapeHTML(`${displayTime(record.time)} ${record.title}`)}"></span>`;
      }).join("");
    }); const now = new Date();
    const nowPercent = dayOffset === 0 ? ((now.getHours() * 60 + now.getMinutes()) / 1440) * 100 : 100;
    ["sleep", "feed", "care"].forEach((lane) => {
      const line = $(`#now-line-${lane}`);
      if (line) {
        line.style.left = `${nowPercent}%`; line.hidden = dayOffset !== 0;
      }
    }); $("#time-rail").setAttribute("aria-label", `${dateLabel()}共有 ${records.length} 条记录，分布在二十四小时内`);
  }
  function activityMarkup(record) {
    const details = [record.detail, record.notes].filter(Boolean).join(" · ");
    return `<li class="activity-item" data-kind="${record.kind}" data-record-row="${record.id}">
      <time class="activity-time" datetime="${record.date}T${record.time}">${escapeHTML(displayTime(record.time))}</time>
      <span class="activity-icon">${icons[record.type] || icons.diaper}</span>
      <span class="activity-copy"><strong>${escapeHTML(record.title)}</strong><small>${escapeHTML(details || "无补充信息")}</small></span>
      <button class="activity-more" type="button" data-record-action="${record.id}" aria-label="管理${escapeHTML(record.title)}记录"><svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"></circle><circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle></svg></button>
    </li>`;
  }
  function stateMarkup(type) {
    if (type === "loading") return '<div class="skeleton-stack" role="status" aria-label="正在加载记录"><div class="skeleton-row"></div><div class="skeleton-row"></div><div class="skeleton-row"></div></div>';
    if (type === "empty") return `<div class="state-card"><div><span class="state-symbol">${icons.breastfeed}</span><h3>从第一条开始</h3><p>这只是状态预览，不会清除现有数据。</p><button class="primary-button" type="button" data-open="record-sheet">添加记录</button></div></div>`;
    if (type === "error") return '<div class="state-card"><div><span class="state-symbol"><svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 8v5M12 17h.01"></path><circle cx="12" cy="12" r="9"></circle></svg></span><h3>记录暂时没显示出来</h3><p>演示错误状态；本地数据没有丢失。</p><button class="secondary-button" type="button" data-retry-state>重新加载</button></div></div>';
    return "";
  }
  function feedMlOf(records) {
    return records.filter((r) => r.type === "bottle" || r.type === "expressed" || r.type === "pump")
      .reduce((sum, r) => sum + (Number(r.meta?.amount) || 0), 0);
  }
  function breastMinOf(records) {
    return Math.round(records.filter((r) => r.type === "breastfeed")
      .reduce((sum, r) => sum + (Number(r.meta?.duration) || 0), 0) / 60);
  }
  function renderRecords() {
    const records = recordsForSelectedDate();
    const sleepMinutes = records.filter((r) => r.type === "sleep").reduce((sum, r) => sum + (r.meta.duration || 0), 0);
    const pees = records.filter((r) => (r.type === "diaper" && r.meta?.wet) || r.type === "both").length;
    const poops = records.filter((r) => r.type === "poop" || r.meta?.poop || r.type === "both").length; const last = records[0];
    $("#glance-feed-ml").textContent = feedMlOf(records) ? `${feedMlOf(records)}ml` : "0ml";
    $("#glance-bf-min").textContent = breastMinOf(records) ? `${breastMinOf(records)}min` : "0min";
    $("#glance-sleep").textContent = sleepMinutes ? shortDuration(sleepMinutes) : "0m";
    $("#glance-pee").textContent = `${pees}次`; $("#glance-poop").textContent = `${poops}次`;
    $("#day-record-count").textContent = `${records.length} 条记录`;
    const age = ageText(activeBaby().birthday, state.settings.correctedAge);
    $("#hero-age").textContent = `${age} · ${last ? `最近记录于 ${displayTime(last.time)}` : "这天还没有记录"}`; renderRail(records);
    const list = $("#activity-list"); const stateHost = $("#timeline-state");
    const hidesList = ["loading", "empty", "error"].includes(demoStatus); const naturallyEmpty = !records.length && !hidesList;
    list.hidden = hidesList || naturallyEmpty;
    stateHost.innerHTML = hidesList ? stateMarkup(demoStatus) : naturallyEmpty ? stateMarkup("empty") : "";
    list.innerHTML = records.map(activityMarkup).join(""); $("#offline-banner").hidden = demoStatus !== "offline";
    $("#status-summary").textContent = `当前：${statusLabels[demoStatus] || demoStatus}`; renderQuickGrid();
  }
  function renderQuickGrid() {
    let items = (state.quickItems || defaultQuickItems).slice(0, 8);
    if (state.settings.bfTimer === false) items = items.filter((id) => id !== "breastfeed");
    const grid = $(".quick-grid");
    if (!grid) return;
    grid.innerHTML = items.map((typeId) => {
      const type = recordTypes.find((t) => t.id === typeId);
      if (!type) return "";
      return `<button type="button" class="quick-button" data-record="${type.id}"><span class="quick-icon ${type.color}">${icons[type.id] || ""}</span><span><strong>${escapeHTML(type.name)}</strong></span></button>`;
    }).join("");
  }
  function sparkline(values, emptyLabel) {
    if (!values.length || values.every((v) => !v)) {
      return `<button type="button" class="trend-empty" data-open="record-sheet">${escapeHTML(emptyLabel)}</button>`;
    }
    const max = Math.max(...values, 1);
    return `<div class="spark-bars" role="img" aria-label="趋势">${values.map((v, i) => `<span class="spark-bar" style="height:${Math.max(8, (v / max) * 100)}%" title="${v}"></span>`).join("")}</div>`;
  }
  function compareText(current, previous, unit) {
    if (!previous && !current) return "数据不足，暂无对比";
    if (!previous) return "较上一周期：首次统计";
    const delta = current - previous;
    if (!delta) return `较上一周期持平`;
    const sign = delta > 0 ? "+" : ""; return `较上一周期 ${sign}${delta}${unit}`;
  }
  function renderSummary() {
    const empty = forceSummaryEmpty || demoStatus === "summary-empty"; const records = empty ? [] : rangeRecords();
    const prev = empty ? [] : rangeRecords(summaryRange, summaryRange === "day" ? 1 : summaryRange === "week" ? 7 : 30);
    const feeds = records.filter((r) => r.kind === "feed");
    const breastSeconds = records.filter((r) => r.type === "breastfeed").reduce((s, r) => s + (r.meta.duration || 0), 0);
    const bottleMl = feedMlOf(records); const sleeps = records.filter((r) => r.type === "sleep");
    const sleepMinutes = sleeps.reduce((s, r) => s + (r.meta.duration || 0), 0);
    const diapers = records.filter((r) => r.type === "diaper" || r.type === "poop" || r.type === "both");
    const poops = diapers.filter((r) => r.meta?.poop || r.type === "poop" || r.type === "both").length;
    const pees = diapers.filter((r) => r.meta?.wet || r.type === "diaper" || r.type === "both").length;
    const prevSleep = prev.filter((r) => r.type === "sleep").reduce((s, r) => s + (r.meta.duration || 0), 0);
    const prevFeedMl = feedMlOf(prev);
    const prevDiaper = prev.filter((r) => r.type === "diaper" || r.type === "poop" || r.type === "both").length;
    $("#summary-empty-state").hidden = !empty && records.length > 0;
    const cards = $$("#view-summary .insight-card, #view-summary .summary-grid, #view-summary .range-control");
    cards.forEach((el) => { el.hidden = empty; });
    if (empty) {
      $("#caregiver-count").textContent = "0 条"; return;
    }
    $("#summary-feed-count").textContent = bottleMl ? `${bottleMl}ml` : `${feeds.length}次`; const feedDetail = [];
    if (breastSeconds) feedDetail.push(`母乳 ${Math.round(breastSeconds / 60)} 分钟`);
    if (bottleMl) feedDetail.push(`奶量 ${bottleMl} ml`);
    $("#summary-feed-detail").textContent = feedDetail.join(" · ") || "暂无详情";
    $("#summary-sleep-total").textContent = shortDuration(sleepMinutes);
    $("#summary-sleep-detail").textContent = `${sleeps.length} 段睡眠`; $("#summary-diaper-count").textContent = diapers.length;
    $("#summary-diaper-detail").textContent = `尿 ${pees} · 便 ${poops}`; $("#caregiver-count").textContent = `${records.length} 条`;
    $("#feeding-window").textContent = summaryRange === "day" ? "今日" : summaryRange === "week" ? "近 7 天" : "近 30 天";
    const buckets = [0, 0, 0, 0];
    feeds.forEach((r) => { buckets[Math.min(3, Math.floor(Number(r.time.slice(0, 2)) / 6))] += 1; });
    const max = Math.max(...buckets, 1);
    $("#rhythm-chart").innerHTML = buckets.map((count, index) => `<div class="rhythm-column"><div class="rhythm-bar-wrap"><span class="rhythm-bar" style="height:${Math.max(5, (count / max) * 100)}%" title="${count} 次"></span></div><small>${pad(index * 6)}–${pad((index + 1) * 6)}</small></div>`).join("");
    $("#rhythm-insight").textContent = feeds.length ? `共 ${feeds.length} 次喂养；仅呈现已记录时段，不作医学判断。` : "范围内暂无喂养记录。";
    const maxSleep = Math.max(...sleeps.map((r) => r.meta.duration || 0), 1);
    $("#sleep-segments").innerHTML = sleeps.length
      ? sleeps.slice(0, 5).map((r) => `<div class="sleep-segment"><span>${escapeHTML(displayTime(r.time))}</span><div class="sleep-track"><div class="sleep-fill" style="width:${Math.max(7, ((r.meta.duration || 0) / maxSleep) * 100)}%"></div></div><strong>${shortDuration(r.meta.duration || 0)}</strong></div>`).join("")
      : '<p class="support-copy">范围内暂无已完成睡眠记录。</p>';
    const dayBuckets = (list, fn) => {
      const days = summaryRange === "day" ? 6 : summaryRange === "week" ? 7 : 6; const arr = Array(days).fill(0);
      list.forEach((r) => {
        const d = new Date(`${r.date}T12:00:00`); const today = new Date(); today.setHours(0, 0, 0, 0);
        const diff = Math.round((today - d) / 86400000); const idx = days - 1 - Math.min(days - 1, Math.max(0, diff));
        arr[idx] += fn(r);
      }); return arr;
    };
    $("#trend-feed-compare").textContent = compareText(bottleMl + Math.round(breastSeconds / 60), prevFeedMl + Math.round(prev.filter((r) => r.type === "breastfeed").reduce((s, r) => s + (r.meta.duration || 0), 0) / 60), "");
    $("#trend-feed-chart").innerHTML = sparkline(dayBuckets(feeds, (r) => r.type === "breastfeed" ? Math.round((r.meta.duration || 0) / 60) : (r.meta.amount || 1)), "暂无喂养趋势，去添加记录");
    $("#trend-sleep-compare").textContent = compareText(sleepMinutes, prevSleep, " 分钟");
    $("#trend-sleep-chart").innerHTML = sparkline(dayBuckets(sleeps, (r) => r.meta.duration || 0), "暂无睡眠趋势，去添加记录");
    $("#trend-diaper-compare").textContent = compareText(diapers.length, prevDiaper, " 次");
    $("#trend-diaper-chart").innerHTML = sparkline(dayBuckets(diapers, () => 1), "暂无尿布趋势，去添加记录");
    const temps = records.filter((r) => r.type === "temperature"); const prevTemps = prev.filter((r) => r.type === "temperature");
    $("#trend-temp-compare").textContent = temps.length && prevTemps.length
      ? compareText(Number(temps.at(-1).meta.value || 0).toFixed(1), Number(prevTemps.at(-1).meta.value || 0).toFixed(1), "°")
      : temps.length ? "较上一周期：首次体温" : "数据不足，暂无对比";
    $("#trend-temp-chart").innerHTML = sparkline(temps.slice(-6).map((r) => Number(r.meta.value) || 0), "暂无体温趋势，去添加记录");
  }
  function convertMeasurement(value, metric) {
    if (state.settings.units === "metric") return value;
    return metric === "weight" ? value * 2.20462 : value / 2.54;
  }
  function metricUnit(metric) {
    if (state.settings.units === "metric") return metric === "weight" ? "kg" : "cm";
    return metric === "weight" ? "lb" : "in";
  }
  function whoBands(metric, ageMonths) {
    // Simplified WHO-like demo bands (not clinical)
    if (metric === "weight") {
      const p50 = 3.3 + ageMonths * 0.7; return { p3: p50 * 0.82, p50, p97: p50 * 1.2 };
    }
    if (metric === "height") {
      const p50 = 50 + ageMonths * 2.4; return { p3: p50 * 0.92, p50, p97: p50 * 1.08 };
    }
    const p50 = 34 + ageMonths * 0.55; return { p3: p50 * 0.93, p50, p97: p50 * 1.07 };
  }
  function monthsSince(iso) {
    const birth = new Date(`${iso}T12:00:00`); const now = new Date();
    let months = (now.getFullYear() - birth.getFullYear()) * 12 + now.getMonth() - birth.getMonth();
    if (now.getDate() < birth.getDate()) months -= 1;
    return Math.max(0, months);
  }
  function renderGrowth() {
    const labels = { weight: "体重", height: "身高", head: "头围" }; const empty = forceGrowthEmpty || demoStatus === "growth-empty";
    const entries = empty ? [] : [...(state.growth[state.activeBabyId] || [])].sort((a, b) => a.date.localeCompare(b.date));
    const latest = entries.at(-1); const previous = entries.at(-2); const unit = metricUnit(growthMetric);
    const baby = activeBaby(); const hasDue = Boolean(baby.dueDate && baby.dueDate > baby.birthday);
    $("#corrected-age-row").hidden = !hasDue; $("#corrected-age-toggle").checked = state.settings.correctedAge;
    if (hasDue) {
      $("#corrected-age-label").textContent = state.settings.correctedAge
        ? `修正月龄 ${ageText(baby.birthday, true)}（预产期 ${longDate(baby.dueDate)}）`
        : `实际 ${ageText(baby.birthday)} · 可切换修正月龄`;
    }
    $("#growth-empty-state").hidden = !empty && entries.length > 0;
    $$("#view-growth .growth-card, #view-growth .section-block, #view-growth .metric-control").forEach((el) => {
      el.hidden = empty;
    });
    if (empty) {
      $("#growth-record-count").textContent = "0 次"; return;
    }
    $("#growth-record-count").textContent = `${entries.length} 次`;
    $("#growth-metric-label").textContent = `最新${labels[growthMetric]}`; $("#growth-unit").textContent = unit;
    $("#growth-chart-title").textContent = `${labels[growthMetric]}变化趋势`;
    $("#growth-chart-desc").textContent = `${baby.name}最近 ${entries.length} 次${labels[growthMetric]}测量趋势`;
    if (!latest) {
      $("#growth-latest-value").textContent = "—"; $("#growth-change").textContent = "还没有测量记录";
      $("#growth-path").setAttribute("d", ""); $("#growth-area").setAttribute("d", "");
      $("#growth-points").innerHTML = ""; $("#growth-table-body").innerHTML = '<tr><td colspan="4">暂无测量</td></tr>'; return;
    }
    const latestValue = convertMeasurement(latest[growthMetric], growthMetric);
    const decimals = growthMetric === "weight" ? 2 : 1;
    $("#growth-latest-value").textContent = latestValue.toFixed(decimals).replace(/\.0+$/, "");
    if (previous) {
      const change = latestValue - convertMeasurement(previous[growthMetric], growthMetric);
      $("#growth-change").textContent = `较上次 ${change >= 0 ? "+" : ""}${change.toFixed(decimals).replace(/\.0+$/, "")} ${unit}`;
    } else {
      $("#growth-change").textContent = "第一条测量记录";
    }
    const values = entries.map((entry) => convertMeasurement(entry[growthMetric], growthMetric));
    const ageMonths = monthsSince(state.settings.correctedAge && baby.dueDate ? baby.dueDate : baby.birthday);
    const who = whoBands(growthMetric, ageMonths);
    const whoValues = [who.p3, who.p50, who.p97].map((v) => convertMeasurement(v, growthMetric));
    const min = Math.min(...values, ...whoValues); const max = Math.max(...values, ...whoValues);
    const span = Math.max(max - min, 0.1); const yOf = (value) => 160 - ((value - min) / span) * 115;
    const points = values.map((value, index) => ({
      x: entries.length === 1 ? 175 : 30 + (index / (entries.length - 1)) * 290, y: yOf(value), value
    })); const path = points.map((point, index) => `${index ? "L" : "M"}${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(" ");
    $("#growth-path").setAttribute("d", path);
    $("#growth-area").setAttribute("d", `${path} L${points.at(-1).x.toFixed(1)} 166 L${points[0].x.toFixed(1)} 166 Z`);
    const whoLines = growthMetric === "head" ? "" : `
      <path class="who-line p3" d="M24 ${yOf(whoValues[0]).toFixed(1)} H326" />
      <path class="who-line p50" d="M24 ${yOf(whoValues[1]).toFixed(1)} H326" />
      <path class="who-line p97" d="M24 ${yOf(whoValues[2]).toFixed(1)} H326" />
      <text class="who-label" x="328" y="${yOf(whoValues[1]).toFixed(1)}">P50</text>`;
    $("#growth-points").innerHTML = whoLines + points.map((point) => `<circle class="chart-point" cx="${point.x}" cy="${point.y}" r="5"></circle><text class="chart-point-label" x="${point.x}" y="${Math.max(12, point.y - 11)}">${point.value.toFixed(decimals).replace(/\.0+$/, "")}</text>`).join("");
    $("#growth-table-body").innerHTML = [...entries].reverse().map((entry) => `<tr><td>${escapeHTML(entry.date.slice(5).replace("-", "/"))}</td><td>${convertMeasurement(entry.weight, "weight").toFixed(2).replace(/0$/, "")} ${metricUnit("weight")}</td><td>${convertMeasurement(entry.height, "height").toFixed(1)} ${metricUnit("height")}</td><td>${convertMeasurement(entry.head, "head").toFixed(1)} ${metricUnit("head")}</td></tr>`).join("");
  }
  function babyItemMarkup(baby, picker = false) {
    const current = baby.id === state.activeBabyId;
    return `<button class="${picker ? "baby-picker-item" : "baby-list-item"}${current ? " is-current" : ""}" type="button" data-baby-id="${baby.id}"><span class="mini-avatar">${escapeHTML(baby.name.slice(0, 1))}</span><span><strong>${escapeHTML(baby.name)}</strong><small>${escapeHTML(ageText(baby.birthday))} · ${escapeHTML(baby.gender)}</small></span><span class="current-mark">${current ? "当前" : "切换"}</span></button>`;
  }
  function renderAccount() {
    $("#baby-list").innerHTML = state.babies.map((baby) => babyItemMarkup(baby)).join("");
    $("#baby-picker").innerHTML = state.babies.map((baby) => babyItemMarkup(baby, true)).join("");
    $("#device-id-value").textContent = getDeviceId();
  }
  function applySettings() {
    document.documentElement.dataset.theme = state.settings.dark ? "dark" : "light";
    document.documentElement.classList.toggle("reduce-motion", state.settings.reduceMotion);
    $("#theme-toggle").setAttribute("aria-pressed", String(state.settings.dark));
    $("#menu-theme-toggle").setAttribute("aria-pressed", String(state.settings.dark));
    $("#menu-theme-status").textContent = `跟随当前设置：${state.settings.dark ? "开启" : "关闭"}`;
    $("#settings-summary").textContent = `${state.settings.time24 ? "24 小时制" : "12 小时制"} · ${state.settings.units === "metric" ? "公制" : "英制"}`;
    const feeds = allActiveRecords().filter((r) => r.kind === "feed").sort((a, b) => `${b.date}${b.time}`.localeCompare(`${a.date}${a.time}`));
    const hint = $("#next-feed-hint");
    if (hint) {
      if (state.settings.reminders && feeds[0]) {
        hint.textContent = `最近喂养 ${feeds[0].time} · 示例下次提醒约 ${state.settings.feedInterval || 3} 小时后（仅本机，不发通知）`;
      } else {
        hint.textContent = "下次提醒示例将按最近喂养推算，不发送系统通知。";
      }
    }
  }
  function renderAll() {
    applySettings(); renderHeader(); renderRecords(); renderSummary(); renderGrowth(); renderAccount();
    renderSessionBar(); updateSleepTimerButton();
  }
  function setView(view) {
    if (!$("#view-" + view)) return;
    activeView = view;
    $$(".view").forEach((section) => {
      const active = section.dataset.view === view; section.hidden = !active; section.classList.toggle("is-active", active);
    });
    $$(".nav-item").forEach((button) => {
      const active = button.dataset.viewTarget === view; button.classList.toggle("is-active", active);
      if (active) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current");
    }); $("#record-fab").hidden = view !== "records";
    window.scrollTo({ top: 0, behavior: state.settings.reduceMotion ? "auto" : "smooth" });
  }
  function openDialog(id, source) {
    const dialog = document.getElementById(id);
    if (!dialog) return;
    const opened = $("dialog[open]");
    if (opened && opened !== dialog) opened.close();
    prepareDialog(id); dialog._returnFocus = source || document.activeElement;
    window.setTimeout(() => {
      if (typeof dialog.showModal === "function") dialog.showModal(); else dialog.setAttribute("open", "");
      document.body.classList.add("dialog-open");
      if (id === "search-dialog") $("#search-input").focus();
    }, opened ? 20 : 0);
  }
  function closeDialog(dialog) {
    if (!dialog) return;
    if (typeof dialog.close === "function") dialog.close(); else dialog.removeAttribute("open");
  }
  function prepareDialog(id) {
    const now = timeNow();
    if (id === "bottle-dialog") $("#bottle-time").value = now;
    if (id === "expressed-feed-dialog") $("#expressed-time").value = now;
    if (id === "pump-dialog") $("#pump-time").value = now;
    if (id === "diaper-dialog") $("#diaper-time").value = now;
    if (id === "poop-dialog") $("#poop-time").value = now;
    if (id === "temperature-dialog") {
      $("#temp-time").value = now; checkFeverNotice();
    }
    if (id === "memo-dialog") $("#memo-time").value = now;
    if (id === "diary-dialog") {
      $("#diary-time").value = now; diaryPhotoCount = 0; $("#diary-photo-count").textContent = "未添加照片（原型占位）";
      $("#diary-thumbs").hidden = true; $("#diary-thumbs").innerHTML = "";
    }
    if (id === "bath-dialog") $("#bath-time").value = now;
    if (id === "walk-dialog") {
      const earlier = new Date(Date.now() - 30 * 60000);
      $("#walk-start").value = `${pad(earlier.getHours())}:${pad(earlier.getMinutes())}`; $("#walk-end").value = now;
    }
    if (id === "symptom-dialog") $("#symptom-time").value = now;
    if (id === "medication-dialog") $("#med-time").value = now;
    if (id === "doctor-dialog") $("#doctor-time").value = now;
    if (id === "other-dialog") $("#other-time").value = now;
    if (id === "sleep-dialog") {
      const earlier = new Date(Date.now() - 60 * 60000);
      $("#sleep-start").value = `${pad(earlier.getHours())}:${pad(earlier.getMinutes())}`;
      $("#sleep-end").value = now; updateSleepTimerButton();
    }
    if (id === "growth-dialog") {
      $("#growth-date").value = localISO(); const latest = (state.growth[state.activeBabyId] || []).at(-1);
      $("#growth-weight").value = latest?.weight || ""; $("#growth-height").value = latest?.height || "";
      $("#growth-head").value = latest?.head || "";
    }
    if (id === "add-baby-dialog") {
      $("#baby-birthday").max = localISO(); $("#baby-birthday").value = localISO();
      if ($("#due-date-input")) $("#due-date-input").value = "";
    }
    if (id === "settings-dialog") {
      $("#setting-dark").checked = state.settings.dark; $("#setting-24h").checked = state.settings.time24;
      $("#setting-reminders").checked = state.settings.reminders; $("#setting-motion").checked = state.settings.reduceMotion;
      $("#setting-relative-time").checked = state.settings.relativeTime;
      $("#setting-bf-timer").checked = state.settings.bfTimer !== false;
      $("#setting-feed-interval").value = state.settings.feedInterval || 3;
      const unitInput = $(`input[name="units"][value="${state.settings.units}"]`);
      if (unitInput) unitInput.checked = true;
    }
    if (id === "status-dialog") {
      const statusInput = $(`input[name="demo-status"][value="${demoStatus}"]`);
      if (statusInput) statusInput.checked = true;
    }
    if (id === "calendar-dialog") renderCalendar();
    if (id === "record-config-dialog") renderRecordConfig();
    if (id === "export-dialog") {
      $("#export-preview").hidden = true; $("#export-download").hidden = true;
    }
    if (id === "clear-data-dialog") {
      clearStep = 1; $("#clear-step-copy").textContent = "第一步：确认你要删除本机全部记录与设置。此操作无法撤销。";
      $("#clear-step-two").hidden = true; $("#clear-confirm-input").value = ""; $("#confirm-clear-data").textContent = "继续";
    }
    if (id === "record-sheet") {
      const moreWrap = $("#more-types-wrap");
      const moreToggle = $("#more-types-toggle");
      if (moreWrap) moreWrap.hidden = true;
      if (moreToggle) moreToggle.setAttribute("aria-expanded", "false");
    }
  }
  function addRecord(record) {
    if (!state.records[state.activeBabyId]) state.records[state.activeBabyId] = [];
    state.records[state.activeBabyId].push({ id: newId(record.type), date: selectedDate(), notes: "", ...record });
    demoStatus = "normal"; forceSummaryEmpty = false; forceGrowthEmpty = false; persist(); renderAll();
  }
  function openRecordType(type, source) {
    closeDialog($("#record-sheet"));
    if (type === "diaper") {
      addRecord({ time: timeNow(), type: "diaper", kind: "care", title: "小便", detail: "尿湿", notes: "", meta: { wet: true, poop: false } });
      showToast("小便记录已保存"); return;
    }
    if (type === "bath") {
      addRecord({ time: timeNow(), type: "bath", kind: "care", title: "洗澡", detail: "洗澡", notes: "", meta: {} });
      showToast("洗澡记录已保存"); return;
    }
    if (type === "both") {
      $("#diaper-wet").checked = true; $("#diaper-poop").checked = true; $("#poop-details").hidden = false;
      openDialog("diaper-dialog", source); return;
    }
    const map = {
      breastfeed: "breastfeed-dialog", bottle: "bottle-dialog", expressed: "expressed-feed-dialog", pump: "pump-dialog", sleep: "sleep-dialog", poop: "poop-dialog", temperature: "temperature-dialog", memo: "memo-dialog", diary: "diary-dialog", walk: "walk-dialog", symptom: "symptom-dialog", medication: "medication-dialog", doctor: "doctor-dialog", growth: "growth-dialog", other: "other-dialog"
    };
    if (map[type]) openDialog(map[type], source);
  }
  function recordById(id) {
    return allActiveRecords().find((record) => record.id === id);
  }
  function openRecordActions(id, source) {
    const record = recordById(id);
    if (!record) return;
    selectedRecordId = id;
    $("#record-preview").innerHTML = `<strong>${escapeHTML(record.title)}</strong><small>${escapeHTML(`${displayTime(record.time)} · ${record.detail}${record.notes ? ` · ${record.notes}` : ""}`)}</small>`;
    openDialog("record-actions-dialog", source);
  }
  function fillEditTypeFields(record) {
    const host = $("#edit-type-fields"); const meta = record.meta || {}; let html = "";
    if (record.type === "bottle" || record.type === "expressed" || record.type === "pump") {
      html = `<label class="field"><span>奶量（ml）</span><input id="edit-amount" type="number" min="1" max="500" value="${meta.amount || 90}" required></label>`;
    } else if (record.type === "temperature") {
      html = `<label class="field"><span>体温</span><input id="edit-temp" type="number" min="34" max="42" step="0.1" value="${meta.value || 36.5}" required></label>`;
    } else if (record.type === "sleep") {
      html = `<div class="field-row"><label class="field"><span>入睡</span><input id="edit-sleep-start" type="time" value="${meta.start || record.time}" required></label><label class="field"><span>醒来</span><input id="edit-sleep-end" type="time" value="${meta.end || record.time}" required></label></div>`;
    } else if (record.type === "poop" || meta.poop) {
      html = `<div class="field-row"><label class="field"><span>便量</span><select id="edit-poop-amount">${POOP_AMOUNTS.map((a) => `<option ${a === meta.amount ? "selected" : ""}>${a}</option>`).join("")}</select></label><label class="field"><span>颜色</span><select id="edit-poop-color">${POOP_COLORS.map((c) => `<option ${c === meta.color ? "selected" : ""}>${c}</option>`).join("")}</select></label></div><label class="field"><span>稠度</span><select id="edit-poop-texture">${POOP_TEXTURES.map((t) => `<option ${t === meta.texture ? "selected" : ""}>${t}</option>`).join("")}</select></label>`;
    } else if (record.type === "breastfeed") {
      html = `<div class="field-row"><label class="field"><span>左侧（秒）</span><input id="edit-bf-left" type="number" min="0" value="${meta.left || 0}"></label><label class="field"><span>右侧（秒）</span><input id="edit-bf-right" type="number" min="0" value="${meta.right || 0}"></label></div>`;
    } else if (record.type === "memo" || record.type === "diary") {
      html = `<label class="field"><span>正文</span><textarea id="edit-content" rows="3" maxlength="500">${escapeHTML(meta.content || record.notes || "")}</textarea></label>`;
    } else {
      html = `<label class="field"><span>详情</span><input id="edit-detail" type="text" maxlength="120" value="${escapeHTML(record.detail || "")}"></label>`;
    }
    host.innerHTML = html;
  }
  function applyEditTypeFields(record) {
    const meta = { ...(record.meta || {}) };
    if (document.getElementById("edit-"+"amount")) {
      meta.amount = Number(document.getElementById("edit-"+"amount").value);
      record.detail = `${meta.milkType || (record.type === "pump" ? "" : "母乳")}${meta.milkType || record.type === "pump" ? " · " : ""}${meta.amount} ml`.replace(/^ · /, "");
      if (record.type === "pump") record.detail = `${meta.amount} ml`;
    }
    if (document.getElementById("edit-"+"temp")) {
      meta.value = Number(document.getElementById("edit-"+"temp").value);
      meta.celsius = meta.unit === "f" ? (meta.value - 32) * 5 / 9 : meta.value;
      record.detail = `${meta.value}${meta.unit === "f" ? "℉" : "℃"}`;
    }
    if (document.getElementById("edit-"+"sleep-start")) {
      meta.start = document.getElementById("edit-"+"sleep-start").value;
      meta.end = document.getElementById("edit-"+"sleep-end").value;
      meta.duration = minutesBetween(meta.start, meta.end); record.time = meta.start;
      record.detail = `${durationLabel(meta.duration)} · ${meta.quality || "安稳"}`;
    }
    if (document.getElementById("edit-"+"poop-amount")) {
      meta.amount = document.getElementById("edit-"+"poop-amount").value;
      meta.color = document.getElementById("edit-"+"poop-color").value;
      meta.texture = document.getElementById("edit-"+"poop-texture").value; meta.poop = true;
      const base = meta.wet ? "尿湿 + 便便" : "便便"; record.detail = `${base} · ${meta.amount} · ${meta.color}${meta.texture}`;
    }
    if (document.getElementById("edit-"+"bf-left")) {
      meta.left = Number(document.getElementById("edit-"+"bf-left").value);
      meta.right = Number(document.getElementById("edit-"+"bf-right").value); meta.duration = meta.left + meta.right;
      record.detail = `左侧 ${Math.round(meta.left / 60)} 分钟 · 右侧 ${Math.round(meta.right / 60)} 分钟`;
    }
    if (document.getElementById("edit-"+"content")) {
      const content = document.getElementById("edit-"+"content").value.trim();
      meta.content = content; record.detail = content.slice(0, 40); record.notes = content;
    }
    if (document.getElementById("edit-"+"detail")) {
      record.detail = document.getElementById("edit-"+"detail").value.trim() || record.detail;
    }
    record.meta = meta;
  }
  function updateSearch() {
    const query = $("#search-input").value.trim().toLowerCase(); const allBabies = $("#search-all-babies").checked;
    const babyIds = allBabies ? state.babies.map((baby) => baby.id) : [state.activeBabyId]; const matches = [];
    if (query) {
      babyIds.forEach((babyId) => {
        const baby = state.babies.find((item) => item.id === babyId);
        (state.records[babyId] || []).forEach((record) => {
          const haystack = `${record.title} ${record.detail} ${record.notes || ""} ${baby.name}`.toLowerCase();
          if (haystack.includes(query)) matches.push({ baby, record });
        });
      });
    }
    matches.sort((a, b) => `${b.record.date}${b.record.time}`.localeCompare(`${a.record.date}${a.record.time}`));
    $("#search-count").textContent = query ? `${matches.length} 条结果` : "输入关键词开始查找";
    $("#search-results").innerHTML = !query
      ? '<p class="search-empty">可以搜索记录类型、便便属性或自己写下的备注。</p>'
      : matches.length
        ? matches.map(({ baby, record }) => `<button class="search-result" type="button" data-search-id="${record.id}" data-search-baby="${baby.id}"><span class="activity-icon">${icons[record.type] || icons.diaper}</span><span><strong>${escapeHTML(record.title)} · ${escapeHTML(baby.name)}</strong><small>${escapeHTML(`${record.date} ${displayTime(record.time)} · ${record.detail}`)}</small></span><svg class="row-chevron" aria-hidden="true" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"></path></svg></button>`).join("")
        : '<p class="search-empty">没有匹配记录。换个更短的关键词试试。</p>';
  }
  function switchBaby(id) {
    if (!state.babies.some((baby) => baby.id === id)) return;
    state.activeBabyId = id; dayOffset = 0; persist(); renderAll(); showToast(`已切换到 ${activeBaby().name}`);
  }
  function toggleTheme() {
    state.settings.dark = !state.settings.dark; persist(); applySettings();
    showToast(state.settings.dark ? "已开启深色模式" : "已切换为浅色模式");
  }
  function renderCalendar() {
    const grid = $("#calendar-grid"); const label = $("#cal-month-label");
    if (!grid || !label) return;
    const year = calendarYear; const month = calendarMonth; label.textContent = `${year}年${month + 1}月`;
    const firstDay = new Date(year, month, 1).getDay(); const daysInMonth = new Date(year, month + 1, 0).getDate();
    const today = localISO(); const selected = selectedDate(); const recordDates = new Set(allActiveRecords().map((r) => r.date));
    let html = ["日", "一", "二", "三", "四", "五", "六"].map((d) => `<span class="calendar-dow">${d}</span>`).join("");
    const prevDays = new Date(year, month, 0).getDate();
    for (let i = 0; i < firstDay; i++) html += `<button class="calendar-day other-month" type="button" disabled>${prevDays - firstDay + i + 1}</button>`;
    for (let d = 1; d <= daysInMonth; d++) {
      const iso = `${year}-${pad(month + 1)}-${pad(d)}`; const classes = ["calendar-day"];
      if (iso === today) classes.push("today");
      if (iso === selected) classes.push("selected");
      if (recordDates.has(iso)) classes.push("has-records");
      html += `<button class="${classes.join(" ")}" type="button" data-cal-date="${iso}">${d}</button>`;
    }
    const totalCells = firstDay + daysInMonth; const remaining = (7 - (totalCells % 7)) % 7;
    for (let i = 1; i <= remaining; i++) html += `<button class="calendar-day other-month" type="button" disabled>${i}</button>`;
    grid.innerHTML = html;
  }
  function orderedConfigTypes() {
    const order = state.quickItems || defaultQuickItems;
    const rest = recordTypes.map((t) => t.id).filter((id) => !order.includes(id));
    return [...order, ...rest].map((id) => recordTypes.find((t) => t.id === id)).filter(Boolean);
  }
  function renderRecordConfig() {
    const list = $("#record-config-list");
    if (!list) return;
    const quickItems = state.quickItems || defaultQuickItems; const types = orderedConfigTypes();
    list.innerHTML = types.map((type, index) => {
      const checked = quickItems.includes(type.id);
      return `<div class="config-item" data-config-type="${type.id}">
        <span class="config-item-icon">${icons[type.id] || ""}</span>
        <span class="config-item-name">${escapeHTML(type.name)}</span>
        <div class="config-sort">
          <button type="button" class="icon-button compact" data-config-move="up" data-config-type="${type.id}" aria-label="上移${escapeHTML(type.name)}" ${index === 0 ? "disabled" : ""}><svg aria-hidden="true" viewBox="0 0 24 24"><path d="m6 14 6-6 6 6"></path></svg></button>
          <button type="button" class="icon-button compact" data-config-move="down" data-config-type="${type.id}" aria-label="下移${escapeHTML(type.name)}" ${index === types.length - 1 ? "disabled" : ""}><svg aria-hidden="true" viewBox="0 0 24 24"><path d="m6 10 6 6 6-6"></path></svg></button>
        </div>
        <span class="config-item-toggle"><input type="checkbox" id="config-${type.id}" ${checked ? "checked" : ""}><label for="config-${type.id}"></label></span>
      </div>`;
    }).join("");
  }
  function generateExportText() {
    const baby = activeBaby(); const records = allActiveRecords();
    const lines = [`乐记 · ${baby.name}的记录导出`, `导出时间：${new Date().toLocaleString("zh-CN")}`, `宝宝：${baby.name} · ${longDate(baby.birthday)}出生 · ${baby.gender}`, `本机 ID：${getDeviceId()}`, "", "--- 记录 ---", ""];
    const sorted = [...records].sort((a, b) => `${a.date}${a.time}`.localeCompare(`${b.date}${b.time}`));
    sorted.forEach((record) => {
      lines.push(`[${record.date} ${displayTime(record.time)}] ${record.title}`); lines.push(`  ${record.detail}`);
      if (record.notes) lines.push(`  备注：${record.notes}`);
      lines.push("");
    });
    if (!records.length) lines.push("暂无记录。");
    return lines.join("\n");
  }
  function checkFeverNotice() {
    const value = Number($("#temp-value")?.value || 0); const unit = $('input[name="temp-unit"]:checked')?.value || "c";
    const celsius = unit === "f" ? (value - 32) * 5 / 9 : value; const monthsOld = monthsSince(activeBaby().birthday);
    const notice = $("#temp-fever-notice");
    if (notice) notice.hidden = !(monthsOld < 3 && celsius >= 38);
  }
  // --- Event wiring ---
  document.addEventListener("click", (event) => {
    const openButton = event.target.closest("[data-open]");
    if (openButton) {
      openDialog(openButton.dataset.open, openButton); return;
    }
    const closeButton = event.target.closest("[data-close]");
    if (closeButton) {
      closeDialog(closeButton.closest("dialog")); return;
    }
    const viewButton = event.target.closest("[data-view-target], [data-view-link]");
    if (viewButton) {
      event.preventDefault(); setView(viewButton.dataset.viewTarget || viewButton.dataset.viewLink); return;
    }
    const recordButton = event.target.closest("[data-record]");
    if (recordButton) {
      openRecordType(recordButton.dataset.record, recordButton); return;
    }
    const actionButton = event.target.closest("[data-record-action]");
    if (actionButton) {
      openRecordActions(actionButton.dataset.recordAction, actionButton); return;
    }
    const babyButton = event.target.closest("[data-baby-id]");
    if (babyButton) {
      closeDialog(babyButton.closest("dialog")); switchBaby(babyButton.dataset.babyId);
      return;
    }
    const searchResult = event.target.closest("[data-search-id]");
    if (searchResult) {
      state.activeBabyId = searchResult.dataset.searchBaby; const record = recordById(searchResult.dataset.searchId);
      if (record) {
        const today = new Date(`${localISO()}T12:00:00`); const target = new Date(`${record.date}T12:00:00`);
        dayOffset = Math.round((target - today) / 86400000);
      }
      persist(); closeDialog($("#search-dialog")); demoStatus = "normal"; setView("records"); renderAll();
      showToast("已定位到记录所在日期"); return;
    }
    const moveBtn = event.target.closest("[data-config-move]");
    if (moveBtn) {
      const typeId = moveBtn.dataset.configType; const dir = moveBtn.dataset.configMove;
      const order = orderedConfigTypes().map((t) => t.id); const idx = order.indexOf(typeId);
      if (idx < 0) return;
      const swap = dir === "up" ? idx - 1 : idx + 1;
      if (swap < 0 || swap >= order.length) return;
      [order[idx], order[swap]] = [order[swap], order[idx]]; const visible = new Set(state.quickItems || defaultQuickItems);
      state.quickItems = order.filter((id) => visible.has(id));
      // keep non-visible order for display by storing full order in quickItems when toggled later
      const tempVisible = state.quickItems;
      // reconstruct: all types in new order, visibility preserved via checkboxes on save
      state._configOrder = order; state.quickItems = order.filter((id) => tempVisible.includes(id)); persist();
      renderRecordConfig(); return;
    }
    if (event.target.closest("[data-retry-state]")) {
      demoStatus = "normal"; forceSummaryEmpty = false; forceGrowthEmpty = false; renderAll(); showToast("记录已重新载入");
    }
  });
  $$("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) closeDialog(dialog);
    });
    dialog.addEventListener("close", () => {
      if (dialog.id === "breastfeed-dialog") pauseTimer();
      if (!$("dialog[open]")) document.body.classList.remove("dialog-open");
      if (dialog._returnFocus?.isConnected && !dialog._returnFocus.closest("dialog")) dialog._returnFocus.focus({ preventScroll: true });
    });
  });
  $("#previous-day").addEventListener("click", () => { dayOffset -= 1; renderAll(); });
  $("#next-day").addEventListener("click", () => { if (dayOffset < 0) dayOffset += 1; renderAll(); });
  $("#date-label").addEventListener("click", () => {
    calendarMonth = new Date().getMonth(); calendarYear = new Date().getFullYear();
    openDialog("calendar-dialog", $("#date-label"));
  }); $("#theme-toggle").addEventListener("click", toggleTheme); $("#menu-theme-toggle").addEventListener("click", toggleTheme);
  $$("[data-range]").forEach((button) => button.addEventListener("click", () => {
    summaryRange = button.dataset.range;
    $$("[data-range]").forEach((item) => {
      const selected = item === button;
      item.classList.toggle("is-selected", selected); item.setAttribute("aria-pressed", String(selected));
    }); renderSummary();
  }));
  $$("[data-metric]").forEach((button) => button.addEventListener("click", () => {
    growthMetric = button.dataset.metric;
    $$("[data-metric]").forEach((item) => {
      const selected = item === button;
      item.classList.toggle("is-selected", selected); item.setAttribute("aria-pressed", String(selected));
    }); renderGrowth();
  }));
  $$("[data-timer-side]").forEach((button) => button.addEventListener("click", () => startTimer(button.dataset.timerSide)));
  $("#timer-pause").addEventListener("click", () => {
    if (runningSide) pauseTimer();
    else if (timerSeconds.left || timerSeconds.right) startTimer(selectedTimerSide);
  }); $("#timer-reset").addEventListener("click", resetTimer);
  $("#session-bar-secondary").addEventListener("click", () => {
    if (activeSessionKind() === "breastfeed") {
      if (runningSide) pauseTimer();
      else startTimer(selectedTimerSide);
    }
  });
  $("#session-bar-primary").addEventListener("click", () => {
    if (activeSessionKind() === "sleep") endSleepSession();
    else if (activeSessionKind() === "breastfeed") {
      pauseTimer(); const total = timerSeconds.left + timerSeconds.right;
      if (!total) { showToast("请先为左侧或右侧计时"); return; }
      addRecord({
        time: timeNow(), type: "breastfeed", kind: "feed", title: "母乳喂养", detail: `左侧 ${Math.round(timerSeconds.left / 60)} 分钟 · 右侧 ${Math.round(timerSeconds.right / 60)} 分钟`, notes: "", meta: { left: timerSeconds.left, right: timerSeconds.right, duration: total }
      }); resetTimer(); showToast("母乳记录已保存");
    }
  });
  $("#breastfeed-form").addEventListener("submit", (event) => {
    event.preventDefault(); pauseTimer(); const total = timerSeconds.left + timerSeconds.right;
    if (!total) { showToast("请先为左侧或右侧计时"); return; }
    addRecord({
      time: timeNow(), type: "breastfeed", kind: "feed", title: "母乳喂养", detail: `左侧 ${Math.round(timerSeconds.left / 60)} 分钟 · 右侧 ${Math.round(timerSeconds.right / 60)} 分钟`, notes: $("#breastfeed-notes").value.trim(), meta: { left: timerSeconds.left, right: timerSeconds.right, duration: total }
    }); closeDialog($("#breastfeed-dialog")); resetTimer(); $("#breastfeed-notes").value = ""; showToast("母乳记录已保存");
  });
  $("#bottle-form").addEventListener("submit", (event) => {
    event.preventDefault(); const milkType = $('input[name="milk-type"]:checked').value;
    const amount = Number($("#bottle-amount").value);
    addRecord({ time: $("#bottle-time").value, type: "bottle", kind: "feed", title: "配方奶", detail: `${milkType} · ${amount} ml`, notes: $("#bottle-notes").value.trim(), meta: { milkType, amount } });
    closeDialog($("#bottle-dialog")); $("#bottle-notes").value = ""; showToast("配方奶记录已保存");
  });
  $("#expressed-feed-form").addEventListener("submit", (event) => {
    event.preventDefault(); const milkType = $('input[name="expressed-milk-type"]:checked').value;
    const amount = Number($("#expressed-amount").value);
    addRecord({ time: $("#expressed-time").value, type: "expressed", kind: "feed", title: "喂挤出乳", detail: `${milkType} · ${amount} ml`, notes: $("#expressed-notes").value.trim(), meta: { milkType, amount } });
    closeDialog($("#expressed-feed-dialog")); $("#expressed-notes").value = ""; showToast("喂挤出乳已保存");
  });
  $("#sleep-timer-btn").addEventListener("click", () => {
    if (session.sleep?.running) endSleepSession();
    else startSleepSession();
    updateSleepTimerButton();
  });
  $("#sleep-form").addEventListener("submit", (event) => {
    event.preventDefault(); const start = $("#sleep-start").value; const end = $("#sleep-end").value;
    const quality = $('input[name="sleep-quality"]:checked').value; const duration = minutesBetween(start, end);
    if (duration > 12 * 60) showToast("睡眠区间较长，已软提醒但仍可保存");
    addRecord({ time: start, type: "sleep", kind: "sleep", title: "睡眠", detail: `${durationLabel(duration)} · ${quality}`, notes: $("#sleep-notes").value.trim(), meta: { start, end, duration, quality } });
    closeDialog($("#sleep-dialog")); $("#sleep-notes").value = ""; showToast("睡眠记录已保存");
  });
  $("#diaper-poop").addEventListener("change", (event) => { $("#poop-details").hidden = !event.target.checked; });
  $("#diaper-form").addEventListener("submit", (event) => {
    event.preventDefault(); const wet = $("#diaper-wet").checked; const poop = $("#diaper-poop").checked;
    if (!wet && !poop) { $("#diaper-error").hidden = false; return; }
    $("#diaper-error").hidden = true; const amount = poop ? $('input[name="poop-amount"]:checked').value : "";
    const color = poop ? $("#poop-color").value : ""; const texture = poop ? $("#poop-texture").value : "";
    const base = wet && poop ? "尿湿 + 便便" : wet ? "尿湿" : "便便";
    const detail = poop ? `${base} · ${amount} · ${color}${texture}` : base;
    addRecord({ time: $("#diaper-time").value, type: wet && poop ? "both" : wet ? "diaper" : "poop", kind: "care", title: wet && poop ? "尿+便" : wet ? "小便" : "便便", detail, notes: $("#diaper-notes").value.trim(), meta: { wet, poop, amount, color, texture } });
    closeDialog($("#diaper-dialog")); $("#diaper-notes").value = ""; showToast("尿布记录已保存");
  });
  $("#growth-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const measurement = {
      id: newId("growth"), date: $("#growth-date").value, weight: Number($("#growth-weight").value), height: Number($("#growth-height").value), head: Number($("#growth-head").value), notes: $("#growth-notes").value.trim()
    };
    if (!state.growth[state.activeBabyId]) state.growth[state.activeBabyId] = [];
    state.growth[state.activeBabyId].push(measurement);
    if (!state.records[state.activeBabyId]) state.records[state.activeBabyId] = [];
    state.records[state.activeBabyId].push({ id: newId("growth-record"), date: measurement.date, time: timeNow(), type: "growth", kind: "care", title: "成长测量", detail: `${measurement.weight} kg · ${measurement.height} cm · 头围 ${measurement.head} cm`, notes: measurement.notes, meta: { measurementId: measurement.id } });
    forceGrowthEmpty = false; persist(); renderAll(); setView("growth"); closeDialog($("#growth-dialog"));
    $("#growth-notes").value = ""; showToast("成长测量已保存");
  });
  $("#search-input").addEventListener("input", updateSearch); $("#search-all-babies").addEventListener("change", updateSearch);
  $("#edit-record-button").addEventListener("click", () => {
    const record = recordById(selectedRecordId);
    if (!record) return;
    $("#edit-preview").innerHTML = `<strong>${escapeHTML(record.title)}</strong><small>${escapeHTML(record.detail)}</small>`;
    $("#edit-time").value = record.time; $("#edit-notes").value = record.notes || ""; fillEditTypeFields(record);
    openDialog("edit-dialog", $("#edit-record-button"));
  });
  $("#edit-form").addEventListener("submit", (event) => {
    event.preventDefault(); const record = recordById(selectedRecordId);
    if (!record) return;
    record.time = $("#edit-time").value; record.notes = $("#edit-notes").value.trim(); applyEditTypeFields(record);
    persist(); renderAll(); closeDialog($("#edit-dialog")); showToast("记录已更新");
  });
  $("#delete-record-button").addEventListener("click", () => openDialog("confirm-dialog", $("#delete-record-button")));
  $("#confirm-delete").addEventListener("click", () => {
    state.records[state.activeBabyId] = allActiveRecords().filter((record) => record.id !== selectedRecordId);
    persist(); renderAll(); closeDialog($("#confirm-dialog")); selectedRecordId = null; showToast("记录已删除");
  });
  $("#add-baby-form").addEventListener("submit", (event) => {
    event.preventDefault(); const name = $("#baby-name").value.trim(); const id = newId("baby");
    const gender = $('input[name="baby-gender"]:checked').value; const dueDate = $("#due-date-input")?.value || "";
    state.babies.push({ id, name, birthday: $("#baby-birthday").value, gender, dueDate }); state.records[id] = [];
    state.growth[id] = []; state.activeBabyId = id; persist(); renderAll(); closeDialog($("#add-baby-dialog"));
    event.target.reset(); showToast(`已添加 ${name}，可以开始记录了`);
  });
  $("#settings-form").addEventListener("submit", (event) => {
    event.preventDefault(); state.settings.dark = $("#setting-dark").checked;
    state.settings.time24 = $("#setting-24h").checked; state.settings.reminders = $("#setting-reminders").checked;
    state.settings.reduceMotion = $("#setting-motion").checked; state.settings.relativeTime = $("#setting-relative-time").checked;
    state.settings.bfTimer = $("#setting-bf-timer").checked;
    state.settings.feedInterval = Number($("#setting-feed-interval").value) || 3;
    state.settings.units = $('input[name="units"]:checked').value; persist(); renderAll();
    closeDialog($("#settings-dialog")); showToast("设置已保存到本机");
  });
  $("#corrected-age-toggle").addEventListener("change", (event) => {
    state.settings.correctedAge = event.target.checked; persist(); renderGrowth(); renderHeader();
  });
  $("#status-form").addEventListener("submit", (event) => {
    event.preventDefault(); demoStatus = $('input[name="demo-status"]:checked').value;
    forceSummaryEmpty = demoStatus === "summary-empty"; forceGrowthEmpty = demoStatus === "growth-empty";
    closeDialog($("#status-dialog"));
    if (demoStatus === "summary-empty") setView("summary");
    else if (demoStatus === "growth-empty") setView("growth");
    else if (demoStatus === "onboarding") {
      setView("records"); openDialog("onboarding-dialog");
    } else if (demoStatus === "recording") {
      setView("records");
      if (!session.sleep?.running && !session.breastfeed) startSleepSession();
    } else if (demoStatus === "saved") {
      setView("records"); showToast("保存成功");
    } else {
      setView("records");
    }
    renderAll(); showToast(`已切换为${statusLabels[demoStatus] || demoStatus}状态`);
  });
  $("#copy-family-code").addEventListener("click", async () => {
    const code = $("#family-code").value;
    try {
      await navigator.clipboard.writeText(code); showToast("演示邀请码已复制");
    } catch {
      $("#family-code").select(); showToast(`可手动复制：${code}`);
    }
  }); $("#preview-invite").addEventListener("click", () => showToast(FAMILY_STUB_MSG));
  ["create-family-btn", "join-family-btn", "generate-share-btn", "onboarding-join-stub"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener("click", () => showToast(FAMILY_STUB_MSG));
  }); $("#onboarding-theme").addEventListener("click", toggleTheme);
  $("#pump-form").addEventListener("submit", (event) => {
    event.preventDefault();
    addRecord({ time: $("#pump-time").value, type: "pump", kind: "feed", title: "挤奶", detail: `${Number($("#pump-amount").value)} ml`, notes: $("#pump-notes").value.trim(), meta: { amount: Number($("#pump-amount").value) } });
    closeDialog($("#pump-dialog")); $("#pump-notes").value = ""; showToast("挤奶记录已保存");
  });
  $("#poop-form").addEventListener("submit", (event) => {
    event.preventDefault(); const amount = $('input[name="poop-only-amount"]:checked').value;
    const color = $("#poop-only-color").value; const texture = $("#poop-only-texture").value;
    addRecord({ time: $("#poop-time").value, type: "poop", kind: "care", title: "便便", detail: `${amount} · ${color}${texture}`, notes: $("#poop-notes").value.trim(), meta: { poop: true, wet: false, amount, color, texture } });
    closeDialog($("#poop-dialog")); $("#poop-notes").value = ""; showToast("便便记录已保存");
  });
  if ($("#temp-value")) {
    $("#temp-value").addEventListener("input", checkFeverNotice);
    $$('input[name="temp-unit"]').forEach((i) => i.addEventListener("change", checkFeverNotice));
  }
  $("#temperature-form").addEventListener("submit", (event) => {
    event.preventDefault(); const value = Number($("#temp-value").value); const unit = $('input[name="temp-unit"]:checked').value;
    addRecord({ time: $("#temp-time").value, type: "temperature", kind: "care", title: "体温", detail: `${value}${unit === "f" ? "℉" : "℃"}`, notes: $("#temp-notes").value.trim(), meta: { value, unit, celsius: unit === "f" ? (value - 32) * 5 / 9 : value } });
    closeDialog($("#temperature-dialog")); $("#temp-notes").value = ""; showToast("体温记录已保存");
  });
  $("#memo-form").addEventListener("submit", (event) => {
    event.preventDefault(); const c = $("#memo-content").value.trim();
    if (!c) { showToast("请输入内容"); return; }
    addRecord({ time: $("#memo-time").value, type: "memo", kind: "care", title: "备忘", detail: c.slice(0, 40), notes: c, meta: { content: c } });
    closeDialog($("#memo-dialog")); $("#memo-content").value = ""; showToast("备忘已保存");
  });
  $("#diary-add-photo").addEventListener("click", () => {
    diaryPhotoCount += 1; $("#diary-photo-count").textContent = `已添加 ${diaryPhotoCount} 张照片占位`; const thumbs = $("#diary-thumbs");
    thumbs.hidden = false; thumbs.innerHTML += `<span class="diary-thumb" aria-hidden="true">照${diaryPhotoCount}</span>`;
  });
  $("#diary-form").addEventListener("submit", (event) => {
    event.preventDefault(); const c = $("#diary-content").value.trim();
    if (!c) { showToast("请输入日记正文"); return; }
    addRecord({ time: $("#diary-time").value, type: "diary", kind: "care", title: "日记", detail: c.slice(0, 40) + (diaryPhotoCount ? ` · ${diaryPhotoCount} 张照片` : ""), notes: c, meta: { content: c, photos: diaryPhotoCount } });
    closeDialog($("#diary-dialog")); $("#diary-content").value = ""; diaryPhotoCount = 0; showToast("日记已保存");
  });
  $("#walk-form").addEventListener("submit", (event) => {
    event.preventDefault(); const start = $("#walk-start").value; const duration = minutesBetween(start, $("#walk-end").value);
    addRecord({ time: start, type: "walk", kind: "care", title: "散步", detail: durationLabel(duration), notes: $("#walk-notes").value.trim(), meta: { start, end: $("#walk-end").value, duration } });
    closeDialog($("#walk-dialog")); $("#walk-notes").value = ""; showToast("散步记录已保存");
  });
  $("#symptom-form").addEventListener("submit", (event) => {
    event.preventDefault(); const d = $("#symptom-desc").value.trim();
    addRecord({ time: $("#symptom-time").value, type: "symptom", kind: "care", title: "症状", detail: d.slice(0, 40), notes: $("#symptom-notes").value.trim(), meta: { description: d } });
    closeDialog($("#symptom-dialog")); $("#symptom-desc").value = ""; $("#symptom-notes").value = ""; showToast("症状记录已保存");
  });
  $("#medication-form").addEventListener("submit", (event) => {
    event.preventDefault(); const name = $("#med-name").value.trim(); const dose = $("#med-dose").value.trim();
    addRecord({ time: $("#med-time").value, type: "medication", kind: "care", title: "用药", detail: `${name}${dose ? ` · ${dose}` : ""}`, notes: $("#med-notes").value.trim(), meta: { name, dose } });
    closeDialog($("#medication-dialog")); $("#med-name").value = ""; $("#med-dose").value = ""; $("#med-notes").value = ""; showToast("用药记录已保存");
  });
  $("#doctor-form").addEventListener("submit", (event) => {
    event.preventDefault(); const r = $("#doctor-reason").value.trim();
    addRecord({ time: $("#doctor-time").value, type: "doctor", kind: "care", title: "就医", detail: r.slice(0, 40), notes: $("#doctor-notes").value.trim(), meta: { reason: r } });
    closeDialog($("#doctor-dialog")); $("#doctor-reason").value = ""; $("#doctor-notes").value = ""; showToast("就医记录已保存");
  });
  $("#other-form").addEventListener("submit", (event) => {
    event.preventDefault(); const title = $("#other-title-input").value.trim() || "其他";
    addRecord({ time: $("#other-time").value, type: "other", kind: "care", title, detail: $("#other-detail").value.trim() || title, notes: "", meta: { customTitle: title } });
    closeDialog($("#other-dialog")); $("#other-title-input").value = ""; $("#other-detail").value = ""; showToast("记录已保存");
  });
  $("#more-types-toggle").addEventListener("click", () => {
    const wrap = $("#more-types-wrap"); const btn = $("#more-types-toggle");
    const expanded = btn.getAttribute("aria-expanded") === "true";
    btn.setAttribute("aria-expanded", String(!expanded)); wrap.hidden = expanded;
  });
  $("#cal-prev").addEventListener("click", () => {
    calendarMonth--;
    if (calendarMonth < 0) { calendarMonth = 11; calendarYear--; }
    renderCalendar();
  });
  $("#cal-next").addEventListener("click", () => {
    calendarMonth++;
    if (calendarMonth > 11) { calendarMonth = 0; calendarYear++; }
    renderCalendar();
  });
  $("#cal-today").addEventListener("click", () => {
    dayOffset = 0; calendarMonth = new Date().getMonth(); calendarYear = new Date().getFullYear();
    closeDialog($("#calendar-dialog")); renderAll(); showToast("已回到今天");
  });
  $("#calendar-grid").addEventListener("click", (event) => {
    const dayBtn = event.target.closest("[data-cal-date]");
    if (!dayBtn) return;
    const iso = dayBtn.dataset.calDate; const today = new Date(`${localISO()}T12:00:00`);
    const target = new Date(`${iso}T12:00:00`);
    dayOffset = Math.round((target - today) / 86400000); closeDialog($("#calendar-dialog")); renderAll();
    showToast(`已切换到 ${dateLabel()}`);
  });
  $("#save-record-config").addEventListener("click", () => {
    const order = state._configOrder || orderedConfigTypes().map((t) => t.id);
    const items = order.filter((typeId) => {
      const input = document.getElementById(`config-${typeId}`); return input && input.checked;
    }); state.quickItems = items.length ? items : [...defaultQuickItems]; persist(); renderQuickGrid();
    closeDialog($("#record-config-dialog")); showToast("快捷入口已更新");
  });
  $("#export-txt").addEventListener("click", () => {
    const text = generateExportText(); const preview = $("#export-preview");
    preview.textContent = text; preview.hidden = false; $("#export-download").hidden = false;
  });
  $("#export-download").addEventListener("click", () => {
    const text = generateExportText(); const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob); const a = document.createElement("a");
    a.href = url; a.download = `乐记-${activeBaby().name}-${localISO()}.txt`; a.click(); URL.revokeObjectURL(url);
    showToast("TXT 文件已开始下载");
  });
  $("#clear-all-data").addEventListener("click", () => openDialog("clear-data-dialog", $("#clear-all-data")));
  $("#confirm-clear-data").addEventListener("click", () => {
    if (clearStep === 1) {
      clearStep = 2; $("#clear-step-copy").textContent = "第二步：请输入「清除」后确认，才会删除全部本机数据。";
      $("#clear-step-two").hidden = false; $("#confirm-clear-data").textContent = "确认清除"; $("#clear-confirm-input").focus();
      return;
    }
    if ($("#clear-confirm-input").value.trim() !== "清除") {
      showToast("请输入「清除」以确认"); return;
    }
    localStorage.removeItem(STORAGE_KEY); localStorage.removeItem(SESSION_KEY); state = makeSeed();
    session = { breastfeed: null, sleep: null }; dayOffset = 0; demoStatus = "normal"; forceSummaryEmpty = false;
    forceGrowthEmpty = false; resetTimer(); persist(); renderAll(); closeDialog($("#clear-data-dialog"));
    setView("records"); showToast("全部数据已清除");
  });
  $("#reset-demo").addEventListener("click", () => {
    if (!window.confirm("恢复演示数据？当前浏览器中的新增、编辑与设置将被清除。")) return;
    localStorage.removeItem(STORAGE_KEY); localStorage.removeItem(SESSION_KEY); state = makeSeed();
    session = { breastfeed: null, sleep: null }; dayOffset = 0; demoStatus = "normal"; forceSummaryEmpty = false;
    forceGrowthEmpty = false; resetTimer(); persist(); renderAll(); setView("records"); showToast("演示数据已恢复");
  });
  window.addEventListener("offline", () => { $("#offline-banner").hidden = false; });
  window.addEventListener("online", () => { if (demoStatus !== "offline") $("#offline-banner").hidden = true; });
  restoreBreastfeedFromSession();
  sessionTick = window.setInterval(() => {
    if (session.sleep?.running || runningSide) {
      renderSessionBar(); updateSleepTimerButton();
    }
  }, 1000);
  updateTimerUI(); updateSearch(); renderAll(); setView(activeView);
})();
