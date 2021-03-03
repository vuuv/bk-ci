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

package com.tencent.devops.process.engine.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.base.Preconditions
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.BuildFormProperty
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.pipeline.pojo.element.agent.ManualReviewUserTaskElement
import com.tencent.devops.common.pipeline.pojo.element.quality.QualityGateInElement
import com.tencent.devops.common.pipeline.pojo.element.quality.QualityGateOutElement
import com.tencent.devops.common.pipeline.utils.ModelUtils
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.common.websocket.enum.RefreshType
import com.tencent.devops.model.process.tables.records.TPipelineBuildDetailRecord
import com.tencent.devops.process.dao.BuildDetailDao
import com.tencent.devops.process.engine.control.ControlUtils
import com.tencent.devops.process.engine.dao.PipelineBuildDao
import com.tencent.devops.process.engine.dao.PipelineBuildSummaryDao
import com.tencent.devops.process.engine.dao.PipelinePauseValueDao
import com.tencent.devops.process.engine.pojo.PipelineBuildStageControlOption
import com.tencent.devops.process.engine.pojo.event.PipelineBuildWebSocketPushEvent
import com.tencent.devops.process.engine.utils.PauseRedisUtils
import com.tencent.devops.process.pojo.BuildStageStatus
import com.tencent.devops.process.pojo.VmInfo
import com.tencent.devops.process.pojo.pipeline.ModelDetail
import com.tencent.devops.process.service.BuildVariableService
import com.tencent.devops.process.service.PipelineTaskPauseService
import com.tencent.devops.process.utils.PipelineVarUtil
import com.tencent.devops.store.api.atom.ServiceMarketAtomEnvResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Suppress("ALL")
@Service
class PipelineBuildDetailService @Autowired constructor(
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
    private val buildDetailDao: BuildDetailDao,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val buildVariableService: BuildVariableService,
    private val redisOperation: RedisOperation,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val pipelineTaskPauseService: PipelineTaskPauseService,
    private val pipelineBuildSummaryDao: PipelineBuildSummaryDao,
    private val client: Client,
    private val pipelineBuildDao: PipelineBuildDao,
    private val pipelinePauseValueDao: PipelinePauseValueDao
) {

    companion object {
        val logger = LoggerFactory.getLogger(PipelineBuildDetailService::class.java)!!
        private const val ExpiredTimeInSeconds: Long = 10
    }

    /**
     * 查询ModelDetail
     * @param buildId: 构建Id
     * @param refreshStatus: 是否刷新状态
     */
    fun get(buildId: String, refreshStatus: Boolean = true): ModelDetail? {

        val record = buildDetailDao.get(dslContext, buildId) ?: return null

        val buildInfo = pipelineBuildDao.convert(pipelineBuildDao.getBuildInfo(dslContext, buildId)) ?: return null

        val latestVersion = pipelineRepositoryService.getPipelineInfo(buildInfo.pipelineId)?.version ?: -1

        val buildSummaryRecord = pipelineBuildSummaryDao.get(dslContext, buildInfo.pipelineId)

        val model = JsonUtil.to(record.model, Model::class.java)

        // 构建机环境的会因为构建号不一样工作空间可能被覆盖的问题, 所以构建号不同不允许重试
        val canRetry =
            buildSummaryRecord?.buildNum == buildInfo.buildNum && buildInfo.status.isFailure() // 并且是失败后

        // 判断需要刷新状态，目前只会改变canRetry状态
        if (refreshStatus) {
            ModelUtils.refreshCanRetry(model, canRetry, buildInfo.status)
        }

        val triggerContainer = model.stages[0].containers[0] as TriggerContainer
        val buildNo = triggerContainer.buildNo
        if (buildNo != null) {
            buildNo.buildNo = pipelineBuildSummaryDao.get(dslContext, buildInfo.pipelineId)?.buildNo
                ?: buildNo.buildNo
        }
        val params = triggerContainer.params
        val newParams = mutableListOf<BuildFormProperty>()
        params.forEach {
            // 变量名从旧转新: 兼容从旧入口写入的数据转到新的流水线运行
            val newVarName = PipelineVarUtil.oldVarToNewVar(it.id)
            if (!newVarName.isNullOrBlank()) {
                newParams.add(
                    BuildFormProperty(
                        id = newVarName!!,
                        required = it.required,
                        type = it.type,
                        defaultValue = it.defaultValue,
                        options = it.options,
                        desc = it.desc,
                        repoHashId = it.repoHashId,
                        relativePath = it.relativePath,
                        scmType = it.scmType,
                        containerType = it.containerType,
                        glob = it.glob,
                        properties = it.properties
                    )
                )
            } else newParams.add(it)
        }
        triggerContainer.params = newParams

        return ModelDetail(
            id = record.buildId,
            pipelineId = buildInfo.pipelineId,
            pipelineName = model.name,
            userId = record.startUser ?: "",
            trigger = StartType.toReadableString(buildInfo.trigger, buildInfo.channelCode),
            startTime = record.startTime?.timestampmilli() ?: LocalDateTime.now().timestampmilli(),
            endTime = record.endTime?.timestampmilli(),
            status = record.status ?: "",
            model = model,
            currentTimestamp = System.currentTimeMillis(),
            buildNum = buildInfo.buildNum,
            cancelUserId = record.cancelUser ?: "",
            curVersion = buildInfo.version,
            latestVersion = latestVersion,
            latestBuildNum = buildSummaryRecord?.buildNum ?: -1
        )
    }

    fun getBuildModel(buildId: String): Model? {
        val record = buildDetailDao.get(dslContext, buildId) ?: return null
        return JsonUtil.to(record.model, Model::class.java)
    }

    fun pipelineDetailChangeEvent(buildId: String) {
        val pipelineBuildInfo = pipelineBuildDao.getBuildInfo(dslContext, buildId) ?: return
        // 异步转发，解耦核心
        pipelineEventDispatcher.dispatch(
            PipelineBuildWebSocketPushEvent(
                source = "pauseTask",
                projectId = pipelineBuildInfo.projectId,
                pipelineId = pipelineBuildInfo.pipelineId,
                userId = pipelineBuildInfo.startUser,
                buildId = pipelineBuildInfo.buildId,
                refreshTypes = RefreshType.DETAIL.binary
            )
        )
    }

    fun updateModel(buildId: String, model: Model) {
        buildDetailDao.update(
            dslContext = dslContext,
            buildId = buildId,
            model = JsonUtil.getObjectMapper().writeValueAsString(model),
            buildStatus = BuildStatus.RUNNING
        )
        pipelineDetailChangeEvent(buildId)
    }

    fun containerPreparing(buildId: String, containerId: Int) {
        update(buildId, object : ModelInterface {
            var update = false
            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (id == containerId) {
                    container.startEpoch = System.currentTimeMillis()
                    container.status = BuildStatus.PREPARE_ENV.name
                    container.startVMStatus = BuildStatus.RUNNING.name
                    update = true
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun containerStart(buildId: String, containerId: Int) {
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (id == containerId) {
                    if (container.startEpoch != null) {
                        container.systemElapsed = System.currentTimeMillis() - container.startEpoch!!
                    }
                    container.status = BuildStatus.RUNNING.name
                    update = true
                    return Traverse.BREAK
                }
                return Traverse.BREAK
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun taskEnd(
        buildId: String,
        taskId: String,
        buildStatus: BuildStatus,
        canRetry: Boolean? = false,
        errorType: ErrorType? = null,
        errorCode: Int? = null,
        errorMsg: String? = null
    ) {
        update(buildId, object : ModelInterface {

            var update = false
            override fun onFindElement(e: Element, c: Container): Traverse {
                if (e.id == taskId) {
                    e.canRetry = canRetry
                    e.status = buildStatus.name
                    if (e.startEpoch == null) {
                        e.elapsed = 0
                    } else {
                        e.elapsed = System.currentTimeMillis() - e.startEpoch!!
                    }
                    c.canRetry = canRetry ?: false
                    if (errorType != null) {
                        e.errorType = errorType.name
                        e.errorCode = errorCode
                        e.errorMsg = errorMsg
                    }

                    var elementElapsed = 0L
                    run lit@{
                        c.elements.forEach {
                            if (it.elapsed == null) {
                                return@forEach
                            }
                            elementElapsed += it.elapsed!!
                            if (it == e) {
                                return@lit
                            }
                        }
                    }

                    c.elementElapsed = elementElapsed
                    update = true
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun pipelineTaskEnd(
        buildId: String,
        taskId: String,
        buildStatus: BuildStatus,
        errorType: ErrorType?,
        errorCode: Int?,
        errorMsg: String?
    ) {
        taskEnd(
            buildId = buildId,
            taskId = taskId,
            buildStatus = buildStatus,
            canRetry = buildStatus.isFailure(),
            errorType = errorType,
            errorCode = errorCode,
            errorMsg = errorMsg
        )
    }

    fun containerSkip(buildId: String, containerId: String) {
        logger.info("[$buildId|$containerId] Normal container skip")
        update(buildId, object : ModelInterface {

            var update = false

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (container !is TriggerContainer) {
                    // 兼容id字段
                    if (container.id == containerId || container.containerId == containerId) {
                        update = true
                        container.status = BuildStatus.SKIP.name
                        container.startVMStatus = BuildStatus.SKIP.name
                        container.elements.forEach {
                            it.status = BuildStatus.SKIP.name
                        }
                        return Traverse.BREAK
                    }
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun buildCancel(buildId: String, buildStatus: BuildStatus) {
        logger.info("Cancel the build $buildId")
        update(buildId, object : ModelInterface {

            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.status == BuildStatus.RUNNING.name) {
                    stage.status = buildStatus.name
                    if (stage.startEpoch == null) {
                        stage.elapsed = 0
                    } else {
                        stage.elapsed = System.currentTimeMillis() - stage.startEpoch!!
                    }
                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (container.status == BuildStatus.PREPARE_ENV.name) {
                    if (container.startEpoch == null) {
                        container.systemElapsed = 0
                    } else {
                        container.systemElapsed = System.currentTimeMillis() - container.startEpoch!!
                    }

                    var containerElapsed = 0L
                    run lit@{
                        stage.containers.forEach {
                            containerElapsed += it.elementElapsed ?: 0
                            if (it == container) {
                                return@lit
                            }
                        }
                    }

                    stage.elapsed = containerElapsed

                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun onFindElement(e: Element, c: Container): Traverse {
                if (e.status == BuildStatus.RUNNING.name || e.status == BuildStatus.REVIEWING.name) {
                    val status = if (e.status == BuildStatus.RUNNING.name) {
                        BuildStatus.TERMINATE.name
                    } else buildStatus.name
                    e.status = status
                    c.status = status

                    if (e.startEpoch != null) {
                        e.elapsed = System.currentTimeMillis() - e.startEpoch!!
                    }

                    var elementElapsed = 0L
                    run lit@{
                        c.elements.forEach {
                            elementElapsed += it.elapsed ?: 0
                            if (it == e) {
                                return@lit
                            }
                        }
                    }

                    c.elementElapsed = elementElapsed

                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, buildStatus)
    }

    fun buildEnd(buildId: String, buildStatus: BuildStatus, cancelUser: String? = null): List<BuildStageStatus> {
        logger.info("[$buildId]|BUILD_END|buildStatus=$buildStatus|cancelUser=$cancelUser")
        var allStageStatus: List<BuildStageStatus> = emptyList()
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (!container.status.isNullOrBlank() && BuildStatus.valueOf(container.status!!).isRunning()) {
                    container.status = buildStatus.name
                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (allStageStatus.isEmpty()) {
                    allStageStatus = fetchHistoryStageStatus(model)
                }
                if (stage.id.isNullOrBlank()) {
                    return Traverse.BREAK
                }
                if (!stage.status.isNullOrBlank() && BuildStatus.valueOf(stage.status!!).isRunning()) {
                    stage.status = buildStatus.name
                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun onFindElement(e: Element, c: Container): Traverse {
                if (!e.status.isNullOrBlank() && BuildStatus.valueOf(e.status!!).isRunning()) {
                    e.status = buildStatus.name
                    update = true
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, buildStatus)
        return allStageStatus
    }

    private fun takeBuildStatus(
        record: TPipelineBuildDetailRecord,
        buildStatus: BuildStatus
    ): Pair<Boolean, BuildStatus> {

        val status = record.status
        val oldStatus = if (status.isNullOrBlank()) {
            null
        } else {
            BuildStatus.valueOf(status)
        }

        return if (oldStatus == null || !oldStatus.isFinish()) {
//            logger.info("[${record.buildId}]|Update the build to status $buildStatus from $oldStatus")
            true to buildStatus
        } else {
//            logger.info("[${record.buildId}]|old($oldStatus) do not replace with the new($buildStatus)")
            false to oldStatus
        }
    }

    fun updateBuildCancelUser(buildId: String, cancelUserId: String) {
        buildDetailDao.updateBuildCancelUser(dslContext, buildId, cancelUserId)
    }

    fun updateContainerStatus(buildId: String, containerId: String, buildStatus: BuildStatus) {
        logger.info("[$buildId]|container_end|containerId=$containerId|status=$buildStatus")
        update(buildId, object : ModelInterface {

            var update = false

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (container.id == containerId) {
                    update = true
                    container.status = buildStatus.name
                    if (buildStatus.isFinish() &&
                        (container.startVMStatus == null || !BuildStatus.valueOf(container.startVMStatus!!).isFinish())
                    ) {
                        container.startVMStatus = container.status
                    }
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun pauseTask(buildId: String, stageId: String, containerId: String, taskId: String, buildStatus: BuildStatus) {
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindElement(e: Element, c: Container): Traverse {
                if (c.id.equals(containerId)) {
                    if (e.id.equals(taskId)) {
                        logger.info("ENGINE|$buildId|pauseTask|$stageId|j($containerId)|t($taskId)|${buildStatus.name}")
                        update = true
                        e.status = buildStatus.name
                        return Traverse.BREAK
                    }
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun updateStageStatus(buildId: String, stageId: String, buildStatus: BuildStatus): List<BuildStageStatus> {
        logger.info("[$buildId]|update_stage_status|stageId=$stageId|status=$buildStatus")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = buildStatus.name
                    if (buildStatus.isRunning() && stage.startEpoch == null) {
                        stage.startEpoch = System.currentTimeMillis()
                    } else if (buildStatus.isFinish() && stage.startEpoch != null) {
                        stage.elapsed = System.currentTimeMillis() - stage.startEpoch!!
                    }
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    fun stageSkip(buildId: String, stageId: String): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_skip|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.SKIP.name
                    stage.containers.forEach {
                        it.status = BuildStatus.SKIP.name
                    }
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    fun stagePause(
        pipelineId: String,
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_pause|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.PAUSE.name
                    stage.reviewStatus = BuildStatus.REVIEWING.name
                    stage.stageControlOption!!.triggerUsers = controlOption.stageControlOption.triggerUsers
                    stage.startEpoch = System.currentTimeMillis()
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.STAGE_SUCCESS)
        return allStageStatus ?: emptyList()
    }

    fun stageCancel(buildId: String, stageId: String) {
        logger.info("[$buildId]|stage_cancel|stageId=$stageId")
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = ""
                    stage.reviewStatus = BuildStatus.REVIEW_ABORT.name
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.STAGE_SUCCESS)
    }

    fun stageStart(
        pipelineId: String,
        buildId: String,
        stageId: String,
        controlOption: PipelineBuildStageControlOption
    ): List<BuildStageStatus> {
        logger.info("[$buildId]|stage_start|stageId=$stageId")
        var allStageStatus: List<BuildStageStatus>? = null
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindStage(stage: Stage, model: Model): Traverse {
                if (stage.id == stageId) {
                    update = true
                    stage.status = BuildStatus.QUEUE.name
                    stage.reviewStatus = BuildStatus.REVIEW_PROCESSED.name
                    stage.stageControlOption?.triggered = controlOption.stageControlOption.triggered
                    stage.stageControlOption?.reviewParams = controlOption.stageControlOption.reviewParams
                    allStageStatus = fetchHistoryStageStatus(model)
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
        return allStageStatus ?: emptyList()
    }

    fun taskSkip(buildId: String, taskId: String) {
        update(buildId, object : ModelInterface {
            var update = false
            override fun onFindElement(e: Element, c: Container): Traverse {
                if (e.id == taskId) {
                    update = true
                    e.status = BuildStatus.SKIP.name
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun taskStart(buildId: String, taskId: String) {
        val variables = buildVariableService.getAllVariable(buildId)
        update(buildId, object : ModelInterface {
            var update = false
            override fun onFindElement(e: Element, c: Container): Traverse {
                if (e.id == taskId) {
                    if (e is ManualReviewUserTaskElement) {
                        e.status = BuildStatus.REVIEWING.name
//                        c.status = BuildStatus.REVIEWING.name
                        // Replace the review user with environment
                        val list = mutableListOf<String>()
                        e.reviewUsers.forEach { reviewUser ->
                            list.addAll(EnvUtils.parseEnv(reviewUser, variables).split(","))
                        }
                        e.reviewUsers.clear()
                        e.reviewUsers.addAll(list)
                    } else if (e is QualityGateInElement || e is QualityGateOutElement) {
                        e.status = BuildStatus.REVIEWING.name
                        c.status = BuildStatus.REVIEWING.name
                    } else {
                        c.status = BuildStatus.RUNNING.name
                        e.status = BuildStatus.RUNNING.name
                    }
                    e.startEpoch = System.currentTimeMillis()
                    if (c.startEpoch == null) {
                        c.startEpoch = e.startEpoch
                    }
                    e.errorType = null
                    e.errorCode = null
                    e.errorMsg = null
                    e.version = findTaskVersion(buildId, e.getAtomCode(), e.version, e.getClassType()) ?: e.version
                    update = true
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun taskCancel(buildId: String, stageId: String, containerId: String, taskId: String) {
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindElement(e: Element, c: Container): Traverse {
                if (c.id.equals(containerId)) {
                    if (e.id.equals(taskId)) {
                        c.status = BuildStatus.CANCELED.name
                        e.status = BuildStatus.CANCELED.name
                        update = true
                        return Traverse.BREAK
                    }
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    fun updateStartVMStatus(buildId: String, containerId: String, buildStatus: BuildStatus) {
        update(buildId, object : ModelInterface {
            var update = false
            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (container !is TriggerContainer) {
                    // 兼容id字段
                    if (container.id == containerId || container.containerId == containerId) {
                        update = true
                        container.startVMStatus = buildStatus.name
                        // #2074 如果是失败的，则将Job整体状态设置为失败
                        if (buildStatus.isFailure()) {
                            container.status = buildStatus.name
                        }
                        return Traverse.BREAK
                    }
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    @Suppress("ALL")
    fun updateElementWhenPauseContinue(
        buildId: String,
        stageId: String,
        containerId: String,
        taskId: String,
        element: Element?
    ) {
        val detailRecord = buildDetailDao.get(dslContext, buildId) ?: return
        val model = JsonUtil.to(detailRecord.model, Model::class.java)
        model.stages.forEach { s ->
            if (s.id.equals(stageId)) {
                s.containers.forEach { c ->
                    if (c.id.equals(containerId)) {
                        val newElement = mutableListOf<Element>()
                        c.elements.forEach { e ->
                            if (e.id.equals(taskId)) {
                                // 设置插件状态为排队状态
                                c.status = BuildStatus.QUEUE.name
                                // 若element不为null，说明element内的input有改动，需要提供成改动后的input
                                if (element != null) {
                                    element.status = null
                                    newElement.add(element)
                                } else {
                                    // 若element为null，需把status至空，用户展示
                                    e.status = null
                                    newElement.add(e)
                                }
                            } else {
                                newElement.add(e)
                            }
                        }
                        c.elements = newElement
                    }
                }
            }
        }
        buildDetailDao.updateModel(dslContext, buildId, objectMapper.writeValueAsString(model))
    }

    @Suppress("ALL")
    fun updateElementWhenPauseRetry(buildId: String, model: Model) {
        var needUpdate = false
        model.stages.forEach { stage ->
            stage.containers.forEach { container ->
                val newElements = mutableListOf<Element>()
                container.elements.forEach nextElement@{ element ->
                    if (element.id == null) {
                        return@nextElement
                    }
                    // 重置插件状态开发
                    val pauseFlag = redisOperation.get(PauseRedisUtils.getPauseRedisKey(buildId, element.id!!))
                    if (pauseFlag != null) { // 若插件已经暂停过,重试构建需复位对应构建暂停状态位
                        logger.info("Refresh pauseFlag| $buildId|${element.id}")
                        pipelineTaskPauseService.pauseTaskFinishExecute(buildId, element.id!!)
                    }

                    if (ControlUtils.pauseFlag(element.additionalOptions)) {
                        val defaultElement = pipelinePauseValueDao.get(dslContext, buildId, element.id!!)
                        if (defaultElement != null) {
                            logger.info("Refresh element| $buildId|${element.id}")
                            // 恢复detail表model内的对应element为默认值
                            newElements.add(objectMapper.readValue(defaultElement.defaultValue, Element::class.java))
                            needUpdate = true
                        } else {
                            newElements.add(element)
                        }
                    } else {
                        newElements.add(element)
                    }
                }
                container.elements = newElements
            }
        }

        // 若插件暫停继续有修改插件变量，重试需环境为原始变量
        if (needUpdate) {
            buildDetailDao.updateModel(dslContext, buildId, objectMapper.writeValueAsString(model))
        }
    }

    private fun fetchHistoryStageStatus(model: Model): List<BuildStageStatus> {
        // 更新Stage状态至BuildHistory
        return model.stages.map {
            BuildStageStatus(
                stageId = it.id!!,
                name = it.name ?: it.id!!,
                status = it.status,
                startEpoch = it.startEpoch,
                elapsed = it.elapsed
            )
        }
    }

    private fun update(buildId: String, modelInterface: ModelInterface, buildStatus: BuildStatus) {
        val watcher = Watcher(id = "updateDetail#$buildId")
        var message = "nothing"
        val lock = RedisLock(redisOperation, "process.build.detail.lock.$buildId", ExpiredTimeInSeconds)

        try {
            watcher.start("lock")
            lock.lock()

            watcher.start("getDetail")
            val record = buildDetailDao.get(dslContext, buildId)
            Preconditions.checkArgument(record != null, "The build detail is not exist")

            watcher.start("model")
            val model = JsonUtil.to(record!!.model, Model::class.java)
            Preconditions.checkArgument(model.stages.size > 1, "Trigger container only")

            watcher.start("updateModel")
            update(model, modelInterface)
            watcher.stop()

            val modelStr: String? = if (!modelInterface.needUpdate()) {
                null
            } else {
                watcher.start("toJson")
                JsonUtil.toJson(model)
            }

            val (change, finalStatus) = takeBuildStatus(record, buildStatus)
            if (modelStr.isNullOrBlank() && !change) {
                message = "Will not update"
                return
            }

            watcher.start("updateModel")
            buildDetailDao.update(dslContext, buildId, modelStr, finalStatus)

            watcher.start("dispatchEvent")
            pipelineDetailChangeEvent(buildId)
            message = "update done"
        } catch (ignored: Throwable) {
            message = ignored.message ?: ""
            logger.warn("[$buildId]| Fail to update the build detail: ${ignored.message}", ignored)
        } finally {
            lock.unlock()
            watcher.stop()
            logger.info("[$buildId|$buildStatus]|update_detail_model| $message")
            LogUtils.printCostTimeWE(watcher)
        }
    }

    @Suppress("ALL")
    private fun update(model: Model, modelInterface: ModelInterface) {
        var containerId = 1
        model.stages.forEachIndexed { index, stage ->
            if (index == 0) {
                return@forEachIndexed
            }

            if (Traverse.BREAK == modelInterface.onFindStage(stage, model)) {
                return
            }

            stage.containers.forEach { c ->
                if (Traverse.BREAK == modelInterface.onFindContainer(containerId, c, stage)) {
                    return
                }
                containerId++
                c.elements.forEach { e ->
                    if (Traverse.BREAK == modelInterface.onFindElement(e, c)) {
                        return
                    }
                }
            }
        }
    }

    private fun findTaskVersion(buildId: String, atomCode: String, atomVersion: String?, atomClass: String): String? {
        // 只有是研发商店插件,获取插件的版本信息
        if (atomClass != "marketBuild" && atomClass != "marketBuildLess") {
            return atomVersion
        }
        return if (atomVersion?.contains("*") == true) {
            val projectCode = pipelineBuildDao.getBuildInfo(dslContext, buildId)!!.projectId
            client.get(ServiceMarketAtomEnvResource::class)
                .getAtomEnv(projectCode, atomCode, atomVersion).data?.version ?: atomVersion
        } else {
            atomVersion
        }
    }

    fun saveBuildVmInfo(projectId: String, pipelineId: String, buildId: String, containerId: Int, vmInfo: VmInfo) {
        update(buildId, object : ModelInterface {
            var update = false

            override fun onFindContainer(id: Int, container: Container, stage: Stage): Traverse {
                if (id == containerId) {
                    if (container is VMBuildContainer && container.showBuildResource == true) {
                        container.name = vmInfo.name
                    }
                    update = true
                    return Traverse.BREAK
                }
                return Traverse.CONTINUE
            }

            override fun needUpdate(): Boolean {
                return update
            }
        }, BuildStatus.RUNNING)
    }

    private interface ModelInterface {

        fun onFindStage(stage: Stage, model: Model) = Traverse.CONTINUE

        fun onFindContainer(id: Int, container: Container, stage: Stage) = Traverse.CONTINUE

        fun onFindElement(e: Element, c: Container) = Traverse.CONTINUE

        fun needUpdate(): Boolean
    }

    enum class Traverse {
        BREAK,
        CONTINUE
    }
}
