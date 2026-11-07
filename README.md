# WebShortcuts

把常用网站放进一个轻巧的 Android 应用，为每个网站创建独立的桌面快捷方式。

WebShortcuts v1.2 使用 Kotlin、Jetpack Compose 和 Material 3。支持 Android 8.0（API 26）及以上，面向 Android 16（API 36）。收藏与图片默认保存在设备私有目录，可手动导出到所选文件位置；不包含账号系统、自动云同步或统计 SDK。

## 功能

- **网站收藏**：新增、编辑、删除、名称/网址搜索，也可以从浏览器分享网址到 WebShortcuts。
- **完整备份与恢复**：ZIP 包含收藏、自动/自定义图标、可编辑原图、裁切参数、来源时间及外观设置。先预览再合并或全量替换；重复 ID 保留本机，不同 ID 的同网址定制可同时保留。外观恢复默认关闭。
- **浏览器书签互通**：导入或导出 Chrome、Edge、Firefox 通用的 Netscape HTML 书签。文件夹中的书签平铺导入，按完整网址去重，仅接受 HTTP(S)；HTML 不携带图标定制或浏览器登录态。
- **三种图标模式**：自动发现网站图标、系统 Photo Picker 导入图片、根据名称生成字母/汉字图标。模式显式切换，自定义图片不会被自动刷新覆盖。
- **自由裁切**：拖动选区、调整四角、切换自由比例与 1:1，也可用尺寸/位置滑块精确调整；支持重置、取消和应用。保留私有原图和裁切参数，保存后仍可重新编辑，不会在已缩小的图标上反复裁切。
- **预览与安全区**：圆形/圆角桌面遮罩、Full Canvas 完整画布、安全区蒙层与桌面可见边界；辅助线仅用于预览，不写入图片。
- **自动图标**：解析 Web App Manifest、Apple Touch Icon、HTML 图标，并尝试根目录 `/apple-touch-icon.png`、`/apple-touch-icon-precomposed.png` 和 `/favicon.ico`；优先寻找 SVG 或 512 像素原图，按真实解码尺寸选择，不把放大的小 favicon 当作高清；支持 PNG、JPEG、WebP、安全 SVG 与 ICO 的 PNG/DIB 帧。
- **刷新反馈**：首页下拉或菜单批量刷新，显示逐项状态、完成数量及可关闭的结果摘要；编辑器展示可复制的来源 URL 和最近成功获取时间。抓取成功与桌面同步待重试分别反馈，失败保留缓存和上次成功时间。
- **可靠回退**：离线、解码失败或没有网站图标时显示生成图标；刷新失败保留可用缓存，模式继续保持 AUTO。
- **顺畅保存**：自动抓图尚未完成时也能先保存收藏；缺失的自动图标在后台继续获取，返回时再次检查当前模式和版本，避免覆盖后续编辑。
- **桌面同步**：修改名称、网址或图标后更新相同 UUID 的已固定快捷方式。桌面入口只携带书签 ID，点击时读取最新 URL。
- **浏览器登录状态**：优先使用 Chrome Custom Tabs，复用所选浏览器的正常配置和 Cookie；没有 Chrome 时尝试其他 Custom Tabs 浏览器及系统浏览器。
- **个性化外观**：Android 12+ 默认使用壁纸动态配色，也可选择翡翠青、深海蓝、落日琥珀、鸢尾紫、樱花粉五套完整明暗色板；支持跟随系统、浅色和深色模式，设置保存在本机并即时生效。
- **Material 3 界面**：紧凑列表与双列网格一键切换，卡片与编辑器预览根据图标柔和染色；使用标准字号层级、小型顶栏和紧凑预览，保留 48dp 点击区域、完整编辑页及错误/刷新反馈。150% 及以上字体下网格自动使用单列，设置仍保留。
- **隐私与资源限制**：只声明网络权限，无全盘/相册读取权限；文本、图像大小与分辨率均受限。

## 获取 APK

打开仓库的 **Actions → Android CI → 成功的运行记录 → Artifacts**：

- `WebShortcuts-debug`：解压后的 `app-debug.apk` 可直接侧载安装，适合功能体验。
- `WebShortcuts-release-unsigned`：启用 R8 的未签名发布包，必须使用自己的正式签名后分发。
- `verification-reports`、`device-test-reports`：单元测试、Lint 和设备测试结果。
- `device-screenshots`：Android 模拟器实际渲染的浅色、深色和编辑页截图，演示数据只存在于测试中。

每个构建的 debug 签名由 CI 环境生成，跨运行安装时可能需要先卸载旧 debug 包。卸载会删除本地收藏；正式长期使用请维护固定的发布签名。发布签名和密钥不会写入仓库。

## 将 APK 发布到版本标签

Android CI 的手动发布使用两个可选字段：`publish_tag` 和 `build_run_id`。留空时正常构建和测试；填写版本标签及已成功的 CI 运行 ID 时，只发布这次运行的产物，不重复构建。

先对目标 tag/分支运行 Android CI，等待构建、Lint 和 Android 35 设备测试成功，再使用对应 tag 与运行 ID 执行发布。任务会核对同仓库、精确提交 SHA、APK 版本、内嵌源码版本及测试报告；生成带来源运行 ID 的 APK、SHA-256 和构建信息，上传后重新下载校验，最后发布对应 Release。不会移动标签或覆盖同名附件。

排查设备测试失败时，可单独填写 `inspect_run_id` 读取该次运行的完整 JUnit 失败信息，不构建或发布应用。

发布包保留 CI 测试签名；它可能与本地交付包或另一次 CI 的签名不同。更换签名前先导出 ZIP 备份，卸载旧包后再安装并导入。R8 包仍为未签名版本。

## 本地构建

安装 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0，然后用 Android Studio 打开仓库，或配置 `ANDROID_HOME` / 未跟踪的 `local.properties`：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
# 启动 Android 模拟器或连接设备之后：
./gradlew :app:connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`。Gradle Wrapper 已包含在仓库中，无需手工安装 Gradle。

工具链固定为 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21、Compose BOM 2025.12.00。构建依赖从 Google Maven 和 Maven Central 获取。

`app/src/debug/.../ui/CompactUiPreviews.kt` 提供列表、网格、深色、图标编辑器、外观设置与 200% 字体预览。Android Studio 可直接渲染；安装 Android CLI 后也可运行 `android preview render :app --fqn=io.github.homura251.webshortcuts.ui.CompactUiPreviewsKt.CompactListPreview --component-tree`。这些预览仅使用本地演示数据。

## 使用方式

1. 新建网站，输入网址和名称。省略协议时默认补全 HTTPS；可显式输入 HTTP 来访问局域网站点。
2. 选择自动、自定义或字母生成图标，检查预览并保存。
3. 在收藏右侧菜单选择「添加到桌面」，按系统提示确认。
4. 后续编辑同一收藏并保存，应用会请求原地更新已有桌面图标；启动器何时重绘由设备决定。
5. 点击首页右上角的调色板打开「个性化外观」，调整配色、深浅色和布局；选择精选色板会关闭壁纸动态配色。首页统计区也可直接切换列表与网格。
6. 首页更多菜单打开「导入 / 导出」。导出完整备份用于迁移图标与收藏，导出 HTML 用于浏览器；两种格式均导出全部收藏，不受搜索筛选影响。系统文件选择器决定保存位置，无需存储权限。
7. 选择 ZIP 或 HTML 导入后，先检查新增、重复、无效条目和图像异常摘要。默认合并并保留本机重复项；全量替换需要再次确认。HTML 没有有效网站时禁止替换。恢复 ZIP 可勾选恢复外观；导入不会自动添加桌面入口或联网抓取图标，之后可手动刷新。

ZIP 使用格式版本 1，包含 `manifest.json`、现有 schemaVersion 3 的 `bookmarks.json`、`appearance.json` 与图像文件。最多 2000 条收藏，HTML/收藏 JSON 上限 4 MiB，ZIP 文件与解压总量各上限 256 MiB。备份不加密，包含网址与图片，请妥善保存。缓存中已缺失的图像会明确提示并从备份引用中移除；原图存在时继续保留原图和裁切参数。文件损坏、版本不支持或导入失败时保留现有收藏。

自动获取图片不会获得 Chrome 的 Cookie；仅登录后可见的图标可能无法下载，此时可使用自定义图片。浏览器登录态由浏览器、用户配置和网站自身策略管理，应用不能保证所有网站永久保持登录。

从早期构建更新后，请在首页菜单选择「刷新所有自动图标」，以重新下载原图并应用新的尺寸处理；未保留原图的旧自定义图片需要重新导入一次，之后即可重复编辑。已缓存的小图无法恢复丢失的细节。透明或纯色外边距会在适配时去除，Manifest 的 maskable 图标会使用其现有安全区；圆形/圆角预览显示桌面的实际可见区域，最终外形和刷新时机仍由启动器决定。

## 工程说明

```text
app/src/main/java/io/github/homura251/webshortcuts/
  data/       本地元数据、格式迁移、URL 校验
  icons/      网站图标发现、受限解码、私有图片存储、最终图标解析
  shortcuts/  固定/更新快捷方式、UUID 路由、Custom Tabs
  transfer/   ZIP/HTML 文件格式、导入预检、事务与文件选择器状态
  ui/         Material 3 界面与主题
  MainViewModel.kt  编辑事务、异步任务与批量刷新协调
```

具体要求、边界和验收步骤见：

- [原始 v1.1 PRD](docs/PRD-v1.1.zh-CN.md)
- [架构与开发说明](docs/ARCHITECTURE.md)
- [验收清单](docs/ACCEPTANCE.md)
- [隐私说明](docs/PRIVACY.md)

当前仓库从空工程开始实现，未经过应用商店发布或 OEM 真机验收。请以 Actions 的实际运行结果区分「已有实现」与「已经验证」。
