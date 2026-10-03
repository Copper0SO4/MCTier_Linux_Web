#!/usr/bin/env bash
set -Eeuo pipefail
APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVICE="$APP_DIR/bin/mctier-linux-web-service"
CORE="$APP_DIR/bin/binaries/easytier-core"
URL="http://127.0.0.1:14700"
CORE_SHA256="f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322"
fail() { printf 'MCTier Linux: %s\n' "$*" >&2; exit 1; }
[[ "$(id -u)" -ne 0 ]] || fail "请以普通用户运行，不要用 sudo 或 pkexec 启动 MCTier。"
[[ -x "$SERVICE" ]] || fail "缺少本地服务可执行文件：$SERVICE"
[[ -f "$CORE" && ! -L "$CORE" ]] || fail "未找到随包 EasyTier core。请从压缩包完整解压后运行。"
command -v sha256sum >/dev/null || fail "缺少 sha256sum；请安装 coreutils。"
[[ "$(sha256sum -- "$CORE" | cut -d' ' -f1)" == "$CORE_SHA256" ]] || fail "EasyTier core 校验失败，拒绝运行。"
GETCAP="$(command -v getcap || true)"
SETCAP="$(command -v setcap || true)"
PKEXEC="$(command -v pkexec || true)"
[[ -n "$GETCAP" ]] || fail "缺少 getcap；Debian/Ubuntu 请安装 libcap2-bin。"
capability_ok() {
  local value
  value="$("$GETCAP" "$CORE" 2>/dev/null || true)"
  [[ "$(awk '{print $NF}' <<<"$value")" == "cap_net_admin,cap_net_raw=ep" ]]
}
if ! capability_ok; then
  [[ -n "$PKEXEC" && -n "$SETCAP" ]] || fail "EasyTier 缺少 cap_net_admin,cap_net_raw=ep。请安装 policykit-1 和 libcap2-bin 后重试。"
  printf 'EasyTier 缺少 TUN 网络权限。接下来会请求系统授权，仅对随包 easytier-core 设置 capability。\n'
  "$PKEXEC" "$SETCAP" cap_net_admin,cap_net_raw+ep "$CORE" || fail "授权已取消或 setcap 失败；没有以 root 启动任何 MCTier 进程。"
  capability_ok || fail "setcap 后复核未通过。请确认解压位置的文件系统支持 Linux capabilities。"
fi
command -v curl >/dev/null || fail "缺少 curl；请安装 curl。"
command -v xdg-open >/dev/null || fail "缺少 xdg-open；请安装 xdg-utils。"
command -v awk >/dev/null || fail "缺少 awk。"
"$SERVICE" &
SERVICE_PID=$!
cleanup() {
  if kill -0 "$SERVICE_PID" 2>/dev/null; then
    kill -TERM "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" || true
  fi
}
trap cleanup EXIT INT TERM
ready=0
for _ in $(seq 1 40); do
  kill -0 "$SERVICE_PID" 2>/dev/null || fail "本地服务启动失败。请从终端查看上方错误信息。"
  if curl --silent --fail --max-time 1 "$URL/healthz" >/dev/null; then ready=1; break; fi
  sleep 0.25
done
[[ "$ready" -eq 1 ]] || fail "本地服务未能在限定时间内就绪；请确认端口 14700 未被占用。"
printf 'MCTier Linux 服务已就绪，将用系统默认浏览器打开 %s\n关闭此终端或按 Ctrl+C 会停止本地服务。\n' "$URL"
xdg-open "$URL" || printf '自动打开浏览器失败；请手动访问 %s\n' "$URL" >&2
wait "$SERVICE_PID"
