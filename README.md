# Unofficial SakuraFRP Moonlight TCP Mux Android-Compatible Client

## 中文

这是一个**非官方**项目。

本项目不是官方的 `sakuramux_moonlight` / `Moonlight TCP mux` 程序，也不隶属于 SakuraFRP、Moonlight、Sunshine 或其维护者。它是一个面向 Android 的兼容客户端，用于在 Android 设备上运行 mux client，可让 Moonlight 通过 Android 本机 `127.0.0.1` 的 Moonlight 端口多路复用与指定域名或IP进行通信

### 项目内容

- `client/moonlight-mux-client`：Go 编写的命令行客户端。
- `android-app`：Android App 外壳，打包并启动 Go 客户端。
- APK 内置 `arm64-v8a` 和 `armeabi-v7a` 两套 Android 客户端。
- 支持默认 Moonlight TCP/UDP 端口映射。
- App 使用 Foreground Service 运行客户端。
- App 在运行期间可创建 1x1 透明悬浮窗，用于提升部分 Android 设备后台保持稳定性。

### 适用范围

- 支持 Android 8.0+ / API 26+。
- 推荐 Android 10+，后台限制和权限行为通常更可控。
- 支持设备上最常见的 `arm64-v8a` 和 `armeabi-v7a`。
- 不包含 `x86` / `x86_64`，因此不面向 Intel Android 设备或 x86 模拟器发布。
- 部分国产系统可能仍需要手动允许悬浮窗、后台运行和无限制省电策略，这有助于应用运行稳定性。

### 背景与 mux 配置示例

Moonlight/Sunshine 使用一组固定 offset 的 TCP/UDP 端口，配对、发现、HTTP/RTSP 和串流都需要对应端口可达。自建 frpc 并完整配置所有需要的端口是可行的，不过使用 mux 的好处是只需要维护一个隧道端口，而不需要分别配置多个 TCP/UDP 端口。可以使用官方 `sakuramux_moonlight` mux 程序把 Moonlight 默认端口映射复用到一条隧道入口。**本客户端必须配合Sakuramux-moonlight服务端使用**,官方程序可从这里获取：

```text
https://nya.globalslb.net/natfrp/client/mux/moonlight/1.0.1/
```

官方程序目前缺少完整文档，下面是最小配置示例。

在安装 Sunshine 的机器上运行 mux server：

```json
{
  "mode": "server",
  "key": "自己设置一个口令，至少8位数字",
  "server": "127.0.0.1:YOUR_LOCAL_MUX_PORT"
}
```

然后使用任意可用的 TCP 隧道服务或自建隧道，目标指向：

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

两边启动 mux，并在sunshine侧启动隧道后，在 Moonlight 中连接 `127.0.0.1`，即可发现并连接远端 Sunshine 主机。

`YOUR_LOCAL_MUX_PORT` 可以自行选择；它只需要和 mux server 监听端口、隧道本地目标端口保持一致。`YOUR_TUNNEL_HOST:PORT` 则是 Android 客户端实际能访问到的隧道公网入口。

### SakuraFRP mux 二进制程序说明

- 本项目是非官方 Android 客户端，不是 SakuraFRP 官方客户端，也不代表 SakuraFRP 对本项目提供支持、合作、认可、背书或担保。
- 本仓库当前发布的 APK 内置的是本项目自行构建的 Go 客户端，不包含 SakuraFRP 官方发布的 mux 二进制程序。
- SakuraFRP 官方 mux 二进制程序的原始下载地址为：

```text
https://nya.globalslb.net/natfrp/client/mux/moonlight/1.0.1/
```

- SakuraFRP 官方 mux 二进制程序按“原样”提供；SakuraFRP 不对本 wrapper / Android 客户端的功能、兼容性、安全性或可用性承担责任。

### 使用方式

PC / Sunshine 侧仍需要运行可用的 mux server，并确保 key与客户端一致。

Android 侧安装 App 后：

1. 打开 **Moonlight Mux Client**。
2. 填写 隧道提供的 地址和 端口号，以及服务端设置的key。
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
