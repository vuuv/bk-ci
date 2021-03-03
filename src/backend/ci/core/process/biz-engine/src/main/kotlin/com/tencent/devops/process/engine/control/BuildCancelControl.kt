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

import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.NormalContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.utils.BuildStatusSwitcher
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.process.engine.control.lock.BuildIdLock
import com.tencent.devops.process.engine.pojo.event.PipelineBuildCancelEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildFinishEvent
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.measure.MeasureService
import com.tencent.devops.process.pojo.mq.PipelineAgentShutdownEvent
import com.tencent.devops.process.pojo.mq.PipelineBuildLessShutdownDispatchEvent
import com.tencent.devops.process.service.BuildVariableService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Suppress("ALL")
@Service
class BuildCancelControl @Autowired constructor(
    private val mutexControl: MutexControl,
    private val redisOperation: RedisOperation,
    private val pipelineMQEventDispatcher: PipelineEventDispatcher,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineBuildDetailService: PipelineBuildDetailService,
    private val buildVariableService: BuildVariableService,
    @Autowired(required = false)
    private val measureService: MeasureService?
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(BuildCancelControl::class.java)
    }

    fun handle(event: PipelineBuildCancelEvent) {
        val watcher = Watcher(id = "ENGINE|BuildCancel|${event.traceId}|${event.buildId}|${event.status}")
        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = event.buildId)
        try {
            watcher.start("lock")
            redisLock.lock()
            watcher.start("execute")
            execute(event)
        } catch (ignored: Exception) {
            LOG.error("ENGINE|${event.buildId}|{${event.source}}|build finish fail: $ignored", ignored)
        } finally {
            redisLock.unlock()
            watcher.stop()
            LogUtils.printCostTimeWE(watcher = watcher)
        }
    }

    private fun execute(event: PipelineBuildCancelEvent): Boolean {

        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId = event.buildId)
        // 已经结束的构建，不再受理，抛弃消息
        if (buildInfo == null || buildInfo.status.isFinish()) {
            LOG.info("[$${event.buildId}|{${event.source}}|REPEAT_CANCEL_EVENT|${event.status}| abandon!")
            return false
        }

        val model = pipelineBuildDetailService.getBuildModel(buildId = event.buildId)
        return if (model != null) {
            LOG.info("ENGINE|${event.buildId}|{${event.source}}|CANCEL|status=${event.status}")

            cancelAllTask(event = event, model = model)
            // 修改detail model
            pipelineBuildDetailService.buildCancel(buildId = event.buildId, buildStatus = event.status)

            sendBuildFinishEvent(event)

            measureService?.postCancelData(
                projectId = event.projectId,
                pipelineId = event.pipelineId,
                buildId = event.buildId,
                userId = event.userId
            )
            true
        } else {
            false
        }
    }

    private fun sendBuildFinishEvent(event: PipelineBuildCancelEvent) {
        pipelineMQEventDispatcher.dispatch(
            PipelineBuildFinishEvent(
                source = "cancel_build",
                projectId = event.projectId,
                pipelineId = event.pipelineId,
                userId = event.userId,
                buildId = event.buildId,
                status = event.status
            )
        )
    }

    private fun cancelAllTask(event: PipelineBuildCancelEvent, model: Model) {

        val variables: Map<String, String> by lazy { buildVariableService.getAllVariable(event.buildId) }
        val executeCount: Int by lazy { buildVariableService.getBuildExecuteCount(buildId = event.buildId) }

        model.stages.forEach { stage ->
            stage.containers.forEach C@{ container ->
                unlockMutexGroup(variables = variables, container = container,
                    buildId = event.buildId, projectId = event.projectId, stageId = stage.id!!
                )
                // 调整Container状态位
                val containerBuildStatus = BuildStatus.parse(container.status)
                if (!containerBuildStatus.isFinish()) {
                    pipelineRuntimeService.updateContainerStatus(
                        buildId = event.buildId,
                        stageId = stage.id ?: "",
                        containerId = container.id ?: "",
                        startTime = null,
                        endTime = LocalDateTime.now(),
                        buildStatus = BuildStatusSwitcher.cancel(containerBuildStatus)
                    )
                }

                // 构建机关机
                if (container is VMBuildContainer) {
                    container.shutdown(event = event, executeCount = executeCount)
                }
            }
        }

        // 发送其他非编译环境关机
        shutdownBuildLess(event = event, executeCount = executeCount)
    }

    private fun shutdownBuildLess(event: PipelineBuildCancelEvent, executeCount: Int) {
        pipelineMQEventDispatcher.dispatch(
            PipelineBuildLessShutdownDispatchEvent(
                source = "shutdownAllBLVMTaskAtom",
                projectId = event.projectId,
                pipelineId = event.pipelineId,
                userId = event.userId,
                buildId = event.buildId,
                buildResult = true,
                vmSeqId = null,
                executeCount = executeCount
            )
        )
    }

    private fun VMBuildContainer.shutdown(event: PipelineBuildCancelEvent, executeCount: Int) {
        pipelineMQEventDispatcher.dispatch(
            PipelineAgentShutdownEvent(
                source = "shutdownAllVMTaskAtom",
                projectId = event.projectId,
                pipelineId = event.pipelineId,
                userId = event.userId,
                buildId = event.buildId,
                buildResult = true,
                vmSeqId = id,
                routeKeySuffix = dispatchType?.routeKeySuffix?.routeKeySuffix,
                executeCount = executeCount
            )
        )
    }

    private fun unlockMutexGroup(
        container: Container,
        buildId: String,
        projectId: String,
        stageId: String,
        variables: Map<String, String>
    ) {

        val mutexGroup = when (container) {
            is VMBuildContainer -> mutexControl.decorateMutexGroup(container.mutexGroup, variables)
            is NormalContainer -> mutexControl.decorateMutexGroup(container.mutexGroup, variables)
            else -> null
        }

        if (mutexGroup?.enable == true && !mutexGroup.mutexGroupName.isNullOrBlank()) {
            mutexControl.releaseContainerMutex(
                projectId = projectId,
                buildId = buildId,
                stageId = stageId,
                containerId = container.id!!,
                mutexGroup = mutexGroup
            )
        }
    }
}
