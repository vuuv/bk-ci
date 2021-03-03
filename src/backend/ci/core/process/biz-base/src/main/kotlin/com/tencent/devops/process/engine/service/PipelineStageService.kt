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

import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.websocket.enum.RefreshType
import com.tencent.devops.process.engine.common.BS_MANUAL_START_STAGE
import com.tencent.devops.process.engine.common.BS_STAGE_CANCELED_END_SOURCE
import com.tencent.devops.process.engine.dao.PipelineBuildDao
import com.tencent.devops.process.engine.dao.PipelineBuildStageDao
import com.tencent.devops.process.engine.dao.PipelineBuildSummaryDao
import com.tencent.devops.process.engine.pojo.PipelineBuildStage
import com.tencent.devops.process.engine.pojo.event.PipelineBuildFinishEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildStageEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildWebSocketPushEvent
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 流水线Stage相关的服务
 * @version 1.0
 */
@Service
class PipelineStageService @Autowired constructor(
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val dslContext: DSLContext,
    private val pipelineBuildDao: PipelineBuildDao,
    private val pipelineBuildSummaryDao: PipelineBuildSummaryDao,
    private val pipelineBuildStageDao: PipelineBuildStageDao,
    private val pipelineBuildDetailService: PipelineBuildDetailService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PipelineStageService::class.java)
    }

    fun getStage(buildId: String, stageId: String?): PipelineBuildStage? {
        val result = pipelineBuildStageDao.get(dslContext, buildId, stageId)
        if (result != null) {
            return pipelineBuildStageDao.convert(result)
        }
        return null
    }

    /**
     * 取构建[buildId]序号为[stageSeq]的[PipelineBuildStage]
     * 如果不存在则返回null
     */
    fun getStageBySeq(buildId: String, stageSeq: Int): PipelineBuildStage? {
        val result = pipelineBuildStageDao.getBySeq(dslContext, buildId, stageSeq)
        if (result != null) {
            return pipelineBuildStageDao.convert(result)
        }
        return null
    }

    fun updateStageStatus(buildId: String, stageId: String, buildStatus: BuildStatus) {
        logger.info("[$buildId]|updateStageStatus|status=$buildStatus|stageId=$stageId")
        pipelineBuildStageDao.updateStatus(
            dslContext = dslContext,
            buildId = buildId,
            stageId = stageId,
            buildStatus = buildStatus
        )
    }

    fun listStages(buildId: String): List<PipelineBuildStage> {
        val list = pipelineBuildStageDao.listByBuildId(dslContext, buildId)
        val result = mutableListOf<PipelineBuildStage>()
        if (list.isNotEmpty()) {
            list.forEach {
                result.add(pipelineBuildStageDao.convert(it)!!)
            }
        }
        return result
    }

    fun skipStage(userId: String, buildStage: PipelineBuildStage) {
        with(buildStage) {
            val allStageStatus = pipelineBuildDetailService.stageSkip(buildId = buildId, stageId = stageId)
            dslContext.transaction { configuration ->
                val context = DSL.using(configuration)
                pipelineBuildStageDao.updateStatus(
                    dslContext = context, buildId = buildId, stageId = stageId,
                    buildStatus = BuildStatus.SKIP, controlOption = controlOption
                )

                pipelineBuildDao.updateBuildStageStatus(
                    dslContext = context, buildId = buildId, stageStatus = allStageStatus
                )
            }

            pipelineEventDispatcher.dispatch(
                PipelineBuildWebSocketPushEvent(
                    source = "skipStage", projectId = projectId, pipelineId = pipelineId,
                    userId = userId, buildId = buildId, refreshTypes = RefreshType.HISTORY.binary
                )
            )
        }
    }

    fun pauseStage(userId: String, buildStage: PipelineBuildStage) {
        with(buildStage) {
            val allStageStatus = pipelineBuildDetailService.stagePause(
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                controlOption = controlOption!!
            )
            dslContext.transaction { configuration ->
                val context = DSL.using(configuration)
                pipelineBuildStageDao.updateStatus(
                    dslContext = context, buildId = buildId, stageId = stageId,
                    buildStatus = BuildStatus.PAUSE, controlOption = controlOption
                )

                pipelineBuildDao.updateStatus(
                    dslContext = context, buildId = buildId,
                    oldBuildStatus = BuildStatus.RUNNING, newBuildStatus = BuildStatus.STAGE_SUCCESS
                )

                pipelineBuildDao.updateBuildStageStatus(
                    dslContext = context, buildId = buildId, stageStatus = allStageStatus
                )
                // 被暂停的流水线不占构建队列，在执行数-1
                pipelineBuildSummaryDao.updateRunningCount(
                    dslContext = context, pipelineId = pipelineId, buildId = buildId, runningIncrement = -1
                )
            }

            // #3400 点Stage启动时处于DETAIL界面，以操作人视角，没有刷历史列表的必要
        }
    }

    fun startStage(userId: String, buildStage: PipelineBuildStage) {
        buildStage.controlOption!!.stageControlOption.triggered = true
        with(buildStage) {
            val allStageStatus = pipelineBuildDetailService.stageStart(
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                controlOption = buildStage.controlOption!!
            )
            dslContext.transaction { configuration ->
                val context = DSL.using(configuration)
                pipelineBuildStageDao.updateStatus(
                    dslContext = context, buildId = buildId, stageId = stageId,
                    buildStatus = BuildStatus.QUEUE, controlOption = controlOption
                )

                pipelineBuildDao.updateStatus(
                    dslContext = context, buildId = buildId,
                    oldBuildStatus = BuildStatus.STAGE_SUCCESS, newBuildStatus = BuildStatus.RUNNING
                )

                pipelineBuildDao.updateBuildStageStatus(
                    dslContext = context, buildId = buildId, stageStatus = allStageStatus
                )

                pipelineBuildSummaryDao.updateRunningCount(
                    dslContext = context, pipelineId = pipelineId, buildId = buildId, runningIncrement = 1
                )
            }

            pipelineEventDispatcher.dispatch(
                PipelineBuildStageEvent(
                    source = BS_MANUAL_START_STAGE,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    buildId = buildId,
                    stageId = stageId,
                    actionType = ActionType.REFRESH
                )
                // #3400 点Stage启动时处于DETAIL界面，以操作人视角，没有刷历史列表的必要
            )
        }
    }

    fun cancelStage(userId: String, buildStage: PipelineBuildStage) {

        with(buildStage) {
            pipelineBuildDetailService.stageCancel(buildId = buildId, stageId = stageId)

            dslContext.transaction { configuration ->
                val context = DSL.using(configuration)
                pipelineBuildStageDao.updateStatus(
                    dslContext = context, buildId = buildId, stageId = stageId, buildStatus = BuildStatus.REVIEW_ABORT
                )

                pipelineBuildDao.updateStageCancelStatus(dslContext = context, buildId = buildId)
            }

            pipelineEventDispatcher.dispatch(
                PipelineBuildFinishEvent(
                    source = BS_STAGE_CANCELED_END_SOURCE,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    buildId = buildId,
                    status = BuildStatus.STAGE_SUCCESS
                )
            // #3400 FinishEvent会刷新HISTORY列表的Stage状态
            )
        }
    }
}
