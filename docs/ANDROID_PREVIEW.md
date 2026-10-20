# Android 页面预览与模拟

本轮页面设计见[移动端优化](superpowers/specs/2026-10-19-mobile-ui-polish-design.md)。当前使用原生 Android Emulator 运行 APK，截图来自实际控件和合成记录；不使用网页仿真界面代替 Android 验收。

## 已准备的环境

开发机已安装 Android Emulator 37.2.12、Android 16 / API 36 Google APIs ARM64 镜像。专用 AVD 名称为 `trace_life_preview`，设备配置为 Pixel 8，1080 × 2400 像素、420 dpi。首次运行的设备序号为 `emulator-5554`，以 `adb devices -l` 的实际结果为准。

AVD、调试签名、合成记录与截图保存在仓库之外。该模拟器与实体手机相互独立；最初页面预览未配置真实 DeepSeek 密钥；后续若进行真实合成资料验证，调用次数、资料范围和凭据清理分别记录在开发验证中。

## 本机启动与安装

本机已有该 AVD，无需重复创建。在终端启动模拟器，也可以在 Android Studio 的 Device Manager 选择同名设备：

```bash
TRACE_LIFE_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
"$TRACE_LIFE_SDK/emulator/emulator" -avd trace_life_preview -no-boot-anim -no-audio
```

另开终端，在仓库执行构建、查看设备并安装：

```bash
./gradlew assembleDebug
TRACE_LIFE_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
"$TRACE_LIFE_SDK/platform-tools/adb" devices -l
"$TRACE_LIFE_SDK/platform-tools/adb" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$TRACE_LIFE_SDK/platform-tools/adb" -s emulator-5554 shell am start -W -n dev.tracelife.app/dev.tracelife.MainActivity
```

`install -r` 为覆盖安装，不清空已有资料。包名仍为 `dev.tracelife.app`，预览版使用同一台机器的调试签名；其他机器的调试包可能无法覆盖安装，不能为解决签名问题而直接卸载有未备份资料的应用。

其他机器可通过 SDK Manager 安装 Emulator 和与 CPU 匹配的 API 36 系统镜像，再在 Device Manager 创建 Pixel 8。Apple Silicon 使用 ARM64，Intel / AMD 主机选对应 x86_64 镜像。官方操作说明：[创建虚拟设备](https://developer.android.com/studio/run/managing-avds)、[运行模拟器](https://developer.android.com/studio/run/emulator-commandline)。

## 页面检查

建议使用明确标注「合成资料」的随记，按顺序验证：

1. 首页直接看到记录及写入入口；菜单能进入全部八个功能页，再返回记录列表。
2. 写一条中文记录，展开日期与主题，保存后关闭再打开仍可读；未知日期和日期区间保持原意。
3. 展开、收起筛选时输入不消失；应用及清除筛选后列表正确变化。
4. 写记录时弹出键盘，检查正文、保存按钮与返回键；修改主记录草稿后旋转，内容保留。
5. 切换深浅色、放大字体和缩小屏幕后，入口可见或可滚动到达，没有横向裁切。
6. 行动编辑预填已有内容；回看选择调整时显示下一轮表单，切回继续不会提交隐藏的改动。
7. 设置中启用自动整理会先说明发送内容与可能费用；取消确认后仍保持关闭。此项可在未配置密钥时验收，不执行真实模型请求。

模拟器支持点击、键盘输入和系统深浅色切换。测试软键盘时，如果主机键盘使软键盘隐藏，可在模拟器的系统输入设置中启用「使用实体键盘时显示屏幕键盘」。ADB 截图可用 `exec-out screencap -p`，输出文件仍应放在仓库外。

## 验证边界

本轮 APK、自动测试和实际模拟检查的结果记录在[开发与验证](DEVELOPMENT.md#移动端页面优化验证)。模拟器不代表华为实体手机；健康授权、厂商后台限制、系统文件访问与恢复仍需单独验收。记录／模板、分析、经验、行动复盘、画像生成与维护的表单或任务恢复按各页面测试检查；仍须区分旋转重建、进程重启和实体手机系统回收。
