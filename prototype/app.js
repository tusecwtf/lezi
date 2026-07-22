/* 暖芽原型：数据、状态与组件渲染均集中于此，便于映射到 Jetpack Compose。 */

const icons = {
  feed: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 3h6M10 3v4l-2.5 3.2v8.1A2.7 2.7 0 0 0 10.2 21h3.6a2.7 2.7 0 0 0 2.7-2.7v-8.1L14 7V3M8 12h8M10 16h4"/></svg>`,
  sleep: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18.5 15.6A7.5 7.5 0 0 1 8.4 5.5 8 8 0 1 0 18.5 15.6Z"/><path d="M15.5 5.2v3M14 6.7h3"/></svg>`,
  diaper: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 6.5 8.5 5l1.2 4.3h4.6L15.5 5 19 6.5l-1 10.2c-1.8 2-3.8 3-6 3s-4.2-1-6-3L5 6.5Z"/><path d="M7 14c3.3 1.3 6.7 1.3 10 0"/></svg>`,
  temperature: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 14.4V5a3 3 0 0 1 6 0v9.4a5 5 0 1 1-6 0Z"/><path d="M13 7v9M13 17.5h.1"/></svg>`,
  memo: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4.5h14v15H5zM8 8h8M8 12h8M8 16h5"/></svg>`,
  state: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14"/><circle cx="9" cy="7" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="11" cy="17" r="1.5"/></svg>`,
  chevronLeft: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14.5 6-6 6 6 6"/></svg>`,
  chevronRight: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9.5 6 6 6-6 6"/></svg>`,
  shield: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 19 6v5c0 4.5-2.3 7.7-7 10-4.7-2.3-7-5.5-7-10V6l7-3Z"/><path d="m9 12 2 2 4-4"/></svg>`,
  export: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12M8 11l4 4 4-4M5 18v3h14v-3"/></svg>`,
  bell: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 16V10a6 6 0 0 1 12 0v6l2 2H4l2-2ZM10 21h4"/></svg>`,
  personAdd: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM3 21v-2a6 6 0 0 1 10.5-4M18 13v8M14 17h8"/></svg>`,
};

const initialRecords = [
  { id: "feed-1420", type: "feed", time: "14:20", title: "配方奶 120ml", detail: "奶瓶喂养 · 妈妈记录", amount: 120 },
  { id: "diaper-1345", type: "diaper", time: "13:45", title: "换尿布（尿）", detail: "皮肤状态正常", diaper: "尿" },
  { id: "sleep-1210", type: "sleep", time: "12:10–13:30", title: "午睡 1小时20分", detail: "自然醒来", minutes: 80 },
];

const state = {
  page: "today",
  selectedDate: "today",
  period: "week",
  loading: false,
  overlay: null,
  quickType: null,
  feedKind: "配方奶",
  feedAmount: 120,
  diaperKind: "尿",
  temperature: 37.2,
  memo: "安安今天洗澡时一直在玩水，心情很好。",
  sleepTimerStartedAt: null,
  failNextSave: false,
  saveError: false,
  reminders: { feed: true, sleep: false },
  records: initialRecords.map((record) => ({ ...record })),
  summary: { feedCount: 5, feedMl: 610, sleepMinutes: 700, diaperCount: 7 },
  saveSequence: 0,
  lastUndo: null,
};

const refs = {
  header: document.querySelector("#appHeader"),
  viewport: document.querySelector("#screenViewport"),
  today: document.querySelector("#todayScreen"),
  stats: document.querySelector("#statsScreen"),
  settings: document.querySelector("#settingsScreen"),
  fab: document.querySelector("#quickFab"),
  overlay: document.querySelector("#overlay"),
  sheet: document.querySelector("#bottomSheet"),
  toast: document.querySelector("#toast"),
  live: document.querySelector("#liveRegion"),
};

let toastTimer = null;
let loadingTimer = null;

function headerTemplate() {
  if (state.page === "today") {
    return `
      <div class="baby-identity" data-od-id="baby-identity">
        <div class="avatar" aria-label="安安的抽象字母头像"><span>A</span></div>
        <div class="identity-copy"><strong>安安</strong><span>4个月18天</span></div>
      </div>
      ${stateButton()}`;
  }

  const title = state.page === "stats" ? "统计" : "设置";
  const subtitle = state.page === "stats" ? "找到安安自己的节奏" : "资料、提醒与家庭共享";
  return `
    <div><h1 class="page-heading">${title}</h1><span class="page-subtitle">${subtitle}</span></div>
    ${stateButton()}`;
}

function stateButton() {
  return `<button class="header-action" type="button" data-action="open-state-demo" aria-label="打开状态演示">${icons.state}<span>状态</span></button>`;
}

function summaryTemplate() {
  const { feedCount, feedMl, sleepMinutes, diaperCount } = state.summary;
  const hours = Math.floor(sleepMinutes / 60);
  const minutes = sleepMinutes % 60;
  return `
    <section class="summary-strip" aria-label="今日摘要" data-od-id="today-summary">
      <div class="summary-item"><span>喂养</span><strong>${feedCount}次 · ${feedMl}ml</strong></div>
      <div class="summary-item"><span>睡眠</span><strong>${hours}时${minutes}分</strong></div>
      <div class="summary-item"><span>尿布</span><strong>${diaperCount}次</strong></div>
    </section>`;
}

function dateStripTemplate() {
  const isToday = state.selectedDate === "today";
  return `
    <div class="date-strip" data-od-id="date-switcher">
      <button class="date-arrow" type="button" data-action="previous-date" aria-label="前一天">${icons.chevronLeft}<span>前</span></button>
      <div class="date-center"><strong>${isToday ? "今天" : "7月20日"}</strong><span>${isToday ? "7月22日 · 周三" : "周一"}</span></div>
      <button class="date-arrow" type="button" data-action="next-date" aria-label="后一天" ${isToday ? "disabled" : ""}><span>后</span>${icons.chevronRight}</button>
    </div>
    ${isToday ? "" : `<button class="today-button" type="button" data-action="go-today">回到今天</button>`}`;
}

function timerBannerTemplate() {
  if (!state.sleepTimerStartedAt) return "";
  return `
    <section class="timer-banner" aria-label="睡眠记录中" data-od-id="active-sleep-timer">
      <span class="timer-pulse" aria-hidden="true"></span>
      <div class="timer-copy"><strong>睡眠记录中</strong><span data-timer-mini>${formatElapsed()}</span></div>
      <button class="mini-button" type="button" data-action="end-sleep">结束并保存</button>
    </section>`;
}

function timelineTemplate() {
  const sleepBlocks = [
    [2.1, 24.3],
    [36.8, 3.1],
    [50.7, 5.6],
  ];
  const feedEvents = [5.6, 27.8, 42.0, 50.0, 59.7];
  const diaperEvents = [9.0, 22.9, 36.1, 45.1, 57.3, 60.8, 62.0];
  return `
    <section class="timeline-card" aria-label="24 小时记录时间轴" data-od-id="today-timeline">
      <div class="card-heading-row"><h2>24 小时</h2><span class="card-kicker">全天分布</span></div>
      <div class="timeline-legend" aria-label="图例">
        <span class="legend-item"><i class="legend-swatch sleep"></i>睡眠</span>
        <span class="legend-item"><i class="legend-swatch feed"></i>喂奶</span>
        <span class="legend-item"><i class="legend-swatch diaper"></i>尿布</span>
      </div>
      <div class="timeline-axis" aria-hidden="true"><span></span><span>00</span><span>06</span><span>12</span><span>18</span><span>24</span></div>
      <div class="timeline-row">
        <span class="lane-label">睡眠</span>
        <div class="timeline-track">
          <span class="now-label" style="left:63.2%">现在</span><span class="now-line" style="left:63.2%"></span>
          ${sleepBlocks.map(([left, width], index) => `<span class="sleep-block" style="left:${left}%;width:${width}%" role="img" aria-label="第${index + 1}段睡眠"></span>`).join("")}
        </div>
      </div>
      <div class="timeline-row">
        <span class="lane-label">喂奶</span>
        <div class="timeline-track">
          <span class="now-line" style="left:63.2%"></span>
          ${feedEvents.map((left, index) => `<span class="timeline-event" style="left:${left}%" role="img" aria-label="第${index + 1}次喂奶"><i class="feed-mark"></i></span>`).join("")}
        </div>
      </div>
      <div class="timeline-row">
        <span class="lane-label">尿布</span>
        <div class="timeline-track">
          <span class="now-line" style="left:63.2%"></span>
          ${diaperEvents.map((left, index) => `<span class="timeline-event" style="left:${left}%" role="img" aria-label="第${index + 1}次尿布"><i class="diaper-mark"></i></span>`).join("")}
        </div>
      </div>
    </section>`;
}

function recordRowTemplate(record) {
  return `
    <button class="record-row" type="button" data-record="${record.id}" aria-label="编辑 ${record.time} ${record.title}" data-od-id="record-${record.id}">
      <span class="record-time">${record.time}</span>
      <span class="record-icon ${record.type}">${icons[record.type]}</span>
      <span class="record-main"><strong>${record.title}</strong><span>${record.detail}</span></span>
      <span class="chevron" aria-hidden="true">›</span>
    </button>`;
}

function emptyDayTemplate() {
  return `
    <section class="empty-day" data-od-id="empty-history-state">
      <div><div class="empty-illustration" aria-hidden="true"></div><h2>这一天还没有记录</h2><p>当时没有留下数据也没关系。需要时，可以从右下角补记一条。</p></div>
    </section>`;
}

function loadingTemplate() {
  return `
    <div aria-label="今日记录正在加载" data-od-id="today-loading-state">
      <div class="skeleton skeleton-date"></div>
      <div class="skeleton skeleton-summary"></div>
      <div class="skeleton skeleton-timeline"></div>
      <div class="skeleton skeleton-line"></div>
      <div class="skeleton skeleton-list"></div>
    </div>`;
}

function renderToday() {
  if (state.loading) {
    refs.today.innerHTML = loadingTemplate();
    return;
  }
  if (state.selectedDate !== "today") {
    refs.today.innerHTML = `${dateStripTemplate()}${emptyDayTemplate()}`;
    return;
  }
  refs.today.innerHTML = `
    ${dateStripTemplate()}
    ${summaryTemplate()}
    ${timerBannerTemplate()}
    ${timelineTemplate()}
    <div class="section-heading-row"><h2>最近记录</h2><span class="card-kicker">共 ${state.records.length} 条可见</span></div>
    <div class="record-list" data-od-id="recent-records">
      ${state.records.slice(0, 5).map(recordRowTemplate).join("")}
    </div>`;
}

function milkChart() {
  const week = [540, 620, 590, 680, 640, 570, 610];
  const month = [598, 622, 607, 635];
  const values = state.period === "week" ? week : month;
  const labels = state.period === "week" ? ["16", "17", "18", "19", "20", "21", "22"] : ["第1周", "第2周", "第3周", "第4周"];
  const step = state.period === "week" ? 38 : 64;
  const start = state.period === "week" ? 46 : 58;
  const width = state.period === "week" ? 21 : 28;
  return `
    <svg class="chart-svg" viewBox="0 0 330 164" role="img" aria-labelledby="milkTitle milkDesc">
      <title id="milkTitle">每日奶量柱状图</title><desc id="milkDesc">${state.period === "week" ? "7月16日至22日" : "本月四周"}奶量，单位毫升。</desc>
      <path class="chart-grid" d="M34 20H321M34 72H321M34 124H321"/>
      <text x="2" y="23">800</text><text x="2" y="75">400</text><text x="14" y="127">0</text><text x="2" y="11">ml</text>
      ${values.map((value, index) => {
        const height = value / 800 * 104;
        const x = start + index * step;
        return `<rect class="milk-bar" x="${x}" y="${124 - height}" width="${width}" height="${height}" rx="5"/><text x="${x + width / 2}" y="145" text-anchor="middle">${labels[index]}</text>`;
      }).join("")}
      <text x="316" y="158" text-anchor="end">${state.period === "week" ? "7月 / 日" : "周"}</text>
    </svg>`;
}

function sleepChart() {
  const points = state.period === "week"
    ? [[44, 62], [84, 52], [124, 68], [164, 44], [204, 56], [244, 49], [284, 46]]
    : [[64, 57], [132, 49], [200, 52], [268, 45]];
  const labels = state.period === "week" ? ["16", "17", "18", "19", "20", "21", "22"] : ["第1周", "第2周", "第3周", "第4周"];
  const line = points.map((point, index) => `${index ? "L" : "M"}${point[0]} ${point[1]}`).join(" ");
  const area = `${line} L${points.at(-1)[0]} 124 L${points[0][0]} 124 Z`;
  return `
    <svg class="chart-svg" viewBox="0 0 330 156" role="img" aria-labelledby="sleepTitle sleepDesc">
      <title id="sleepTitle">睡眠时长趋势图</title><desc id="sleepDesc">${state.period === "week" ? "每日" : "每周日均"}睡眠时长折线与面积图，单位小时。</desc>
      <path class="chart-grid" d="M34 24H321M34 74H321M34 124H321"/>
      <text x="5" y="27">14h</text><text x="9" y="77">10h</text><text x="13" y="127">6h</text>
      <path class="sleep-area" d="${area}"/><path class="sleep-line" d="${line}"/>
      ${points.map((point, index) => `<circle class="sleep-point" cx="${point[0]}" cy="${point[1]}" r="3.5"/><text x="${point[0]}" y="145" text-anchor="middle">${labels[index]}</text>`).join("")}
    </svg>`;
}

function diaperChart() {
  const points = state.period === "week"
    ? [[44, 60], [84, 78], [124, 48], [164, 68], [204, 40], [244, 58], [284, 50]]
    : [[64, 62], [132, 52], [200, 56], [268, 48]];
  const labels = state.period === "week" ? ["16", "17", "18", "19", "20", "21", "22"] : ["第1周", "第2周", "第3周", "第4周"];
  const line = points.map((point, index) => `${index ? "L" : "M"}${point[0]} ${point[1]}`).join(" ");
  return `
    <svg class="chart-svg" viewBox="0 0 330 146" role="img" aria-labelledby="diaperTitle diaperDesc">
      <title id="diaperTitle">尿布次数点图</title><desc id="diaperDesc">${state.period === "week" ? "每日" : "每周日均"}尿布次数，单位次。</desc>
      <path class="chart-grid" d="M34 24H321M34 74H321M34 114H321"/>
      <text x="14" y="27">10</text><text x="19" y="77">5</text><text x="19" y="117">0</text><text x="2" y="11">次</text>
      <path class="diaper-line" d="${line}"/>
      ${points.map((point, index) => `<circle class="diaper-point" cx="${point[0]}" cy="${point[1]}" r="5"/><text x="${point[0]}" y="136" text-anchor="middle">${labels[index]}</text>`).join("")}
    </svg>`;
}

function metricCard(label, value, unit, note, type) {
  return `
    <article class="metric-card" data-od-id="metric-${type}">
      <div class="metric-top"><span>${label}</span><span class="metric-icon">${icons[type] || icons.memo}</span></div>
      <strong class="metric-value">${value}<small>${unit}</small></strong><span class="metric-note">${note}</span>
    </article>`;
}

function renderStats() {
  const month = state.period === "month";
  refs.stats.innerHTML = `
    <div class="stats-controls" data-od-id="stats-period-controls">
      <div class="period-switch" role="group" aria-label="统计周期">
        <button class="period-button ${month ? "" : "is-active"}" type="button" data-period="week" aria-pressed="${!month}">周</button>
        <button class="period-button ${month ? "is-active" : ""}" type="button" data-period="month" aria-pressed="${month}">月</button>
      </div>
      <button class="range-button" type="button" data-action="change-range" aria-label="切换统计日期范围">${icons.chevronLeft}<span>${month ? "7月" : "7/16–7/22"}</span>${icons.chevronRight}</button>
    </div>
    <div class="metric-grid" aria-label="核心指标">
      ${metricCard("喂养量", month ? "18.6" : "4,250", month ? "L" : "ml", month ? "本月累计示例" : "本周累计示例", "feed")}
      ${metricCard("睡眠时长", month ? "327" : "79.3", "小时", month ? "本月累计示例" : "本周累计示例", "sleep")}
      ${metricCard("尿布次数", month ? "201" : "49", "次", month ? "本月累计示例" : "本周累计示例", "diaper")}
      ${metricCard("趋势", month ? "更规律" : "较平稳", "", "按最近记录概括", "memo")}
    </div>
    <section class="chart-card" data-od-id="milk-chart"><div class="chart-heading"><h2>每日奶量</h2><span class="chart-legend"><i></i>${month ? "周日均" : "奶量"} · ml</span></div>${milkChart()}</section>
    <section class="chart-card sleep-chart" data-od-id="sleep-chart"><div class="chart-heading"><h2>睡眠时长</h2><span class="chart-legend"><i></i>睡眠 · 小时</span></div>${sleepChart()}</section>
    <section class="chart-card diaper-chart" data-od-id="diaper-chart"><div class="chart-heading"><h2>尿布次数</h2><span class="chart-legend"><i></i>尿布 · 次</span></div>${diaperChart()}</section>`;
}

function settingIcon(iconName) {
  return `<span class="setting-row-icon">${icons[iconName]}</span>`;
}

function renderSettings() {
  refs.settings.innerHTML = `
    <section class="profile-card" data-od-id="baby-profile-card">
      <div class="avatar" aria-label="安安的抽象字母头像"><span>A</span></div>
      <div class="profile-copy"><strong>安安</strong><span>生日 · 2026年3月4日</span></div>
      <button class="text-button" type="button" data-action="edit-profile">编辑</button>
    </section>

    <section class="settings-section" data-od-id="reminder-settings"><h2>提醒</h2>
      <div class="settings-list">
        <div class="setting-row">${settingIcon("bell")}<div class="setting-copy"><strong>喂奶提醒</strong><span>${state.reminders.feed ? "下次 16:30 · 约 1小时后" : "已关闭"}</span></div><button class="switch" type="button" role="switch" aria-label="喂奶提醒" aria-checked="${state.reminders.feed}" data-reminder="feed"></button></div>
        <div class="setting-row">${settingIcon("sleep")}<div class="setting-copy"><strong>睡眠提醒</strong><span>${state.reminders.sleep ? "下次 18:45 · 睡前准备" : "已关闭"}</span></div><button class="switch" type="button" role="switch" aria-label="睡眠提醒" aria-checked="${state.reminders.sleep}" data-reminder="sleep"></button></div>
      </div>
    </section>

    <section class="settings-section" data-od-id="family-sharing"><h2>家庭成员共享</h2>
      <div class="settings-list">
        <div class="setting-row"><span class="member-avatar">妈</span><div class="setting-copy"><strong>妈妈（我）</strong><span>刚刚记录了配方奶</span></div><span class="sync-badge">本机</span></div>
        <div class="setting-row"><span class="member-avatar dad">爸</span><div class="setting-copy"><strong>爸爸</strong><span>2分钟前同步</span></div><span class="sync-badge">已同步</span></div>
        <button class="setting-row" type="button" data-action="add-member">${settingIcon("personAdd")}<div class="setting-copy"><strong>添加成员</strong><span>通过家庭邀请码加入</span></div><span class="chevron">›</span></button>
      </div>
    </section>

    <section class="settings-section" data-od-id="data-settings"><h2>其他</h2>
      <div class="settings-list">
        <button class="setting-row" type="button" data-action="privacy">${settingIcon("shield")}<div class="setting-copy"><strong>数据与隐私</strong><span>本地记录与共享范围</span></div><span class="chevron">›</span></button>
        <button class="setting-row" type="button" data-action="export">${settingIcon("export")}<div class="setting-copy"><strong>导出记录</strong><span>生成 CSV 与可打印摘要</span></div><span class="chevron">›</span></button>
      </div>
    </section>

    <aside class="prototype-entry" data-od-id="prototype-state-entry"><button type="button" data-action="open-state-demo"><span>原型辅助 · 验证加载、空数据与反馈状态</span><strong>状态演示 ›</strong></button></aside>`;
}

function renderApp({ preserveScroll = true } = {}) {
  const scrollTop = refs.viewport.scrollTop;
  refs.header.innerHTML = headerTemplate();
  renderToday();
  renderStats();
  renderSettings();

  [refs.today, refs.stats, refs.settings].forEach((screen) => {
    const active = screen.dataset.page === state.page;
    screen.hidden = !active;
    screen.classList.toggle("is-active", active);
  });
  document.querySelectorAll("[data-nav]").forEach((button) => {
    const active = button.dataset.nav === state.page;
    button.classList.toggle("is-active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  refs.fab.hidden = state.page !== "today";
  if (preserveScroll) refs.viewport.scrollTop = scrollTop;
  updateTimerDisplays();
}

function switchPage(page) {
  state.page = page;
  renderApp({ preserveScroll: false });
  refs.viewport.scrollTop = 0;
  refs.viewport.focus({ preventScroll: true });
  announce(`已切换到${page === "today" ? "今日" : page === "stats" ? "统计" : "设置"}页`);
}

function openOverlay(kind, detail = {}) {
  window.clearTimeout(toastTimer);
  refs.toast.hidden = true;
  if (kind !== "quick") state.failNextSave = false;
  state.overlay = { kind, ...detail };
  state.saveError = false;
  renderSheet();
  refs.overlay.hidden = false;
  window.setTimeout(() => refs.sheet.focus({ preventScroll: true }), 0);
}

function closeOverlay() {
  refs.overlay.hidden = true;
  state.overlay = null;
  state.saveError = false;
  const returnTarget = state.page === "today" ? refs.fab : document.querySelector(`[data-nav="${state.page}"]`);
  returnTarget?.focus({ preventScroll: true });
}

function quickTypeTemplate(type, title, subtitle) {
  return `<button class="quick-type" type="button" data-quick-type="${type}" aria-label="记录${title}" data-od-id="quick-type-${type}"><span class="quick-type-icon ${type}">${icons[type]}</span><span class="quick-type-copy"><strong>${title}</strong><span>${subtitle}</span></span></button>`;
}

function quickHomeTemplate() {
  return `
    <div class="sheet-handle"></div>
    <div class="sheet-heading"><div class="sheet-heading-copy"><h2 id="sheetTitle">快速记录</h2><p>选类型后，下一步就能保存</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div>
    ${state.sleepTimerStartedAt ? `<div class="timer-banner"><span class="timer-pulse"></span><div class="timer-copy"><strong>睡眠记录中</strong><span data-timer-mini>${formatElapsed()}</span></div><button class="mini-button" type="button" data-action="end-sleep">结束</button></div>` : ""}
    <div class="quick-grid">
      ${quickTypeTemplate("feed", "喂奶", "配方奶 120ml")}
      ${quickTypeTemplate("sleep", "睡眠", state.sleepTimerStartedAt ? "计时进行中" : "开始计时")}
      ${quickTypeTemplate("diaper", "尿布", "尿 / 便 / 都有")}
      ${quickTypeTemplate("temperature", "体温", "默认 37.2°C")}
      ${quickTypeTemplate("memo", "备忘", "记下今天的小事")}
    </div>`;
}

function inlineErrorTemplate() {
  if (!state.saveError) return "";
  return `<div class="inline-error" role="alert"><span>这次没有保存成功，记录内容还在。</span><button class="danger-retry" type="button" data-action="retry-save">重试</button></div>`;
}

function quickEditorTemplate() {
  const labels = { feed: "喂奶", sleep: "睡眠", diaper: "尿布", temperature: "体温", memo: "备忘" };
  return `
    <div class="sheet-handle"></div>
    <div class="sheet-heading"><button class="sheet-back" type="button" data-action="quick-back">← 返回</button><div class="sheet-heading-copy"><h2 id="sheetTitle">${labels[state.quickType]}</h2><p>确认后即可写入今天</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div>
    ${quickEditorBody()}
    ${inlineErrorTemplate()}`;
}

function quickEditorBody() {
  if (state.quickType === "feed") {
    return `
      <div class="field-group"><div class="field-label"><span>喂养方式</span><span>今天第 ${state.summary.feedCount + 1} 次</span></div>
        <div class="segmented" role="group" aria-label="喂养方式">${["母乳", "配方奶", "瓶喂"].map((kind) => `<button class="segment-button ${state.feedKind === kind ? "is-selected" : ""}" type="button" data-feed-kind="${kind}" aria-pressed="${state.feedKind === kind}">${kind}</button>`).join("")}</div>
      </div>
      <div class="field-group"><div class="field-label"><span>奶量</span><span>每次调整 10ml</span></div>
        <div class="amount-stepper"><button class="stepper-button" type="button" data-amount-change="-10" aria-label="减少 10 毫升">−10</button><div class="amount-value"><strong>${state.feedAmount}</strong><span>ml</span></div><button class="stepper-button" type="button" data-amount-change="10" aria-label="增加 10 毫升">+10</button></div>
        <div class="preset-row">${[90, 120, 150].map((amount) => `<button class="preset-button ${state.feedAmount === amount ? "is-selected" : ""}" type="button" data-feed-preset="${amount}">${amount} ml</button>`).join("")}</div>
      </div>
      <button class="primary-button" type="button" data-action="save-quick">保存 ${state.feedKind} ${state.feedAmount}ml</button>`;
  }

  if (state.quickType === "sleep") {
    const running = Boolean(state.sleepTimerStartedAt);
    return `
      <div class="timer-panel"><span class="eyebrow">${running ? "记录中" : "准备开始"}</span><div class="timer-display" data-timer-display>${running ? formatElapsed() : "00:00:00"}</div><p>${running ? "开始于 " + formatStartTime() : "点击后会持续计时，可随时结束并保存"}</p></div>
      <button class="primary-button" type="button" data-action="${running ? "end-sleep" : "start-sleep"}">${running ? "结束并保存" : "开始睡眠计时"}</button>`;
  }

  if (state.quickType === "diaper") {
    return `
      <div class="field-group"><div class="field-label"><span>尿布情况</span><span>选择一项</span></div>
        <div class="segmented" role="group" aria-label="尿布情况">${["尿", "便", "都有"].map((kind) => `<button class="segment-button ${state.diaperKind === kind ? "is-selected" : ""}" type="button" data-diaper-kind="${kind}" aria-pressed="${state.diaperKind === kind}">${kind}</button>`).join("")}</div>
      </div>
      <div class="detail-summary"><span class="record-icon diaper">${icons.diaper}</span><div><strong>换尿布（${state.diaperKind}）</strong><span>记录时间 · 现在</span></div></div>
      <button class="primary-button" type="button" data-action="save-quick">保存尿布记录</button>`;
  }

  if (state.quickType === "temperature") {
    return `
      <div class="field-group"><div class="field-label"><span>体温</span><span>腋温</span></div>
        <div class="temperature-input"><button class="stepper-button" type="button" data-temp-change="-0.1" aria-label="降低 0.1 摄氏度">−0.1</button><div class="temperature-value"><strong>${state.temperature.toFixed(1)}</strong><span>°C</span></div><button class="stepper-button" type="button" data-temp-change="0.1" aria-label="升高 0.1 摄氏度">+0.1</button></div>
      </div>
      <button class="primary-button" type="button" data-action="save-quick">保存体温 ${state.temperature.toFixed(1)}°C</button>`;
  }

  return `
    <div class="field-group"><label class="field-label" for="memoInput"><span>想记下什么</span><span>${state.memo.length}/80</span></label><textarea class="memo-input" id="memoInput" maxlength="80">${state.memo}</textarea></div>
    <button class="primary-button" type="button" data-action="save-quick">保存备忘</button>`;
}

function stateDemoTemplate() {
  return `
    <div class="sheet-handle"></div>
    <div class="sheet-heading"><div class="sheet-heading-copy"><h2 id="sheetTitle">状态演示</h2><p>这些入口只用于验证原型，不影响主流程</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div>
    <div class="state-demo-list">
      <button class="state-demo-button" type="button" data-demo="loading"><strong>加载</strong><span>今日页骨架屏，约 1.6 秒后恢复</span></button>
      <button class="state-demo-button" type="button" data-demo="empty"><strong>空数据</strong><span>切到 7月20日的温和空状态</span></button>
      <button class="state-demo-button" type="button" data-demo="running"><strong>记录中</strong><span>启动已运行 18 分钟的睡眠计时</span></button>
      <button class="state-demo-button" type="button" data-demo="success"><strong>保存成功</strong><span>新增一条备忘并提供撤销</span></button>
      <button class="state-demo-button" type="button" data-demo="error"><strong>保存失败</strong><span>打开喂奶记录，保存后可重试</span></button>
    </div>`;
}

function detailSheetTemplate(record) {
  if (!record) return stateDemoTemplate();
  const duration = record.type === "sleep" ? `${record.minutes || 80} 分钟` : record.type === "feed" ? `${record.amount || 120} ml` : record.type === "temperature" ? `${record.temperature || 37.2}°C` : record.type === "diaper" ? record.diaper || "尿" : "文字记录";
  return `
    <div class="sheet-handle"></div>
    <div class="sheet-heading"><div class="sheet-heading-copy"><h2 id="sheetTitle">记录详情</h2><p>查看或编辑这条记录</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div>
    <div class="detail-summary"><span class="record-icon ${record.type}">${icons[record.type]}</span><div><strong>${record.title}</strong><span>${record.time} · 妈妈记录</span></div></div>
    <div class="detail-meta"><div class="meta-cell"><span>类型</span><strong>${typeLabel(record.type)}</strong></div><div class="meta-cell"><span>本次数据</span><strong>${duration}</strong></div></div>
    <div class="field-group"><label class="field-label" for="detailNote"><span>备注</span><span>可选</span></label><textarea class="detail-input" id="detailNote">${record.detail || ""}</textarea></div>
    <div class="button-row"><button class="secondary-button" type="button" data-action="close-sheet">取消</button><button class="primary-button" type="button" data-action="save-detail" data-record-id="${record.id}">保存修改</button></div>`;
}

function infoSheetTemplate(kind) {
  if (kind === "profile") {
    return `<div class="sheet-handle"></div><div class="sheet-heading"><div class="sheet-heading-copy"><h2 id="sheetTitle">宝宝资料</h2><p>用于年龄与统计范围</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div><div class="field-group"><label class="field-label" for="babyName"><span>名字</span></label><input class="detail-input single" id="babyName" value="安安" /></div><div class="field-group"><label class="field-label" for="birthday"><span>生日</span></label><input class="detail-input single" id="birthday" value="2026年3月4日" /></div><button class="primary-button" type="button" data-action="save-profile">保存资料</button>`;
  }
  return `<div class="sheet-handle"></div><div class="sheet-heading"><div class="sheet-heading-copy"><h2 id="sheetTitle">数据与隐私</h2><p>暖芽只展示原型内的示例记录</p></div><button class="sheet-close" type="button" data-action="close-sheet">关闭</button></div><div class="settings-list"><div class="setting-row">${settingIcon("shield")}<div class="setting-copy"><strong>家庭可见</strong><span>仅妈妈与已同步的爸爸可查看</span></div></div><div class="setting-row">${settingIcon("export")}<div class="setting-copy"><strong>导出前确认</strong><span>每次导出都会再次确认范围</span></div></div></div>`;
}

function renderSheet() {
  if (!state.overlay) return;
  if (state.overlay.kind === "quick") refs.sheet.innerHTML = state.overlay.step === "editor" ? quickEditorTemplate() : quickHomeTemplate();
  if (state.overlay.kind === "state") refs.sheet.innerHTML = stateDemoTemplate();
  if (state.overlay.kind === "detail") refs.sheet.innerHTML = detailSheetTemplate(state.records.find((record) => record.id === state.overlay.recordId));
  if (state.overlay.kind === "profile" || state.overlay.kind === "privacy") refs.sheet.innerHTML = infoSheetTemplate(state.overlay.kind);
  updateTimerDisplays();
}

function typeLabel(type) {
  return ({ feed: "喂奶", sleep: "睡眠", diaper: "尿布", temperature: "体温", memo: "备忘" })[type] || "记录";
}

function formatElapsed() {
  if (!state.sleepTimerStartedAt) return "00:00:00";
  const elapsed = Math.max(0, Math.floor((Date.now() - state.sleepTimerStartedAt) / 1000));
  const hours = String(Math.floor(elapsed / 3600)).padStart(2, "0");
  const minutes = String(Math.floor((elapsed % 3600) / 60)).padStart(2, "0");
  const seconds = String(elapsed % 60).padStart(2, "0");
  return `${hours}:${minutes}:${seconds}`;
}

function formatStartTime() {
  if (!state.sleepTimerStartedAt) return "现在";
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(state.sleepTimerStartedAt);
}

function updateTimerDisplays() {
  document.querySelectorAll("[data-timer-display], [data-timer-mini]").forEach((element) => {
    element.textContent = formatElapsed();
  });
}

function prototypeTime() {
  const minutes = 12 + state.saveSequence;
  return `15:${String(minutes).padStart(2, "0")}`;
}

function buildQuickRecord() {
  state.saveSequence += 1;
  const time = prototypeTime();
  const base = { id: `${state.quickType}-${Date.now()}`, type: state.quickType, time };
  if (state.quickType === "feed") return { ...base, title: `${state.feedKind} ${state.feedAmount}ml`, detail: "快速记录 · 妈妈", amount: state.feedAmount, delta: { feedCount: 1, feedMl: state.feedAmount } };
  if (state.quickType === "diaper") return { ...base, title: `换尿布（${state.diaperKind}）`, detail: "快速记录 · 妈妈", diaper: state.diaperKind, delta: { diaperCount: 1 } };
  if (state.quickType === "temperature") return { ...base, title: `体温 ${state.temperature.toFixed(1)}°C`, detail: "腋温 · 妈妈记录", temperature: state.temperature };
  return { ...base, title: "备忘", detail: state.memo, note: state.memo };
}

function applyDelta(delta = {}, direction = 1) {
  Object.entries(delta).forEach(([key, value]) => {
    state.summary[key] += value * direction;
  });
}

function saveQuick({ retry = false } = {}) {
  if (state.failNextSave && !retry) {
    state.failNextSave = false;
    state.saveError = true;
    renderSheet();
    announce("保存失败，记录内容仍保留，可以重试");
    return;
  }
  const record = buildQuickRecord();
  state.records.unshift(record);
  applyDelta(record.delta);
  state.lastUndo = () => {
    state.records = state.records.filter((item) => item.id !== record.id);
    applyDelta(record.delta, -1);
    renderApp();
  };
  closeOverlay();
  state.page = "today";
  state.selectedDate = "today";
  renderApp({ preserveScroll: false });
  showToast(`已保存：${record.title}`, true);
}

function startSleep() {
  state.sleepTimerStartedAt = Date.now();
  closeOverlay();
  state.page = "today";
  state.selectedDate = "today";
  renderApp({ preserveScroll: false });
  showToast("睡眠计时已开始");
}

function finishSleep() {
  if (!state.sleepTimerStartedAt) return;
  const minutes = Math.max(1, Math.round((Date.now() - state.sleepTimerStartedAt) / 60000));
  state.saveSequence += 1;
  const record = { id: `sleep-${Date.now()}`, type: "sleep", time: `${formatStartTime()}–${prototypeTime()}`, title: `睡眠 ${minutes}分钟`, detail: "计时完成 · 妈妈记录", minutes, delta: { sleepMinutes: minutes } };
  state.sleepTimerStartedAt = null;
  state.records.unshift(record);
  applyDelta(record.delta);
  state.lastUndo = () => {
    state.records = state.records.filter((item) => item.id !== record.id);
    applyDelta(record.delta, -1);
    renderApp();
  };
  if (!refs.overlay.hidden) closeOverlay();
  state.page = "today";
  state.selectedDate = "today";
  renderApp({ preserveScroll: false });
  showToast(`已保存：睡眠 ${minutes}分钟`, true);
}

function saveDetail(recordId) {
  const record = state.records.find((item) => item.id === recordId);
  const note = document.querySelector("#detailNote")?.value.trim();
  if (record && note) record.detail = note;
  closeOverlay();
  renderApp();
  showToast("记录已更新");
}

function showToast(message, undo = false) {
  window.clearTimeout(toastTimer);
  refs.toast.innerHTML = `<span>${message}</span>${undo ? `<button type="button" data-action="undo-save">撤销</button>` : ""}`;
  refs.toast.hidden = false;
  announce(message);
  toastTimer = window.setTimeout(() => {
    refs.toast.hidden = true;
  }, 4200);
}

function announce(message) {
  refs.live.textContent = "";
  window.setTimeout(() => { refs.live.textContent = message; }, 10);
}

function triggerDemo(kind) {
  if (kind === "loading") {
    closeOverlay();
    state.page = "today";
    state.selectedDate = "today";
    state.loading = true;
    renderApp({ preserveScroll: false });
    window.clearTimeout(loadingTimer);
    loadingTimer = window.setTimeout(() => {
      state.loading = false;
      renderApp();
      showToast("今日记录已加载");
    }, 1600);
    return;
  }
  if (kind === "empty") {
    closeOverlay();
    state.page = "today";
    state.selectedDate = "empty";
    renderApp({ preserveScroll: false });
    return;
  }
  if (kind === "running") {
    state.sleepTimerStartedAt = Date.now() - 18 * 60 * 1000 - 32 * 1000;
    closeOverlay();
    state.page = "today";
    state.selectedDate = "today";
    renderApp({ preserveScroll: false });
    return;
  }
  if (kind === "success") {
    state.failNextSave = false;
    state.quickType = "memo";
    state.memo = "安安午睡醒来后很放松，抱着小毯子看了很久。";
    saveQuick();
    return;
  }
  state.quickType = "feed";
  state.feedKind = "配方奶";
  state.feedAmount = 120;
  state.failNextSave = true;
  state.overlay = { kind: "quick", step: "editor" };
  renderSheet();
  announce("保存失败演示已准备，点击保存即可触发");
}

function handleClick(event) {
  const nav = event.target.closest("[data-nav]");
  if (nav) return switchPage(nav.dataset.nav);

  const record = event.target.closest("[data-record]");
  if (record) return openOverlay("detail", { recordId: record.dataset.record });

  const quickType = event.target.closest("[data-quick-type]");
  if (quickType) {
    state.quickType = quickType.dataset.quickType;
    state.overlay = { kind: "quick", step: "editor" };
    state.saveError = false;
    return renderSheet();
  }

  const period = event.target.closest("[data-period]");
  if (period) {
    state.period = period.dataset.period;
    renderStats();
    return;
  }

  const reminder = event.target.closest("[data-reminder]");
  if (reminder) {
    const key = reminder.dataset.reminder;
    state.reminders[key] = !state.reminders[key];
    renderSettings();
    announce(`${key === "feed" ? "喂奶" : "睡眠"}提醒已${state.reminders[key] ? "开启" : "关闭"}`);
    return;
  }

  const feedKind = event.target.closest("[data-feed-kind]");
  if (feedKind) {
    state.feedKind = feedKind.dataset.feedKind;
    return renderSheet();
  }
  const feedAmount = event.target.closest("[data-amount-change]");
  if (feedAmount) {
    state.feedAmount = Math.min(300, Math.max(10, state.feedAmount + Number(feedAmount.dataset.amountChange)));
    return renderSheet();
  }
  const preset = event.target.closest("[data-feed-preset]");
  if (preset) {
    state.feedAmount = Number(preset.dataset.feedPreset);
    return renderSheet();
  }
  const diaper = event.target.closest("[data-diaper-kind]");
  if (diaper) {
    state.diaperKind = diaper.dataset.diaperKind;
    return renderSheet();
  }
  const temperature = event.target.closest("[data-temp-change]");
  if (temperature) {
    state.temperature = Math.min(42, Math.max(34, Math.round((state.temperature + Number(temperature.dataset.tempChange)) * 10) / 10));
    return renderSheet();
  }

  const demo = event.target.closest("[data-demo]");
  if (demo) return triggerDemo(demo.dataset.demo);

  const action = event.target.closest("[data-action]")?.dataset.action;
  if (!action) return;
  const actions = {
    "open-state-demo": () => openOverlay("state"),
    "close-sheet": closeOverlay,
    "quick-back": () => { state.overlay = { kind: "quick", step: "home" }; state.saveError = false; renderSheet(); },
    "save-quick": () => saveQuick(),
    "retry-save": () => saveQuick({ retry: true }),
    "start-sleep": startSleep,
    "end-sleep": finishSleep,
    "previous-date": () => { state.selectedDate = "empty"; renderToday(); refs.viewport.scrollTop = 0; },
    "next-date": () => { state.selectedDate = "today"; renderToday(); refs.viewport.scrollTop = 0; },
    "go-today": () => { state.selectedDate = "today"; renderToday(); refs.viewport.scrollTop = 0; },
    "change-range": () => showToast(state.period === "week" ? "已保持当前周：7月16日–22日" : "已保持当前月份：7月"),
    "edit-profile": () => openOverlay("profile"),
    "save-profile": () => { closeOverlay(); showToast("宝宝资料已更新"); },
    "add-member": () => showToast("家庭邀请入口已准备（原型演示）"),
    "privacy": () => openOverlay("privacy"),
    "export": () => showToast("导出文件已准备（原型演示）"),
    "save-detail": () => saveDetail(event.target.closest("[data-record-id]")?.dataset.recordId),
    "undo-save": () => {
      if (state.lastUndo) state.lastUndo();
      state.lastUndo = null;
      refs.toast.hidden = true;
      announce("已撤销刚才的保存");
    },
  };
  actions[action]?.();
}

document.addEventListener("click", handleClick);
refs.fab.addEventListener("click", () => openOverlay("quick", { step: "home" }));
refs.overlay.querySelector(".overlay-backdrop").addEventListener("click", closeOverlay);
document.addEventListener("input", (event) => {
  if (event.target.matches("#memoInput")) state.memo = event.target.value;
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !refs.overlay.hidden) closeOverlay();
});

window.setInterval(updateTimerDisplays, 1000);
renderApp({ preserveScroll: false });
