# Sleepy v1.0.61

> Reminders for every timetable, nine schedule fixes, and school #349.
>
> Birthday edition — shipped on my birthday. From here on, expect roughly one release a week.

## What's New

### Per-timetable reminder switch

Before-class, daily, and tomorrow reminders used to fire only for the currently selected timetable. Background timetables stayed silent, and there was no way to mute one table while keeping another.

Each timetable now carries its own reminder switch (default on). Edit a timetable, flip the switch, done. Background timetables remind on their own; when several tables have classes at once, each summary carries a "timetable name ·" prefix so you can tell them apart.

**Evidence:** PR #143 (merged 2026-10-09). Database upgraded v10 → v11 (`reminderEnabled` column, old rows default on — upgrade changes nothing).

### Hubei Business University (湖北商贸学院) supported — school #349

jwxt.hbc.edu.cn, new Zhengfang (zftal-ui-v5) at the bare `/kbcx/` path. A user-supplied capture with 18 courses parses 1:1 with no parser changes: single-week "11-17周(单)" kept, 军训-style practice sessions (sjkList) correctly excluded. Campus cities registered: Wuhan + Xianning.

**Evidence:** PRs #155/#156/#157 (2026-10-10), cross-verified against qiqqqqq517/shangkeschedule (independent listing of the same school).

### Fluid cloud test button: split state + stop

"流体云测试" was one button with no way out once tapped — you waited out the 2-minute window or killed the app. It now flips to "casting | stop casting" while active; tapping the right half stops the service immediately and the notification disappears.

**Evidence:** PR #116 (merged 2026-10-09).

### Sponsor page

The repo now has a sponsor page (Alipay / WeChat QR codes), linked from both READMEs. If Sleepy saves you a skip-card trip, it's there.

**Evidence:** PR #140 (merged 2026-10-08).

## Fixes

- **Edit screen no longer jumps to the wrong slot:** tapping a course capsule opens the edit screen scrolled to that time slot. Three defects fixed: the scroll index ignored four header items above the list, courses with an empty group ID never matched, and Monday/Thursday slots with the same periods merged into one group.

  **Evidence:** PR #144 (2026-10-09), on top of PR #136. Verified on emulator: 42 real courses × week/grid views, 84 taps, 0 mismatches.

- **Overlapping courses rendered 2 periods too low:** in the grid view, a 1-2 period course stacked with a 2-4 course drew at 3-4 and 4-6. A swapped argument pair in the cluster offset calculation; fixed, and the same math now backs lane offsets too.

  **Evidence:** PR #147, issue #146.

- **调休 no longer doubles the column:** moving Saturday's classes onto Wednesday used to keep Wednesday's own classes too, stacking both. The target column's original courses are now suppressed.

  **Evidence:** PR #148, issue #145.

- **Custom make-up days now un-grey:** the "ignore workday" toggle only exempted statutory make-up days returned by the holiday API. User-defined transfer dates now count.

  **Evidence:** PR #139 (2026-10-08).

- **SWJTU import recovered:** the YETHAN platform stores its login token in a cookie, not localStorage — the old script gave up silently. Token is now read cookie-first, and expired-session codes get a clear "login expired" message instead of "page may have changed". The platform stopped serving lesson times in 2025-09, so the official 13-period SWJTU schedule (published 2025-05-21) is bundled as fallback, with a snackbar note.

  **Evidence:** PR #134 (2026-10-08), contributor @YYiChen.

- **Irregular-time toggle turns off again:** the switch could only be flipped on; leaving required exiting the screen. Extra irregular courses also drew on top of regular ones — same-day conflicts now split into side-by-side lanes.

  **Evidence:** PR #142 (2026-10-09).

- **Period tables export without course tables:** with zero course timetables but a period timetable present, the export page claimed "nothing to export". The empty state now requires both to be absent.

  **Evidence:** PR #152 (2026-10-10).

- **Fluid cloud test: permission gate + OPPO entry + state truth:** casting used to start with no permission check (invisible if denied), OPPO's "go to settings" landed on a page without the fluid-cloud switch, the button stayed stuck on "casting" after the window expired, and the first tap could fail on ColorOS. Now: notification permission prompt, a three-check gate (notification / promoted-updates / vendor capability) with per-item explanations, OPPO/OnePlus/RealMe on API 36+ jump to the official Live Updates settings page, and casting state is reconciled against the service on resume.

  **Evidence:** PR #151 (2026-10-10).

- **Database upgrade guardrails:** Room schema export is on with migration contract tests (v3 → v11 chain), so future upgrades can't silently drop or rename columns. Internal, but it protects your data.

  **Evidence:** PR #115 (2026-10-09), contributor @Kerry1020.

## Known Limitations

- **SWJTU period times are a snapshot.** The bundled 13-period schedule follows the official 2025-05-21 notice; if the school shifts class times again, an app update is needed.
- **Fluid cloud remains vendor-dependent.** ColorOS 16 live updates require the vendor service, notification permission, and device support; an AOSP emulator cannot verify rendering.
- **HBSM support is verified against a user capture.** Live import depends on the school's service being reachable.

## Verification

- Range audited: `v1.0.60..HEAD`; 28 commits.
- Unit tests: `:app:testDebugUnitTest` — 2,645 tests, 0 failures, 0 errors (294 suites).
- Lint: `:app:lintDebug` — 0 errors, 522 warnings (baseline).
- Build: `:app:assembleRelease` succeeded. `versionName` 1.0.61, `versionCode` 10061, all four variants.
- APK SHA-256 (local release build, JDK 17 / Gradle 9.3.1; CI rebuilds and publishes tagged assets with `sha256sums.txt`):
  - `app-arm64-v8a-release.apk`: `0c4d7c3666eca7a4a30956ee38af3533bec0a4b5ccae8f09d1379f393be9d5f0` (4,658,551 bytes)
  - `app-armeabi-v7a-release.apk`: `399632c18b65b3b1a3eb2f8b72982e190c686e9c0536687904c5b522806f2737` (4,655,859 bytes)
  - `app-x86_64-release.apk`: `37b9dd598a4ee38f73e24351d6adb8849298a4835450aff8056e723a4840d435` (4,657,657 bytes)
  - `app-universal-release.apk`: `58e9b68d9e53bf9bd021f083551459fbb385572ab69797ac739f4848bded07b3` (4,756,487 bytes)
  - Signing certificate SHA-256 `5D:97:52:2B:A8:FA:B0:C1:4A:BC:D8:14:FE:92:BB:0D:EF:19:91:49:AB:D0:DF:72:40:1A:52:80:87:B7:0F:09` — same identity as every prior release; CI asserts this fingerprint against the secret keystore before publishing.

---

# Sleepy v1.0.61

> 每张课表独立提醒、九项课表修复、第 349 所学校。
>
> 生日特别版——发布于我的生日。往后基本一周一更。

## 新增功能

### 每张课表独立提醒开关

课前、每日、明日提醒以前只对当前选中的课表生效。后台课表一声不响，也没法单独关掉某张表。

现在每张课表自带提醒开关（默认开启）。编辑课表，拨一下开关就行。后台课表照常提醒；多张表同时有课时，每条通知带「课表名 ·」前缀，分得清是哪张表。

**依据：** PR #143（2026-10-09 合并）。数据库 v10 → v11（新增 `reminderEnabled` 列，旧行默认开启——升级后行为不变）。

### 湖北商贸学院支持 — 第 349 所学校

jwxt.hbc.edu.cn，新正方（zftal-ui-v5）裸 `/kbcx/` 路径。用户采集包 18 门课 1:1 全量解析，parser 零改动：单周「11-17周(单)」保留，军训类实践环节（sjkList）正确不入课表。校区城市登记：武汉 + 咸宁。

**依据：** PR #155/#156/#157（2026-10-10），跨仓互证 qiqqqqq517/shangkeschedule（独立收录同校）。

### 流体云测试按钮：分裂态 + 停止入口

「流体云测试」原来是单按钮，点了就没有退路——要么等满 2 分钟，要么杀 App。现在投放中变成「投放中 | 结束投放」两半，点右半立即停服务，通知同步消失。

**依据：** PR #116（2026-10-09 合并）。

### 赞助页

仓库新增赞助页（支付宝 / 微信二维码），两个 README 都加了入口。Sleepy 帮你少跑一趟教务处的话，可以看看。

**依据：** PR #140（2026-10-08 合并）。

## 修复

- **编辑页不再跳错位置：** 点课程胶囊打开编辑页，直接滚到对应时段卡。修了三处缺陷：滚动下标没算列表上方的 4 个固定项、空 groupId 的课程永远匹配不上、周一和周四同节次的时段被并进同一组。

  **依据：** PR #144（2026-10-09），在 PR #136 之上。模拟器验证：42 门真实课 × 周视图/网格 = 84 次点测，0 错位。

- **重叠课整体下移两节：** 网格视图里 1-2 节的课和 2-4 节的课叠放时，画出来落在 3-4 和 4-6。簇偏移计算里一对参数写反了；修正后同一套算法同时供车道偏移复用。

  **依据：** PR #147，issue #146。

- **调休不再叠加整列：** 把周六的课调到周三，原来周三自己的课还在，两拨叠在一起显示。现在目标列的原课会被屏蔽。

  **依据：** PR #148，issue #145。

- **自定义补班日不再灰显：** 「忽略补班日」开关以前只对假日 API 返回的法定补班日生效，用户自定义的调休日期不算数。现在算。

  **依据：** PR #139（2026-10-08）。

- **西南交大导入恢复：** YETHAN 平台的登录 token 存在 cookie 里而不是 localStorage，旧脚本静默放弃。现在 cookie 优先读取；登录过期给出明确的「登录态已过期」，不再误报「页面可能改版」。该平台 2025-09 起不再下发节次时间，内置西南交大官方 13 节作息（2025-05-21 发布）兜底，并有 snackbar 提示。

  **依据：** PR #134（2026-10-08），贡献者 @YYiChen。

- **非常规时间开关能关了：** 开关以前只能开、关不上，退出重进才行。非常规时间多出的课还盖在常规课上——现在同天冲突改为并排分道显示。

  **依据：** PR #142（2026-10-09）。

- **没有课表也能导出作息表：** 一张课表都没有、但作息表存在时，导出页以前显示「暂无可导出」。现在空态只在两者都缺时出现。

  **依据：** PR #152（2026-10-10）。

- **流体云测试：权限闸门 + OPPO 入口 + 状态对账：** 投放以前不做任何权限检查（被拒了也投、看不见）；OPPO 的「去设置」跳到没有流体云开关的页面；窗口到期后按钮卡在「投放中」；ColorOS 上第一次点击必败。现在：通知权限先弹授权框；三查闸门（通知 / 实时更新 / 厂商能力）逐条列出缺什么；OPPO/一加/真我 API 36+ 直接跳官方 Live Updates 设置页；回前台时投放状态与服务对账。

  **依据：** PR #151（2026-10-10）。

- **数据库升级护栏：** Room schema 导出开启并配迁移契约测试（v3 → v11 全链），以后的升级没法悄悄丢列改名。内部改动，保的是你的数据。

  **依据：** PR #115（2026-10-09），贡献者 @Kerry1020。

## 已知限制

- **西南交大节次时间是快照。** 内置 13 节作息以 2025-05-21 官方通知为准；学校再调整时间需要等 App 更新。
- **流体云依赖厂商。** ColorOS 16 Live Updates 需要厂商服务、通知权限和设备支持；AOSP 模拟器无法验证渲染。
- **湖北商贸学院基于用户采集包验证。** 在线导入取决于校方服务可达性。

## 验证

- 审计范围：`v1.0.60..HEAD`；28 个 commit。
- 单元测试：`:app:testDebugUnitTest` — 2,645 条，0 失败，0 错误（294 套件）。
- Lint：`:app:lintDebug` — 0 错误，522 警告（基线）。
- 构建：`:app:assembleRelease` 成功。`versionName` 1.0.61，`versionCode` 10061，四个变体一致。
- APK SHA-256（本地 release 构建，JDK 17 / Gradle 9.3.1；CI 会在发布时重建并附 `sha256sums.txt`）：
  - `app-arm64-v8a-release.apk`: `0c4d7c3666eca7a4a30956ee38af3533bec0a4b5ccae8f09d1379f393be9d5f0`（4,658,551 字节）
  - `app-armeabi-v7a-release.apk`: `399632c18b65b3b1a3eb2f8b72982e190c686e9c0536687904c5b522806f2737`（4,655,859 字节）
  - `app-x86_64-release.apk`: `37b9dd598a4ee38f73e24351d6adb8849298a4835450aff8056e723a4840d435`（4,657,657 字节）
  - `app-universal-release.apk`: `58e9b68d9e53bf9bd021f083551459fbb385572ab69797ac739f4848bded07b3`（4,756,487 字节）
  - 签名证书 SHA-256 `5D:97:52:2B:A8:FA:B0:C1:4A:BC:D8:14:FE:92:BB:0D:EF:19:91:49:AB:D0:DF:72:40:1A:52:80:87:B7:0F:09` — 与此前所有版本同一身份；CI 发布前会对 secret keystore 断言此指纹。
