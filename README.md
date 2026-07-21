# YTV

<div align="center">
  <img src="y.png" alt="YTV Logo" width="120">
</div>

>🎬 **YTV** 是一个开箱即用的,纯播放 IPTV 客户端（Android / Android TV）。启动后从远程 M3U 加载频道列表并起播，聚焦换台、换源与稳定播放。

---
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
| 版本 | 0.2（versionCode 20） |

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

## 下载

Release 预编译包：

- **[ytv_v0.2.apk](https://github.com/blyenso-del/ytv/releases/download/v0.2/ytv_v0.2.apk)**
- 发布页：https://github.com/blyenso-del/ytv/releases/tag/v0.2

## 安装

```bash
adb uninstall com.blyen.ytv   # 签名不一致时需先卸载
adb push ytv_v0.2.apk /data/local/tmp/ytv_release.apk
adb shell pm install -r -t /data/local/tmp/ytv_release.apk
adb shell am start -n com.blyen.ytv/.MainActivity
```

## 频道列表

| 来源 | 说明 |
|------|------|
| 远程 | `https://sub.blyen.ccwu.cc/channels.txt`
| 缓存 | 应用私有目录 `channels_remote_cache.txt` |

更新频道：修改托管仓库中的 `channels.txt` 并推送后，重新打开 App 即可（有短超时；失败则用缓存）。

## 常用操作

| 操作 | 行为 |
|------|------|
| MENU / 设置键 | 打开频道菜单 |
| 确认 ×1（延迟） | 菜单 |
| 确认 ×4 | 多源选择 |
| 右键 | 换源 |
| ▲▼ | 换台 |


## License

[MIT](LICENSE) © 2026 blyen
