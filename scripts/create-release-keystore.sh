#!/usr/bin/env bash
# 创建 NanobotKT 的稳定 Android 发布签名。
#
# 这份 keystore 决定后续 dev 测试版和正式版是否能够覆盖安装更新。
# 脚本只在本地生成文件，不上传 GitHub、不读取或保存密码，也不修改任何仓库配置。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
KEYSTORE_FILE="${1:-$ROOT_DIR/nanobotkt-release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-nanobotkt}"

if ! command -v keytool >/dev/null 2>&1; then
  echo '未找到 keytool。请先安装 JDK 17 或更高版本。' >&2
  exit 1
fi

if [[ -e "$KEYSTORE_FILE" ]]; then
  echo "文件已存在，不覆盖：$KEYSTORE_FILE" >&2
  echo '如需使用已有签名，请直接备份该文件并配置 GitHub Secrets。' >&2
  exit 1
fi

cat <<INFO
即将创建稳定 Android 发布签名：
  文件：$KEYSTORE_FILE
  别名：$KEY_ALIAS

请在接下来的提示中设置并记住 keystore 密码和 key 密码。
这两个密码无法从 keystore 恢复；丢失后将无法继续发布可覆盖安装的更新。
INFO

# 不在命令行参数中写密码，让 keytool 通过交互式提示读取，避免密码出现在 shell 历史和 CI 日志中。
keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

chmod 600 "$KEYSTORE_FILE"

cat <<INFO

已创建：$KEYSTORE_FILE
请立即完成以下操作：
1. 把该文件复制到安全位置做离线备份；
2. 执行下面的命令生成 KEYSTORE_BASE64，并粘贴到 GitHub Secret：

   base64 < "$KEYSTORE_FILE" | tr -d '\\n'

3. 配置 KEYSTORE_PASSWORD、KEY_ALIAS=$KEY_ALIAS、KEY_PASSWORD 三个 Secret；
4. 确认该文件不会被 git add 或上传到公开位置。
INFO
