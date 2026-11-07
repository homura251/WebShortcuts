# 架构与实现约束

## 数据与事务

`BookmarkItem.id` 是不可变 UUID，同时作为桌面快捷方式 ID。`BookmarkRepository` 使用私有 `bookmarks.json`，通过 Android `AtomicFile` 写入。磁盘提交成功后才发布新的 `StateFlow`，文件损坏时保留原文件并阻止写入，不把读取失败当作空列表。

JSON schemaVersion 为 4，读取兼容旧列表格式和 v1–3 字段；旧数据默认没有标签，读取不改写文件。模型保存图标/私有原图的相对路径、归一化裁切坐标和来源元数据，不把图片写入 JSON 或 SharedPreferences。主应用、透明路由 Activity 使用同一个 Application 容器中的 Repository。

保存顺序为：规范化输入 → 写入新版本图片 → 原子写入元数据 → 解析最终图标并请求桌面更新 → 清理不再引用的图片。应用级 Mutex 将文件写入、元数据和清理串行化；桌面更新失败不回滚已经保存的收藏。

## 图标与并发

- AUTO、CUSTOM、GENERATED 各自独立；切换模式不会删除另一个模式的有效图片。
- URL 变化会使旧网站的自动图片失效，自定义图片继续保留。
- 临时导入图片保存在私有草稿缓存，用户点击保存前不改变书签；文字、原图、预览及裁切坐标可随保存状态恢复。先写入恢复快照再发布编辑完成状态，确保收到完成通知后立即保存或重建也能读取一致坐标。预览丢失但原图仍在时自动重建；新原图草稿丢失时阻止误用旧原图覆盖收藏。
- 自定义原图先校正 EXIF 并限制最长边为 2048 像素，不含桌面留白。保存后与 512 像素最终图标分开版本化存储；再次编辑从原图和坐标生成，不叠加旧裁切。原图 PNG/草稿读写最多 20 MiB，删除和清理保留原子文件恢复族。自由比例、像素 1:1、移动/四角调整及可访问滑块共享同一有界坐标模型。
- 网络请求可取消，有连接、读取及整个发现流程的时间上限。
- 编辑器检查请求的书签、模式和 URL，防止迟到的图片覆盖新输入。
- 批量刷新在网络返回后重新查询当前元数据。如果用户已修改模式、URL 或图片版本，跳过该结果。
- 每个收藏有独立刷新状态并防止重复请求；批量显示完成数/总数与结果摘要。图标落盘成功后，即使已固定桌面拒绝更新也反馈为“桌面同步待重试”，不误报为下载失败。失败不会覆盖来源 URL 和最近成功获取时间；界面时间指图标获取成功，不代表启动器重绘。
- 保存后的图标补全在当前 ViewModel 生命周期内异步执行。进程或页面被结束时任务可被取消，不承诺退出应用后持续下载；收藏仍保持 AUTO 并使用可用缓存或生成图标，之后可手动重新抓取。
- 图标请求先按矢量/声明分辨率安排有界预算，下载后按真实源像素比较；ICO 以实际解码成功的帧为准，SVG 视为可缩放。找到 512 像素原图或 SVG 才提前结束，否则保留最好回退；总截止时间耗尽也不会丢弃已获得的图片。来源标签显示原图尺寸，而不是统一输出尺寸。
- HTML 声明之外，以最终跳转页的 origin 尝试标准 Apple Touch Icon、precomposed 和 favicon；三条根路径保留请求预算，声明与根路径按 URL 去重，总候选数最多 12。
- 所有图标收敛为 512×512 adaptive layer。透明图片或四边完全相同的纯色底图片按实际主体像素半径映射到 66/108 安全圆，去除重复外边距；不透明复杂边缘图片仍按整张矩形对角线适配，保留角落细节。Manifest maskable 的 80% 安全圆只映射一次，避免重复缩小。预览显示中心 72/108 可见区域，和桌面使用同一 layer。图片缺失或损坏时始终可生成替代图标。
- Full Canvas 预览显示完整 layer，桌面预览只显示其中心可见区域；安全区蒙层、圆形/圆角虚线及网格均为 UI 绘制，不会写入保存图片。

网络仅请求 HTTP(S)。HTML/Manifest 至多 512 KiB，HTML 读到 head 结束即停止；单个图片至多 5 MiB。解码前检查签名、MIME 和尺寸，拒绝边长超过 4096 的图片。SVG 拒绝外部引用、脚本、DOCTYPE/实体等不安全内容，并限制结构复杂度。ICO 目录和帧数据先检查边界，再按分辨率尝试解码。

## 桌面与浏览器

固定与更新都使用稳定 UUID 和显式 `ShortcutRouterActivity` Intent，Intent 中不保存 URL。透明路由按 UUID 读取最新 URL，再进入 `ChromeTabLauncher`。删除收藏会禁用对应的系统快捷方式；即使设备没有立即禁用，路由也不会打开被删除的旧 URL。

`requestPinShortcut()` 返回 true 表示系统接受请求，仍需用户在启动器中确认。`updateShortcuts()` 返回 true 表示平台接受更新，并不能保证 OEM 启动器已经立即重绘；限流或拒绝时提供重试入口。

优先选择 Chrome 的 Custom Tabs 提供者，否则使用其他提供者，最后退回可处理网址的系统浏览器。不创建 WebView，也不使用隔离的临时浏览会话。应用无权读取浏览器 Cookie。

## 外观状态与颜色

- `ui/theme/ThemePreferences` 将主题模式、动态配色开关、精选色板和首页布局写入独立的 `appearance` SharedPreferences，通过 StateFlow 在 Activity 中收集；不参与书签 JSON、图片存储或编辑草稿事务。
- 五套明暗色板由 Material Color Utilities 0.3.0 的 HCT / `SchemeTonalSpot`（标准对比度）生成静态令牌，包含五级表面容器；应用没有新增依赖。Android 12+ 开启动态配色时使用系统方案，较旧系统回退至已选色板。
- 图标色调只在 UI 层后台采样，每张图最多读取 24×24 个像素，忽略透明和近中性色；不修改、不回收调用方 Bitmap，不建立无界图片缓存。卡片、徽章和预览以 16% 为目标染色，并在需要时降低比例，保证正文色的对比度至少 4.5:1。
- 首页的列表和双列网格共用有稳定 key 的 LazyVerticalGrid；统计、搜索、空状态和刷新反馈跨越所有列，无刷新时不生成占位行。字体缩放达到 150% 后自动使用单列卡片，不改变已保存的布局偏好。
- 字体直接使用 Material 3 `Typography()`：页面标题 `titleLarge`（22/28sp）、收藏与预览标题 `titleMedium`（16/24sp）、分区标题 `titleSmall`（14/20sp）、正文 `bodyMedium`（14/20sp）、辅助文字 `bodySmall` / `labelMedium`（12/16sp）；输入框和交互标签保留组件默认字级。保留系统字体缩放，不通过压小字或禁用最小触控区域换取密度。
- 小型顶栏使用 64dp 标准高度；首页边距 16dp、项目间距 8dp，列表卡片内纵向边距 8dp。编辑器预览选项按行换行，裁切尺寸和位置各为一对滑块；按钮、芯片和图标操作保留至少 48dp 触控区域，内容随字体增长并可滚动。URL 提示仅在聚焦或错误时显示，字段错误始终保留。
- `CompactUiPreviews` 仅进入 debug 变体，覆盖 360dp 列表/网格/深色/编辑器、320dp 与 200% 字体、外观设置及裁切。`EditorWindow` 在 Layoutlib 中直接渲染内容，运行时仍使用原生 Dialog 和相同返回/放弃编辑逻辑。
- `ThemePreferencesTest`、`PaletteContrastTest`、`IconAccentTest` 验证偏好恢复、色彩对比度和采样边界；`AppearanceInteractionTest` 验证外观面板、双列布局与小屏首屏操作。

## 多标签分组

- `LibrarySnapshot(bookmarks, tags)` 是 Repository 唯一的状态源，收藏与标签目录在同一个 AtomicFile 中提交。`BookmarkItem.tagIds` 引用稳定的标签 ID；目录允许空标签，最多 2000 项，名称去首尾空白、1–40 字符、忽略大小写唯一，元数据总量仍限 4 MiB。旧 `bookmarks` 只读投影始终读取同一快照。
- `LibraryTags.filter` 统一首页与批量全选的范围：名称/网址/标签名称的普通搜索，再与所有选中标签取交集；「无标签」是独立虚拟筛选，不写入目录。新建收藏继承当前选中标签，多选时固定搜索和筛选。
- 编辑器、单项和批量标签面板保存新增/移除差量；持有应用锁后应用于最新记录，不覆盖并发添加的标签、迟到抓图或其他字段。新标签名称先暂存，保存时匹配最新目录；取消不创建目录。删除标签只移除关联；纯标签操作不改变图像版本或请求桌面更新。
- 查询、筛选、多选、标签面板与编辑草稿保存在 SavedStateHandle，旋转或进程恢复后剔除不存在的 ID；冷启动显示全部收藏。界面临时状态不进入备份。卡片没有标签时不增加行，有标签最多显示一行及「+N」，触控区域保留 48dp。
- ZIP 封装版本保持 1，收藏 JSON 使用 schema 4，包含空标签及关联。标签合并优先匹配 ID（保留本机名称），其次匹配忽略大小写的名称；重复收藏取标签并集。新增标签及补充标签的收藏分别计数，重复导入保持幂等。替换同时替换目录；不含标签的旧 ZIP 或 HTML 替换会清除标签，确认时明确提示。HTML 不读取文件夹或 TAGS 作为标签，也不导出标签。

## 手动导入导出

- `transfer/TransferManager` 管理系统文档 URI、私有准备缓存和批量事务；`TransferViewModel` 只保存缓存 token、阶段及用户选项，不把图像或整份备份放入 SavedStateHandle。预览/提交状态发布前先更新恢复状态；旋转保留操作，进程中断仅恢复已完整准备的预览或导出文件。
- ZIP 清单标识 `format=WebShortcutsBackup`、`formatVersion=1`、应用版本、导出时间和缺失图像数量。收藏数据仍使用 BookmarkCodec schema 4；外观使用枚举名称和动态色开关。只接收清单、收藏、外观及被引用的安全图像路径，校验实际 CRC、条目数、单文件尺寸与总量；不把归档路径直接写到应用数据目录。
- HTML 使用现有 jsoup 的流式解析器读取 Netscape 书签，解码 BOM/声明字符集，不执行脚本、不加载资源；按规范化完整 URL 去重，保留路径、查询与 fragment。重复项取文件首次出现的有效记录，文件夹平铺。空名称回退域名，无效协议/名称计数跳过，名称上限与存储、编辑统一为 160 字符。
- ZIP 按 ID 去重，HTML 按 URL 去重；合并保留本机记录，仅 ZIP 为重复收藏补充标签，将新增项按文件顺序置顶，替换按导入顺序。图片恢复使用全新私有路径并保留已处理的 512px layer 原始字节，原图最多 2048px，不重复适配安全区。图片准备成功后通过 `replaceAll` 一次提交元数据，再清理未引用文件；失败保持原元数据和其引用图像。
- `TransferGate` 的会话租约暂停收藏修改，并递增进程级代际。刷新在请求前与写入锁内检查代际，防止同 ID/URL/版本的旧请求在恢复后覆盖图片。导出快照与图片读取共用应用级 mutationMutex，外部文档写入发生在快照准备后。
- 外观使用 AppContainer 中的同一实例，在收藏提交后按选项同步落盘并更新 UI，失败单独反馈。提交后只协调已存在的桌面快捷方式：保留的恢复并更新，移除的停用，新设备不会自动固定。启动器拒绝不回滚收藏。
- 上限：收藏 2000 条；HTML/收藏 JSON 4 MiB；图标 5 MiB、原图 20 MiB；ZIP 压缩文件与实际解压总量各 256 MiB。已有收藏读取失败时继续执行现有原文件保护，不把错误当空列表导出或导入。

## 构建与验证

- Gradle Wrapper 8.13 的脚本和 JAR 来自 Gradle 官方 v8.13.0 标签。
- JDK 17 / AGP 8.13.2 / Kotlin 2.2.21 / compileSdk & targetSdk 36 / minSdk 26。
- CI 执行 debug 构建、JVM/Robolectric 测试、Lint、R8 release 构建与 Android 35 仪器测试。
- PR 不分发密钥、不自动发布商店版本，也不自动合并。

官方参考：

- [AGP 8.13 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [创建快捷方式](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts)
- [管理与更新快捷方式](https://developer.android.com/develop/ui/compose/system/shortcuts/managing-shortcuts)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photopicker)
