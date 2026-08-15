#!/usr/bin/env bash
# NanobotKT 本地发布脚本。
#
# 发布类型由唯一参数显式指定，同时必须与当前 Git 分支匹配：
# - scripts/release.sh dev     只能在 dev 分支准备 Dev 版本；
# - scripts/release.sh release 只能在 main 分支准备正式版本。
#
# 显式参数避免调用者仅凭当前目录猜测发布类型；分支校验则防止版本号递增到错误分支。
#
# 版本号和更新日志必须在本地进入 Git 提交，GitHub Actions 只读取该提交并负责测试、
# 构建和发布。脚本只创建本地提交，不会自动 push，避免未经确认修改远程分支。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERSION_FILE="$ROOT_DIR/version.properties"
CHANGELOG_FILE="$ROOT_DIR/docs/CHANGELOG.md"

usage() {
  cat <<'USAGE'
用法：
  scripts/release.sh dev
  scripts/release.sh release
  scripts/release.sh --help

说明：
  dev      只能在 dev 分支执行，递增版本并创建 Dev 版本提交。
  release  只能在 main 分支执行，递增版本并创建正式版本提交。

脚本会在本地完成版本递增、更新日志生成和 Git 提交，但不会自动 push。
提交完成后执行：git push origin <当前分支>
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
    printf '## 变更\n\n'
    if [[ -n "$previous_tag" ]]; then
      git -C "$ROOT_DIR" log --pretty=format:'- %s (`%h`)' "$previous_tag..HEAD"
    else
      git -C "$ROOT_DIR" log --pretty=format:'- %s (`%h`)' -20
    fi
    printf '\n'
  } > "$output"
}

calculate_next_version() {
  local current_version=$1
  if [[ "$current_version" =~ ^0\.1\.([0-9]+)$ ]]; then
    printf '0.1.%s\n' "$((BASH_REMATCH[1] + 1))"
  else
    echo "当前版本 ${current_version} 不是 0.1.x，无法自动递增" >&2
    return 1
  fi
}

validate_version() {
  local version=$1
  local version_code=$2
  [[ "$version" =~ ^0\.1\.[0-9]+$ ]] || {
    echo "版本号必须符合 0.1.x：${version}" >&2
    return 1
  }
  [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || {
    echo "versionCode 必须是正整数：${version_code}" >&2
    return 1
  }
}

prepare_release() {
  local requested_kind=$1
  local branch expected_branch release_kind commit_prefix
  local current_version current_code next_version next_code

  # 参数先决定发布语义，再用当前分支做第二层保护。两者不一致时立即退出，
  # 不修改版本文件，也不生成半成品更新日志。
  case "$requested_kind" in
    dev)
      expected_branch=dev
      release_kind='Dev 版本'
      commit_prefix='chore(dev): prepare v'
      ;;
    release)
      expected_branch=main
      release_kind='正式版本'
      commit_prefix='chore(release): prepare v'
      ;;
    *)
      echo "发布类型必须是 dev 或 release：${requested_kind}" >&2
      return 2
      ;;
  esac

  branch=$(git -C "$ROOT_DIR" branch --show-current)
  if [[ "$branch" != "$expected_branch" ]]; then
    echo "当前分支是 ${branch}，${requested_kind} 发布必须在 ${expected_branch} 分支执行。" >&2
    return 1
  fi

  # 版本提交只包含版本文件和更新日志；业务代码必须在运行脚本前已经提交。
  # 这样可以避免脚本把未完成的本地修改一起带入可发布版本，也避免覆盖用户改动。
  if [[ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=all)" ]]; then
    echo '工作区不是干净状态，请先提交或处理现有修改后再准备发布。' >&2
    return 1
  fi

  current_version=$(read_property VERSION_NAME)
  current_code=$(read_property VERSION_CODE)
  next_version=$(calculate_next_version "$current_version")
  next_code=$((current_code + 1))
  validate_version "$next_version" "$next_code"

  write_version "$next_version" "$next_code"
  build_changelog "$next_version" "$CHANGELOG_FILE"
  git -C "$ROOT_DIR" add version.properties docs/CHANGELOG.md
  git -C "$ROOT_DIR" commit -m "$commit_prefix$next_version"

  printf '已准备%s：%s\n' "$release_kind" "$next_version"
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$next_version" "$next_code"
  printf '下一步：git push origin %s\n' "$expected_branch"
}

case "${1:-}" in
  dev|release)
    [[ $# -eq 1 ]] || {
      echo '发布脚本只接受一个发布类型参数。' >&2
      usage >&2
      exit 2
    }
    prepare_release "$1"
    ;;
  --help|-h)
    usage
    ;;
  "")
    echo '缺少发布类型参数：dev 或 release。' >&2
    usage >&2
    exit 2
    ;;
  *)
    echo "未知发布类型：${1}" >&2
    usage >&2
    exit 2
    ;;
esac
