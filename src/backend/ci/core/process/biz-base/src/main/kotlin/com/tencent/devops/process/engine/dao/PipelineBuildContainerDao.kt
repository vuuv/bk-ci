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

package com.tencent.devops.process.engine.dao

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.db.util.JooqUtils
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.option.JobControlOption
import com.tencent.devops.model.process.Tables.T_PIPELINE_BUILD_CONTAINER
import com.tencent.devops.model.process.tables.records.TPipelineBuildContainerRecord
import com.tencent.devops.process.engine.pojo.PipelineBuildContainer
import com.tencent.devops.process.engine.pojo.PipelineBuildContainerControlOption
import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.InsertOnDuplicateSetMoreStep
import org.jooq.Query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Suppress("ALL")
@Repository
class PipelineBuildContainerDao {

    fun create(
        dslContext: DSLContext,
        buildContainer: PipelineBuildContainer
    ) {

        val count =
            with(T_PIPELINE_BUILD_CONTAINER) {
                dslContext.insertInto(
                    this,
                    PROJECT_ID,
                    PIPELINE_ID,
                    BUILD_ID,
                    STAGE_ID,
                    CONTAINER_TYPE,
                    SEQ,
                    STATUS,
                    START_TIME,
                    END_TIME,
                    COST,
                    EXECUTE_COUNT,
                    CONDITIONS
                )
                    .values(
                        buildContainer.projectId,
                        buildContainer.pipelineId,
                        buildContainer.buildId,
                        buildContainer.stageId,
                        buildContainer.containerType,
                        buildContainer.seq,
                        buildContainer.status.ordinal,
                        buildContainer.startTime,
                        buildContainer.endTime,
                        buildContainer.cost,
                        buildContainer.executeCount,
                        if (buildContainer.controlOption != null) {
                            JsonUtil.toJson(buildContainer.controlOption!!)
                        } else null
                    )
                    .execute()
            }
        logger.info("save the buildContainer=$buildContainer, result=${count == 1}")
    }

    fun batchSave(dslContext: DSLContext, taskList: Collection<PipelineBuildContainer>) {
        val records =
            mutableListOf<InsertOnDuplicateSetMoreStep<TPipelineBuildContainerRecord>>()
        with(T_PIPELINE_BUILD_CONTAINER) {
            taskList.forEach {
                records.add(
                    dslContext.insertInto(this)
                        .set(PROJECT_ID, it.projectId)
                        .set(PIPELINE_ID, it.pipelineId)
                        .set(BUILD_ID, it.buildId)
                        .set(STAGE_ID, it.stageId)
                        .set(CONTAINER_ID, it.containerId)
                        .set(CONTAINER_TYPE, it.containerType)
                        .set(SEQ, it.seq)
                        .set(STATUS, it.status.ordinal)
                        .set(START_TIME, it.startTime)
                        .set(END_TIME, it.endTime)
                        .set(COST, it.cost)
                        .set(EXECUTE_COUNT, it.executeCount)
                        .set(CONDITIONS, if (it.controlOption != null) JsonUtil.toJson(it.controlOption!!) else null)
                        .onDuplicateKeyUpdate()
                        .set(STATUS, it.status.ordinal)
                        .set(START_TIME, it.startTime)
                        .set(END_TIME, it.endTime)
                        .set(COST, it.cost)
                        .set(EXECUTE_COUNT, it.executeCount)
                )
            }
        }
        dslContext.batch(records).execute()
    }

    fun batchUpdate(dslContext: DSLContext, taskList: List<TPipelineBuildContainerRecord>) {
        val records = mutableListOf<Query>()
        with(T_PIPELINE_BUILD_CONTAINER) {
            taskList.forEach {
                records.add(
                    dslContext.update(this)
                        .set(PROJECT_ID, it.projectId)
                        .set(PIPELINE_ID, it.pipelineId)
                        .set(CONTAINER_TYPE, it.containerType)
                        .set(SEQ, it.seq)
                        .set(STATUS, it.status)
                        .set(START_TIME, it.startTime)
                        .set(END_TIME, it.endTime)
                        .set(COST, it.cost)
                        .set(EXECUTE_COUNT, it.executeCount)
                        .set(CONDITIONS, it.conditions)
                        .where(BUILD_ID.eq(it.buildId)
                            .and(STAGE_ID.eq(it.stageId)).and(CONTAINER_ID.eq(it.containerId)))
                )
            }
        }
        dslContext.batch(records).execute()
    }

    fun get(
        dslContext: DSLContext,
        buildId: String,
        stageId: String?,
        containerId: String
    ): TPipelineBuildContainerRecord? {

        return with(T_PIPELINE_BUILD_CONTAINER) {
            val query = dslContext.selectFrom(this).where(BUILD_ID.eq(buildId))
            if (stageId.isNullOrBlank()) {
                query.and(CONTAINER_ID.eq(containerId)).fetchAny()
            } else {
                query.and(STAGE_ID.eq(stageId)).and(CONTAINER_ID.eq(containerId)).fetchAny()
            }
        }
    }

    fun updateStatus(
        dslContext: DSLContext,
        buildId: String,
        stageId: String,
        containerId: String,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        buildStatus: BuildStatus
    ): Int {
        return with(T_PIPELINE_BUILD_CONTAINER) {
            val update = dslContext.update(this)
                .set(STATUS, buildStatus.ordinal)

            if (startTime != null) {
                update.set(START_TIME, startTime)
            }
            if (endTime != null) {
                update.set(END_TIME, endTime)
                if (buildStatus.isFinish()) {
                    update.set(
                        COST, COST + JooqUtils.timestampDiff(
                        DatePart.SECOND,
                        START_TIME.cast(java.sql.Timestamp::class.java),
                        END_TIME.cast(java.sql.Timestamp::class.java)
                    )
                    )
                }
            }

            update.where(BUILD_ID.eq(buildId)).and(STAGE_ID.eq(stageId))
                .and(CONTAINER_ID.eq(containerId)).execute()
        }
    }

    fun listByBuildId(
        dslContext: DSLContext,
        buildId: String,
        stageId: String? = null
    ): Collection<TPipelineBuildContainerRecord> {
        return with(T_PIPELINE_BUILD_CONTAINER) {
            val conditionStep = dslContext.selectFrom(this).where(BUILD_ID.eq(buildId))
            if (!stageId.isNullOrBlank()) {
                conditionStep.and(STAGE_ID.eq(stageId))
            }
            conditionStep.orderBy(SEQ.asc()).fetch()
        }
    }

    fun deletePipelineBuildContainers(dslContext: DSLContext, projectId: String, pipelineId: String): Int {
        return with(T_PIPELINE_BUILD_CONTAINER) {
            dslContext.delete(this)
                .where(PROJECT_ID.eq(projectId))
                .and(PIPELINE_ID.eq(pipelineId))
                .execute()
        }
    }

    fun countByStatus(dslContext: DSLContext, status: Int): Int {
        return with(T_PIPELINE_BUILD_CONTAINER) {
            dslContext.selectCount().from(this).where(STATUS.eq(status)).fetchOne(0, Int::class.java)
        }
    }

    fun convert(tTPipelineBuildContainerRecord: TPipelineBuildContainerRecord): PipelineBuildContainer? {
        return with(tTPipelineBuildContainerRecord) {
            val controlOption = if (!conditions.isNullOrBlank()) {
                JsonUtil.to(conditions, PipelineBuildContainerControlOption::class.java)
            } else {
                PipelineBuildContainerControlOption(jobControlOption = JobControlOption())
            }
            PipelineBuildContainer(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                containerType = containerType,
                containerId = containerId,
                seq = seq,
                status = BuildStatus.values()[status],
                startTime = startTime,
                endTime = endTime,
                cost = cost ?: 0,
                executeCount = executeCount ?: 1,
                controlOption = controlOption
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineBuildContainerDao::class.java)
    }
}
