# Enco Free4 Control

无需 Root 的 **OPPO Enco Free4 / Enco X4** Android 控制面板与桌面小部件。

An unofficial Android controller for OPPO Enco Free4 and Enco X4: noise control, battery status, media volume and glass-style home screen widgets.

[下载 2.0.0 APK](https://github.com/uninner/EncoFree4-Control/releases/latest) · [更新记录](CHANGELOG.md) · [隐私说明](PRIVACY.md) · [协议笔记](docs/PROTOCOL.md)

---

## 🙏 致原作者

**本仓库是 [@uninner](https://github.com/uninner) 的 [EncoFree4-Control](https://github.com/uninner/EncoFree4-Control) 的衍生适配版。**

原作者以极其严谨的工程方式，**从零逆向出 OPPO Enco 系列的经典蓝牙 SPP 传输层、TLV 帧格式、命令号与降噪模式位图**，并将其整理成可读的协议笔记；在此基础上完成了底部控制面板、玻璃质感界面、快捷设置磁贴与桌面小部件，还搭建了无需模拟器的纯 JVM 测试与 CI 流水线。

没有原作者这份慷慨开源的高质量工作，本次对 OPPO Enco X4 的适配将完全无从谈起。**在此向原作者致以最诚挚、最隆重的感谢。**

---

## 功能

- 控制中心快捷设置磁贴：点按打开底部控制面板。
- 降噪、通透和关闭模式；收到耳机确认后更新选中状态。
- 左右耳电量；耳机均在盒内且协议返回数据时显示充电盒电量。
- 白色媒体音量条，与手机音量键同步。
- 2×2 / 2×1 固定尺寸玻璃小部件：连接状态、电量、降噪与通透切换。
- 240ms 滑块动画；具体流畅度取决于设备、系统与桌面启动器。
- 主面板采用局部轻磨砂；系统关闭模糊时自动加强底色，保持文字可读。
- 本地蓝牙通信，无账号、广告、统计 SDK 或网络权限。

## OPPO Enco X4 适配说明

本分支在原作者 2.0.0 的基础上增加了 **OPPO Enco X4** 支持，Enco Free4 的原有功能保持不变。

### 适配了什么

| 项目 | 说明 |
|---|---|
| **型号识别** | 新增 `EncoModels`，集中管理支持的机型（`encofree4` / `encox4`），替换原先分散在主面板与小部件服务中的硬编码名称判断 |
| **传输层** | 完全复用。实测 X4 使用相同的 SPP 服务 UUID、TLV 帧格式、序列号与通知注册集，无需改动 |
| **自适应模式** | X4 的「自适应」是**独立主模式**（状态位图 bit 12），与降噪 / 通透 / 关闭同级；写入时该字段需为 **2 字节小端位图** `01 01 00 10`，单字节枚举无法表达 |
| **降噪子档** | X4 的降噪分四档：深度（实时计算）/ 中度 / 轻度 / 自适应，分别对应状态位图 **bit 4 ~ bit 7**。面板上**长按「降噪」按钮**循环切换 |
| **状态显示** | 按层级显示当前状态：`当前 · 降噪 · 深度降噪（实时计算）`、`当前 · 降噪 · 中度降噪`、`当前 · 自适应`、`当前 · 通透`、`当前 · 关闭` |
| **界面** | 主模式按钮由 3 个扩展为 4 个：降噪 / 自适应 / 通透 / 关闭 |
| **型号图片** | 按识别到的型号自动切换耳机图；X4 提供白 / 紫灰 / 粉三种配色，通过耳机图左上角色块切换并记住选择 |
| **兼容处理** | 连接 Free4 时自动隐藏「自适应」按钮（该模式在 Free4 上的位编号未经真机验证） |
| **探针日志** | 帧收发日志改为按构建类型开关（debug 开启 / release 关闭），便于后续适配新机型 |

### 真机验证

- **手机**：小米 17 Ultra（2512BPNDAC）/ HyperOS 4.0 / Android 17
- **耳机**：OPPO Enco X4，固件 `116.116.100`
- **已验证**：连接与重连、左右耳与充电盒电量、降噪 / 通透 / 关闭切换、自适应切换、四档降噪子档切换、模式变更主动通知、快捷设置磁贴、2×2 与 2×1 桌面小部件

### 已知限制

- **稳定性仍需长期测试**：目前仅在单台设备上完成验证
- 耳机颜色无法通过蓝牙协议识别，需手动切换
- Free4 的降噪子档位编号与 X4 不同（原协议笔记记录为 index 5/6/7/11），长按切换子档在 Free4 上可能不准确
- 桌面小部件暂不随型号切换耳机图片

### 协议记录

本次新增的协议发现已补充进 [`docs/PROTOCOL.md`](docs/PROTOCOL.md) 的 **Enco X4** 章节。

## 预览

<img src="docs/images/panel.png" width="230" alt="底部控制面板" /> <img src="docs/images/widget-2x2.png" width="300" alt="2×2 桌面小部件" />

<img src="docs/images/widget-2x1.png" width="420" alt="2×1 桌面小部件" />

以上为程序渲染预览；实际玻璃效果、模糊与桌面占位由系统、壁纸和启动器共同决定。

## 安装与使用

要求 **Android 14 或更新版本**。目前支持 OPPO Enco Free4 与 OPPO Enco X4（固件 116.116.100 实测通过），其他耳机型号未验证。

1. 在 [Releases](https://github.com/uninner/EncoFree4-Control/releases) 下载 APK 并安装。
2. 在系统蓝牙设置中配对并连接耳机。
3. 下拉控制中心，编辑快捷设置磁贴，添加 **Enco Free4**。
4. 点按磁贴打开面板，并允许“附近的设备”权限。
5. 如需桌面控制，长按桌面进入小部件列表，添加 **Enco Free4 · 2×2** 或 **2×1**。

本应用通过快捷设置磁贴和小部件进入，没有独立的桌面启动图标。

点击主面板右上角状态标签不会切换连接；连接异常时可从底部“连接帮助”处理。小部件中，断开状态可点按重连；点按电量可刷新。

官方 Release 沿用固定签名，支持覆盖升级。自行编译的调试版使用不同签名，不能用于覆盖官方版。发布签名的 SHA-256 指纹：

```text
f8beea137cbe95daa6fa3844397f08c642e62ed3860a0fb460a6d86783db9b9c
```

小部件后台同步使用 Android 的 connected-device 前台服务。应用不申请通知权限；系统仍可能在“运行中应用”中显示其后台活动。不同系统的后台限制可能影响同步。

## 从源码编译

工具链：JDK 17、Android SDK 34、Build Tools 35.0.0；Gradle Wrapper 8.13，Android Gradle Plugin 8.7.3。

用 Android Studio 打开项目并配置 SDK；命令行也可通过 ANDROID_HOME 或未提交的 local.properties 指定 SDK。

```bash
# macOS / Linux
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug

# Windows PowerShell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

调试 APK 位于 app/build/outputs/apk/debug/app-debug.apk。未配置签名时，assembleRelease 生成未签名的 release APK。

项目保留了国内 Maven 镜像和官方仓库配置。首次构建需要下载 Android/Gradle/测试依赖；网络受限时请检查仓库可达性。

公开仓库和 CI 均不包含发布私钥。维护者在本机发布时，通过环境变量 ENCO_KEYSTORE、ENCO_STORE_PASSWORD、ENCO_KEY_PASSWORD、ENCO_KEY_ALIAS 提供原签名信息。

## 测试与贡献

2.0.0 在本地完成 24 组原生回归测试、lint 与 APK 签名核验。与 1.10.1 的 8 张渲染图比较完全一致。协议、连接生命周期和共享会话还提供独立测试，见 [tools/README.md](tools/README.md)。自动化测试不替代真实耳机与桌面测试。

欢迎提交 Issue 和 Pull Request。反馈连接或界面问题时，请注明手机型号、Android/系统版本、桌面启动器、耳机固件和复现步骤。请遮挡蓝牙地址、设备标识、通知与个人信息。

修改协议或连接逻辑时请补充测试；调整桌面布局前请同时检查两个规格的占位和点击区域。

## 许可证与声明

本项目为非官方社区工具，与 OPPO、HeyMelody、Xiaomi 等品牌无隶属或背书关系。

原创代码与文档采用 [MIT License](LICENSE)。第三方商标、设备图片与 Gradle Wrapper 等内容的许可范围见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 致谢

**再次向原作者 [@uninner](https://github.com/uninner) 致以最隆重的感谢。**

本次适配的全部工作，都建立在原作者打好的基础之上：

- **蓝牙协议逆向**：`docs/PROTOCOL.md` 中记录的 SPP 服务 UUID、TLV 帧格式、命令号与位图语义，全部来自原作者的工作。本次只是沿着这份笔记，在其未覆盖的机型上继续验证
- **工程结构**：协议解析、连接控制、共享会话的分层设计，以及无需真机与模拟器的纯 JVM 测试方案，均由原作者搭建
- **界面与交互**：底部控制面板、玻璃质感、快捷设置磁贴与桌面小部件，均为原作者原创设计

原作者的严谨与克制（不打印蓝牙地址、协议文档不收录原始抓包、测试可复现），让这次衍生适配得以在一个干净、可信的代码库上进行。

**感谢原作者的开源与分享。**
