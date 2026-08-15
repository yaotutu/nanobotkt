#!/usr/bin/env bash
# NanobotKT 本地发布辅助脚本。
#
# 版本号和更新日志必须在本地进入 Git 提交，GitHub Actions 只读取该提交并负责测试、
# 构建和发布。这样远端构建不会再回写 version.properties，开发者本地仓库始终可以
# 通过普通 git push 与云端保持一致。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERSION_FILE="$ROOT_DIR/version.properties"
CHANGELOG_FILE="$ROOT_DIR/docs/CHANGELOG.md"

usage() {
  cat <<'USAGE'
用法：
  scripts/release.sh bump [--version VERSION] [--version-code CODE]
  scripts/release.sh changelog VERSION [OUTPUT_FILE]
  scripts/release.sh prepare dev
  scripts/release.sh prepare release

说明：
  bump       在本地递增 0.1.x 的 x，并同步递增 versionCode。
  changelog  根据上一个 tag 到 HEAD 的 git log 生成 Markdown 更新日志。
  prepare    在本地完成版本递增、更新日志生成和版本提交；不会自动 push。

推荐流程：
  git commit -m "feat: ..."             # 先提交代码
  scripts/release.sh prepare dev        # 本地准备 Dev 版本并提交
  git push origin dev                   # 云端只负责构建和发布
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

calculate_next_version() {
  local current_version=$1
  if [[ "$current_version" =~ ^0\.1\.([0-9]+)$ ]]; then
    printf '0.1.%s\n' "$((BASH_REMATCH[1] + 1))"
  else
    echo "当前版本 $current_version 不是 0.1.x，无法自动递增" >&2
    return 1
  fi
}

validate_version() {
  local version=$1
  local version_code=$2
  [[ "$version" =~ ^0\.1\.[0-9]+$ ]] || {
    echo "版本号必须符合 0.1.x：$version" >&2
    return 1
  }
  [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || {
    echo "versionCode 必须是正整数：$version_code" >&2
    return 1
  }
}

bump_version() {
  local current_version current_code next_version next_code
  current_version=$(read_property VERSION_NAME)
  current_code=$(read_property VERSION_CODE)
  next_version=$(calculate_next_version "$current_version")
  next_code=$((current_code + 1))
  validate_version "$next_version" "$next_code"
  write_version "$next_version" "$next_code"
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$next_version" "$next_code"
}

prepare_release() {
  local release_kind=$1
  local branch expected_branch commit_prefix current_version current_code next_version next_code

  case "$release_kind" in
    dev)
      expected_branch=dev
      commit_prefix='chore(dev): prepare v'
      ;;
    release)
      expected_branch=main
      commit_prefix='chore(release): prepare v'
      ;;
    *)
      echo "发布类型必须是 dev 或 release：$release_kind" >&2
      return 2
      ;;
  esac

  branch=$(git -C "$ROOT_DIR" branch --show-current)
  [[ "$branch" == "$expected_branch" ]] || {
    echo "当前分支是 $branch，$release_kind 发布必须在 $expected_branch 分支执行" >&2
    return 1
  }

  # 先提交业务代码，再运行 prepare；这样版本提交只包含版本文件和更新日志，
  # 避免把未完成的本地修改一起带入可发布版本，也避免脚本覆盖用户改动。
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

  printf '已准备 %s 版本：%s\n' "$release_kind" "$next_version"
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$next_version" "$next_code"
  printf '下一步：git push origin %s\n' "$expected_branch"
}

command=${1:-}
case "$command" in
  bump)
    shift
    # bump 保留为底层能力，便于本地检查或其他脚本复用；它只改文件，不自动提交。
    next_version=
    next_code=
    current_version=$(read_property VERSION_NAME)
    current_code=$(read_property VERSION_CODE)
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
      next_version=$(calculate_next_version "$current_version")
    fi
    validate_version "$next_version" "$next_code"
    write_version "$next_version" "$next_code"
    printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$next_version" "$next_code"
    ;;
  changelog)
    version=${2:?changelog requires VERSION}
    output=${3:-"$CHANGELOG_FILE"}
    build_changelog "$version" "$output"
    printf 'generated %s\n' "$output"
    ;;
  prepare)
    prepare_release "${2:?prepare requires dev or release}"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
