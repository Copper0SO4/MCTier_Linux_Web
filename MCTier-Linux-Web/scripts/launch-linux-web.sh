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
SERVICE="$(readlink -f -- "$SERVICE")"
is_service_exe() {
  local exe
  exe="$(readlink -- "$1" 2>/dev/null || true)"
  exe="${exe% (deleted)}"
  [[ "$exe" == "$SERVICE" ]]
}
service_pids() {
  local proc pid
  for proc in /proc/[0-9]*/exe; do
    [[ -e "$proc" ]] || continue
    pid="${proc#/proc/}"
    pid="${pid%/exe}"
    [[ -O "/proc/$pid" ]] || continue
    is_service_exe "$proc" && printf '%s\n' "$pid"
  done
}
if [[ "${1:-}" == "--stop" ]]; then
  mapfile -t running_pids < <(service_pids)
  if ((${#running_pids[@]} == 0)); then
    printf 'MCTier Linux: 没有找到由此安装位置启动的服务。\n'
    exit 0
  fi
  printf '正在请求停止 MCTier 服务（PID: %s）…\n' "${running_pids[*]}"
  kill -TERM "${running_pids[@]}" 2>/dev/null || true
  for _ in $(seq 1 50); do
    remaining=()
    for pid in "${running_pids[@]}"; do
      is_service_exe "/proc/$pid/exe" && remaining+=("$pid")
    done
    ((${#remaining[@]} == 0)) && { printf 'MCTier Linux 服务已停止。\n'; exit 0; }
    sleep 0.2
  done
  fail "已发送停止信号，但服务仍在退出（PID: ${remaining[*]}）。请稍后检查。"
fi
[[ "${1:-}" == "" || "${1:-}" == "--help" ]] || fail "用法：mctier [--stop|--help]"
if [[ "${1:-}" == "--help" ]]; then
  printf '用法：mctier 启动前台服务；mctier --stop 停止此安装位置的服务。\n'
  exit 0
fi
command -v curl >/dev/null || fail "缺少 curl；请安装 curl。"
command -v xdg-open >/dev/null || fail "缺少 xdg-open；请安装 xdg-utils。"
command -v awk >/dev/null || fail "缺少 awk。"
mapfile -t running_pids < <(service_pids)
((${#running_pids[@]} == 0)) || fail "MCTier 服务已经在运行（PID: ${running_pids[*]}）。使用 mctier --stop 停止后再启动。"
if curl --silent --fail --max-time 1 "$URL/healthz" >/dev/null; then
  fail "端口 14700 已有服务响应；本次没有启动 MCTier。若这是旧版遗留的 MCTier 服务，请先查明并停止它，再重试。"
fi
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
"$SERVICE" &
SERVICE_PID=$!
cleanup() {
  trap - EXIT INT TERM HUP
  if kill -0 "$SERVICE_PID" 2>/dev/null; then
    kill -TERM "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
child_running() {
  local pid
  while IFS= read -r pid; do
    [[ "$pid" == "$SERVICE_PID" ]] && return 0
  done < <(jobs -pr)
  return 1
}
ready=0
for _ in $(seq 1 40); do
  child_running || { wait "$SERVICE_PID" || true; fail "本地服务启动失败。请从终端查看上方错误信息。"; }
  if curl --silent --fail --max-time 1 "$URL/healthz" >/dev/null; then
    child_running || { wait "$SERVICE_PID" || true; fail "本地服务启动失败；健康检查连接到了其他进程。"; }
    ready=1
    break
  fi
  sleep 0.25
done
[[ "$ready" -eq 1 ]] || fail "本地服务未能在限定时间内就绪；请确认端口 14700 未被占用。"
printf 'MCTier Linux 服务已就绪，将用系统默认浏览器打开 %s\n此命令保持前台运行；按 Ctrl+C 或在另一终端执行 mctier --stop 可停止服务。\n' "$URL"
xdg-open "$URL" || printf '自动打开浏览器失败；请手动访问 %s\n' "$URL" >&2
wait "$SERVICE_PID"
