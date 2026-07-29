# Ticket 01 device smoke

- Checkout HEAD: `86539bb3572984128b58f161093d95da7d27f31d`
- Device: `emulator-5554`, AVD `lezi_api35`
- Screen: physical `1080×2400` at `420 dpi`; logical configuration approximately `411×914 dp`
- Installed smoke APK SHA-256: `0acb9bf890880ca36eecefbd81e5384ddd9f90890e7bef568a03ba51e984cff9`
- Final assembled Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Final SHA-256: `06b60bdd91cbdac23d4fefc51bf8376ac4082c2082e6350b787a21b936a3a804`
- The final rebuild only centralized the remaining four-column/four-slot numeric references; it did not change rendered geometry or interaction behavior from the installed smoke build.

## Theme matrix

- [warm light](./warm-light.png)
- [warm dark](./warm-dark.png)
- [journal light](./journal-light.png)
- [journal dark](./journal-dark.png)
- [journal light at 1.3× font scale](./journal-light-font-130.png)

Each theme smoke shows the editor-only title/action, first category, four-column catalog, local-deleted section, four quick slots, and locked More. Date chrome, primary navigation labels, and “常用补充” are absent.

## Interaction checks

- Everyday and editor quick Dock bounds were both `y=1872..2040`; removing the primary tabs did not move the Dock.
- A short tap on the first catalog card left `编辑布局 / 完成 / 喂养 / 本机已删除` in the hierarchy; no Composer save/cancel surface appeared.
- System Back returned to the normal record page with `今天`, `7月30日 · 周四`, `记录`, `菜单`, and `还没有记录` intact.
- At system font scale `1.3`, the editor title, Done action, category titles, and long labels such as `母乳瓶喂` remained legible without text clipping.
