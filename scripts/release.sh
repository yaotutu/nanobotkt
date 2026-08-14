#!/usr/bin/env bash
# NanobotKT 发布辅助脚本。
#
# 该脚本只负责生成“下一版”版本号和变更日志文件，不执行 git commit/push，
# 这样本地预览和 GitHub Actions 都可以安全复用，真正的提交边界由调用方控制。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERSION_FILE="$ROOT_DIR/version.properties"
CHANGELOG_FILE="$ROOT_DIR/docs/CHANGELOG.md"

usage() {
  cat <<'USAGE'
用法：
  scripts/release.sh bump [--version VERSION] [--version-code CODE]
  scripts/release.sh changelog VERSION [OUTPUT_FILE]

说明：
  bump       递增 0.1.x 的 x，并同步递增 versionCode；也可显式传入版本。
  changelog  根据上一个 tag 到 HEAD 的 git log 生成 Markdown 更新日志。
USAGE
}

read_property() {
  local key=$1
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$VERSION_FILE"
}

write_version() {
  local version=$1
  local version_code=$2
  VERSION_NAME="$version" VERSION_CODE="$version_code" python3 - "$VERSION_FILE" <<'PY'
from pathlib import Path
import os
import re
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
values = {
    "VERSION_NAME": os.environ["VERSION_NAME"],
    "VERSION_CODE": os.environ["VERSION_CODE"],
}
for key, value in values.items():
    text, count = re.subn(rf"(?m)^{key}=.*$", f"{key}={value}", text, count=1)
    if count != 1:
        raise SystemExit(f"missing {key} in {path}")
path.write_text(text, encoding="utf-8")
PY
}

build_changelog() {
  local version=$1
  local output=$2
  local previous_tag
  previous_tag=$(git -C "$ROOT_DIR" describe --tags --match 'v*' --abbrev=0 2>/dev/null || true)

  {
    printf '# NanobotKT %s\n\n' "$version"
    printf '> 自动构建发布，提交：`%s`。\n\n' "$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
    if [[ -n "$previous_tag" ]]; then
      printf '## 变更\n\n'
      git -C "$ROOT_DIR" log --pretty=format:'- %s (`%h`)' "$previous_tag..HEAD"
      printf '\n'
    else
      printf '## 变更\n\n'
      git -C "$ROOT_DIR" log --pretty=format:'- %s (`%h`)' -20
      printf '\n'
    fi
  } > "$output"
}

command=${1:-}
case "$command" in
  bump)
    shift
    current_version=$(read_property VERSION_NAME)
    current_code=$(read_property VERSION_CODE)
    next_version=
    next_code=$((current_code + 1))
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --version)
          next_version=${2:?--version requires a value}
          shift 2
          ;;
        --version-code)
          next_code=${2:?--version-code requires a value}
          shift 2
          ;;
        *)
          usage >&2
          exit 2
          ;;
      esac
    done

    if [[ -z "$next_version" ]]; then
      if [[ "$current_version" =~ ^0\.1\.([0-9]+)$ ]]; then
        next_version="0.1.$((BASH_REMATCH[1] + 1))"
      else
        echo "当前版本 $current_version 不是 0.1.x，无法自动递增" >&2
        exit 1
      fi
    fi

    [[ "$next_version" =~ ^0\.1\.[0-9]+$ ]] || {
      echo "版本号必须符合 0.1.x：$next_version" >&2
      exit 1
    }
    [[ "$next_code" =~ ^[1-9][0-9]*$ ]] || {
      echo "versionCode 必须是正整数：$next_code" >&2
      exit 1
    }

    write_version "$next_version" "$next_code"
    printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$next_version" "$next_code"
    ;;
  changelog)
    version=${2:?changelog requires VERSION}
    output=${3:-"$ROOT_DIR/docs/CHANGELOG.md"}
    build_changelog "$version" "$output"
    printf 'generated %s\n' "$output"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
