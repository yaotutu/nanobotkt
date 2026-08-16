# NanobotKT 自动构建与发布使用手册

这份文档是 NanobotKT 发布流程的**唯一操作入口**。后续 Agent 在修改版本、发布 Dev 或发布正式版之前，必须先阅读本文件；不要根据 GitHub Actions 页面里的零散日志自行猜测流程。

## 1. 先记住这 5 条规则

1. **版本号在本地递增。** 在 `dev` 分支执行 `scripts/release.sh dev`，在 `main` 分支执行 `scripts/release.sh release`；不要在 GitHub Actions 里手动改版本。
2. **更新日志在本地生成。** 脚本会生成 `docs/CHANGELOG.md`，并把它和 `version.properties` 一起提交。
3. **云端只负责测试、构建、签名和发布。** GitHub Actions 不会回写版本文件，也不会自动创建版本提交。
4. **Dev 和正式版共用包名、签名和升级链。** 两者都是 `com.nanobotkt`，新 APK 的 `versionCode` 更高时可以直接覆盖旧 APK。
5. **不发布 Debug 版本。** 用户可安装的产物只有 Dev 和正式 Release；Pull Request 的构建仅用于 CI 预检，不能当作用户升级包。

## 2. 这套系统各文件分别负责什么

| 文件 | 作用 | 谁负责修改 |
|---|---|---|
| `version.properties` | 保存 `VERSION_NAME=0.1.x` 和递增的 `VERSION_CODE` | 本地脚本 |
| `scripts/release.sh` | 检查分支/工作区、递增版本、生成日志、创建版本提交并 push | 本地执行 |
| `docs/CHANGELOG.md` | 当前待发布版本的更新日志 | 本地脚本生成，可在发布前人工检查 |
| `app/build.gradle.kts` | 读取版本文件，配置 `com.nanobotkt`、Dev 和 Release 构建 | 工程代码 |
| `.github/workflows/android-build.yml` | PR 检查和 `dev` 分支构建/发布 | GitHub Actions |
| `.github/workflows/android-release.yml` | `main` 分支正式构建和 GitHub Release | GitHub Actions |

版本递增的真实入口是本地脚本，不是云端：

```text
scripts/release.sh dev|release
        ├── 校验参数与当前分支匹配
        ├── 修改 version.properties
        ├── 生成 docs/CHANGELOG.md
        ├── 创建 chore(dev/release): prepare v0.1.x 提交
        └── 自动 push，随后由 GitHub Actions 读取这次提交并构建
```

## 3. 构建产物和覆盖安装关系

| 类型 | 分支 | Gradle 任务 | 版本名示例 | 包名 | 发布位置 |
|---|---|---|---|---|---|
| Dev 测试版 | `dev` | `:app:assembleDev` | `0.1.5-dev` | `com.nanobotkt` | 滚动预发布 `dev-latest` |
| 正式版 | `main` | `:app:assembleRelease` | `0.1.6` | `com.nanobotkt` | 版本化 Release，例如 `v0.1.6` |

Dev 和正式版使用同一套 GitHub Secrets：

- `applicationId` 都是 `com.nanobotkt`；
- 都使用同一份稳定 keystore；
- `VERSION_CODE` 每发布一次加 1；
- 因此 Dev、正式版可以互相覆盖安装。

如果设备上安装的是以前用**另一份签名**构建的 APK，第一次切换到本方案的稳定签名时可能必须卸载一次。完成这次切换后，后续 Dev 和正式版都应直接覆盖安装，不要为了切换渠道反复卸载。

## 4. 第一次配置签名（只做一次）

不需要品牌配置，也不需要购买商业证书。只需要生成一份稳定的 Android keystore，并把它安全地保存下来。

### 4.1 本地生成 keystore

在仓库根目录执行：

```bash
./scripts/create-release-keystore.sh
```

脚本会生成类似下面的文件：

```text
nanobotkt-release.keystore
```

请立即把该文件复制到离线安全位置。**不要把 keystore、密码、Base64 内容或任何 Token 提交到 Git。**

### 4.2 配置 GitHub Secrets

在 GitHub 仓库的 `Settings → Secrets and variables → Actions` 创建以下 4 个 Repository secrets：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 Base64 文本 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 创建密钥时使用的 alias，默认通常是 `nanobotkt` |
| `KEY_PASSWORD` | alias 对应的密钥密码 |

macOS 生成 Base64 并通过 GitHub CLI 写入的方式：

```bash
# 先确认 gh 已登录，并且当前仓库是目标仓库。
gh auth status
gh repo set-default yaotutu/nanobotkt

# 只会显示 Secret 名称，不会显示 Secret 值。
gh secret list --repo yaotutu/nanobotkt

# 通过 stdin 写入 keystore；不要把 Base64 复制进聊天、代码或日志。
base64 < nanobotkt-release.keystore | tr -d '\n' \
  | gh secret set KEYSTORE_BASE64 --repo yaotutu/nanobotkt

# 密码不要直接写在命令行中，避免进入 shell history。
read -r -s KEYSTORE_PASSWORD
printf '%s' "$KEYSTORE_PASSWORD" \
  | gh secret set KEYSTORE_PASSWORD --repo yaotutu/nanobotkt
unset KEYSTORE_PASSWORD

read -r -s KEY_PASSWORD
printf '%s' "$KEY_PASSWORD" \
  | gh secret set KEY_PASSWORD --repo yaotutu/nanobotkt
unset KEY_PASSWORD

# alias 可以直接写入；如果 alias 不同于默认值，必须与 keytool 创建时一致。
printf '%s' 'nanobotkt' \
  | gh secret set KEY_ALIAS --repo yaotutu/nanobotkt
```

再次检查：

```bash
gh secret list --repo yaotutu/nanobotkt
```

列表中应至少有这 4 个名称。GitHub CLI 不会返回 Secret 的明文值，因此无法通过 `gh secret list` 检查密码是否正确；真正构建时 workflow 会在签名步骤验证。

## 5. 日常发布：Dev 测试版

### 5.1 推荐操作顺序

```bash
cd /Users/yaotutu/Desktop/code/nanobotkt

git switch dev
git pull --ff-only origin dev

# 如果上一轮正式版已经发布，先把最新 main 同步回 dev。
# 当前仓库若 main 比 dev 更新，这一步是必须的。
git merge origin/main

# 修改业务代码并先提交业务提交；不要让 release.sh 把未完成修改带入发布。
git add <本轮业务文件>
git commit -m "feat: ..."

# 工作区必须干净；脚本会自动把 0.1.x 和 VERSION_CODE 各加 1。
scripts/release.sh dev             # 显式准备 Dev 版本并提交

# 脚本成功后会自动 push；如需确认版本，可查看：
git log -2 --oneline
git show --stat --oneline HEAD
```

`scripts/release.sh dev` 会：

1. 确认当前分支是 `dev`；
2. 确认工作区没有未提交或未跟踪文件；
3. 把 `0.1.x` 的补丁号加 1；
4. 把 `VERSION_CODE` 加 1；
5. 根据上一个 tag 之后的提交生成 `docs/CHANGELOG.md`；
6. 创建类似下面的版本提交：

```text
chore(dev): prepare v0.1.5
```

脚本 push 成功后，`android-build.yml` 会自动运行测试、`lintDev`、稳定签名的 `assembleDev`，然后更新唯一的滚动预发布 `dev-latest`。旧的 `dev-latest` 会被删除并由当前版本替换，不会无限堆积 Dev Release。

### 5.2 下载 Dev APK

网页上可在仓库的 `Releases → dev-latest` 下载。也可以用 CLI：

```bash
mkdir -p /tmp/nanobotkt-dev
cd /tmp/nanobotkt-dev
gh release download dev-latest --repo yaotutu/nanobotkt --pattern '*.apk' --clobber
```

Dev APK 可覆盖旧 Dev，也可覆盖当前正式版，只要设备上的旧 APK 已经使用同一份稳定签名。

## 6. 日常发布：正式版

正式版必须来自已经测试通过的 Dev 代码。推荐的分支关系是：

```text
dev 开发 → release.sh dev（自动 push）→ 测试 dev-latest
                                      ↓ 测试通过
                                  合并到 main
                                      ↓
                             release.sh release（自动 push）
```

### 6.1 合并并发布

```bash
cd /Users/yaotutu/Desktop/code/nanobotkt

git switch main
git pull --ff-only origin main

git merge --no-ff dev

# 合并成功并确认测试结果后，准备正式版本。
scripts/release.sh release         # 显式准备正式版本并提交

git log -2 --oneline
git show --stat --oneline HEAD
```

`scripts/release.sh release` 必须在 `main` 分支执行，并创建类似下面的提交：

```text
chore(release): prepare v0.1.6
```

正式 workflow 会读取这次提交里的本地版本号和 `docs/CHANGELOG.md`，然后：

1. 运行全工程 JVM 测试；
2. 运行 `lintRelease`；
3. 检查 4 个签名 Secrets；
4. 构建稳定签名的 `assembleRelease`；
5. 上传 ABI APK 和 universal APK；
6. 创建 `v0.1.6` GitHub Release，并将 `docs/CHANGELOG.md` 作为更新日志。

### 6.2 下载正式版 APK

网页上可在仓库的 `Releases` 页面下载。也可以用 CLI：

```bash
mkdir -p /tmp/nanobotkt-release
cd /tmp/nanobotkt-release
gh release download v0.1.6 --repo yaotutu/nanobotkt --pattern '*.apk' --clobber
```

发布后选择 `universal` APK 最方便；如果需要按设备架构分发，再选择 `arm64-v8a`、`armeabi-v7a`、`x86` 或 `x86_64` 对应文件。

## 7. 版本号和更新日志到底在哪里变化

一次正常的发布只会在本地产生一次版本提交：

```text
当前：version.properties
  VERSION_NAME=0.1.4
  VERSION_CODE=5

执行 scripts/release.sh dev             # 显式准备 Dev 版本并提交

结果：version.properties
  VERSION_NAME=0.1.5
  VERSION_CODE=6

同时生成：docs/CHANGELOG.md
同时创建：chore(dev): prepare v0.1.5
```

正式版同理，只需要切换到 `main` 分支后执行 `scripts/release.sh release`。云端不会修改 `version.properties`、不会提交代码；这样本地和 CI 使用的是同一个 Git 提交，不会出现“云端已经是新版本、本地还是旧版本”的分叉。

脚本不再提供 `bump`、`changelog`、`prepare` 等额外子命令；发布类型通过唯一参数 `dev` 或 `release` 显式指定，并且必须与当前分支匹配。

## 8. 发布失败怎么处理

### 8.1 构建或测试失败

**不要再次执行发布脚本。** 当前提交已经包含版本号和更新日志；如果只是临时 CI 失败，应直接重跑当前 Action，避免无意义地递增到下一个版本。

处理方式：

1. 打开失败的 GitHub Actions run，查看失败步骤；
2. 如果只是临时 CI 问题，在该 run 页面点击 `Re-run failed jobs`；
3. 也可以用 CLI：

```bash
gh run list --repo yaotutu/nanobotkt --limit 10
gh run rerun <RUN_ID> --failed --repo yaotutu/nanobotkt
```

4. 如果是代码或 workflow 问题，先修复并提交修复；修复提交应保留在同一个分支，然后重新运行当前版本 workflow，或按仓库现有规则重新推送触发。

只有当当前版本已经无法继续使用、需要发布下一轮版本时，才再次执行发布脚本。

### 8.2 正式 Release 已经存在

正式 workflow 会拒绝已存在的 tag 或 Release，例如 `v0.1.6` 已存在时不能再次创建同名 Release。不要删除历史 Release 来“重跑版本号”，应从当前版本继续递增到下一个 `0.1.x`。

### 8.3 workflow 没有执行发布 job

检查最后一个提交标题是否符合本地脚本格式：

```text
Dev：    chore(dev): prepare v0.1.x
正式版： chore(release): prepare v0.1.x
```

如果直接 push 了普通业务提交，workflow 可能只做检查或跳过发布 job。正确做法是先确认版本文件状态，再执行对应的发布脚本，不要手工伪造提交标题。

### 8.4 签名错误或无法覆盖安装

依次检查：

1. GitHub 是否存在 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 这 4 个 Secret；
2. Dev 和正式 workflow 是否使用同一组 Secret；
3. 设备上的旧 APK 是否来自另一份签名；
4. 新 APK 的 `versionCode` 是否高于旧 APK。

只有第 3 种情况允许做一次卸载重装；不要把“签名错误”当作需要每次卸载的正常流程。

### 8.5 工作区不干净

`release.sh` 会主动拒绝以下情况：

- 已修改但未提交的文件；
- 未跟踪文件；
- 其他 Agent 留下的修改；
- 本地临时构建文件未被忽略。

不要用 `git reset`、`git clean` 或覆盖文件来强行通过。先查看：

```bash
git status --short
git diff --stat
git diff
```

确认修改归属后，提交业务代码、处理临时文件，或停止发布并交给原 Agent 处理。特别是 `gradle/libs.versions.toml` 等用户手工修改，未经确认不能覆盖、暂存或提交。

## 9. 给其他 Agent 的最短操作卡

### 只发布 Dev

```bash
git switch dev
git pull --ff-only origin dev
# 必要时先：git merge origin/main
scripts/release.sh dev             # 显式准备 Dev 版本并提交
```

### 发布正式版

```bash
git switch main
git pull --ff-only origin main
git merge --no-ff dev
scripts/release.sh release         # 显式准备正式版本并提交
```

### 检查发布结果

```bash
gh run list --repo yaotutu/nanobotkt --limit 10
gh release list --repo yaotutu/nanobotkt --limit 10
```

### 失败重试

```bash
gh run rerun <RUN_ID> --failed --repo yaotutu/nanobotkt
```

### 绝对不要做

```text
不要在云端手工 bump 版本。
不要连续执行两次发布脚本。
不要把 keystore、密码、Token 提交到仓库。
不要把 Dev 改成另一个 applicationId，否则不能覆盖正式版。
不要为了普通发布构建或上传 Debug APK。
```

## 10. 本地验证命令（不发布）

本地只想检查构建，不想递增版本时：

```bash
# 全工程 JVM 测试，CI 使用同样的串行参数，避免 MockWebServer 测试在高负载时超时。
sh gradlew test --no-parallel --console=plain

# 非 Debug 的 Dev 构建；没有稳定 keystore 时仅用于本地验证，不能作为可升级发布包。
sh gradlew :app:lintDev --console=plain
sh gradlew :app:assembleDev --console=plain

# 非 Debug 的正式构建；没有稳定 keystore 时本地可能只有未签名 APK。
sh gradlew :app:lintRelease --console=plain
sh gradlew :app:assembleRelease --console=plain
```

这些命令不会修改 `version.properties`。正常用户发布仍以 GitHub Actions 使用稳定 Secrets 构建的 APK 为准。
