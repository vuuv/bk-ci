/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.control

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.process.engine.control.command.container.ContainerCmd
import com.tencent.devops.process.engine.control.command.container.ContainerCmdChain
import com.tencent.devops.process.engine.control.command.container.ContainerContext
import com.tencent.devops.process.engine.control.command.container.impl.CheckConditionalSkipContainerCmd
import com.tencent.devops.process.engine.control.command.container.impl.CheckDependOnContainerCmd
import com.tencent.devops.process.engine.control.command.container.impl.CheckMutexContainerCmd
import com.tencent.devops.process.engine.control.command.container.impl.CheckPauseContainerCmd
import com.tencent.devops.process.engine.control.command.container.impl.ContainerCmdLoop
import com.tencent.devops.process.engine.control.command.container.impl.StartActionTaskContainerCmd
import com.tencent.devops.process.engine.control.command.container.impl.UpdateStateContainerCmdFinally
import com.tencent.devops.process.engine.control.lock.ContainerIdLock
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import com.tencent.devops.process.service.BuildVariableService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 *  Job（运行容器）控制器
 * @version 1.0
 */
@Service
class ContainerControl @Autowired constructor(
    private val redisOperation: RedisOperation,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val buildVariableService: BuildVariableService,
    private val mutexControl: MutexControl
) {

    companion object {
        private const val CACHE_SIZE = 500L
        private val LOG = LoggerFactory.getLogger(ContainerControl::class.java)
    }

    private val commandCache: LoadingCache<Class<out ContainerCmd>, ContainerCmd> = CacheBuilder.newBuilder()
        .maximumSize(CACHE_SIZE).build(
            object : CacheLoader<Class<out ContainerCmd>, ContainerCmd>() {
                override fun load(clazz: Class<out ContainerCmd>): ContainerCmd {
                    return SpringContextUtil.getBean(clazz)
                }
            }
        )

    fun handle(event: PipelineBuildContainerEvent) {
        val watcher = Watcher(id = "ENGINE|ContainerControl|${event.traceId}|${event.buildId}|Job#${event.containerId}")
        with(event) {
            val containerIdLock = ContainerIdLock(redisOperation, buildId, containerId)
            try {
                containerIdLock.lock()
                watcher.start("execute")
                execute(watcher)
            } finally {
                containerIdLock.unlock()
                watcher.stop()
                LogUtils.printCostTimeWE(watcher = watcher)
            }
        }
    }

    private fun PipelineBuildContainerEvent.execute(watcher: Watcher) {

        watcher.start("getContainer")
        val container = pipelineRuntimeService.getContainer(buildId, stageId, containerId) ?: run {
            LOG.warn("ENGINE|$buildId|$source|$stageId|j($containerId)|bad container")
            return
        }

        // 当build的状态是结束的时候，直接返回
        if (container.status.isFinish()) {
            LOG.info("ENGINE|$buildId|$source|$stageId|j($containerId)|status=${container.status}")
            return
        }

        if (container.status == BuildStatus.UNEXEC) {
            LOG.warn("ENGINE|UNEXPECT_STATUS|$buildId|$source|$stageId|j($containerId)|status=${container.status}")
        }

        watcher.start("init_context")
        val variables = buildVariableService.getAllVariable(buildId)
        val mutexGroup = mutexControl.decorateMutexGroup(container.controlOption?.mutexGroup, variables)
        val containerTasks = pipelineRuntimeService.listContainerBuildTasks(buildId, containerId) // 已按任务序号递增排序，如未排序要注意
        val executeCount = buildVariableService.getBuildExecuteCount(buildId)

        val context = ContainerContext(
            buildStatus = container.status, // 初始状态为容器状态，中间流转会切换状态，并最张赋值给容器状态
            mutexGroup = mutexGroup,
            event = this,
            container = container,
            latestSummary = "init",
            watcher = watcher,
            containerTasks = containerTasks,
            variables = variables,
            executeCount = executeCount
        )
        watcher.stop()

        val commandList = listOf<ContainerCmd>(
            commandCache.get(CheckConditionalSkipContainerCmd::class.java), // 检查条件跳过处理
            commandCache.get(CheckPauseContainerCmd::class.java), // 检查暂停处理
            commandCache.get(CheckDependOnContainerCmd::class.java), // 检查DependOn依赖处理
            commandCache.get(CheckMutexContainerCmd::class.java), // 检查Job互斥组处理
            commandCache.get(StartActionTaskContainerCmd::class.java), // 检查启动事件消息
            commandCache.get(ContainerCmdLoop::class.java), // 发送本事件的循环消息
            commandCache.get(UpdateStateContainerCmdFinally::class.java) // 更新Job状态并可能返回Stage处理
        )

        ContainerCmdChain(commandList).doCommand(context)
    }
}
