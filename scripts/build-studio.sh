#!/usr/bin/env bash
# 构建 Studio 前端并拷贝进 Spring Boot 静态资源，使单个 jar 同时提供 UI 与 API。
# 用法: scripts/build-studio.sh
set -euo pipefail
cd "$(dirname "$0")/../studio"

# 兼容 nvm 环境：非交互 shell 里 npm 可能不在 PATH，按版本目录探测。
NPM_BIN="$(command -v npm || true)"
if [ -z "$NPM_BIN" ]; then
  for CANDIDATE in "$HOME"/.nvm/versions/node/*/bin/npm; do
    if [ -x "$CANDIDATE" ]; then
      export PATH="$(dirname "$CANDIDATE"):$PATH"
      NPM_BIN="$CANDIDATE"
      break
    fi
  done
fi
if [ -z "$NPM_BIN" ]; then
  echo "错误: 找不到 npm，请先安装 Node.js" >&2
  exit 1
fi

"$NPM_BIN" install
"$NPM_BIN" run build

cd ..
rm -rf examples/task-assistant/src/main/resources/static
mkdir -p examples/task-assistant/src/main/resources/static
cp -r studio/dist/* examples/task-assistant/src/main/resources/static/
echo "Studio 已拷贝到 examples/task-assistant/src/main/resources/static"
