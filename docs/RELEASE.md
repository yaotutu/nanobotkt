# NanobotKT 自动构建与发布说明

这套方案只做必要的 CI/CD：不需要品牌配置、不需要购买证书，也不发布 Debug 版本。

| 构建 | 触发方式 | Gradle 任务 | 包名 | GitHub 发布形式 |
|---|---|---|---|---|
| dev 测试版 | 向 `dev` push | `:app:assembleDev` | `com.nanobotkt` | 滚动预发布 `dev-latest` |
| 正式版 | 向 `main` push | `:app:assembleRelease` | `com.nanobotkt` | 版本化 Release，例如 `v0.1.1` |

## 覆盖安装规则（重要）

Dev 和正式版故意使用**同一个包名**和**同一份稳定签名**：

- 包名都是 `com.nanobotkt`；
- dev 和正式版都从同一份 GitHub Secrets 读取 keystore；
- 每次构建都会递增 `versionCode`；
- 因此新 dev 可以覆盖旧 dev，新正式版可以覆盖旧正式版；
- 只要新构建的 `versionCode` 更高，dev 与正式版也可以互相覆盖安装。

Dev 只在版本名称上增加 `-dev` 后缀，例如 `0.1.1-dev`，用户可以一眼识别测试版，但这不会改变包名。

**注意：**如果设备上已经安装过早期使用另一份签名的 APK，第一次切换到这份稳定签名时可能需要卸载一次。完成切换后，后续 dev 和正式版都可以直接覆盖安装。

Pull Request 只做 `assembleDev` 构建和检查，不发布 GitHub Release，也不使用正式签名；它不是给用户安装升级的版本。

## 第一次配置签名（只需做一次）

你不需要品牌配置，也不需要购买证书。Android 应用使用本地生成的一份 `.keystore` 文件即可。

在自己的电脑上执行：

```bash
./scripts/create-release-keystore.sh
```

脚本会在项目根目录生成 `nanobotkt-release.keystore`，并提示你妥善备份。**不要把这个文件提交到 Git，也不要把密码写进代码。**

然后在 GitHub 仓库的 `Settings → Secrets and variables → Actions` 中创建以下 4 个 Repository secrets：

| Secret | 填写内容 |
|---|---|
| `KEYSTORE_BASE64` | `nanobotkt-release.keystore` 的 Base64 文本 |
| `KEYSTORE_PASSWORD` | 生成 keystore 时设置的密码 |
| `KEY_ALIAS` | 生成时使用的别名，脚本默认是 `nanobotkt` |
| `KEY_PASSWORD` | 生成别名密钥时设置的密码 |

生成 Base64 的命令：

```bash
base64 < nanobotkt-release.keystore | tr -d '\\n'
```

这 4 个 Secrets 同时供 dev 和正式版 workflow 使用。私钥只保存在你的备份和 GitHub Secrets 中，不会进入仓库。

## 自动发布流程

### dev 测试版

向 `dev` 分支 push 后自动：

1. 将 `0.1.x` 的补丁号加 1，例如 `0.1.0` → `0.1.1`；
2. 同步将 Android `versionCode` 加 1；
3. 根据 Git 提交记录生成 `docs/CHANGELOG.md`；
4. 执行全工程 JVM 测试和 `lintDev`；
5. 使用稳定 keystore 构建 `assembleDev`，不会构建 Debug APK；
6. 将版本文件提交回 `dev`；
7. 更新 GitHub 上唯一的 `dev-latest` prerelease。

### 正式版

向 `main` 分支 push 后自动：

1. 将 `0.1.x` 的补丁号加 1；
2. 同步将 Android `versionCode` 加 1；
3. 根据 Git 提交记录生成 `docs/CHANGELOG.md`；
4. 执行全工程 JVM 测试和 `lintRelease`；
5. 使用同一份稳定 keystore 构建 `assembleRelease`；
6. 创建 `v0.1.x` GitHub Release，并上传所有 ABI APK 和 universal APK。

如果 4 个签名 Secrets 没有配置完整，workflow 会主动停止，不会发布一个用户无法覆盖升级的 APK。

## 版本号与更新日志

版本号集中在 `/version.properties`：

```properties
VERSION_NAME=0.1.0
VERSION_CODE=1
```

发布 workflow 会自动修改，不需要每次手动改版本。更新日志由 `scripts/release.sh` 根据 Git 提交记录自动生成，并作为 GitHub Release 的说明。

为保证 dev 和正式版始终保持同一条可升级链，正常流程应当是：先在 `dev` 验证，之后把 `dev` 合并到 `main` 再发布正式版。这样 `version.properties` 会沿用已经递增的版本号。

## 本地构建

```bash
# 开发版，非 Debug；没有稳定 keystore 时仅用于本地验证
./gradlew :app:assembleDev --console=plain

# 正式版，非 Debug；没有稳定 keystore 时可生成未签名产物，但不能给用户安装
./gradlew :app:assembleRelease --console=plain
```
