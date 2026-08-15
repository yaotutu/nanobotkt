# NanobotKT 自动构建与发布说明

这套方案只做必要的 CI/CD：不需要品牌配置、不需要购买证书，也不发布 Debug 版本。

核心原则是：

> **版本号和更新日志在本地准备并提交；GitHub Actions 只负责测试、构建、签名和发布，不修改仓库代码。**

这样本地提交和云端构建使用的是同一份 `version.properties`，不会出现云端版本已经递增、但本地仍停留在旧版本的情况。

| 构建 | 触发方式 | Gradle 任务 | 包名 | GitHub 发布形式 |
|---|---|---|---|---|
| dev 测试版 | 向 `dev` push 本地准备好的发布提交 | `:app:assembleDev` | `com.nanobotkt` | 滚动预发布 `dev-latest` |
| 正式版 | 向 `main` push 本地准备好的发布提交 | `:app:assembleRelease` | `com.nanobotkt` | 版本化 Release，例如 `v0.1.2` |

## 覆盖安装规则（重要）

Dev 和正式版故意使用**同一个包名**和**同一份稳定签名**：

- 包名都是 `com.nanobotkt`；
- dev 和正式版都从同一份 GitHub Secrets 读取 keystore；
- 版本号和 `versionCode` 在本地发布前递增；
- 只要新构建的 `versionCode` 更高，dev 与正式版就可以互相覆盖安装。

Dev 只在版本名称上增加 `-dev` 后缀，例如 `0.1.1-dev`，用户可以一眼识别测试版，但这不会改变包名。

**注意：**如果设备上已经安装过早期使用另一份签名的 APK，第一次切换到这份稳定签名时可能需要卸载一次。完成切换后，后续 dev 和正式版都可以直接覆盖安装更新。

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

## 本地准备版本

版本号集中在：

```text
version.properties
```

内容示例：

```properties
VERSION_NAME=0.1.1
VERSION_CODE=2
```

本地发布脚本：

```text
scripts/release.sh
```

推荐使用 `prepare` 命令。它会在本地完成以下操作：

1. 检查当前分支是否正确；
2. 检查工作区是否干净，避免把未完成修改带入发布；
3. 将 `0.1.x` 的补丁号加 1；
4. 将 `VERSION_CODE` 加 1；
5. 根据 Git 提交记录生成 `docs/CHANGELOG.md`；
6. 创建版本提交，但不会自动 push。

### 准备 Dev 版本

先提交业务代码，再执行：

更常见的实际顺序是：

```bash
git switch dev
# 修改代码
git add .
git commit -m "feat: your feature"
./scripts/release.sh prepare dev
git push origin dev
```

脚本会生成类似提交：

```text
chore(dev): prepare v0.1.1
```

### 准备正式版本

Dev 测试通过后，将 Dev 代码合并到 `main`，然后在 `main` 上再次准备正式版本：

```bash
git switch main
git merge dev
./scripts/release.sh prepare release
git push origin main
```

脚本会生成类似提交：

```text
chore(release): prepare v0.1.2
```

这里正式版会比 Dev 版再递增一次，保证正式 APK 的 `versionCode` 高于已经安装的 Dev APK。

## 自动发布流程

### dev 测试版

向 `dev` push 本地准备好的 `chore(dev): prepare v0.1.x` 提交后，GitHub Actions 自动：

1. 读取提交中的 `version.properties`；
2. 检查提交确实是本地准备好的 Dev 版本；
3. 执行全工程 JVM 测试和 `lintDev`；
4. 使用稳定 keystore 构建 `assembleDev`，不会构建 Debug APK；
5. 上传 Actions artifact；
6. 更新 GitHub 上唯一的 `dev-latest` prerelease。

GitHub Actions **不会**修改 `version.properties`，也不会向 `dev` 推送新的提交。

### 正式版

向 `main` push 本地准备好的 `chore(release): prepare v0.1.x` 提交后，GitHub Actions 自动：

1. 读取提交中的 `version.properties`；
2. 检查提交确实是本地准备好的正式版本；
3. 检查对应的 Release tag 尚不存在；
4. 执行全工程 JVM 测试和 `lintRelease`；
5. 使用同一份稳定 keystore 构建 `assembleRelease`；
6. 上传 Actions artifact；
7. 创建 `v0.1.x` GitHub Release，并上传所有 ABI APK 和 universal APK。

GitHub Actions **不会**修改 `version.properties`，也不会向 `main` 推送新的版本提交。

如果 4 个签名 Secrets 没有配置完整，workflow 会主动停止，不会发布一个用户无法覆盖升级的 APK。

## 推荐的分支顺序

为了让 Dev 和正式版始终处于同一条可升级链，推荐始终按下面顺序操作：

```text
1. 在 dev 开发
2. 本地执行 scripts/release.sh prepare dev
3. push dev，测试 dev-latest
4. 测试通过后合并 dev → main
5. 在 main 本地执行 scripts/release.sh prepare release
6. push main，发布正式版
7. 下一轮开发前，将最新 main 同步回 dev
```

Dev 和正式版各发布一次，因此版本号会类似这样递增：

```text
基础版本：0.1.0 / versionCode 1
Dev：    0.1.1-dev / versionCode 2
正式版： 0.1.2     / versionCode 3
下一 Dev：0.1.3-dev / versionCode 4
```

正式发布完成后，下一轮 Dev 必须基于最新的 `main`，不能继续从旧的 Dev 提交上递增，否则可能生成重复的 `versionCode`。

## 本地构建

```bash
# 开发版，非 Debug；没有稳定 keystore 时仅用于本地验证
sh gradlew :app:assembleDev --console=plain

# 正式版，非 Debug；没有稳定 keystore 时可生成未签名产物，但不能给用户安装
sh gradlew :app:assembleRelease --console=plain
```
