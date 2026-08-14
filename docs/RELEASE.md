# NanobotKT 自动构建与发布说明

项目现在区分两个**非 Debug 构建**：

| 构建 | 触发分支 | Gradle 任务 | 包名 | GitHub 发布形式 |
|---|---|---|---|---|
| dev 测试版 | `dev` | `:app:assembleDev` | `com.nanobotkt.dev` | 滚动预发布 `dev-latest` |
| 正式版 | `main` | `:app:assembleRelease` | `com.nanobotkt` | 版本化 Release，例如 `v0.1.1` |

## 覆盖安装规则（重要）

Android 覆盖安装要求两个条件同时满足：

1. 包名相同；
2. 签名证书相同。

因此，`dev` 测试版和正式版都不能每次使用临时生成的签名。当前方案是：

- 所有 `dev` 分支 push 的 `dev-latest` 都使用同一份稳定 keystore；
- 所有 `main` 分支正式 Release 也使用这份稳定 keystore；
- `dev` 使用独立包名 `com.nanobotkt.dev`，所以 dev 会覆盖安装之前的 dev，正式版会覆盖安装之前的正式版，但两条渠道互不覆盖；
- Pull Request 构建不读取发布签名 Secrets，可以使用临时 debug 签名，仅用于 CI 构建验证，不能当作给用户升级的发布包。

也就是说，用户从 `dev-latest` 下载的测试版，下一次继续安装新的 `dev-latest` 时可以直接覆盖安装，不需要先卸载。正式版同理。

## 第一次配置签名（只需做一次）

你不需要品牌配置，也不需要购买证书。Android 应用使用本地生成的一份 `.jks` 文件即可。

在自己的电脑上执行：

```bash
./scripts/create-release-keystore.sh
```

脚本会在当前项目目录生成 `nanobotkt-release.keystore`，并提示你妥善备份。**不要把这个文件提交到 Git，也不要把密码写进代码。**

然后把同一份 keystore 配置到 GitHub 仓库的 `Settings → Secrets and variables → Actions`，新建以下 4 个 Repository secrets：

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

## dev 测试版

向 `dev` 分支 push 后自动：

1. 执行全工程 JVM 测试；
2. 执行 `lintDev`；
3. 使用稳定 keystore 构建 `assembleDev`，不会构建 Debug APK；
4. 上传 Actions artifact；
5. 更新 GitHub 上的 `dev-latest` prerelease。

Pull Request 只做构建验证，不会修改 GitHub Release，也不要求配置签名 Secrets。

## 正式版

向 `main` 分支 push 后自动：

1. 递增 `0.1.x` 的补丁号，例如 `0.1.0` → `0.1.1`；
2. 同步递增 Android `versionCode`；
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

## 本地构建

```bash
# 开发版，非 Debug；没有稳定 keystore 时仅用于本地验证
./gradlew :app:assembleDev --console=plain

# 正式版，非 Debug；没有稳定 keystore 时可生成未签名产物，但不能给用户安装
./gradlew :app:assembleRelease --console=plain
```
