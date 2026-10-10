# MaaFwApp

MaaFramework 的 Android GUI

[![License](https://img.shields.io/github/license/Aliothmoon/MaaFwApp?style=flat-square&color=4a90d9)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Aliothmoon/MaaFwApp)
[![API](https://img.shields.io/badge/minSdk-28-green?style=flat-square)](https://developer.android.com/google/play/requirements/target-sdk)
[![Commit Activity](https://img.shields.io/github/commit-activity/m/Aliothmoon/MaaFwApp?style=flat-square&color=00d4aa)](https://github.com/Aliothmoon/MaaFwApp/commits)
[![Stars](https://img.shields.io/github/stars/Aliothmoon/MaaFwApp?style=flat-square&color=ffca28)](https://github.com/Aliothmoon/MaaFwApp/stargazers)

## 项目简介

MaaFwApp 是基于 [MaaFramework](https://github.com/MaaXYZ/MaaFramework) 的 Android 通用 GUI。资源开发者通过 [Project Interface V2](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md) 描述任务、选项和界面文本，用户即可在手机上配置并运行自动化任务。

MaaFramework 跑在 [Shizuku](https://shizuku.rikka.app/) 或 root 拉起的特权进程里，截屏和点击走本机 native controller，不经过 adb。

MaaFwApp 本身不包含具体业务资源。Android 上要把资源在**构建期**打进 APK。接入步骤见 [资源接入](INTEGRATION.md)。

## 主要功能

### 任务与资源

- 支持 Project Interface V2 的任务、选项（选择 / 开关 / 多选 / 输入）、预设、分组、`import`、`global_option` 与国际化。
- 任务说明支持 Markdown、文件路径和 http(s) 链接；首启欢迎、关于页的联系方式 / 许可 / 仓库地址取自 PI 顶层字段。
- 可同时保存多份运行配置，配置之间互相独立；同一配置里允许重复添加同一任务。
- 支持 PI 声明的 agent（自定义识别器 / 动作）。

### 运行与设备

- 后台模式：在虚拟屏上跑任务，手机可正常使用，分辨率可选 720P / 1080P。
- 前台模式：直接操作当前屏幕；可用悬浮球或音量键呼出操作面板。
- 后台运行时可自动熄屏挂机；定时触发支持亮屏并输入数字 PIN。
- 运行中在系统通知里显示进度。

### 定时、通知与诊断

- 支持按规则定时执行，可绑定到指定运行配置。
- 任务结束可发系统通知，也可推到 Server酱、Telegram、Discord、钉钉、KOOK、SMTP、Bark、Qmsg、Gotify 和自定义 Webhook。
- 每次运行的日志可回看、导出；另有应用错误日志。
- 资源可在 PI 里配置 Sentry 遥测；只有资源提供了配置、且用户未关闭「帮助改进本项目」时才会发送。

## 运行要求

| 项目 | 要求 |
|:---|:---|
| 系统 | Android 9（API 28）及以上 |
| 提权 | [Shizuku](https://shizuku.rikka.app/) 或 root，二选一，可在应用内切换 |
| 资源 | 一份符合 Project Interface V2 的资源项目（已打进当前 APK） |

不同资源可能还要求目标应用已安装、通知权限、电池白名单等，以对应资源项目的说明为准。没连上特权进程时只能看界面，不能跑任务。

## 快速开始

### 使用已打包的应用

1. 安装资源项目提供的 APK。
2. 按提示授予通知、电池白名单等权限。
3. 在应用里选择 Shizuku 或 root，完成授权并等到服务连上。
4. 选择服务器（资源）、核对任务列表，开始运行。

### 接入自己的资源项目

1. 先按 [Project Interface V2 协议](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md) 写好 `interface.json` 和资源。开发和排查 Pipeline 请用 MaaFramework 提供的调试工具，不要把本应用当调试器。
2. 拷贝 [`pi-profile.sample.yaml`](pi-profile.sample.yaml)，填资源路径和包名，放到本仓库之外。
3. 在 `local.properties` 里写 `pi.profile=<配方的绝对路径>`（或设环境变量 `PI_PROFILE`）。
4. 拉 MaaFramework 的 Android 产物并出包，详见 [资源接入](INTEGRATION.md)。

换一份资源 = 换一份配方 + 重新出包，不必改本仓库的代码。

## 资源开发

- [Project Interface V2 协议](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md)
- [MaaPracticeBoilerplate](https://github.com/MaaXYZ/MaaPracticeBoilerplate)
- [把资源打进 MaaFwApp](INTEGRATION.md)

## 从源码构建

需要 JDK 17、Android SDK、Python 3。构建用 git 计算版本号：独立 checkout 跟随本仓库，作为 submodule 时跟随最外层主仓库。请在 clone 下来的仓库里执行：

```bash
git clone https://github.com/Aliothmoon/MaaFwApp.git
cd MaaFwApp
python scripts/setup_maa_framework.py
./gradlew :app:installDebug          # Windows 用 .\gradlew.bat
```

未配置 `pi.profile` 时构建不会失败，只是包里没有资源。Release 签名读 `KEYSTORE_PATH` 等环境变量或 `local.properties`；缺了就打出未签名包。

## MAH 分支的版本与发布

MAH 主仓库负责组装 UIApp、MAH 项目和资源，构建 APK 与 Android 项目 ZIP，并把它们发布到 MAH Release。推送 `v*` 标签时，由 `install.yml` 调用 `android-apk.yml` 构建 APK；普通代码推送不触发 APK 构建，仍可手动运行工作流。当前工作流沿用 Debug APK 构建方式。

UIApp 推送 `v*` 标签只生成 Release 公告，不构建 APK、不上传安装包或项目包，也不拉取 MAH 与 mah_res。UIApp 标签是内部校验版本，不替换 APK 的 MAH 版本或界面显示版本。发布新 UIApp 后，需要由 MAH 主仓库构建并发布包含该代码的 APK，用户才能安装。

- “检查项目更新”先读取 UIApp 公告 tag，与 APK 内的 UIApp 版本比较。相同时继续检查 MAH 项目 ZIP；不同时从 MAH 更新源检查 APK，APK 与本机版本不同则提示安装并停止。
- UIApp 公告或 APK 检查失败会继续后续检查。手动检查保留失败提示，启动检查静默继续。
- 启动自动检查顺序为 APK → 资源 → MAH 项目，发现可用更新即停止。资源也可以独立检查。
- UIApp、MAH APK 和项目 ZIP 使用同一正式版／公测版渠道。GitHub 正式版取 `/releases/latest`，公测版取 Release 列表中最新的有效版本（含预发布）。比较 tag 是否相同，不按版本号大小阻止切换，也不跳过没有附件的最新 Release 去找旧包。资源更新沿用资源仓库的独立规则。
- APK 下载和项目包下载均来自 MAH，资源来自 mah_res。更新源选择和 CDK 设置继续生效；UIApp 内部版本公告通过 GitHub 查询。
- APK 变更后的首次启动会同步版本不同的内置 MAH 项目，包括版本号较低的项目；保留配置、作战列表和独立资源。相同 APK 再次启动会保留之后安装的项目 ZIP。

构建脚本的 `--version` 指定 MAH 版本；UIApp 内部版本默认取当前 UIApp checkout 可达的最近一个 `v*` 标签，无标签时记为 `0.0.0`，也可显式指定：

```bash
python scripts/build_mah.py --mah ../MAH --resources ../mah_res --version v2.0.1-alpha6 --uiapp-version v1.0.0-alpha1
```

APK 的 `versionName` 使用 MAH 版本，`MAFW_APP_VERSION` 使用 UIApp 内部版本，安装用 `versionCode` 沿用原来的 Git 计算规则。初始化标记同时记录安装编号与 MAH 版本，保证仅项目版本变化时也能同步内置项目。概况和关于页显示当前安装的 MAH 项目版本，资源版本单独显示。

UIApp 公告工作流不使用签名密钥。MAH 主仓库的云端 Debug APK 使用固定签名，在 MAH 仓库的 Settings → Secrets and variables → Actions 中配置以下 Repository secrets：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 密钥库文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名条目的别名 |
| `ANDROID_KEY_PASSWORD` | 签名条目的密码 |

主发布流程会将这四项传给 APK 工作流。工作流还原临时密钥文件，通过 `MAH_CI_DEBUG_SIGNING=true` 开启固定 Debug 签名，并传入 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。缺少 Secrets 时停止构建；出包后核对 APK 证书与密钥库证书一致，再上传产物。临时密钥文件在清理步骤中删除。本地 Debug 沿用自动调试签名，无需配置本地密钥。

已有 MAH 安装中的旧 `software_github`（指向 MAH-UIApp）会迁回 MAH，其他自定义更新源保持不变。

## 相关项目

- [MaaFramework](https://github.com/MaaXYZ/MaaFramework) — 基于图像识别的自动化框架

## 开源许可

本项目基于 [GNU Affero General Public License v3.0](LICENSE) 开源。

## 致谢

MaaFwApp 使用了 [MaaFramework](https://github.com/MaaXYZ/MaaFramework)、[Shizuku](https://github.com/RikkaApps/Shizuku) 等开源项目。
