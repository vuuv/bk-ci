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

import com.tencent.devops.common.api.pojo.ErrorCode
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_TIMEOUT_IN_BUILD_QUEUE
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_TIMEOUT_IN_RUNNING
import com.tencent.devops.process.engine.common.Timeout
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.pojo.BuildInfo
import com.tencent.devops.process.engine.pojo.PipelineBuildContainer
import com.tencent.devops.process.engine.pojo.PipelineBuildStage
import com.tencent.devops.process.engine.pojo.event.PipelineBuildFinishEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildMonitorEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildStartEvent
import com.tencent.devops.process.engine.service.PipelineRuntimeExtService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.PipelineSettingService
import com.tencent.devops.process.engine.service.PipelineStageService
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 构建控制器
 * @version 1.0
 */
@Service
class BuildMonitorControl @Autowired constructor(
    private val buildLogPrinter: BuildLogPrinter,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val pipelineSettingService: PipelineSettingService,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineRuntimeExtService: PipelineRuntimeExtService,
    private val pipelineStageService: PipelineStageService
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(BuildMonitorControl::class.java)
    }

    fun handle(event: PipelineBuildMonitorEvent): Boolean {

        val buildId = event.buildId
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
        if (buildInfo == null || buildInfo.status.isFinish()) {
            LOG.info("ENGINE|$buildId|${event.source}|BUILD_MONITOR|status=${buildInfo?.status}")
            return true
        }

        return when {
            buildInfo.status.isReadyToRun() -> monitorQueueBuild(event, buildInfo)
            else -> {
                monitorPipeline(event)
            }
        }
    }

    private fun monitorPipeline(event: PipelineBuildMonitorEvent): Boolean {

        // 由于30天对应的毫秒数值过大，以Int的上限值作为下一次monitor时间
        val stageMinInt = min(monitorStage(event), Int.MAX_VALUE.toLong()).toInt()
        val jobMinInt = monitorContainer(event)

        val minInterval = min(jobMinInt, stageMinInt)

        if (minInterval < min(Timeout.CONTAINER_MAX_MILLS.toLong(), Timeout.STAGE_MAX_MILLS)) {
            LOG.info("ENGINE|${event.buildId}|${event.source}|BUILD_MONITOR_CONTINUE|Interval=$minInterval")
            event.delayMills = minInterval
            pipelineEventDispatcher.dispatch(event)
        }
        return true
    }

    private fun monitorContainer(event: PipelineBuildMonitorEvent): Int {

        val containers = pipelineRuntimeService.listContainers(event.buildId)
            .filter { !it.status.isFinish() }

        var minInterval = Timeout.CONTAINER_MAX_MILLS

        if (containers.isEmpty()) {
            LOG.info("ENGINE|${event.buildId}|${event.source}|BUILD_CONTAINER_MONITOR|empty containers")
            return minInterval
        }

        containers.forEach { container ->
            val interval = container.checkNextContainerMonitorIntervals(event.userId)
            // 根据最小的超时时间来决定下一次监控执行的时间
            if (interval in 1 until minInterval) {
                minInterval = interval
            }
        }
        return minInterval
    }

    private fun monitorStage(event: PipelineBuildMonitorEvent): Long {

        val stages = pipelineStageService.listStages(event.buildId)
            .filter { !it.status.isFinish() }

        var minInterval = Timeout.STAGE_MAX_MILLS

        if (stages.isEmpty()) {
            LOG.info("ENGINE|${event.buildId}|${event.source}|BUILD_STAGE_MONITOR|empty stage")
            return minInterval
        }

        stages.forEach Next@{ stage ->
            if (!stage.status.isFinish()) {
                val interval = stage.checkNextStageMonitorIntervals(event.userId)
                // 根据最小的超时时间来决定下一次监控执行的时间
                if (interval in 1 until minInterval) {
                    minInterval = interval
                }
            }
        }

        return minInterval
    }

    private fun PipelineBuildContainer.checkNextContainerMonitorIntervals(userId: String): Int {

        var interval = 0

        if (status.isFinish()) {
            return interval
        }
        val (minute: Int, timeoutMills: Long) = Timeout.transMinuteTimeoutToMills(
            timeoutMinutes = controlOption?.jobControlOption?.timeout
        )
        val usedTimeMills: Long = if (status.isRunning() && startTime != null) {
            System.currentTimeMillis() - startTime!!.timestampmilli()
        } else {
            0
        }

        interval = (timeoutMills - usedTimeMills).toInt()
        if (interval <= 0) {
            val errorInfo = MessageCodeUtil.generateResponseDataObject<String>(
                messageCode = ERROR_TIMEOUT_IN_RUNNING,
                params = arrayOf("Job", "$minute")
            )
            buildLogPrinter.addRedLine(
                buildId = buildId,
                message = errorInfo.message ?: "Job timeout($minute) min",
                tag = VMUtils.genStartVMTaskId(containerId),
                jobId = containerId,
                executeCount = executeCount
            )
            // 终止当前容器下的任务
            pipelineEventDispatcher.dispatch(
                PipelineBuildContainerEvent(
                    source = "running_timeout",
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    buildId = buildId,
                    stageId = stageId,
                    containerId = containerId,
                    containerType = containerType,
                    actionType = ActionType.TERMINATE,
                    reason = errorInfo.message ?: "Job timeout($minute) min!",
                    errorCode = ErrorCode.USER_JOB_OUTTIME_LIMIT,
                    errorTypeName = ErrorType.USER.name
                )
            )
        }

        return interval
    }

    private fun PipelineBuildStage.checkNextStageMonitorIntervals(userId: String): Long {
        var interval: Long = 0

        if (status.isFinish() || controlOption?.stageControlOption?.manualTrigger != true) {
            return interval
        }

        var hours = controlOption?.stageControlOption?.timeout ?: Timeout.DEFAULT_STAGE_TIMEOUT_HOURS
        if (hours <= 0 || hours > Timeout.MAX_HOURS) {
            hours = Timeout.MAX_HOURS.toInt()
        }
        val timeoutMills = TimeUnit.HOURS.toMillis(hours.toLong())

        val usedTimeMills: Long = if (startTime != null) {
            System.currentTimeMillis() - startTime!!.timestampmilli()
        } else {
            0
        }

        interval = timeoutMills - usedTimeMills
        if (interval <= 0) {
            buildLogPrinter.addRedLine(
                buildId = buildId,
                message = "Stage Review timeout $hours hours. Shutdown build!",
                tag = stageId,
                jobId = "",
                executeCount = executeCount
            )
            pipelineStageService.cancelStage(userId = userId, buildStage = this)
        }

        return interval
    }

    private fun monitorQueueBuild(event: PipelineBuildMonitorEvent, buildInfo: BuildInfo): Boolean {
        // 判断是否超时
        if (pipelineSettingService.isQueueTimeout(event.pipelineId, buildInfo.startTime!!)) {
            LOG.info("ENGINE|${event.buildId}|${event.source}|BUILD_QUEUE_MONITOR_TIMEOUT|queue timeout")
            val errorInfo = MessageCodeUtil.generateResponseDataObject<String>(
                messageCode = ERROR_TIMEOUT_IN_BUILD_QUEUE,
                params = arrayOf(event.buildId)
            )
            buildLogPrinter.addRedLine(
                buildId = event.buildId,
                message = errorInfo.message ?: "Queue timeout. Cancel build!",
                tag = "QUEUE_TIME_OUT",
                jobId = "",
                executeCount = 1
            )
            pipelineEventDispatcher.dispatch(
                PipelineBuildFinishEvent(
                    source = "queue_timeout",
                    projectId = event.projectId,
                    pipelineId = event.pipelineId,
                    userId = event.userId,
                    buildId = event.buildId,
                    status = BuildStatus.QUEUE_TIMEOUT,
                    errorType = ErrorType.USER,
                    errorCode = ErrorCode.USER_JOB_OUTTIME_LIMIT,
                    errorMsg = "Job排队超时，请检查并发配置/Queue timeout"
                )
            )
        } else {
            // 判断当前监控的排队构建是否可以尝试启动(仅当前是在队列中排第1位的构建可以)
            val canStart = pipelineRuntimeExtService.queueCanPend2Start(
                projectId = event.projectId, pipelineId = event.pipelineId, buildId = buildInfo.buildId
            )
            if (canStart) {
                LOG.info("ENGINE|${event.buildId}|${event.source}|BUILD_QUEUE_TRY_START")
                pipelineEventDispatcher.dispatch(
                    PipelineBuildStartEvent(
                        source = "start_monitor",
                        projectId = buildInfo.projectId,
                        pipelineId = buildInfo.pipelineId,
                        userId = buildInfo.startUser,
                        buildId = buildInfo.buildId,
                        taskId = buildInfo.firstTaskId,
                        status = BuildStatus.RUNNING,
                        actionType = ActionType.START
                    )
                )
            }
            // next time to loop monitor
            pipelineEventDispatcher.dispatch(event)
        }

        return true
    }
}
