# YTV

纯播放 IPTV 客户端（Android / Android TV），包名 `com.blyen.ytv`。

启动后从远程 M3U 加载频道列表并起播，聚焦换台、换源与稳定播放，不含设置页、用户验证、应用内更新等业务。

## 功能

- **远程频道列表**：默认拉取  
  `https://sub.blyen.ccwu.cc/channels.txt`  
  失败时使用上次成功写入的本地缓存
- **Media3 ExoPlayer** 播放（HLS / HTTP 等常见直播流）
- **硬解优先**，降低「有声无画」概率
- **频道菜单**：分组 / 列表选台
- **多源线路**：同一频道多 URL 时支持切换（确认键连按等）
- **Leanback**：支持 Android TV 启动入口

## 技术栈

| 项 | 说明 |
|----|------|
| 语言 | Kotlin |
| 最低 / 目标 SDK | minSdk 23 · targetSdk 35 · compileSdk 35 |
| 播放 | AndroidX Media3 ExoPlayer 1.5.x |
| 网络 | OkHttp |
| 构建 | Gradle (Kotlin DSL) |
| 版本 | 0.1（versionCode 10） |

## 架构概览

```text
MainActivity
  └─ MainViewModel          # 拉 M3U、解析频道、触发起播
       └─ PlayerFragment    # ExoPlayer / 超时与换源恢复
  ├─ MenuFragment           # 分组 + 频道列表
  └─ SourceSelectFragment   # 多线路选择
```

源码包路径：`app/src/main/java/com/blyen/ytv/`  
子包：`data` · `models` · `requests`

## 构建

环境要求：JDK 17、Android SDK。

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

Debug APK 输出：

```text
app/build/outputs/apk/debug/ytv_v0.1.apk
```

Release 需自备签名文件（仓库不包含 keystore），并可通过 Gradle 属性配置：

- `YTV_STORE_PASSWORD`
- `YTV_KEY_ALIAS`
- `YTV_KEY_PASSWORD`

## 安装

```bash
adb uninstall com.blyen.ytv   # 签名不一致时需先卸载
adb push app/build/outputs/apk/debug/ytv_v0.1.apk /data/local/tmp/ytv_debug.apk
adb shell pm install -r -t /data/local/tmp/ytv_debug.apk
adb shell am start -n com.blyen.ytv/.MainActivity
```

## 频道列表

| 来源 | 说明 |
|------|------|
| 远程 | `https://sub.blyen.ccwu.cc/channels.txt`（GitHub Pages 自定义域名） |
| 缓存 | 应用私有目录 `channels_remote_cache.txt` |
| 内置 raw | 已移除；无网且无缓存时无频道可播 |

更新频道：修改托管仓库中的 `channels.txt` 并推送后，重新打开 App 即可（有短超时；失败则用缓存）。

## 常用操作

| 操作 | 行为 |
|------|------|
| MENU / 设置键 | 打开频道菜单 |
| 确认 ×1（延迟） | 菜单 |
| 确认 ×4 | 多源选择 |
| 右键 | 换源 |
| ▲▼ | 换台 |

## 说明

- `PLAYBACK_ONLY=true`：构建期写死，起播逻辑按纯播放精简
- 不含 WebView 播放栈、设置大 UI、源加密编码
- 远程列表为公开 URL，请勿在 M3U 中写入账号密码等敏感信息

## License

私有/未声明。若开源请自行补充许可证文件。
