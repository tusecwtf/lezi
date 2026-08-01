# APK smoke (ticket 07)

- device: emulator-5554 (Android SDK built for x86_64)
- apk: dist/lezi-0.3.0-debug.apk (versionCode=6, versionName=0.3.0)
- steps:
  1. trust HTTPS endpoint (TOFU/SPKI): **PASS** (real APK session — required)
     - entered https://192.168.50.4:8765
     - confirmed fingerprint BB:A1:05:DE:…:9E:45 matching deploy init-tls SPKI
     - tapped 信任此证书
  2. owner login with migration new root password: **PASS** (real APK session — required)
     - 加入家庭 → 我是家庭管理员 → entered migration root password → 登录这台设备
     - landed on 时间轴 with family data
  3. historical authoritative records visible (media sample if any): **PASS**
     - timeline UI: 年年, historical 配方奶/睡眠/尿尿 etc., 护理计划 (apk-timeline-texts.txt)
     - media: API GET /v1/media/<uuid> 200 ~51 KiB (UI media preview not separately captured;
       acceptance treats API or UI media sample as sufficient when UI hierarchy proves records)
  4. new record syncs to family: **PASS**
     - API bundle stage+commit of formula “ticket07 cutover smoke write” (allowed substitute when
       UI create is blocked; timeline UI shows 1 条记录 / 配方奶 for today after sync)
- evidence: apk-timeline-uihierarchy.xml / apk-timeline-texts.txt (screencap black on this host)

Notes on acceptance mapping:
- Steps 1–2 must come from a real APK session (not Owner-API alone).
- Steps 3–4 may use API as additional/substitute evidence for history/media/write when UI is limited.
