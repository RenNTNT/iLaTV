# iLaTV 电视版 · Android 工程

基于 `RenNTNT/LibreTV-TV` 改造的电视端影音应用（点播 + IPTV 直播 + 手机网页后台）。

| 项 | 值 |
|---|---|
| 包名 | `com.ilatv.tv` |
| versionCode / versionName | `76` / `2.0`（tv）· `21` / `2.0`（phone） |
| minSdk / targetSdk / compileSdk | 21 / 34 / 35（Android 5.0+） |
| 后台端口 | `9600`，手机访问 `http://电视IP:9600/m/` |
| 语言 | 纯 Java，无 layout XML，界面全部动态构建 |

## 一、编译

环境要求：

- **JDK 17**
- **Gradle 8.9**（工程**没有** gradle wrapper，用本机 Gradle 或 Android Studio 打开根目录）
- Android SDK：`compileSdk 35` + Build Tools 34；命令行编译请设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`

```bash
# 命令行
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`settings.gradle` 里依赖走腾讯云 Maven 镜像；如需改回官方源，把两处 `maven { url ... }` 换成 `google()` / `mavenCentral()` 即可。首次编译会额外下载 `jcifs-ng`、`slf4j-nop` 与 `bcprov-jdk18on`（SMB 协议栈），需要联网。

SMB 协议栈（`jcifs-ng` + BouncyCastle）让 APK 从 1.3 MB 涨到约 1.7 MB：
- `classes.dex` 里 R8 只保留了 SMB1/2/3 协商与 NTLM 认证实际可达的类；
- `bcprov-jdk18on` jar 内 1.2 MB 的后量子密码（Picnic/LowMC）常量表是 Java 资源、
  R8 裁不到，已由 `app/build.gradle` 的 `packagingOptions.resources.excludes` 排除；
- 其余排除项：X.509 证书校验消息表、Kotlin metadata（本工程纯 Java，无 Kotlin 类）。

`local.properties` 未随工程分发（内含本机 SDK 绝对路径）：Android Studio 会自动生成，命令行用环境变量指定 SDK。

## 二、签名（重要）

`app/debug.keystore` 是随工程提供的 Android 标准调试密钥：

- store / key 密码：`android`，alias：`androiddebugkey`
- SHA-256：`80d53dd973e325c123912aa79c41609a084c615466d6342b4b29005c833cf30f`

`app/build.gradle` 已显式把 `signingConfigs.debug` 指向该文件，因此**用本工程编出的 release 包与已发布的 iLaTV APK 签名一致，可直接覆盖安装、订阅收藏不丢**。

⚠️ 这是调试密钥，仅用于侧载分发；正式上架请换成自己的发布密钥，并注意换签名会导致无法覆盖安装。

## 三、目录结构

```
app/src/main/
├─ java/com/ilatv/tv/
│  ├─ MainActivity.java        WebView 容器、启动页分发、JS 桥接
│  ├─ TvServer.java            内置 HTTP 服务：/api/* 后台接口、/api/smb/*、/api/live-search、/api/push-apk、/smb 流代理、m.html、/api/backup
│  ├─ SmbStore.java            SMB 网盘配置、目录浏览、文件流打开（jcifs-ng）
│  ├─ Store.java               全部本地配置（SharedPreferences）、内置源、备份迁移
│  ├─ Api.java                 采集源请求、m3u / txt 直播列表解析
│  ├─ Model.java               数据模型（频道 / 历史 / 订阅 / 跳过片头片尾等）
│  ├─ PlayerActivity.java      点播播放器（ExoPlayer 2.19.1）
│  ├─ PlayerCore.java          直播内核（Media3 1.2.1）
│  ├─ LivePlayerActivity.java  IPTV 直播页：选台、切线路、解码、画面比例
│  ├─ LivePicker.java          选台抽屉、功能菜单、自绘选择对话框
│  ├─ LiveSubsDialog.java      订阅管理弹框（点播/直播共用）
│  ├─ LiveActivity.java        全屏选台页
│  ├─ LiveLoader.java          直播列表加载与缓存策略
│  ├─ LiveSearch.java          直播频道搜索（纯 Java，频道名 + 分组名 AND 匹配、同名线路合并）
│  ├─ ApkProvider.java         APK 升级包 content:// 只读 provider（系统安装器用）
│  ├─ ApkInstaller.java        调起系统安装界面 + 安装完销毁安装包（含 PackageInstaller 回退）
│  ├─ InstallEventReceiver.java 接收 MY_PACKAGE_REPLACED / 安装状态，清理升级包
│  ├─ LauncherMode.java         动态启停 HOME activity-alias、调起系统默认桌面选择
│  ├─ BootReceiver.java         开机自启动（BOOT_COMPLETED / QUICKBOOT_POWERON）
│  ├─ BootLaunchService.java    Android 10+ 普通应用模式自启的前台服务过渡（一闪而过，不常驻）
│  └─ BuildConfig（按 flavor 生成）  IS_PHONE / BASE_PORT / EXPOSE_LAN / APPLICATION_ID
│  app/src/phone/                 手机版专属：res/values/strings.xml（桌面名 iLaTV）、
│                                AndroidManifest.xml（首页竖屏）、
│  app/src/phone/assets/m.html   手机版专属本机设置页（无 TV 推送、可返回、保存并同步）
│  app/src/tv/assets/admin.html   电视版专属浅色列表风格后台页（v61）
│  ├─ Ui.java                  动态 UI 工具、画面比例「自适应」判定
│  └─ WebAdmin.java            后台变更通知
├─ java/fi/iki/elonen/         NanoHTTPD（精简内嵌 HTTP 服务实现）
├─ assets/
│  ├─ index.html               电视端首页 / 分类 / 搜索 / 详情 / 网页直播面板
│  ├─ m.html                   手机网页后台（播放设置、订阅、备份导入导出）
│  ├─ hls.min.js / flv.min.js  网页播放内核
├─ res/                        iLaTV 图标（5 档 launcher + 3 档 banner）
└─ AndroidManifest.xml
app/proguard-rules.pro          R8 规则：保留 WebView @JavascriptInterface 桥接 + Manifest 组件
```

## 四、当前功能要点

- **启动页**：首页 / 影视分类 / IPTV 直播 三档，电视端「功能菜单 → 启动设置」与网页后台共用同一份配置；菜单项直接标注当前值（与「换台反转」一致）
- **影视分类页**：顶部点播搜索 + 逐层方向键导航（搜索框 → 筛选排 → 海报行）；**默认焦点在「热门」筛选按钮**，进入页面 / 从结果页返回都不会弹软键盘，主动按 ↑ 两层回到搜索框才弹
- **解码方式**：点播 / 直播**分开记忆**，各三档（自动选择 / 内置 / 系统解码），电视端与后台双向实时同步
- **画面比例**：点播 / 直播分开记忆，三档 `自适应`（片源比例接近屏幕则铺满，否则完整显示留黑边）/ `原始比例`（**默认**，恒按片源比例完整显示）/ `全屏显示`；旧版默认「自适应」在首次启动时自动迁移为「原始比例」，用户之后主动选自适应仍可保存
- **直播交互**：↑↓ 换台、←→ 切线路、OK 播放、数字键直选、抽屉内右键收藏/取消、一级目录序号已去、宽度按文字自适应
- **换台反转（v58 收窄作用范围）**：直播功能菜单「换台反转」与网页后台共用一份设置，开启后**仅全屏播放（选台界面未显示）时** ↑↓ / 频道± / 媒体前后键的换台方向整体对调；**选台抽屉 / 全屏选台页里的 ↑↓←→ 一律按原始方向，不反转**（`channelStep` 只用于 `switchChannel` 分支，`moveFocus` 全部传常量 ±1）。←→ 切线路、数字直选、OK 播放不受影响。播放页每次按键直接读 Store，任一边改完立即生效
- **SMB 网盘**：首页「SMB 网盘」入口，可添加多个 SMB/Windows/NAS 共享，浏览目录、播放视频；电视表单与手机后台「SMB 网盘」共用 `config.json` 里的 `smbShares`。视频经内置服务 `/smb` 代理成本机 HTTP 流（支持 Range/206，可拖动进度），同目录视频自动组成选集。密码只落本机、API 不回显，编辑留空即不修改
- **SMB 搜索 + 点播播放器**：首页/影视分类搜索时并行请求 `GET /api/smb/search?wd=`，实时递归遍历所有网盘按文件名匹配（多关键词 AND、大小写不敏感、片名做文件夹名时该目录视频全部命中，最多 6 层 / 300 目录 / 80 结果 / 9 秒保护）。命中项与浏览页点视频**统一走点播链路** `play() → startExo() → window.exo.play`，因此拥有完整点播播放器界面：选集、播放菜单（画面比例 / 解码方式）、观看记录与断点续播。观看记录会保存网盘 id、文件路径与所在目录，从电视「观看记录」或手机后台点开都能重建选集续播
- **TV 推送（手机后台）**：原「搜片推送」改名「TV 推送」，下设三个二级页
  - **影视推送**：原在线搜片推送（`/api/search` → `/api/push` action `play`）
  - **直播推送**：`GET /api/live-search?wd=&force=` 搜索电视端直播频道（优先 `Store.liveCache`，为空/force 时并发重拉已启用订阅，再回退内置 m3u；按频道名 + 分组名 AND 匹配、同名合并线路、最多 60 条）。点推送 → `/api/push` action `live` → 首页 `liveBridge.push(name, urls)` → `LivePlayerActivity` 带 `pushName/pushUrls` 立即起播，后台再补齐完整频道列表
  - **APK 推送**：选择本地 `.apk` 以二进制 `POST /api/push-apk`（`X-File-Name` 头），服务端校验 ZIP 内含 `AndroidManifest.xml` 后存到 `cache/push/update.apk`，`MainActivity.offerApk()` 立即调起系统安装界面（WebView 轮询兜底、按 token 去重）。安装完成 / 取消 / 下次启动都会删除安装包（`ApkInstaller` + `InstallEventReceiver` 的 `MY_PACKAGE_REPLACED`）。升级包必须与当前 App 同一签名
- **网页后台**：播放设置（双解码 + 双画面比例 + 启动页 + 换台反转）、TV 推送、点播源、直播订阅、SMB 网盘、观看记录、设备解码能力、数据备份与导入
- **数据备份**：`GET/POST /api/backup`，导出 SharedPreferences 全表 + `config.json`（含 SMB 网盘），用于换包名迁移
- **内置直播源（tv + phone 共用 main 代码）**：`https://sub.ottiptv.cc/huyayqk.m3u`；历版内置地址（`zbds.top/iptv4.txt`、`peterHchina/IPTV-V4.m3u`、`Kimentanm/iptv.m3u`）在启动 / 恢复内置源 / 备份导入时统一由 `Store.isLegacyLiveUrl()` / `legacyLiveUrls()` 自动升级，用户自填地址一律不动
- **直播弹窗滚动修复 + 两项改名 + 去掉双端同步提示 + 手机设置页删「观看记录」（tv v76 / phone v21，两版都改）**
  - **起因**：用户截图直播功能菜单里的「选台框大小」档位框，8 个档位只露到一半、往下划不动；要求「直播页其他菜单也看看有没有这种问题，一块修复」，把「选台框大小」改名**边框大小**、「节目单字体」改名**字体大小**，「改完后不要提示双端同步」，双端都改；随后又要求把手机端设置页「观看记录」类目删除
  - **滚动根因**：`LivePicker` 的功能菜单用 `AlertDialog.setItems`、档位/换台反转/启动设置/画面比例/解码方式用 `setView(LinearLayout)` 自绘条目。部分 ROM / 横屏下弹框高度被限制后**直接裁掉多余内容且不提供滚动**，所以 10 项菜单只露到「订阅管理」、8 档只露到 125%
  - **统一修复**：新增 `dialogListHeight()`（屏幕高 55%，180dp~400dp 自适应）与 `dialogScroll()`（ScrollView 包可聚焦列，不抢焦点、带滚动条、方向键移焦点自动滚到可见区）；功能菜单由 `setItems` 改为自绘 `menuItem()` + 10 个 `Runnable`，档位框 `showChoiceDialog()` 列表进 ScrollView，分组管理由固定 320dp 改为同一套自适应高度。**排查过没这问题的**：订阅管理（ListView 自带滚动）、搜索/加频道/编辑订阅（短表单）、节目单与频道信息（系统 message 自带滚动）、全屏选台页顶部工具条（横向滚动）
  - **改名（三处文案统一，内部 id / 配置键一律不动）**：直播功能菜单「边框大小（当前 N%）」「字体大小（当前 N%）」、档位框标题、电视版网页后台与手机版设置页的滑块标签与卡片标题（「边框与字体大小」）。两版 HTML 与 Java 注释里的旧名也一并清理
  - **不再提示双端同步**：菜单改完的 Toast 去掉「（两端同步）」；`LivePlayerActivity` 的「选台框 / 节目单字体（已同步）：N%/M%」OSD 整条删除，改为静默 `applyScale()`；两版设置页的 toast 由「已同步：」改为「已保存：」；档位框提示语、网页后台详情里的同步宣传句同步清理。**其它功能（观看记录、启动页、解码、画面比例等）的历史提示未动**
  - **手机设置页删除「观看记录」类目**：删掉 tab 项、`#p-his` 整个 pane、切 tab 时的 `loadHistory()` 调用，以及 `loadHistory` 与专用 `fmtSec` / `fmtDate`（约 3 KB）。`/api/history` 在手机设置页已无引用；`pushPlay`、`.item` 列表样式（搜索结果仍用）、数据备份说明里的「包含观看记录」文字保留——服务端备份确实仍含这项。**剩下的 4 个 tab**：点播源（默认）· 直播源 · SMB 网盘 · 播放设置
  - **不受影响**：电视版网页后台「观看记录」菜单、首页「继续观看」、服务端 `/api/history` 路由、观看记录数据本身全部保留，只是手机版**本机设置页**不再有入口
  - **验证**：两版 `compileTv/PhoneReleaseJavaWithJavac` 零错误；浏览器实测两版滑块改名、135%/80% 回显、拖动 POST body、底部保存仍带两字段、toast 文案、无 JS 错误；手机设置页 4 tab 逐个切换、默认页、无 `#p-his`、片源行与保存按钮正常；CSS/JS 括号平衡、JS `node --check` 通过
  - **交付**：两版 `assembleRelease` + 签名验证 + R8 零 warning + dex/assets 比对
- **电视端网页后台精简：跳过片头片尾迁入点播管理、去掉所有类目说明文字（tv v75；手机端零改动）**
  - **起因**：用户在手机浏览器里截了电视后台「IPTV 管理」页，要求把「系统设置」里的跳过片头片尾搬到「点播管理」，并去掉标题下那两行说明（类目说明 + 访问地址），「所有类目的说明文字都去掉」，**手机端保持不变**
  - **范围**：只改 `app/src/tv/assets/admin.html`（电视版专属网页后台）。`app/src/phone/assets/m.html`（手机版本机设置页）与共用的 `app/src/main/assets/index.html` 逐字节不动；Java 层、两版共用逻辑也不动。因此手机版 APK 内容完全不变，无需更新
  - **迁移**：「跳过片头 / 片尾」整张卡片（两个输入框 + 详情）从「系统设置」移到「点播管理」末尾，与点播播放引擎、点播画面比例、过滤成人分类同页。系统设置由 8 张卡片减到 7 张，点播管理由 3 张增至 4 张。`skipIntro` / `skipOutro` 的 id 不变，`loadConfig` 回显与 `btnSave` 的 `cfgBody` 不受影响，实测保存仍写入 12 / 8 秒
  - **说明文字清理**（覆盖全部 9 个一级类目 + 11 个 pane）：标题下 `.sub` 里的「类目说明」和「当前访问地址」两行不再生成（CSS 隐藏 + `setHeader` 简化，尾部专用于显示地址的 script 同步精简）；菜单首页 9 个类目行的描述文字与 `data-desc` 删除；各页顶部/页内 12 块静态 `.hint` 说明、16 处卡片脚注 `.sc-sub`、2 处滑块 `.scale-tip`、底部 `.foot` 提示全部清空；`PAGE_META` 的 11 项描述清空
  - **刻意保留**（这些不是说明文字，删了会坏功能）：卡片标题、下拉选项、按钮文案、输入框 placeholder（如「搜索分组名」）、只读状态值（主题色、明暗、过滤开关、`modeVal`）、「详情 ›」按钮与点开才出现的 `.sc-long` 长说明、以及带 id 的结果/状态容器（一键测试结果、分组统计、解码器列表、推送结果等）。脚注清空后保留空 `<div class="sc-sub">` 占位，确保「详情 ›」仍靠右、布局零位移
  - **验证**：10 个类目逐页实测标题下 `mSub` 为 `display:none`、无残留常显说明；菜单 9 项与描述清零；点播管理/系统设置卡片清单与输入框回显；保存 POST body；IPTV 管理页 5 张卡片、分组 4 行、滑块 135%/80% 回显、详情仍可展开收起；全站回归（一键测试、主题色、明暗切换、返回）无 JS 错误；与 v74 逐字节比对 `m.html` / `index.html` 完全一致；两版 `assembleRelease` + 签名 + dex 校验
  - ⚠️ 手机浏览器访问电视后台有缓存，装完新版要在浏览器里**刷新一次**才看到新界面（用户截图中 IPTV 管理页没有「选台框与节目单字体」卡片，就是旧缓存页面）
- **直播菜单加「选台框大小 / 节目单字体 / 分组管理」，两端都有（tv v74 / phone v20）**
  - **起因**：用户要求把上一轮加的两个百分比调节和分组管理从网页后台搬进「直播菜单」，电视上用遥控器直接就能改，不用掏手机开后台；「两端都要有」= 电视版与手机版两个 APK
  - **入口**：直播播放时长按画面 / 长按抽屉空白处 / 遥控器菜单键 →「功能菜单」，由 7 项扩到 10 项，新增「选台框大小（当前 N%）」「节目单字体（当前 N%）」「分组管理（隐藏 N 个）」。Java 层 `LivePicker` / `LivePlayerActivity` / `TvServer` 两个 flavor 共用，改一次两版 APK 都带上
  - **选台框大小 / 节目单字体**：菜单里是 7 个常用档位（50/75/100/125/150/175/200%，100% = 历版原样），方向键选中、OK 即确认；网页后台与手机版本机设置页是 50~200%、5% 步长的滑块。若网页后台细调过（如 135%），菜单会额外补一项「保持当前 135%」并设为焦点，避免一按 OK 就把细调值误改成 50%。抽屉改完立刻 `applyScale()` 重算，不用等 1 秒轮询
  - **分组管理**：把网页后台的「显示 / 隐藏直播分组」搬进菜单。数据源是全量频道缓存（未过滤，隐藏的也列得出来随时能勾回来），自绘可聚焦列表：`● 已显示 / ○ 已隐藏　分组名（N 个频道）`，OK 切换即时生效；顶部「全部显示 / 全部隐藏」两个可聚焦快捷项，点了不关对话框。改完写 `Store.saveHiddenLiveGroups` + `WebAdmin.markDirty()`，并走新增的 `Listener.onHiddenGroupsChanged()` 回调，播放页按新名单从缓存重过滤后 `mergeChannels`，**不重新联网、不重建内核、不打断正在播的流**，当前正在看的台即使被隐藏也强制保留
  - **双向同步**：菜单改缩放走新增的 `TvServer.syncLiveScales()`，与 `POST /api/config` 完全等价 —— `Store`（原生 UI 读）与 `config.json`（后台回显 / 备份导出）双写，再即时 `dispatchPush` 给首页 WebView。所以在电视菜单改完，手机浏览器后台、手机版本机设置页、首页网页直播页三处读数同时更新；反过来在网页后台改完，电视菜单项里的「当前 N%」与抽屉也会同步。分组管理直接读写同一份 `Store.hiddenLiveGroups`，菜单与两版网页后台天然一致
  - **补齐上一轮遗漏**：手机版本机设置页 `m.html` 补上两个滑块（上一轮只加了电视后台 `admin.html`），放在「直播源」tab 顶部，亮 / 暗两种模式都适配；`cfgBody` 与 `loadConfig` 同步，拖动即保存。至此电视后台、手机设置页、电视直播菜单三处都能改同一份配置
  - **验证**：三页浏览器无 JS 错误；两版滑块 input / change / 保存 POST body / 回显逐项核对；首页网页直播页实测两栏宽度 100%→108px、150%→161px、50%→54px（calc 生效），节目单按钮字体独立 14→10.5→28px；`appearance` 推送与 config 回显兜底；两版分组管理 4 行 / 切换 / 保存 POST 正常；与 v73 基准逐属性对比，首页差异 0 处、手机设置页仅多出新增的滑块卡片；两版 `assembleRelease` + 签名 + dex 内容校验
- **亮色背景模式：深色 / 亮色全局开关，两版都加（tv v73 / phone v19，共用 Java 层）**
  - **起因**：v72 的 5 套主题只换强调色，背景恒为深色；用户追问「不管怎么换颜色背景都是黑的，能不能有个亮色」。本轮加一个**独立的明暗开关**，与 5 套主题色正交 → 5 色 × 2 明暗 = 10 种组合，默认「深色」= 历版原样
  - **入口**：电视版网页后台 →「系统设置」第一张主题色卡片内新增「背景明暗 · 深色 / 亮色 ☀」；手机版设置 → 顶部主题条内新增同一行。点一下本地立刻生效并 `POST /api/config {mode:...}`（按字段合并，不冲其它设置）
  - **范围**：三处网页界面（首页 WebView、电视版网页后台、手机版设置页）。**电视版网页后台本来就是浅色**（v71 卡片风），所以它保持不变；真正从黑变白的是首页与手机版设置页。Java 绘制的原生选台页 / 播放页按之前确认的「全部网页界面」范围保持历版深色，视频播放区也恒为黑底
  - **实现**：把 index.html / m.html 的中性色（底色、卡片、边框、各级文字）抽成 50 / 26 个 CSS 变量，`:root` 默认值 = 原色值，每个 `var()` 都带原值兜底（脚本断言 0 处不一致）→ **暗色默认与 v72 逐像素一致**：浏览器实测三份页面 55 个选择器 × 7 个颜色属性，差异 0 处（仅新增的「背景明暗」DOM 本身）。`html[data-mode="light"]` 覆盖这些变量为浅底深字
  - **亮色适配的细节**（都是逐项截图核对后修的）：`.home-title` 等浅底白字改用 `--fg-on`；强调色浅文字改用每套主题的深主色 `--ac-on`（5 套各自配了加深值，保证白底可读）；手机主题条、固定保存栏改白底；toast / 直播提示 / 播放器黑区文字保持浅色；输入框 placeholder 加深；主题色块还原成真实色值（自动变量化曾把它们变成深棕，已修）
  - **配置**：`config.json` 的 `mode` 字段，白名单 `dark/light`，非法值回落 dark；`GET /api/config` 回显、`POST` 校验后保存，随 `/api/backup` 整份 config 一起迁移。服务端 `asset()` 首帧注入 `<html data-theme="..." data-mode="...">` 两个属性，零额外请求、刷新不闪色；主题或明暗任一变更统一发 `action:"appearance"` 即时推送给首页 WebView（首页 `handlePush` 新增分支，保留旧 `theme` 兼容）
  - **验证**：暗色零变化逐属性对比；亮色下 body / 标题 / 输入框 / 按钮 / 提示 / 卡片 / 主题条 / 保存栏的 computed 底色与文字色逐项核对；5 主题 × 2 明暗共 10 组确认主色与底色互不干扰；两版开关的初始态 / 点选 / POST body / 状态文案 / 选中态；首页即时收外观推送与 config 回显兜底；三页全站回归（后台 9 菜单 / 8 卡片 / 保存 12 字段 / 分组管理 / 一键测试 / APK 升级，手机版 5 tab / 分组，首页直播页）全部通过，无 JS 错误；7 张截图 + 合成预览；R8 零 warning，两版签名一致
- **全局主题色：5 套，两版都加（tv v72 / phone v18，共用 Java 层与色板）**
  - **色板**：暖橙（经典，= 历版原样）/ 青绿 / 海蓝 / 紫罗兰 / 玫红。只换强调色、按钮、选中态、高亮、链接与状态文字；**浅色底、深色容器底与排版结构一律不变**
  - **切换入口**：电视版网页后台 →「系统设置」第一张卡片；手机版设置 → tabs **上方**的主题条（5 个类目都能看到）。点色块本地立即生效，同时 `POST /api/config {theme:...}` 保存（按字段合并，不会冲掉其它设置）
  - **三处页面全部生效**：首页 WebView、电视版网页后台、手机版设置页。服务端 `asset()` 在输出这 3 份 HTML 时注入 `<html data-theme="...">`，**首帧就带上主题，零额外请求、刷新不闪色**；保存主题时额外经 `MainActivity.dispatchPush` 即时喂给首页 WebView，不用等刷新或轮询（首页 `handlePush` 新增 `theme` 分支）
  - **配置**：`config.json` 的 `theme` 字段，白名单 `ember/mint/ocean/violet/rose`，非法值回落 ember；`GET /api/config` 显式回显、`POST` 校验后保存。`/api/backup` 导出的是整个 `config`，主题随数据备份一起迁移，无需改 Store。两个 flavor 共用同一套逻辑，各设备各存一份、互不干扰
  - **实现方式**：三份 HTML 的 `<style>` 顶部加 `:root` + 5 套 `html[data-theme=...]` 变量表（13 个变量：主色 / 次色 / 强调文字 / 浅高亮底 / 深高亮底 / RGB 光晕等），原硬编码色值改为 `var(--ac, 原值)` 形式，**每个 fallback 与 `:root` 默认值逐项相等**（脚本断言 0 处不一致）→ 默认 ember / 未注入主题 / 老 WebView 忽略 var() 时，界面与历版**逐像素一致**。浏览器实测三份页面共 32 个选择器 × 7 个颜色属性，与上一版差异 0 处（仅新增的主题选择 DOM 本身）
  - **可读性**：逐套算了主按钮白字、浅底链接、深底亮字、深黑底品牌字的 WCAG 对比度。青绿主色初版 `#10b981` 白字仅 2.54:1 偏低，调到 `#0d9668`（3.76:1）；5 套现在全部 ≥3.0
  - **验证**：5 套变量值与色板逐项核对、关键元素 computed style 确有多套取值（真换色）；两版切换 UI 的 5 色块 / 初始选中 / 点选 / POST body / 状态胶囊 / 选中态；首页即时收主题推送、无服务端注入时 `loadConfig` 回显兜底；三页全站回归（后台 9 菜单 / 8 卡片 / 保存 12 字段 / 分组管理 / 一键测试 / APK 升级，手机版 5 tab / 分组 / 一键测试，首页直播页）全部通过，无 JS 错误；首帧注入 Python 模拟 5 套标签均正确；7 张主题截图 + 紧凑合成预览；R8 零 warning，两版签名一致
  - **附注（v73 同期代码，本轮随菜单一起出包）**：选台框 / 节目单字体两个百分比配置、`Store.liveUiScale/epgFontScale`、三处选台界面（原生抽屉、全屏选台页、首页网页直播页）的缩放、`/api/config` 白名单与 `appearance` 推送，均已在源码中，本轮统一编入 tv v74 / phone v20
  - ⚠️ 用户附的 4 个链接（两个 m3u 订阅、两个 GitHub 仓库）解析服务请求失败，未重试、未用于改动
  - **原生界面不在范围内**：电视端选台页 / 播放页由 Java 绘制，按用户选的「全部网页界面」保持历版深色不变
- **电视版后台：三页设置项统一改成白卡片风（tv v71，仅电视版；手机版不变）**
  - **范围**：按用户选的「三页都改成图2卡片风」，把 **系统设置 / 点播管理 / IPTV 管理** 三个页面的设置项，从「深色下拉框 + 大段说明文字」统一改成**白色圆角卡片逐项行**（与源管理页图2 同款），共 14 张卡片：p-set 7 张、p-vod 3 张、p-iptv 3 张 + 分组管理 1 张
  - **去掉深色下拉框**：旧 DOM 里 select 带 `background:#1a2130` 内联 style，优先级压过 `body.tv-admin select{background:#fff}`，所以一直是黑底。新 DOM 移除全部内联深色样式，统一 `.setcard select` 浅灰底 `#f5f7fa` + 深色字 + 9px 圆角；实测三个页面 8 个下拉框均为 `rgb(245,247,250)`，无深色残留
  - **长说明收进「详情」**：每张卡片只保留一行灰色副标题（单行省略号）+ 青色「详情 ›」按钮，点开才显示原来的完整说明；再点「收起 ‹」复原。修复了一个真 bug：`'sc-long'.indexOf('on')` 命中了子串 `on`，导致展开逻辑永远判成已展开，改用 `classList.contains('on')`
  - **只读解码能力框**：默认只显示首行摘要（`max-height:38px`），点同一张卡片的「详情」展开全部诊断信息，避免首屏一大块文字
  - **按钮型卡片带状态胶囊**：过滤成人分类右上角「已开启 / 已关闭」随 `setFilter()` 实时变；设备解码能力「只读」、清除缓存「本机」、数据备份「JSON」、分组管理「显示 / 隐藏」为固定胶囊；按钮沿用 `.btn`（蓝=选中）/`.btn.gray`（浅灰）的既有语义，像图2 的状态胶囊
  - **分组管理卡片并入同一套**：外层加 `.setcard`，顶部改成标题 + 胶囊 + 单行说明 + 详情，内部 8 个 `grp*` 控件与全部逻辑原样保留
  - **所有控件 id 与 JS 逻辑一个没动**：`loadConfig` / `btnSave` / `setFilter` / `loadDecoders` / `grpLoad` 全部照旧；实测 10 个下拉回显正确、保存提交的 12 个字段（含 6 个 v69 迁移字段）一个不少、分组保存 POST 正常、滚到底按钮可点（三页都补了 `padding-bottom:96px` 给固定保存栏留白）
  - **顺手收窄**：「换台反转」两个 option 显示文案缩成「正常（↑ 上一个台）/ 反转（↑ 下一个台）」避免卡片里被截断，值不变、详情里有完整说明；「跳过片头/片尾」副标题标明左片头右片尾
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 55 个文件逐项一致，不重发
  - 验证：三页卡片数 / 标题 / 白底圆角 / 下拉底色 / 说明默认隐藏 / 详情展开收起 / 首卡片标题与控件不重叠逐项实测；菜单 9 项、源管理一键测试、APK 升级三推送隐藏、系统信息、返回全回归通过，全程无 JS 错误；三张首屏截图 + 合成预览；R8 零 warning，两版签名一致
- **直播分组管理：隐藏 / 显示分组（tv v70 / phone v17，两版都改，共用 Java 层）**
  - **功能**：在设置页列出直播源里的全部分组（带频道数），每行可切「已显示 / 已隐藏」；隐藏的分组不出现在 IPTV 直播选台列表，也不参与 ↑↓ / 频道± 换台。支持搜索分组名（只过滤显示、不影响保存）、全部显示 / 全部隐藏、重新读取分组、单独「保存分组设置」
  - **入口**：电视版后台 → **IPTV 管理** 页底部；手机版设置 → **直播源** tab 底部。两版都做成懒加载（切进页面才请求），不拖慢首屏
  - **存储**：`Store.KEY_HIDDEN_LIVE_GROUPS`（SharedPreferences，两版共用同一份逻辑各自存）；`/api/live-groups` GET 返回**全量**分组（基于未过滤的频道缓存，含已隐藏的，否则无法恢复）+ POST 保存名单。导出配置 JSON 也带上 `hidden_groups`
  - **过滤只发生在「交付给界面」的最后一刻，缓存永远保留全量**：`Model.filterHiddenGroups`（纯逻辑，19 项 JVM 单测通过）；`LiveLoader.load` 的 `onCached` / `onResult` 交付前过滤，`fetch` 写缓存仍是全量；`LivePlayerActivity` 推送合并路径同样过滤但强制保留当前推送频道
  - **两条数据通路都覆盖**：① 原生电视端选台页 / 播放页抽屉（`LiveLoader`）；② 网页直播页 `/api/iptv` 的 `iptvPayload` 按隐藏名单剔除分组（单源订阅测试不过滤，诊断仍显示该源全部频道）。两条通路对「无分组」的叫法不同（原生「未分组」/ 网页「其他」），已统一归一成一个名字
  - **不受影响**：收藏夹、直播推送搜索（用户主动搜全库）、频道缓存、播放记录；取消隐藏立即恢复全部频道
  - `index.html` 的 `openLive()` 改为每次进直播页都静默重拉一次（`/api/iptv` 有 6 小时缓存，只是重新按隐藏名单过滤，不重复下载），保证保存后返回即生效；全部分组隐藏时显示后端 hint，不误报「订阅不可用」
  - **配色沿用各自体系**：电视版复用浅色 `.srcrow/.mini` 规则，手机版复用深色规则，没有互相搬色；另给两个 pane 加 `padding-bottom:96px`，修掉固定底部保存栏遮住「保存分组设置」按钮的问题（浏览器实测滚到底按钮可点）
  - ⚠️ 用户附的 4 个链接（两个 m3u 订阅、两个 GitHub 仓库）解析服务请求失败，未重试、未用于改动
  - **验证**：JVM 单测 19/19（分组归一、统计顺序、过滤不改原列表、全隐藏、取消隐藏恢复）；浏览器双端实测 5 组加载 / 行状态 / 切换 / 搜索只过滤显示 / 全部显示隐藏 / 保存 POST body / 重新读取回显；`index.html` 实测隐藏组不出现在分组列表、改隐藏状态后再进直播页自动刷新、全隐藏空态文案；一键测试、APK 升级、菜单 9 项、手机版 5 tab 全回归通过，全程无 JS 错误；两张卡片截图 + 合成预览；R8 零 warning，两版签名一致
- **电视版后台：合并分组 + 点播管理 / IPTV 管理拆分 + 「播放与显示」改名「系统设置」（tv v69，仅电视版）**
  - **分组文字去掉**：删掉「常用功能」「系统」两个 `.mgroup` 分组标题，原来两张 `mcard` 合并成**一张白卡片 9 项**（TV 推送 / 观看记录 / 源管理 / 点播管理 / IPTV 管理 / 系统设置 / APK 升级 / 系统信息 / 关于），并清掉失效的 `.mgroup` CSS
  - **新增两个一级类目**：「点播管理」(`p-vod`，胶片图标)、「IPTV 管理」(`p-iptv`，电视图标)，各自是独立 pane，标题与副标题写进 `PAGE_META`，`PANES` 同步扩容到 11 项；`isSrc` 仍只管源管理那 4 页，新页不显示二级菜单
  - **6 个选项从「播放与显示」移出**（只搬 DOM 块，控件 `id` 一个没改）：
    - → 点播管理：`playEngine` 点播播放引擎、`vodAspect` 点播画面比例、`btnFilterOn/Off` 过滤成人分类
    - → IPTV 管理：`liveDecodeMode` 直播解码、`liveAspect` 直播画面比例、`channelReverse` 换台反转
    - p-set 保留：启动页、跳过片头/片尾、应用模式、开机自启动、设备系统解码能力、清除电视缓存、数据备份
  - **「播放与显示」→「系统设置」**：一级菜单 `data-title`、显示文字、副标题、`PAGE_META['p-set']` 四处同步；图标保持原滑块图标未换
  - **配置不丢**：`loadConfig()` 与 `btnSave` 仍按全局唯一 id 取/提交字段，字段只是换了 pane；浏览器实测 10 个下拉回显正确、`filterAdult` 按钮态正确、从点播管理页点保存提交的 12 个字段一个不少（含 6 个迁移字段的值）
  - ⚠️ 两处如实说明：① 这些下拉框自带深色内联 style（`background:#1a2130`），在浅色卡片里是深色——这是历版就有的状态，本轮**没有改配色**，按「只改分类」最小化；需要统一成白色下拉可单独说。② 「设备系统解码能力」只读框仍留在系统设置页，里面会显示点播/直播解码等状态文字，那是诊断信息不是可编辑选项，未搬走
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 55 个文件逐项一致、签名证书相同，不重发
  - 验证：菜单态 0 个分组标题 / 1 张卡片 / 9 项且精确文本检查无独立「常用功能」「系统」行；三页字段归属与标题逐项核对；源管理二级 4 项、一键测试、APK 升级三推送隐藏、返回、TVBox 预览、保存栏全回归通过，全程无 JS 错误；三张截图 + 合成预览；R8 零 warning，两版签名一致
- **电视版后台：点播源/直播源增加「一键测试」+ 头部去掉「设备管理中心」（tv v68，仅电视版）**
  - **一键测试**（参考手机版设置页）：两个源页底部把原来的单个「+ 添加」按钮改成 `.sr2` 并排两按钮（`+ 添加一个片源 / 一键测试`、`+ 添加直播源 / 一键测试`），下方新增汇总条
    - 并发探测**全部已添加源（含停用的）**，逐个回填各行名称下方的 ms（直播还带频道数），按钮显示「测试中 N/M」，结束后汇总 `完成：X/Y 个正常 · 平均 Z ms · 各源耗时见上方每行`
    - 直接复用 v67 已有的 `probeSrc`（`/hls` 代理）与 `probeLive`（`/api/iptv?force=1&url=`），`runTestAll` 与手机版同源；`testAllBusy` + `btn.disabled` 防重复点击；空列表给 toast 并清空旧汇总；不改任何已保存配置
    - 按钮用浅色系（浅蓝 `#e8f0ff`/`#2f6fd0`），**电视版暖色渐变浅底与白色卡片配色一点没动**，没搬手机版深色
  - **头部去掉「设备管理中心」**：`setHeader` 在 desc 为空时不再创建 `.st` 那一行，菜单首页与点返回后只剩「iLaTV 后台」+ 当前访问地址一行；`PAGE_META` 默认值、返回调用、初始化调用同步改空。详情页仍显示各自页面说明（如「点播源、直播源、TVBox 源与 SMB 网盘」），那不是被要求去掉的文字
  - ⚠️ 用户附的 4 个链接（两个 m3u 订阅、两个 GitHub 仓库）解析服务请求失败，未重试、未用于改动
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 55 个文件逐项一致、签名证书相同，不重发
  - 验证：浏览器实测菜单态 `mSub` 只剩 1 行且页面无该字样；点播 4 源 / 直播 3 源并发测试汇总正确、坏地址显示「地址无效」、同步连点两次只发 3 个请求（防重生效）、空列表清汇总；APK 升级三推送仍隐藏、保存带 enabled、TVBox 预览、二级 4 项均回归通过，全程无 JS 错误；三张截图 + 一张合成预览；R8 零 warning，两版签名一致
- **电视版后台：APK 升级去掉三推送 + 每条源改行格式（tv v67，仅电视版）**
  - **APK 升级页**：从一级菜单进入时隐藏「影视推送 / 直播推送 / APK 推送」三行二级菜单，只留 APK 上传功能（选文件 → 推送 APK 到电视 → 说明与签名提示）。实现为 `apk-only` body 类 + `body.tv-admin.apk-only .subtabs{display:none}`；从「TV 推送」进入或点返回后自动清除，三个 tab 照常可用
  - **点播源 / 直播源每条源的显示格式**改成与手机版设置页一致：已添加的源**只显示名称**，地址点「修改」才展开名称 + 地址两个输入框（保存 / 取消完整还原）；每行固定 `已启用/已停用 · 修改 · 测试 · 删除`，停用行半透明；「测试」在名称下方显示 `7 ms · 正常`（直播还带 `336 频道`）
  - 沿用手机版的 `draft` 编辑态、`probeSrc`（`/hls` 代理）与 `probeLive`（`/api/iptv?force=1&url=`）计时逻辑；保存时 `enabled` 随 `/api/config`、`/api/live-subs` 一起提交
  - **只改显示格式，颜色背景一点没动**：电视版仍是暖色渐变浅底 + 白色圆角卡片，新增按钮用浅色系（绿启用 / 蓝测试 / 红删除 / 蓝保存），没有把手机版的深色配色搬过来；没有加「一键测试」，底部仍是原来的单个「+ 添加」按钮
  - APK 升级页副标题沿用 v66 原文未动（按「只改这个」最小化）
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 55 个文件逐项一致、签名证书相同，不重发
  - 验证：浏览器实测每行 0 个地址输入框、4 按钮顺序、修改展开/带入原值/取消还原/保存改名、启停半透明、点播与直播 ms 回显、保存请求带 enabled；APK 升级 `subtabs=none` 且只有 `s-apk` 可见、返回后再进 TV 推送三 tab 恢复；TVBox 预览、二级 4 项、保存栏均正常，全程无 JS 错误；三张截图核对；R8 零 warning，两版签名一致
- **电视版后台一级菜单合并：删除「源管理」分组（tv v66，仅电视版）**
  - 按截图要求，删掉整个「源管理」分组（标题 + 点播源 / 直播源 / SMB 网盘 3 个一级项），改为在**「常用功能」的「观看记录」下方**新增一个「源管理」入口（layers 堆叠图标，青色方块）
  - 点「源管理」**直达点播源页**（`p-src`）：页面标题「源管理」、副标题「点播源、直播源、TVBox 源与 SMB 网盘」，二级菜单 4 行照旧显示且高亮「点播源」，点播源列表直接可编辑
  - 一级菜单由 **3 组 9 项 → 2 组 7 项**：常用功能（TV 推送 / 观看记录 / 源管理 / 播放与显示）、系统（APK 升级 / 系统信息 / 关于）
  - 直播源 / TVBox 源 / SMB 网盘 三个功能**没有删除**，仍从「源管理」进去后用二级菜单切换，功能与 v65 完全一致
  - `showPane(id, title, desc)` 增加第三参，菜单项可带 `data-desc` 自定义副标题；二级菜单点「点播源」时仍走 `PAGE_META['p-src']`，标题显示「点播源」、副标题显示苹果 CMS 说明，两种入口互不干扰
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 逐 entry 内容一致、签名证书相同，不重发
  - 验证：浏览器实测 2 组 7 项顺序、「源管理」在观看记录正下方、点击直达 `p-src` 且二级高亮点播源、4 项二级逐一切换、TVBox 预览请求、APK 升级 / 观看记录 / 返回等其它入口不受影响，全程无 JS 错误；两张预览截图核对
- **电视版后台「源管理」二级菜单扩为 4 项（tv v65，仅电视版）**
  - 按截图要求，在「点播源 / 直播源」下方新增 **TVBox 源** 与 **SMB 网盘** 两行，沿用 v64 的白卡片分组行风格（无图标、行间细分割线、选中淡青底 + 青字、右侧 ›）
  - **TVBox 源 → `#p-tvbox`**：把原本游离在 pane 外的 TVBox 导入卡片收进独立 pane，只在该项选中时显示；顺手修掉 v64 遗留的「详情态所有类目都能看到 TVBox 表单」问题。预览 / 导入 / 追加去重 / 覆盖 / 仅点播 / 仅直播 全部照旧
  - **SMB 网盘 → `#p-smb`**：复用已有 pane，二级菜单点进去与一级菜单「SMB 网盘」入口效果一致
  - `PANES` 加 `p-tvbox`、`PAGE_META` 加对应标题与副标题、`isSrc` 判定扩到 4 个（源管理 4 页统一显示并高亮二级菜单）；删掉不再需要的 `.tvbox-card` 菜单态兜底隐藏规则，显隐交给 `.pane/.pane.on`
  - ⚠️ 一处行为变化：从一级菜单点「SMB 网盘」时，现在也会显示这 4 行二级菜单并高亮「SMB 网盘」（之前不显示），源管理 4 页导航更统一
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 与 v16 整包 SHA256 一致，不重发
  - 验证：浏览器实测 4 行二级菜单、逐项切换（点播源 / 直播源 / TVBox 源 / SMB 网盘）只显示对应 pane、TVBox 预览请求与结果、一级 SMB 入口同步高亮、返回与其它页 sourceTabs 隐藏，全程无 JS 错误；两张预览截图核对
- **电视版网页后台 iOS 分组样式重做（tv v64，仅电视版）**
  - 参考截图重做：暖色渐变浅底、大标题「iLaTV 后台」+「设备管理中心」+ 当前访问地址（两行）、分组标题 + 白色圆角卡片（16px）+ 行间缩进分割线 + 灰色右箭头 › + 右下角深色圆形 ＋
  - **类目按实际功能重排为 3 组 9 项**（去掉原来与「点播源 / 直播源」重复的「片源管理」）：
    - 常用功能：TV 推送 / 观看记录 / 播放与显示
    - 源管理：点播源 / 直播源 / SMB 网盘
    - 系统：APK 升级 / 系统信息 / 关于
  - 9 个图标全部**内联 SVG**（青绿 `#0e9d9d` 圆角方块，TV 推送用深色 `#1c2738`），不依赖任何外链或图片资源，旧 WebView 也兼容，APK 体积几乎不变；图标按各自功能自绘（显示器/日历/滑块/播放圈/波形/云/下载/人像/信息）
  - 详情页大标题随页面切换（TV 推送 / 点播源 / APK 升级 ...），副标题显示该页说明 + 访问地址，点返回恢复「iLaTV 后台 / 设备管理中心」
  - **二级菜单同款风格、不带图标**：`sourceTabs`（点播源 / 直播源）与 `pushSubtabs`（影视 / 直播 / APK 推送）从横向分段控件改成白卡片分组行，行间细分割线、选中淡青底 + 青字，每行右侧 ›
  - 顺手修两处视觉问题：① TVBox 导入卡片在旧结构里不隶属任何 pane，菜单首页会露出表单 → 菜单态隐藏；② 详情态 FAB 会盖住底部「保存并同步到电视」按钮 → 详情态隐藏（各页本就自带「+ 添加」按钮，功能不丢）
  - ⚠️ 电视版 TVBox 卡片在**详情态**的可见范围按上一轮约定保持不变（仍所有类目可见）；若也想只在点播源页显示，单独说一声
  - **手机版零影响**：本轮只改 `app/src/tv/assets/admin.html`（tv flavor 专属，phone 不打包）；Java / main / phone 全部源码逐字节未动，重编出的手机版 APK 与 v16 一致，不重发
  - 验证：浏览器实测 3 组 9 项、每行 SVG、二级无图标、标题与返回、TVBox 卡片显隐、保存栏完整、FAB 显隐，全程无 JS 错误；菜单态 / 详情态截图核对；R8 零 warning，两版签名一致
- **手机版首页/分类页重叠修复 + TVBox 卡片归类（phone v16，仅手机版）**
  - **重叠根因**：v13 为让底部致谢贴底加了 `.phone #home{display:flex}`，但 `#home` 是 ID 选择器，优先级(1,1,0)高于 `.view{display:none}`(0,1,0) 和 `.view.on{display:block}`(0,2,0)，导致首页在切到影视分类/详情/搜索时**永远显示**，两个 view 叠在一屏（截图里品牌行、两个搜索框、海报全糊在一起）
  - 修法：改成 `.phone #home.on{display:flex}` + `.phone #home:not(.on){display:none}`。浏览器实测初始 `#home=flex / #category=none`，点「影视分类」后 `#home=none / #category=block`、可见 view 只剩 `category`，返回后恢复，无 JS 错误
  - **TVBox 卡片归类根因**：v14 的卡片插在了 `p-src` pane 的闭合 `</div>` **外面**，不受 `.pane{display:none}` 控制，所以观看记录/直播源/SMB/播放设置每个 tab 都能看到。现把卡片移进 `#p-src` 内部，只在「点播源」tab 显示
  - 浏览器实测：卡片 `closest('.pane').id == 'p-src'`；五个 tab 逐个切换，只有点播源下 `p-src=block` 且卡片 `offsetParent` 非空，其余四个 tab `p-src=none`、卡片不可见
  - **电视版零影响**：Java / JS 逐字节未动，只改共用 `index.html` 的 `.phone` 规则 + `phone/assets/m.html`；电视版 body 无 `.phone`，`#home` 仍走 `.view/.view.on`，初始 `block`、切分类后 `none`，computed 与 v63 一致；`tv/assets/admin.html` 未动。电视版 APK 不重发
  - ⚠️ 电视版后台的 TVBox 卡片位置按要求**保持不变**（仍在片源 pane 外）；若之后也想让电视版只在片源页显示，单独说一声即可
- **手机版底部网址改回普通文字（phone v15，仅手机版样式）**
  - 首页底部项目地址去掉蓝色 / 下划线 / 点击变色，`color:#6b7688`、`text-decoration:none`，并显式 `font-size:12px` 压回与上方致谢一致（`#netinfo` 自带 `.clock{font-size:16px}`，不覆盖会比上面一行大）
  - 元素仍是 `<a>`，**点击跳系统浏览器的功能保留**，只是不再带链接外观；若需彻底变成不能点的纯文本，删掉 `isPhone` 分支里的 `replaceChild` 即可
  - **电视版零影响**：本轮只改共用 `index.html` 的 `.phone` CSS（Java / JS 均逐字节未动），电视版 body 无 `.phone`，`#netinfo` 仍是 16px 纯文本 `DIV`、`.tips margin-top:40px`、四行提示与二维码全部原样；浏览器 computed style 与 v14 交付包**全等**，电视版 APK 不重发
  - 浏览器实测：手机版上下两行同为 `12px / rgb(107,118,136) / 无下划线`，仍贴底（y=831~874），点击仍发起导航，无 JS 错误
- **TVBox 订阅导入 A 档（tv v63 / phone v14，两版同步）**
  - 新增 `TvBoxImporter.java`（纯 Java + org.json，无 Android 依赖，可桌面 JVM 单测）：解析 TVBox 订阅 → 提取候选点播/直播源
  - 支持格式：明文 JSON、整体 Base64、TVBox `8位随机串**Base64` 加密、gzip、zip 离线包（取包内最大 .json/.txt）、多仓 txt（每行一个订阅，最多 8 个、递归 2 层）、UTF-8 BOM、`//` 注释包裹、URL 自动跟随重定向
  - **只导入两类**：`sites` 里能当苹果 CMS JSON 采集接口用的站（`type:1`，或没写 type / 显式 `type:0` 但 api 含 `api.php/provide/vod` 等特征）、`lives` 里 `type:0` 的 m3u/txt 直播订阅（`epg` 字段带出但不落库）
  - **明确跳过并计数**：`type:3` Spider/JAR（含 `csp_*`、类名式 api）、`type:4` remote、XML 站、`clan://` 等非 http 地址、`parses / flags / ijk / doh / spider / wallpaper`。不下载也不执行任何外部代码
  - 新增 `POST /api/tvbox`：`{url, raw, mode:append|replace, vod, live, dryRun}` → 返回候选、跳过数、告警与统计。`dryRun` 只算不落盘；`TvBoxImporter.merge()` 做按规范化 URL 去重的统一合并（纯数据、可单测），TvServer 只负责写回 `config.sources` / `Store.liveSubs` 并 `clearLiveCache()` + `markDirty()`
  - 两版设置页（手机 `phone/assets/m.html`、电视 `tv/assets/admin.html`）的「点播源 / 片源」页底部新增**导入 TVBox 订阅**卡片：订阅地址或粘贴配置、追加去重/覆盖现有、点播+直播/仅点播/仅直播、预览、导入；导入后自动 `loadConfig()` 回显
  - 验证：桌面 JVM 单测 **63/63 通过**（8 种格式 × 字段提取 × 去重合并 × 错误边界）；浏览器双端实测请求体分流、预览/导入结果、无 JS 错误；R8 零 warning，两版签名一致
  - ⚠️ A 档不含 Spider/JAR 插件引擎、嗅探解析、XML CMS、站点 header/ext。需要这些属于 B/C 档，见对话说明
- **手机版首页底部信息 + 网址可点击（phone v13，仅手机版）**
  - 首页底部两行（致谢 / 项目地址）由「紧跟按钮」改为**贴屏幕底部**：`.phone #home` 改纵向 flex、`.home-wrap` 设 `flex:1`、`.tips` 用 `margin-top:auto` 沉底。412×915 竖屏实测两行位于 y=824~874（距底 41px 为安全留白），不再悬在半屏
  - 项目地址由纯文本 `<div>` 变成**可点击链接**（蓝色 + 下划线），点击交系统浏览器打开 `https://github.com/RenNTNT/iLaTV`
  - 原生层（`MainActivity.WebViewClient`）新增 `shouldOverrideUrlLoading` 两个重载：外部 http(s) 主框架导航用 `ACTION_VIEW` 转系统浏览器并拦截，本机回环 / 局域网地址仍由 WebView 自己加载。**必须这么做**：否则 WebView 就地导航到外部网页，而该页没有 `window.__onBack`，返回键会被原生层吞掉、用户卡在网页里。该逻辑用 `BuildConfig.IS_PHONE` 守卫，电视版 `IS_PHONE=false` 恒不生效（R8 可优化掉），电视版首页也根本没有可点击外链
  - 浏览器实测：手机版 `#home` flex / `.tips margin-top:auto` / `#netinfo` 为 `<a>` 且 href 正确、蓝色下划线、点击发起导航；电视版 `#home` 仍 `display:block`、`.tips margin-top:40px`、`#netinfo` 仍是 `DIV` 纯文本、二维码正常显示，均无 JS 错误
  - ⚠️ 本轮动了共用 Java（新增外链转发），电视版行为不变但 `classes.dex` 可能变化；电视版 APK 不重发，用户手上的 v62 不用重装
- **手机版「点播源 / 直播源」统一交互 + 一键测试（phone v12，仅手机版）**
  - tab「片源管理」改名 **「点播源」**；其余 4 个 tab 不变（观看记录 / 直播源 / SMB 网盘 / 播放设置）
  - **直播源页交互与点播源完全一致**：已添加的行只显示名称（地址仅在点「修改」后展开、可保存/取消）、每行 `已启用/已停用 · 修改 · 测试 · 删除`、停用行半透明、底部 `+ 添加直播源 / 一键测试` 并排 + 汇总条
  - 两边共用同一套 `.srcrow` 样式与 `runTestAll(cfg)` 批量测试逻辑（并发探测、逐个回填各行 ms、汇总「X/Y 个正常 · 平均 Z ms」、防重入）
  - 点播源探针 `probeSrc` 走 `/hls?url=…`（`XXX ms · 正常` / `HTTP 500` / `无数据`）；直播源探针 `probeLive` 走 `/api/iptv?force=1&url=…`（`XXX ms · 正常 · 336 频道`，失败显示 `读取失败` / `地址无效` / `超时` / `连接失败`），均 12 秒超时、不改已保存配置
  - 直播源仍走 `/api/live-subs` 单独保存，`enabled` 字段随提交；服务端原生 LiveLoader 本就按 `enabled` 过滤，停用真实生效，无需前端额外过滤
  - 浏览器实测：tab 名正确、两侧均无地址输入框、按钮顺序一致、修改取消还原、单行与一键测试 ms/频道数正确、汇总 `1/3 个正常 · 平均 7 ms`、POST 携带 `enabled`、无 JS 错误
  - **电视版零影响**：本轮只改 `app/src/phone/assets/m.html`（phone flavor 专属，tv 不打包）；`admin.html` 与 v62 交付包逐字节一致、不含新按钮；Java / dex / `index.html` / Manifest / 资源表全部不变，电视版 APK 不用重装
- **手机版片源管理改版（phone v11，仅手机版）**
  - **已添加的片源不再显示地址**：默认展示态只有「名称 + 已启用/已停用 + 修改 + 测试 + 删除」一行；地址只在点「修改」或「+ 添加一个片源」的编辑态出现，带出原值，可「保存 / 取消」（取消完整还原）
  - **启用/停用**：按钮显示当前状态并切换，停用行半透明；`enabled` 字段随 `/api/config` 原样存取（公共 Java 不改动）。停用的源在手机版**搜索结果**与详情页**「选择片源」**里都不出现（在 `main/assets/index.html` 用 `window.__phoneOrigin` 分支过滤，电视版恒不生效）
  - **测试按钮**：经本机 `/hls` 代理转发一次采集接口 `?ac=videolist&wd=iLaTV&pg=1`，从发起到收完响应计时，在该行名称下方显示 `XXX ms · 正常` / `XXX ms · HTTP 500` / `超时 >12000 ms` / `连接失败`；不改已保存配置，12 秒超时兜底
  - 浏览器实测：3 行均无地址输入框、按钮顺序 toggle/edit/test/del、切换生效、修改保存/取消还原、ms 显示 `127 ms · 正常`、POST 携带 `enabled`、无 JS 错误；手机版搜索 3 结果过滤为 2、电视版仍 3
  - ⚠️ 边界：电视版网页后台（`admin.html` 保持不变）保存片源时会重建数组、不认识 `enabled`，会把启用状态重置为「全部启用」；需要停用请在手机版设置里改并保存
  - Java / dex / `admin.html` / Manifest / 资源表逐字节一致；电视版 APK 不用重装
- **手机版详情页片源按钮与封面（phone v10，仅手机版）**
  - **片源按钮与选集按钮严格同宽**：`.srclist` 改用与 `.eps` 完全相同的 5 列 grid（`repeat(5,minmax(0,1fr))` + `gap:8px`），清掉 `.srcbtn` 的 margin/定宽。412px 竖屏实测片源与选集单格均为 **71px**、x 坐标逐像素对齐（12/91/170/250/329）
  - 长片源名（如「非凡资源 · 更新至第191集」）在 71px 窄格自动换行、字号 15→12px、`min-height:38px`；grid 行内 stretch 保证同排等高。除本条外所有字号与 v9 全等（浏览器 14 项 computed style 实测一致）
  - **封面缩到「每行 2 张」的单张尺寸**：`.dt-cover` 由 230×320 改为 `calc(50vw - 18px)` × `calc(75vw - 27px)`（即两列网格单张宽、2:3 比例），右侧标题/简介区 margin-left 30→12px。412px 竖屏实测 **188×282**；电视版仍 230×320
  - 三列海报 / 5 列选集 / 上拉加载等 v8–v9 特性全部保留；Java / dex / `admin.html` / `m.html` 逐字节一致，电视版 APK 不用重装
- **手机版详情页选集每行 5 集（phone v9，仅手机版）**：电视版 `.eps` 仍是 flex + `.ep` 定宽 104px / 17px，完全不变
  - 手机版把 `.eps` 改成 `grid-template-columns:repeat(5,minmax(0,1fr))` + `gap:8px`，清掉 `.ep` 的定宽与 margin；`.eps>.empty` 跨整行（加载中/空态不被挤进第一格）
  - 为让 191 集这类长集号在 71px 窄格里完整显示，选集按钮本身字号 17px → 13px（**仅** `.phone .eps .ep`）；实测「第 191 集」`scrollWidth == clientWidth` 无省略。上方搜索框 / tab / 筛选 / 片名 / 标题 / 评分等所有其它字号与 v8 逐项全等（浏览器 computed style 13 项实测一致）
  - 412px 竖屏实测：首行 5 个、格宽 71px、191 集由 64 行变 39 行；三列海报仍生效
  - Java / dex / `admin.html` / `m.html` 逐字节一致，电视版 APK 不用重装
- **手机版海报改三列（phone v8，仅手机版）**：只把 `.phone .grid,.phone .cat-grid` 的 `grid-template-columns` 由 `repeat(2,minmax(0,1fr))` 改为 `repeat(3,minmax(0,1fr))`，gap 仍 12px
  - **所有字体大小逐字节不变**：搜索框 / 搜索·返回按钮 / 电影剧集 tab / 筛选 chip / 片名 / 评分角标 / 底部提示 / 首页按钮的 `font-size` 全部保持 v7 原值（浏览器 computed style 11 项实测全等）
  - 412px 竖屏实测：首行 3 张、卡片宽 188px → 121px、24 部由 12 行变 8 行；首页按钮网格仍 2 列（不是海报，未改）
  - 影视分类 / 搜索结果 / 观看记录三个共用 `.grid` 的页面同步变 3 列；逐层导航与「上拉加载更多」逻辑不变
  - 电视版仍 6 列 flex、卡片宽 284px，Java / dex / `admin.html` / `m.html` 逐字节一致，APK 不用重装
- **手机版分类页图片与「上拉加载更多」修复（phone v7，仅手机版生效）**
  - **根因**：`/api/status` 的 `lan` 是「局域网 IP + 端口」，但手机版内置服务**只绑 127.0.0.1**（`EXPOSE_LAN=false`）。WebView 里所有 `/hls?url=` 请求（豆瓣片单 + 海报图片）都指向 `http://192.168.x.x:9601`，根本连不上 → 图片全部 `onerror` 隐藏、只剩片名占位；`refreshLive()` 失败直接把 `catHasMore=false` 锁死 → 显示「共 24 部 · 已全部加载」，上拉再也加载不了
  - **修复**：`phone/assets/flavor.js` 首帧记录 `window.__phoneOrigin = location.origin`；`index.html` 用它覆盖 `lanUrl`，并阻止 `/api/status` 回调再用局域网 IP 覆盖。电视版不定义该变量，`lanUrl` 结果与原来完全一致（仍为局域网 IP，手机浏览器访问后台需要它）
  - **加载更多兜底**：实时豆瓣接口失败时，手机版不再锁死 `catHasMore`，上拉（`scroll` 触底）或点底部提示时由 `catFallbackRows()` 从内置索引其它分类补未展示条目；底部文案改「上拉加载更多」，提示可点（防个别 ROM 触摸滚动不派发 scroll）。电视版全部走原逻辑、原文案
  - **Java / dex 零改动**，`admin.html`、`m.html` 逐字节一致；变更只在 `main/assets/index.html`（均包在 `window.__phoneOrigin` 分支）与 `phone/assets/flavor.js`
- **手机版影视分类竖屏适配（phone v6，仅手机版样式）**：根因是 `.phone` 网格用 flex + `flex-basis:50%`，但卡片自带左右 10px margin，两卡合计宽度超过 100% 被折成「每行一张」→ 单列巨幅海报
  - 改 CSS Grid 两列（`grid-template-columns:repeat(2,minmax(0,1fr))` + `gap:12px`），清掉卡片 margin/flex 宽度；搜索行三个控件不换行、tabs / 筛选 chip / 片名字号按竖屏收紧，评分角标缩小
  - 手机版海报预置片名占位层 + `onerror` 隐藏破图，加载失败时露出片名而不是空白破图标；**电视版生成的卡片 HTML 逐字节不变**（`phoneMode` 分支）
  - 逐层导航（热门 → 海报第一行 → ↓ 加载更多）不受影响，`#catGrid .cat-card` 选择器不变
- **两版内置直播源更新（tv v62 / phone v6）**：`https://sub.ottiptv.cc/huyayqk.m3u`
- **手机版首页刷新不再闪二维码（phone v5，仅手机版）**：根因是 `.phone` class 原来要等 `/api/status` 异步返回才加到 `<body>`，首帧按默认样式把右下角白色二维码卡片渲染出来，随即被 `.phone #cornerQr{display:none!important}` 隐藏 → 闪一下
  - `main/assets/index.html` 的 `<head>` 在 CSS 之前新增同步 `<script src="flavor.js">`；`phone/assets/flavor.js` 首帧执行 `document.documentElement.classList.add('phone')`，`tv/assets/flavor.js` 为空逻辑
  - 所有 `.phone xxx` 都是后代选择器，`<html>` 上打标与原来 `<body>` 上打标等价；二维码、两列按钮、搜索对齐、隐藏遥控提示等手机版样式**全部在首帧生效**，不再有布局跳变
  - 纯前端改动、**不改 Java / dex**；电视版 HTML 只多一个空脚本引用，二维码仍按原 `/api/status` 分支赋值并显示，行为零变化
- **手机版设置页精简（phone v4，仅手机版）**：只改 `app/src/phone/assets/m.html`，电视版 `admin.html` 与 `index.html` 逐字节不变
  - 去掉「换台反转」「应用模式」「开机自启动」三项设置块（含 `btnHomeChooser` 按钮与相关说明），以及「设备系统解码能力」状态框里对应三行展示；顶部标题 `iLaTV 设置 3.0` → `iLaTV 设置`
  - JS 同步清掉 `loadConfig` 回显、`btnSave` 提交字段、`btnHomeChooser` handler；服务端 `/api/config` 是**按字段 merge**，手机版不提交这三个键即保留原有值，不会把配置冲成默认
  - 保留：观看记录 / 片源管理 / 直播源 / SMB 网盘 / 播放设置 5 个 tab，点播·直播解码方式与画面比例、启动页、解码能力状态框、保存并同步、返回首页
- **手机版首页微调（phone v3，仅手机版）**：全部改动都落在 `index.html` 的 `.phone` CSS 与 `isPhone` 分支，电视版执行不到、显示零变化
  - 顶部品牌行「iLaTV **电视版 2.0**」→「iLaTV **手机版 2.0**」，页面 title 同步改为 `iLaTV 手机版 2.0`（由 `/api/status.isPhone` 分支改写，电视版分支保持原样）
  - 搜索行与下方两列按钮网格**严格左右对齐**：`.search-wrap` 由固定 480px 改为 flex 自适应撑满，搜索按钮定宽 92px、`gap:10px` 与 `.home-actions` 网格一致，输入框字号/内边距/圆角与按钮统一
  - 底部「遥控器…」「播放中…」两行隐藏（`.phone .tips > div:nth-child(1/2)`），保留致谢与项目地址两行；`tips` 上边距收紧到 22px
- **手机版设置页修正（phone v2，仅手机版）**：`app/src/phone/assets/m.html` 是 phone flavor 专属文件，电视版完全不打包它
  - **可返回**：`MainActivity` 把返回键交给 `window.__onBack()`，旧设置页没定义这个函数导致按返回卡住；现在定义 `__onBack` 回首页 `/?tv=1`，并在顶部加「‹ 返回首页」按钮（点击同样回首页）
  - **去掉 TV 推送**：「TV 推送」tab 及影视/直播/APK 三个推送子页全部隐藏，没有任何入口能触发推送；保留 DOM 只是为了让原有 JS 初始化不报错
  - **底部按钮**：「保存并同步到电视」→「保存并同步」，保存后的 toast 同步去掉「到电视」
  - **片源管理（点播源）保留并设为默认页**；观看记录 / 直播源 / SMB 网盘 / 播放设置顺序不变
  - `main/assets/m.html` 已删除（电视版读 `admin.html`，本就不读它），避免 AGP 同名 assets 冲突；电视版 dex 与界面零改动
- **电视版网页后台浅色列表风格（v61，仅电视版）**：新增 `app/src/tv/assets/admin.html`，iOS 分组列表风格（浅灰底 #f2f2f7、白色圆角卡片、标题+副标题+右箭头、右下角 + 悬浮按钮），顶部显示当前访问地址
  - 菜单首页 8 项：TV 推送 / 观看记录 / 片源管理（内含「点播源 / 直播源」二级切换，功能不丢）/ SMB 网盘 / 播放与显示 / APK 升级（直达 s-apk 子页）/ 系统信息（实时读 `/api/status` 与 `/api/decoders`）/ 关于
  - 点卡片进详情、顶部「‹ 返回」回菜单；所有浅色样式都带 `body.tv-admin` 前缀，主 IIFE 用 `showPane()` / `showSub()` 统一导航，原有 `btnSave` / `btnReload` / 表单 / 推送逻辑全部保留
  - **手机版设置页完全不改**：`app/src/main/assets/m.html` 保持 v60 原版深色文件（手机版直接继承），电视版由 `TvServer.adminAsset()` 在 `/`、`/m/`、`/m.html` 处改读 `admin.html`。两个文件不同名，避免 AGP assets 合并报 `Duplicate resources`；因此电视版后台随便改，手机版 APK 里的 `m.html` 逐字节不变。以后改手机版后台请改 `src/main/assets/m.html`（或另建不同名文件再改路由）
- **统一工程 · 两版 App（v60）**：用 `productFlavors` 从同一份 `main` 源码编译出电视版与手机版，互不干扰
  - `tv`：`applicationId com.ilatv.tv`、`versionCode 60`、端口 **9600**、绑全部网卡（局域网可访问）、桌面名「iLaTV 电视版」、保留扫码与手机遥控后台 `m.html`、HOME 桌面能力与开机自启全部生效
  - `phone`：`applicationId com.ilatv.phone`、`versionCode 1`、端口 **9601**、**只绑 127.0.0.1**（局域网不可达）、桌面名「iLaTV」、`MainActivity` 竖屏 + 触摸适配（`.phone` CSS，电视版零影响）、**去掉扫码入口与局域网管理地址**，首页多一个「设置」按钮打开本机设置页
  - 互不干扰的保证：两个 applicationId 不同 → 可同时安装，SharedPreferences / 观看记录 / 订阅 / 缓存天然隔离；端口错开不抢 9600；`ApkProvider` authority 用 `${applicationId}.apkprovider`；安装状态广播 action 用 `${applicationId}.INSTALL_STATUS`；`LauncherMode.HOME_ALIAS` 固定为 namespace 下的 `com.ilatv.tv.HomeLauncher`（组件类名由 namespace 决定，两版相同，靠 ComponentName 的 package 区分所属 APK）
  - 手机版的配置页：`/m/` 仍是本机设置页（供片源、直播源、SMB、解码、画面比例、启动页等配置），按 `/api/status.isPhone` 改标题为「iLaTV 设置」；但**不对外监听、不扫码、不显示遥控地址**。TV 推送 / APK 推送 / 桌面模式开关按「全量复刻」保留在代码与界面里，其中桌面模式在手机版**不实际启用 HOME alias**（防止误换手机桌面）
  - 说明：WebView 首页与全部 `/api/*` 由内置 NanoHTTPD 提供，因此手机版仍需一个**纯本机回环**服务（不监听局域网、开销极小）；这是当前架构必需。若要彻底零服务，需改造成 `file://` + `shouldInterceptRequest` 拦截，改动较大
  - 编译：`gradle assembleTvRelease` / `gradle assemblePhoneRelease`（或一次 `gradle assembleRelease` 全出），产物在各 flavor 的 `outputs/apk/` 下；两版共用同一把 `debug.keystore`
- **应用模式 / 开机自启动（v59）**：手机后台「播放设置」新增两个独立开关，与电视端共用 `Store.launcherMode` / `Store.autoBoot`（SharedPreferences，随 `/api/backup` 备份）
  - **应用模式**：`normal` 普通应用 / `home` 桌面应用。Manifest 里声明 `activity-alias .HomeLauncher`（`MAIN + HOME + DEFAULT`，`enabled=false`），由 `LauncherMode.apply()` 用 `setComponentEnabledSetting` 动态启停；alias 的 launchMode / screenOrientation / theme 全部沿用 `MainActivity`，桌面与图标入口是同一个界面。桌面模式下按 Home 键走 `onNewIntent` → `window.__homePressed()` 回首页；首页返回键不 `finish()`（否则作为 HOME 会被系统反复拉起），只给提示
  - **默认桌面**：Android 无公开 API 静默设置默认 HOME，后台按钮 `GET /api/home-chooser` 让电视弹出系统选择框，用户选 iLaTV 并勾「始终」
  - **开机自启动**：`BootReceiver` 收 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，开关打开时拉起 `MainActivity`（启动页设置照常生效）。桌面应用模式下系统开机本来就会启动 HOME，最可靠；普通应用模式在 Android 10+ 可能被「后台启动 Activity」限制拦下，走 `BootLaunchService` 前台服务短暂过渡，并要求用户在系统「自启动管理 / 电池优化白名单」里放行。安装后必须手动打开过一次 App 才会收到开机广播
  - 状态回显：`/api/config` 与 `/api/decoders` 返回 `launcherMode` / `autoBoot` / `homeAliasEnabled`
- **推送可靠性（v57 修复）**：
  - 直播推送起播时 `loadingText` 默认隐藏并在 `applyChannels` / 合并完成时兜底隐藏，不再一直挂「正在加载频道…」；播放中再推送走 `LivePlayerActivity.onNewIntent` 直接换台
  - APK 推送的 `ApkProvider` 实现 `OpenableColumns.DISPLAY_NAME / SIZE` 查询并显式 `grantUriPermission`；改用 `startActivity`（NEW_TASK，不用 forResult），**不再在安装界面回调里立即删包**（不少 ROM 立刻回 RESULT_CANCELED，提前删会让安装器报「解析软件包出现问题」）。清理改由 `MY_PACKAGE_REPLACED` / PackageInstaller 状态回调立即执行，用户取消时由 `cleanupStale` 10 分钟兜底
  - `POST /api/push` 收到指令即 `MainActivity.dispatchPush()` 主动 `evaluateJavascript` 喂给首页 JS，绕过播放时被系统冻结的 `setInterval(pollPush, 2000)`；指令带 `pushId`，前端按 id 去重，即时送达与轮询不会重复执行。首页若被回收，`apk` / `live` 由服务端原生兜底（Context 直接起安装界面 / NEW_TASK+SINGLE_TOP 起直播页）
  - 从后台 MainActivity 起 Activity 统一带 `FLAG_ACTIVITY_NEW_TASK`，点播推送额外 `CLEAR_TOP` 避免叠两层播放器；`onResume` 主动补一次轮询

## 五、从旧包名升级

旧版包名为 `com.libretv.tv`，与本工程 `com.ilatv.tv` 在 Android 看来是两个 App，无法直接覆盖、数据不互通。

迁移流程：用旧版 → 后台「播放设置 → 导出备份文件」→ 卸载旧版 → 安装本工程编译的 APK → 后台「导入备份文件」→ 重启 App。
