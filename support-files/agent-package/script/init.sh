#!/bin/sh
# shellcheck disable=SC1090
# CI公共构建机初始化. 本脚本应该兼容 POSIX shell.
# 脚本退出码:
# 0 启动启动成功.
# 20-49 可安全重试, dispatch会重新调度到其他主机. 一般适用于网络问题.
# 50-80 不可重试, dispatch应该停止调度, 报错供用户排查.

ci_log (){
  date=$(date +%Y%m%d-%H%M%S)
  logl=${ci_log_LEVEL:-INFO}
  msg="$date $logl $*"
  if [ -n "$CI_LOG_FILE" ]; then echo "$msg" >> "$CI_LOG_FILE"; fi
  echo "$msg" >&2
}

fail (){
  if [ $# -lt 2 ]; then
    ret=4
    set -- "$0:$LINENO: Usage: fail RET_CODE MESSAGE"
  else
    ret=$1
    shift
  fi
  ci_log_LEVEL=ERROR ci_log "$@"
  exit "$ret"
}

check_env (){
  if [ -z "${devops_project_id}" ]; then
    fail 21 "env var is empty: devops_project_id."  # 如果环境变量异常, 可能为当前dockerhost异常.
  fi
  if [ -z "${devops_agent_id}" ]; then
    fail 21 "env var is empty: devops_agent_id."  # 如果环境变量异常, 可能为当前dockerhost异常.
  fi
  if [ -z "${devops_agent_secret_key}" ]; then
    fail 21 "env var is empty: devops_agent_secret_key."  # 如果环境变量异常, 可能为当前dockerhost异常.
  fi
}
check_cmd (){
  cmd=
  ret=0
  for cmd in "$@"; do
    if ! command -v "$cmd" 2>/dev/null; then
      ci_log "check_cmd: command not found: $cmd."
      ret=$((ret+1))
    fi
  done
  return "$ret"
}

ci_curl (){
  curl -k -s -f -H "X-DEVOPS-BUILD-TYPE: DOCKER" \
    -H "X-DEVOPS-PROJECT-ID: ${devops_project_id}" \
    -H "X-DEVOPS-AGENT-ID: ${devops_agent_id}" \
    -H "X-DEVOPS-AGENT-SECRET-KEY: ${devops_agent_secret_key}" \
    "$@"
  return $?
}

ci_down (){
  ci_down_FILE=$1
  ci_down_URL=$2
  ci_log "ci_down: download $ci_down_URL to $ci_down_FILE."
  for i in 1 2 3 4 5; do
    if ci_curl -o "$ci_down_FILE" "$ci_down_URL"; then
      ret=$?
      break
    else
      ret=$?
      ci_log "curl returns $? when download $ci_down_URL to $ci_down_FILE, try again $i..."
    fi
  done
  return $ret
}

# 此处仅为flag变量, 用于被source的docker_init.sh检查确认执行环境用. 防止误执行.
CI_INIT_TYPE=docker

CI_DIR="/data/devops"
CI_LOG_DIR="$CI_DIR/logs"
CI_LOG_FILE="$CI_LOG_DIR/docker.log"
#touch "$CI_LOG_FILE" || true  # 尝试创建文件.
mkdir -p "$CI_LOG_DIR" || fail 20 "failed to create dir: $CI_LOG_DIR"  # 可能主机磁盘不足, 允许重新调度
cd "$CI_DIR" || fail 54 "failed to cd ci-dir: $CI_DIR"  # 可能执行用户权限及镜像问题. 需要直接退出.

ci_log "init.sh was launched."
# 检查执行环境.
check_env
if ! check_cmd curl java unzip; then
  fail 51 "check_cmd fail, required command(s) was not found."  # 命令不存在, 只能更新镜像.
fi

ci_log "start to download the docker_init.sh..."
# 允许使用环境变量 CI_DOCKER_INIT_URL 改变下载地址.
if [ -z "$CI_DOCKER_INIT_URL" ]; then
  CI_DOCKER_INIT_URL="${devops_gateway}/dispatch/gw/build/script/docker_init.sh"
  ci_log "set CI_DOCKER_INIT_URL to $CI_DOCKER_INIT_URL."
else
  ci_log "pick CI_DOCKER_INIT_URL from env: $CI_DOCKER_INIT_URL."
fi
#sleep 1000
docker_init_filename="docker_init.sh"
if ci_down "$docker_init_filename" "$CI_DOCKER_INIT_URL"; then
  ci_log "download $docker_init_filename success, load it..."
else
  ci_log "failed to download $docker_init_filename. first 10 line is:"
  ci_log "$(head "$docker_init_filename")"
  fail 22 "docker-init-download-failed"  # 如果下载失败, 可能当前主机网络问题, 可重新调度.
fi

#cp -r /data/bkdevops/apps/jdk/1.8.0_161_landun/jre /usr/local/jre
#export PATH="/usr/local/jre/bin:${PATH}"
if sh -n "$docker_init_filename"; then
  # source为bash的alias, dash仅支持点命令进行source.
  . "./$docker_init_filename"
else
  ci_log "shell syntax error: $docker_init_filename, will not load it. first 10 line is:"
  ci_log "$(head "$docker_init_filename")"
  fail 23 "docker-init-bad-syntax"  # 语法错误可能因为下载异常所致, 不直接报错, 尝试重新调度.
fi
ci_log "init.sh end."
exit 0
