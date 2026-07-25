# 適合 X11 的終端模擬器（資源佔用合理）

> **元信息**  
> - 調研日：2026-07-23  
> - 方法：優先查閱**上游官網 / README / man 文檔 / 發行說明 / 發行版官方套件中繼資料**；不以「best terminals 20xx」清單文為證據。  
> - 證據分級：  
>   - **[官方]** 上游或維護者/官方套件庫  
>   - **[二手]** 第三方基準、論壇、二級 wiki（僅作線索）  
>   - **[推斷]** 由依賴與架構合理推導（非實測 RSS）  
> - **不虛構 RSS 數字**；文中僅在上游 FAQ 等一手材料明確給出數字時引用。  
> - Arch `installed_size` 為**磁碟安裝體積**（位元組級套件中繼資料），**不是**執行期 RSS。

---

## 1. 結論摘要（給快速決策）

### 超輕量推薦（X11 原生、依賴極少）

| 優先 | 名稱 | 一句話 |
|------|------|--------|
| 1 | **st**（suckless） | 官方自述「simple terminal for X」；體積極小、配置靠 `config.h`；日常用常需補丁。 |
| 2 | **xterm** | X Window System 標準終端（Thomas Dickey 維護）；依賴僅 Xlib 系，套件體積極小。 |
| 3 | **rxvt-unicode (urxvt)** | 純 X11 Unicode 終端；daemon 模式可共享程序、官方 FAQ 討論記憶體與多字體 CJK。 |

### 均衡現代推薦（真彩色 / fontconfig / X11 良好，體積與功能折衷）

| 優先 | 名稱 | 一句話 |
|------|------|--------|
| 1 | **Alacritty** | OpenGL GPU 終端；**預設同時開 X11 + Wayland**（Cargo features）；無內建 tabs/splits（刻意留給 WM/tmux）；依賴相對精簡。 |
| 2 | **sakura** / **lxterminal** / **xfce4-terminal** | VTE+GTK 輕量殼；有標籤、真彩色、X11（GTK 後端）；比 GNOME/KDE 全家桶輕。 |

### 功能豐富但仍合理（可接受 GPU / 較大安裝體積）

| 優先 | 名稱 | 一句話 |
|------|------|--------|
| 1 | **Kitty** | C/Python/OpenGL；官方支援 X11 與 Wayland（Gentoo USE `X`/`wayland`；Arch 依賴含 `libx11` + `wayland`）；內建 tabs/layouts/圖像協議。 |
| 2 | **WezTerm** | Rust GPU；`enable_wayland=false` 時明確走 **X11**（官方配置文檔）；自帶 multiplexer/tabs/panes。 |
| 3 | **mlterm** | 「Multilingual terminal emulator on X11」；`--with-gui=xlib` 為一等公民；CJK/BiDi/多輸入法為設計重心。 |
| 4 | **Contour** | C++/Qt6/GPU；Arch 依賴含 `libxcb` + `qt6-*` + `wayland`，在 X11 可用（Qt 平台插件）。 |
| 5 | **Ghostty** | Zig 核心 + Linux **GTK4** GUI；官方配置含 `x11-instance-name`（「when running under X11」）；功能全但安裝/GTK 依賴偏重。 |

### 需謹慎 / 不推薦於「省資源 + X11」

| 名稱 | 原因 |
|------|------|
| **foot** | 上游明確：**Wayland native**，非 X11 終端。 |
| **Hyper** | Electron + xterm.js；**[推斷]** Chromium 級 runtime，與「合理資源」目標衝突。 |
| **cool-retro-term** | 新奇 CRT 效果；Qt/QML 體積大，非日常省資源選擇。 |
| **GNOME Terminal / Konsole** | 功能完整，但分別綁 GTK/VTE 或 KDE Frameworks，依賴與安裝體積明顯更重；非「輕」基準。 |
| **lilyterm** | 候選名單中：Arch 官方庫無套件；**[仍未確認]** 活躍度與維護狀態，不宜作為首選。 |

**若只選一個 X11 + 合理資源起點：**  
- 老機器 / 多開大量窗口 → **urxvt（daemon）** 或 **st** / **xterm**  
- 日常開發 + 現代字型/真彩色 → **Alacritty**（配 tmux）或 **xfce4-terminal**  
- 要內建分屏/標籤/圖像 → **Kitty** 或 **WezTerm**（接受 GPU + 較大安裝體積）  
- 中日韓/複雜文字為剛需 → **mlterm** 或配置良好的 **urxvt** / 現代 GPU 終端 + 正確 fontconfig 回落字型  

---

## 2. 比較表

| 名稱 | 顯示後端 (X11/Wayland) | 渲染/技術棧 | 依賴重量級 | 資源取向 | 分屏/標籤 | CJK/Unicode 備註 | 一手源 |
|------|------------------------|-------------|------------|----------|-----------|------------------|--------|
| **xterm** | **X11 原生** | Xlib / Xaw / Xft | 極輕 | 經典輕量 | 無內建 | Unicode/字型可配；歷史包袱多 | [invisible-island.net/xterm](https://invisible-island.net/xterm/) · [Arch xterm](https://archlinux.org/packages/extra/x86_64/xterm/) |
| **rxvt-unicode** | **X11 原生** | Xlib + 可選 Xft；Perl 擴展 | 輕（可選 Perl） | 輕量；官方強調 daemon 省記憶體/啟動 | Perl `tabbed` | 多字體並存、locale；官方指複雜文稿/RTL 用 mlterm | [software.schmorp.de](http://software.schmorp.de/pkg/rxvt-unicode.html) · [FAQ](http://pod.tst.eu/http://cvs.schmorp.de/rxvt-unicode/doc/rxvt.7.pod) |
| **st** | **X11 原生**（「for X」） | Xlib + fontconfig | 極輕 | 極簡設計目標 | 無（交給 tmux/WM） | UTF-8 / wide char / truecolor | [st.suckless.org](https://st.suckless.org/) |
| **Alacritty** | **X11 + Wayland**（預設 features 皆開） | Rust + OpenGL (winit/glutin) | 中（需 GPU/OpenGL ES 2.0+） | 追求吞吐；無 GUI 套件 | **刻意無** tabs/splits | fontconfig；Unicode 視字型 | [GitHub README](https://github.com/alacritty/alacritty) · [Cargo features](https://github.com/alacritty/alacritty/blob/master/alacritty/Cargo.toml) |
| **Kitty** | **X11 + Wayland** | C + Python + OpenGL；無大型 UI toolkit | 中–重（GPU + Python） | 功能向 GPU；官方 FAQ 討論記憶體測量 | **有** layouts/tabs | Unicode/truecolor/emoji；圖像協議 | [sw.kovidgoyal.net/kitty](https://sw.kovidgoyal.net/kitty/) · [overview](https://sw.kovidgoyal.net/kitty/overview/) |
| **WezTerm** | **X11 與 Wayland**（`enable_wayland`） | Rust + GPU | 中–重 | 全功能 multiplexer | **有** tabs/panes | harfbuzz 塑形；`treat_east_asian_ambiguous_width_as_wide` 等 | [wezterm.org](https://wezterm.org/) · [enable_wayland](https://wezterm.org/config/lua/config/enable_wayland.html) |
| **foot** | **僅 Wayland**（官方） | C；輕量 Wayland | 極輕（但非 X11） | 輕量 | 無傳統 GUI 標籤 | truecolor / emoji / sixel | [codeberg.org/dnkl/foot](https://codeberg.org/dnkl/foot) |
| **Contour** | Qt 平台（含 X11/Wayland） | C++23 + **Qt6** + OpenGL | 重（Qt6 全家） | 現代全功能 | **有** tabs | grapheme / emoji / ligatures | [GitHub contour](https://github.com/contour-terminal/contour) · [Arch contour](https://archlinux.org/packages/extra/x86_64/contour/) |
| **Ghostty** | GTK4（**可跑 X11**，官方 WM_CLASS 選項） | Zig + OpenGL(Linux) + GTK4/libadwaita | 重 | 功能+原生 UI | **有** windows/tabs/splits | grapheme；Kitty graphics protocol | [ghostty.org/docs/about](https://ghostty.org/docs/about) · [config x11-instance-name](https://ghostty.org/docs/config/reference) |
| **mlterm** | **X11 (xlib)** 一等；另有 wayland/fb 等 | 自有引擎；xcore/xft/cairo | 輕–中 | 多語文專精 | 有限（視建置） | **多語/CJK/BiDi 設計目標** | [mlterm.sourceforge.net](https://mlterm.sourceforge.net/) · [README](https://github.com/arakiken/mlterm/blob/master/README) |
| **xfce4-terminal** | GTK（X11/Wayland 視 GTK） | **VTE** + GTK + libxfce4ui | 中（VTE） | 桌面「輕量」定位 | **有** tabs；dropdown | VTE Unicode/CJK 通常良好 | [docs.xfce.org](https://docs.xfce.org/apps/terminal/start) |
| **sakura** | GTK | **VTE** + GTK | 中（VTE） | 簡單 VTE 殼 | **有** tabs | 同 VTE | [GitHub dabisu/sakura](https://github.com/dabisu/sakura) |
| **lxterminal** | GTK | **VTE** + GTK | 中（VTE） | LXDE 輕量終端 | **有** tabs | 同 VTE | [Arch lxterminal](https://archlinux.org/packages/extra/x86_64/lxterminal/) · [lxde/lxterminal](https://github.com/lxde/lxterminal) |
| **GNOME Terminal** | GTK | **VTE** + GNOME 整合 | 中–重 | 功能完整桌面終端 | **有** | 同 VTE；a11y 等 | [GNOME Terminal](https://wiki.gnome.org/Apps/Terminal) · [vte](https://gitlab.gnome.org/GNOME/vte) |
| **Konsole** | Qt / KDE | KDE Frameworks + Konsole 核心 | 重（若未裝 KDE 則拉很多） | 功能完整 | **有** tabs/profiles | 良好 Unicode | [apps.kde.org/konsole](https://apps.kde.org/konsole/) |
| **terminology** | EFL（X11 等由 EFL 支援） | **EFL**；可選 OpenGL | 中–重（EFL 棧） | 多媒體向 | 有 | 內嵌圖/影等 | [terminology README](https://github.com/borisfaure/terminology) |
| **Hyper** | Electron（跨平台） | **Electron** + xterm.js | **很重** | 不適省資源 | 有 | 視 xterm.js/字型 | [hyper README](https://github.com/vercel/hyper) · `package.json` 使用 `electron` |
| **cool-retro-term** | Qt | Qt/QML + qmltermwidget | 重 | 視覺效果優先 | 有限 | 非 CJK 專精 | [Arch cool-retro-term](https://archlinux.org/packages/extra/x86_64/cool-retro-term/) |

### Arch 官方庫安裝體積對照（**[官方]** 套件 metadata，非 RSS）

| 套件 | installed_size（約） | 備註 |
|------|---------------------|------|
| xterm | ~1.1 MB | 極小 |
| sakura | ~0.2 MB | 本體極小，執行依賴 GTK3+VTE |
| lxterminal | ~0.4 MB | 同上 |
| foot | ~0.8 MB | **Wayland-only** |
| xfce4-terminal | ~2.2 MB | VTE |
| rxvt-unicode | ~3.2 MB | |
| contour | ~5.7 MB | 另需 Qt6 |
| terminology | ~5.9 MB | 另需 efl |
| alacritty | ~8.7 MB | |
| gnome-terminal | ~8.9 MB | |
| konsole | ~10 MB | 另需大量 KF |
| ghostty | ~31 MB | + gtk4/libadwaita |
| kitty | ~69 MB | |
| wezterm | ~111 MB | 最大級原生終端之一 |

來源：Arch Linux 套件 JSON API（2026-07-23 查詢）。

---

## 3. 各候選詳述

### 3.1 xterm

- **X11 狀態**：**[官方]** 「terminal emulator for the X Window System」；由 Thomas Dickey 維護。  
  來源：<https://invisible-island.net/xterm/>  
- **資源/設計**：無現代「輕量行銷語」，但套件依賴僅 `libx11`/`libxft`/`libxaw` 等；Arch 安裝體積約 1.1 MB。**[推斷]** 無 GPU/Electron，空閒 RSS 通常屬最低檔之一（無上游官方 RSS 表）。  
- **特點**：VT 相容性標竿、可配置性極高、scrollback、clipboard（X selection）、Unicode/Xft。  
- **限制**：配置複雜（X resources）；預設 UX 偏古典；無內建 tabs/splits。  
- **打包**：Arch / Debian / Fedora 均有（Debian source `xterm`）。

### 3.2 rxvt-unicode (urxvt)

- **X11 狀態**：**[官方]** X11 終端；Xft/core fonts。  
  來源：<http://software.schmorp.de/pkg/rxvt-unicode.html>  
- **資源/設計**：**[官方]**  
  - Daemon 模式：「one daemon can open multiple windows… improves memory usage and startup time considerably」。  
  - FAQ「Isn't rxvt-unicode supposed to be small?」：最小建置時給出對照 RSS（urxvt 1788 vs rxvt 1824，單位與表格同文）：  
    ```
    text    data     bss     drs     rss filename
    98398    1664      24   15695    1824 rxvt --disable-everything
   188985    9048   66616   18222    1788 urxvt --disable-everything
    ```  
  - FAQ：scrollback 與每 cell 6–8 bytes 會主導記憶體；Xft「resource hog by design」。  
  來源：<http://cvs.schmorp.de/rxvt-unicode/doc/rxvt.7.pod>  
- **特點**：Unicode 存儲、多字型同時、Perl 擴展（tabbed、URL、搜尋）、re-wrap 長行、combining characters。  
- **限制**：官方自列缺 complex script / RTL（建議 **mlterm**）；現代化 truecolor/ligatures 體驗不如 GPU 終端。  
- **打包**：Arch `rxvt-unicode`；Debian `rxvt-unicode`。

### 3.3 st (suckless)

- **X11 狀態**：**[官方]** 「st is a simple terminal implementation for **X**.」  
  來源：<https://st.suckless.org/>  
- **資源/設計**：對比 xterm「65K+ LOC」與 rxvt「32K」，主張終端不該複雜；發佈 tarball ~48 kb（0.9.3）。**[推斷]** 執行期極輕。  
- **特點**：VT10X、UTF-8、wide char、256/true color、fontconfig 抗鋸齒與 fallback、clipboard。  
- **限制**：無官方 tabs/scrollback 無限等（goals：不重做 tmux）；配置需改 `config.h` 重編；Arch 官方庫未必有名為 `st` 的套件（Debian 為 `stterm`）。  
- **打包**：Debian `stterm`；Arch 常需 AUR/自編（**[官方套件]** 本次 exact name 查詢未命中）。

### 3.4 Alacritty

- **X11 狀態**：**[官方]** Linux 依賴含 `libxcb`；Cargo `default = ["wayland", "x11"]`，X11 透過 winit/glutin/GLX。  
  來源：README / INSTALL / `alacritty/Cargo.toml`  
- **資源/設計**：**[官方]** 「fast, cross-platform, OpenGL」；需至少 OpenGL ES 2.0；FAQ 稱不追求用故意降速來省資源，吞吐用 vtebench。無官方「idle RSS = N MB」。**[推斷]** 比 st/xterm 重（GPU 驅動/上下文），比 Electron/全 Qt 桌面終端輕。  
- **特點**：scrollback、vi mode、search、hints、多窗口同一實例（`CreateNewWindow`）；**無** tabs/splits（README 明確交給 WM/tmux）。  
- **限制**：GPU 必要；部分舊/無加速環境不適合。  
- **打包**：Arch `alacritty`；Debian 官方庫搜尋本次未見（**[仍未確認]** 是否進 unstable 以外渠道）；可 cargo/上游二進位。

### 3.5 Kitty

- **X11 狀態**：**[官方]** 設計與 FAQ 多次提及 X11 與 Wayland；Gentoo `x11-terms/kitty` 有 USE `X` 與 `wayland`；Arch 依賴同時含 `libx11` 與 `wayland`。  
  來源：<https://sw.kovidgoyal.net/kitty/> · Gentoo Wiki Kitty · Arch kitty JSON  
- **資源/設計**：**[官方]** GPU + SIMD；「does not depend on any large and complex UI toolkit, using only OpenGL for rendering everything」。FAQ 專節說明 `top` 不適於判斷記憶體、以及 GPU driver arenas。無單一官方 idle RSS。Arch 安裝體積 ~69 MB。  
- **特點**：tabs、多種 window layouts、graphics protocol、ligatures、擴展 kittens、truecolor、clipboard、scrollback。  
- **限制**：功能多 → 安裝體積與 GPU 依賴；多開 tab 後記憶體行為需理解 allocator/GPU 池化。  
- **打包**：Arch/Debian/Fedora 均常見。

### 3.6 WezTerm

- **X11 狀態**：**[官方]** `enable_wayland`：設 `false` 時「do not try to use a Wayland protocol connection… **and instead use X11**」。預設 `true`（新版本）。  
  來源：<https://wezterm.org/config/lua/config/enable_wayland.html>  
- **資源/設計**：**[官方]** 「GPU-accelerated… multiplexer」；Arch ~111 MB 安裝體積。**[推斷]** 功能完整度接近「一站式」，資源高於 Alacritty/st。  
- **特點**：tabs/panes、scrollback、image protocol、SSH domains、Lua 配置、字型塑形。  
- **限制**：體積大；Debian 官方 source 本次未見（Arch 有）。  
- **打包**：Arch `wezterm`；上游提供 Linux 包。

### 3.7 foot

- **X11 狀態**：**[官方]** README 標題與特性列表：「**The fast, lightweight and minimalistic Wayland terminal emulator**」「**Wayland native**」。依賴 `wayland`，無 X11 後端敘述。  
  來源：<https://codeberg.org/dnkl/foot/src/branch/master/README.md>  
- **結論**：對「適合 X11」**不適用**（除非 XWayland 誤用，非設計目標）。  
- **資源**：官方強調 lightweight；Arch 安裝體積極小——但省的是 Wayland 場景。

### 3.8 Contour

- **X11 狀態**：**[推斷/套件]** CMake 使用 Qt6；Arch 依賴 `qt6-base`… 與 `libxcb`、`wayland` → 透過 Qt 可在 X11 運行。上游 README 稱 Linux/macOS/Windows，未寫「X11-only」或「Wayland-only」。  
  來源：<https://github.com/contour-terminal/contour> · Arch contour  
- **資源**：GPU（OpenGL ≥ 3.3）；Qt6 棧 **[推斷]** 記憶體與依賴重於 Alacritty。  
- **特點**：tabs、ligatures、sixel、ReGIS、vi input modes、truecolor。  
- **打包**：Arch/Fedora/openSUSE；Debian 官方名本次搜尋未突出。

### 3.9 Ghostty

- **X11 狀態**：**[官方]** Linux GUI 為 **GTK4**；配置項 `x11-instance-name`：「controls the instance name field of the **WM_CLASS X11 property when running under X11**」。另有多處 X11/Wayland 分支說明。  
  來源：<https://ghostty.org/docs/about> · config reference  
- **資源**：OpenGL 渲染 + GTK4 + libadwaita；Arch ~31 MB + 重 GUI 依賴。**[推斷]** 日常 RSS 高於 st/urxvt/Alacritty 本體類。  
- **特點**：原生 tabs/splits、ligatures、Kitty graphics、主題、多平台「native」目標。  
- **打包**：Arch `ghostty`；Debian 官方本次未見。

### 3.10 mlterm

- **X11 狀態**：**[官方]** 「Mlterm is a **multilingual terminal emulator on X11**.」`configure --with-gui=xlib|…|wayland|…`。  
  來源：<https://mlterm.sourceforge.net/> · README  
- **資源**：無官方 RSS；**[推斷]** 相對 GPU/Qt 終端更輕，比 st 功能多故略重。  
- **特點**：多編碼/BiDi/Indic、多輸入法後端（ibus/fcitx/uim 等可配）、xft/cairo。urxvt 官方 FAQ 在 complex script/RTL 上**直接推薦 mlterm**。  
- **限制**：UX/社群熱度不如 Kitty 系；Arch 官方 exact `mlterm` 本次未命中（Debian 有 `mlterm`）。  
- **打包**：Debian `mlterm`；Fedora 等常見於多語環境。

### 3.11 xfce4-terminal / sakura / lxterminal（VTE 輕殼）

- **X11 狀態**：GTK 應用 → 在 Xorg 上原生運行（Wayland 亦可能）。  
- **技術棧**：**libvte** + GTK。  
- **資源**：**[推斷]** 本體小，但鏈入 VTE+GTK 後 RSS 高於 st/xterm；通常低於 Konsole/GNOME 全家桶整合與 Kitty/WezTerm 安裝體積。  
- **特點**：  
  - **xfce4-terminal**：**[官方]** 「lightweight and easy to use… tabs, unlimited scrolling, full colors, fonts…」  
    <https://docs.xfce.org/apps/terminal/start>  
  - **sakura**：**[官方]** 「simple gtk and vte… tabs… No more no less。」  
    <https://github.com/dabisu/sakura>  
  - **lxterminal**：LXDE 的 VTE 終端（Arch 描述）。  
- **打包**：三者 Arch/Debian 均有。

### 3.12 GNOME Terminal / Konsole

- **GNOME Terminal**：VTE 參考前端；與 GNOME 設定/a11y 整合。源：GNOME Wiki/Gitlab。  
- **Konsole**：KDE 終端；tabs、profiles、bookmarks 等。源：<https://apps.kde.org/konsole/>  
- **資源**：依賴鏈長（GSettings/GTK 或 KDE Frameworks）。適合已在對應桌面者；**非**「省資源首選」。  
- **打包**：各大發行版核心組件級可用。

### 3.13 terminology

- **X11 狀態**：EFL 應用；EFL 支援 X11 等。  
- **[官方]** 「EFL terminal emulator… inline images, video… **GPU Accelerated rendering (optional)**」。  
  來源：<https://github.com/borisfaure/terminology>  
- **資源**：依賴 `efl` 整棧；**[推斷]** 比 st/urxvt 重，多媒體功能進一步抬高。  
- **打包**：Arch/Debian `terminology`。

### 3.14 Hyper / cool-retro-term / lilyterm

- **Hyper**：**[官方]** 建基於 open web standards；`package.json` scripts 使用 **electron** / electron-builder，依賴 **xterm** JS 套件。  
  → **[推斷]** 記憶體與 Chromium 同級，不適合本調研目標。  
  <https://github.com/vercel/hyper>  
- **cool-retro-term**：Arch 依賴 Qt6 + qmltermwidget，安裝 ~32 MB；新奇效果向。  
- **lilyterm**：本次 Arch exact 無套件；**[仍未確認]** 上游活躍度——不納入推薦。

---

## 4. 資源佔用說明與證據分級

### 4.1 為何有的終端「更重」

| 因素 | 說明 | 證據類型 |
|------|------|----------|
| **Electron / Chromium** | 內嵌完整瀏覽器 runtime | Hyper 使用 electron：**[官方]** package.json |
| **Qt6 / GTK4+Adwaita / KDE Frameworks** | GUI 工具包本身數十 MB 級庫與主題 | Contour/Ghostty/Konsole 套件 depends：**[官方]** Arch |
| **GPU 終端** | 驅動、紋理緩存、swap chain；空閒也可能佔 VRAM/RSS | Alacritty/Kitty/WezTerm/Ghostty 自述 OpenGL/Metal：**[官方]**；Kitty FAQ 談 GPU arenas：**[官方]** |
| **VTE** | 共享庫，一進程載入 GTK+VTE | sakura 等 depends vte3：**[官方]** |
| **Scrollback / Unicode cell** | 每 cell 數位元組 × 行列 × 歷史 | urxvt FAQ 6–8 bytes/cell：**[官方]** |
| **功能集成** | 內建 mux、圖像協議、Lua/Python 運行時 | Kitty/WezTerm 設計文檔：**[官方]** |

### 4.2 可核驗的一手「量」

1. **urxvt FAQ 最小建置 RSS 表**（見 §3.2）— **[官方]**  
2. **Arch `installed_size`**（§2 表）— **[官方套件]**，衡量**磁碟與打包肥瘦**，非 idle RSS  
3. **上游自述定位**（lightweight / GPU / Wayland native / multilingual）— **[官方]**  

### 4.3 刻意不寫的內容

- 任何未附來源的「Alacritty 只用 20MB」類數字  
- 清單部落格的橫評截圖  

若需本機 idle RSS，建議在同一 Xorg session 用 `ps`/`smem` 自測，並註明 GPU 驅動與字型集。

---

## 5. 場景建議

### 5.1 極低記憶體 / 老機器 / 多開大量終端

1. **urxvtd + urxvtc**（共享 daemon）  
2. **st** 或 **xterm**（每窗口獨立但本體極小）  
3. 避免：Hyper、WezTerm/Kitty 重功能默認、未共享的完整 GNOME/KDE 會話僅為開終端  

### 5.2 日常開發（真彩色、可選 ligatures、合理資源）

1. **Alacritty + tmux/zellij**（X11 一等、無內建分屏哲學一致）  
2. **xfce4-terminal / sakura**（要 GUI 標籤、接受 VTE）  
3. 已有 NVIDIA/AMD 良好驅動時 **Kitty** 亦常用  

### 5.3 需要分屏 / 標籤 / 圖像協議

| 需求 | 傾向 |
|------|------|
| 標籤+分屏一體 | Kitty、WezTerm、Ghostty、Contour、Konsole |
| 僅標籤 | VTE 系、urxvt tabbed |
| 圖像（Kitty protocol / sixel） | Kitty、WezTerm、Ghostty、Contour、foot(sixel, Wayland)、terminology |

X11 約束下優先：**Kitty** 或 **WezTerm**（`enable_wayland=false` 可強制 X11）。

### 5.4 中日韓文字需求

| 優先 | 方案 | 依據 |
|------|------|------|
| 1 | **mlterm** | 上游定位 multilingual on X11；urxvt 官方複雜文稿也指向它 |
| 2 | **urxvt** 多字型（日文+拉丁分離） | 官方 blurb：multiple fonts at the same time |
| 3 | 現代終端 + **fontconfig** 回落（Noto CJK 等） | Alacritty/Kitty/WezTerm/Ghostty/VTE 皆走 fontconfig/harfbuzz 類路徑 |
| 注意 | 東亞 ambiguous width | WezTerm 有 `treat_east_asian_ambiguous_width_as_wide` 等；其他需 term 與 locale 一致 |

輸入法：GTK/Qt 終端通常吃 fcitx5/ibus；mlterm 另有多種 IM 編譯選項；st/urxvt 依賴 XIM 或外部 IM 框架（需實測）。

---

## 6. 開放問題 / 未核實

| 項目 | 狀態 |
|------|------|
| 各終端在同一機器上的 **idle RSS / 多開 RSS** | 無統一上游基準；需本機實測 |
| **lilyterm** 維護狀態與 X11 | Arch 無官方包；未深挖上游 |
| **Alacritty / WezTerm / Ghostty / Contour** 進 Debian stable 的完整情況 | 本次 sources.debian.org：alacritty/wezterm/ghostty 未見；contour 搜尋無明顯包 |
| Contour 在 pure X11 無 Wayland 庫時的建置裁剪 | Arch 依賴同時拉 wayland；純 X 最小依賴集 **[仍未確認]** |
| Ghostty 在無 GTK Wayland 時僅 X11 的官方支援矩陣細節 | 已確認有 X11 WM_CLASS 選項；完整測試矩陣未讀 |
| Fedora 精確包名 | 簡易 src.fedoraproject 探測：`kitty`/`foot`/`xterm`/`rxvt-unicode` 200；`alacritty`/`wezterm`/`ghostty` 路徑 404（可能不同命名或 COPR） |
| cool-retro-term 僅作新奇用途 | 已排除日常推薦 |
| xterm 官網完整 HTML | 部分環境 curl 結果異常；以 Arch 依賴與歷史定位 + invisible-island URL 為準 |

---

## 7. 來源列表

### 上游專案

| 專案 | URL |
|------|-----|
| xterm | https://invisible-island.net/xterm/ |
| rxvt-unicode | http://software.schmorp.de/pkg/rxvt-unicode.html |
| rxvt-unicode FAQ (pod) | http://cvs.schmorp.de/rxvt-unicode/doc/rxvt.7.pod · http://pod.tst.eu/http://cvs.schmorp.de/rxvt-unicode/doc/rxvt.7.pod |
| st | https://st.suckless.org/ · https://st.suckless.org/goals/ |
| Alacritty | https://github.com/alacritty/alacritty |
| Alacritty 宣佈文（GPU） | https://jwilm.io/blog/announcing-alacritty/ |
| Kitty | https://sw.kovidgoyal.net/kitty/ · https://sw.kovidgoyal.net/kitty/overview/ · FAQ |
| WezTerm | https://wezterm.org/ · https://wezterm.org/config/lua/config/enable_wayland.html |
| foot | https://codeberg.org/dnkl/foot |
| Contour | https://github.com/contour-terminal/contour · https://contour-terminal.org/ |
| Ghostty | https://ghostty.org/docs/about · https://ghostty.org/docs/config/reference · https://github.com/ghostty-org/ghostty |
| mlterm | https://mlterm.sourceforge.net/ · https://github.com/arakiken/mlterm |
| sakura | https://github.com/dabisu/sakura |
| xfce4-terminal | https://docs.xfce.org/apps/terminal/start |
| GNOME Terminal / VTE | https://wiki.gnome.org/Apps/Terminal · https://gitlab.gnome.org/GNOME/vte |
| Konsole | https://apps.kde.org/konsole/ |
| terminology | https://github.com/borisfaure/terminology · https://git.enlightenment.org/enlightenment/terminology |
| Hyper | https://github.com/vercel/hyper · https://hyper.is |

### 發行版 / 套件中繼資料

| 來源 | URL |
|------|-----|
| Arch packages（多包 JSON） | https://archlinux.org/packages/… |
| Debian sources API / packages | https://sources.debian.org/ · https://packages.debian.org/ |
| Gentoo Kitty | https://wiki.gentoo.org/wiki/Kitty |

### 方法論備註

- ArchWiki 等對 urxvt 的說明可用於操作，但**特性與 X11 結論以 schmorp.de 為準**。  
- 任何第三方「終端記憶體橫評」未採納為數字來源。  

---

*本檔為一手源優先的比較調研，供 X11 環境下選擇「資源佔用合理」的終端模擬器；實機 RSS 請自行覆核。*
