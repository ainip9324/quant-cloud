#!/bin/bash
# ============================================================
# Java 服务健康检查脚本：通过 Actuator /actuator/health 探测
#
# 相比纯 TCP 端口探测，真实发起 HTTP 请求并校验响应体含 "status":"UP"，
# 能检测到"端口已监听但应用未就绪"的情况（如启动中/健康检查失败）。
#
# 环境变量：
#   HEALTHCHECK_PORT  服务端口（data-service=8081, core-service=8082, gateway=8080）
#
# 依赖 bash /dev/tcp（eclipse-temurin JRE 镜像自带，无需安装 curl/wget）
# ============================================================
set -e

PORT="${HEALTHCHECK_PORT:-8080}"

# 建立 TCP 连接并发送 HTTP/1.0 GET 请求（HTTP/1.0 响应结束即关闭连接）
exec 3<>"/dev/tcp/127.0.0.1/${PORT}"
printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3
RESPONSE=$(cat <&3)
exec 3<&- 3>&-

# 校验响应体包含 status:"UP"（grep 退出码即脚本退出码）
echo "${RESPONSE}" | grep -q '"status":"UP"'
