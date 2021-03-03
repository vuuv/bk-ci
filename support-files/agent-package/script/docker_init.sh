#!/bin/sh
## Download the docker.jar
export LANG="zh_CN.UTF-8"

if [ "x$CI_INIT_TYPE" != xdocker ]; then
  fail 55 "docker_init.sh should be called by init.sh in docker build host."  # 运行环境检查.
  # 防止fail函数未定义.
  echo >&2 "this file should be sourced by init.sh."
  exit 55
fi
ci_log "docker_init.sh was launched."

# 允许使用环境变量 CI_WORKER_AGENT_URL 改变下载地址.
if [ -z "$CI_WORKER_AGENT_URL" ]; then
  CI_WORKER_AGENT_URL="${devops_gateway}/dispatch/gw/build/jar/worker-agent.jar"
  ci_log "set CI_WORKER_AGENT_URL to $CI_WORKER_AGENT_URL."
else
  ci_log "pick CI_WORKER_AGENT_URL from env: $CI_WORKER_AGENT_URL."
fi

ci_log "start to download the docker.jar..."

worker_agent_filename="worker-agent.jar"
if ci_down "$worker_agent_filename" "$CI_WORKER_AGENT_URL"; then
  ci_log "download $worker_agent_filename success, load it.. first 2 char is: $(head -c 2 "$worker_agent_filename")."
else
  ci_log "failed to download $worker_agent_filename. first 2 char is: $(head -c 2 "$worker_agent_filename")."
  fail 22 "docker-init-download-failed"  # 如果下载失败, 可能当前主机网络问题, 可重新调度.
fi

jar_manifest_path="META-INF/MANIFEST.MF"
jar_manifest_content=$(unzip -p "$worker_agent_filename" "$jar_manifest_path")
if [ -z "$jar_manifest_content" ]; then
  fail 25 "worker-agent-empty-manifest"  # 获取manifest为空, 可能内容变了, 但也尝试重新调度.
fi

## 考虑使用通用的java.
## -cp bcprov:worker-agent
#main_class=$(echo "$jar_manifest_content" | awk '/[Mm]ain-[Cc]lass:/{print $2}')
#if [ -z "$jar_manifest_content" ]; then
#  fail 57 "worker-agent-manifest-no-main-class"  # 获取main-class为空, 及早退出.
#else
#  ci_log "Main-Class of $worker_agent_filename is $main_class."
#fi
# fail 58 预留其他java预检函数用.

ci_log "run java $worker_agent_filename, args: $*."
/usr/local/jre/bin/java -Dfile.encoding=UTF-8 -DLC_CTYPE=UTF-8 -Dlandun.env=prod -Dbuild.type=DOCKER -Ddevops.gateway="${devops_gateway}"  -jar "$worker_agent_filename" "$@" >> "$CI_LOG_FILE" 2>&1 || fail 59 "java returns $?"  # 如果java启动失败, 应该记录, 且不宜自动重试.
#/usr/local/jre/bin/java -Dfile.encoding=UTF-8 -DLC_CTYPE=UTF-8 -Dlandun.env=dev -jar docker.jar $@ >> /data/devops/logs/docker.log
ci_log "docker_init.sh end."
