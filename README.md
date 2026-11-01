### 项目内容

- `client/moonlight-mux-client`：Go 编写的命令行客户端。
- `android-app`：Android 应用，打包并启动 Go 客户端。
- APK 内置 `arm64-v8a` 和 `armeabi-v7a` 两套 Android 客户端。
- 支持默认 Moonlight TCP/UDP 端口映射。
- 应用使用前台服务运行客户端。
- 运行期间可创建 1x1 透明悬浮窗，帮助部分 Android 设备保持后台运行。
- 支持保存和切换多个连接配置；旧版单配置会自动迁移为 Default profile。
- Android 界面使用 [Miuix for Compose](https://github.com/compose-miuix-ui/miuix) 组件库。

### 适用范围

- 支持 Android 8.0+ / API 26+。
- 推荐 Android 10+，后台限制和权限行为通常更容易控制。
- 支持常见的 `arm64-v8a` 和 `armeabi-v7a` 设备。
- 不包含 `x86` / `x86_64`，因此不面向 Intel Android 设备或 x86 模拟器发布。
- 部分国产系统可能还需要手动允许悬浮窗、后台运行，并将省电策略设为无限制，帮助应用稳定运行。

### 背景与 mux 配置示例

在安装 Sunshine 的机器上运行 mux server。在官方程序所在目录新建一个 `.json` 文件，填写以下配置：
```json
{
  "mode": "server",
  "key": "自己设置一个口令，至少8位数字",
  "server": "127.0.0.1:YOUR_LOCAL_MUX_PORT"
}
```

然后使用可用的 TCP 隧道服务或自建隧道，将目标指向：

```text
tcp://127.0.0.1:YOUR_LOCAL_MUX_PORT
```

在安装 Moonlight 的 Android 设备上运行本项目客户端，配置如下：

```配置
  Server:填写隧道启动时提供的域名:端口号
  Key:填写与 mux server 相同的 key
  Local bind:127.0.0.1
  Base port:47989
```

两边启动 mux，并在 Sunshine 侧启动隧道后，在 Moonlight 中连接 `127.0.0.1`，即可发现并连接远端 Sunshine 主机。

`YOUR_LOCAL_MUX_PORT` 可以自行选择，保持 mux server 的监听端口和隧道的本地目标端口一致即可。`填写隧道启动时提供的域名:端口号` 是 Android 客户端能够访问的隧道公网入口。


### 使用方式

PC / Sunshine 侧需要运行可用的 mux server，并确保 key 与客户端一致。

Android 侧安装 App 后：

1. 打开 **Moonlight Mux Client**。
2. 填写隧道提供的地址和端口号，以及服务端设置的 key。
3. 首次启动时授予悬浮窗权限。
4. 点击 **Start**。
5. 在 Moonlight 中连接 `127.0.0.1`。

### 构建 Android APK

从仓库根目录构建 debug APK：

```powershell
gradle :android-app:assembleDebug
```

正式发布时应使用 release 签名 APK：

```powershell
gradle :android-app:assembleRelease
```

release 签名需要本地 keystore 和 `android-app/release-signing.properties`。

生成的 APK 位于：

```text
android-app/build/outputs/apk/debug/android-app-debug.apk
android-app/build/outputs/apk/release/android-app-release.apk
```

安装到 Android 设备：

```powershell
adb install -r -t android-app/build/outputs/apk/debug/android-app-debug.apk
adb install -r android-app/build/outputs/apk/release/android-app-release.apk
```

如果设备上已经安装过 debug 签名 APK，正式签名 APK 不能直接覆盖安装；请先卸载旧版再安装 release APK。卸载会清除 App 内保存的配置。
