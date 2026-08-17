# dsh-android — 应用侧设计（termux-app fork）

目标：dsh（deepseek-harness）跑在 proot 容器里，通过 WebView/浏览器访问
localhost:3080，打包成可离线分发的单 APK。

## 包名决策（v2：独立应用，与 Termux 共存）

**applicationId = dev.lwff.dsh**，namespace 保留 com.termux（Java 类仍位于
com.termux.app，AGP 的 R 类/Manifest 类解析依赖 namespace 不变量）。
本 APK 与官方 Termux 可并排安装，互不覆盖、互不干扰。

独立化的关键：
- 完全不需要 termux bootstrap：宿主侧只需 sh + tar + xz + proot，全部来自
  系统自带（/system/bin/sh + /system/bin/toybox）+ 内置资产（proot/xz/4 个 .so）
- TermuxApplication 换成极简 DshApplication（不接 am-socket/prefix 机制）
- sharedUserId 移除（不再和 Termux 家族共享 UID）
- 稳定签名：keystore/dsh-release.p12（openssl 生成 PKCS12，已提交，
  gradle debug signingConfig 指向它，CI 每轮构建同一签名，可无缝覆盖安装）

含义：
- 容器/运行时/会话数据全在 dev.lwff.dsh 私有目录，卸载即净
- 手机上原有的 Termux 及其 ~/deepseek-harness 环境原样保留，两者并存
- 若日后想与 Termux 侧互通（共用 API key 等），是明确后续课题

## 分层

```
┌─────────────────────────────────────────────┐
│ fork termux-app（外壳）                     │
│  - 终端模拟器（原生 termux 终端，可留可藏）  │
│  - WebViewActivity → 127.0.0.1:3080（主界面）│
│  - SettingsActivity：DEEPSEEK_API_KEY 等     │
├─────────────────────────────────────────────┤
│ termux 运行时（app 的 $PREFIX）             │
│  - 标准 termux bootstrap（pkg 生态可用）     │
│  - proot + libtalloc/libandroid-shmem/...   │  ← assets 内置（~5MB）
│  - rootfs.tar.xz（debian+node+dsh 就绪）     │  ← 内置或 release 下发
├─────────────────────────────────────────────┤
│ proot 容器（debian trixie aarch64）         │
│  - /opt/node    node 22 LTS（glibc）        │
│  - /opt/dsh     deepseek-harness 源码+补丁  │
│  - /opt/dsh-home  会话数据（跨重启保留）     │
│  - /opt/start-dsh.sh  入口脚本              │
└─────────────────────────────────────────────┘
```

## fork 改造点（对照 ~/termux-app-src）

1. app/build.gradle
   - applicationId: com.termux → 自有 id（如 dev.lwff.dsh）
   - applicationLabel: "DSH for Android"
2. AndroidManifest.xml
   - 注册 WebViewActivity（MAIN/LAUNCHER 换主入口；原 TermuxActivity 保留）
   - 权限：INTERNET（必须）、FOREGROUND_SERVICE +
     FOREGROUND_SERVICE_DATA_SYNC（Android 14）、WAKE_LOCK、
     POST_NOTIFICATIONS（Android 13+）
3. 新增 WebViewActivity.java
   - WebView 指向 http://127.0.0.1:3080
   - JS 开启（dsh 前端需要）、localStorage/IndexedDB 开启
   - 启动时确保 TermuxService 里 dsh 会话在跑（不存在则拉起）
   - 服务未就绪时显示本地等待页（内置 HTML asset）
4. 新增 FirstBootService / 复用 TermuxInstaller 后置钩子
   - bootstrap 装完后：解压 proot 资产到 $PREFIX → 导入 rootfs
     （proot-distro install -n dsh <archive>，或直接解目录）
   - 状态记录在 $HOME/.dsh-android-booted，幂等
5. 新增 DshSessionManager
   - 在 TermuxService 里创建会话：proot-distro login dsh -- /opt/start-dsh.sh
   - 崩溃/被杀后由 app 前台时检查并重启
   - termux-wake-lock 防睡眠杀进程
6. assets/
   - proot 二进制 + libtalloc.so.2 + libandroid-shmem.so + libandroid.so
   - bootstrap zip（可选，决定首启是否联网）
   - rootfs.tar.xz（可选，决定是否单文件离线）
   - wait.html（等待页）

## 分发形态（三档，按需选）

- 档1 纯自用快装：APK 空壳 + 首启联网（bootstrap 下载 + rootfs release 下载）
- 档2 半离线：APK 内置 bootstrap + proot 资产，rootfs 从 release 下载（~400MB）
- 档3 全离线单文件：APK 内置一切（~500MB+），任何装法零联网

## 后期拓展（proot 路线收益）

- proot-distro login dsh → apt install 任意包
- 容器里装 python/编译器/工具链自由，不再需要 termux 的 patchelf hack
- dsh 插件/新版本：容器内 git pull + pnpm install 即可，app 无需重装

## 构建流水线（GitHub Actions）

1. build-rootfs job：ubuntu 上 proot-distro(或 debootstrap+qemu-aarch64) 跑
   scripts/build-rootfs.sh → rootfs.tar.xz → artifact
2. build-apk job：./gradlew assembleDebug（termux-app 自带 debug_build.yml）
   → universal+arm64 APK → artifact
3. 发布：tag → 打 release，附 APK + rootfs.tar.xz

## 关键路径风险

- proot 在 app 私有目录跑容器：SELinux untrusted_app 对 rootfs 文件的
  link() 限制 → 已用 rename 补丁规避（dsh 侧）
- Android 14 后台杀进程：前台服务 + 通知常驻；用户手动退出才停
- 首启耗时：bootstrap ~2 分钟 + rootfs 导入解压 ~5-10 分钟
- WebView 与 /api browser-trust fence：localhost 默认放行；LAN 模式需
  --trusted-host + 容器 start 脚本参数化

## V1 实测踩坑记录（2026-08，容器内安装）

- debian 基础镜像无 patch 包 → apt 列表必须带 patch（build-essential 也带上）
- 只跑 vite 前端构建会挂：workspace 包 exports 指向 lib/，必须先
  `pnpm run build`（= build:lib tsc+tsdown + build:web vite），与 termux
  实测流程一致
- node-pty：预编译下载和 node-gyp 的 headers 下载都会在这网络超时——
  用本地头 `node-gyp rebuild --nodedir=/opt/node`（node tarball 自带
  include/node），glibc 环境编译经实测通过（81KB pty.node）
- 首跑实测：容器 ~2.7GB 解包，依赖全装复用了 pnpm store（重试幂等）