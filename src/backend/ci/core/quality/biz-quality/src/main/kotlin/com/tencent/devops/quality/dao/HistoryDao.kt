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

package com.tencent.devops.quality.dao

import com.tencent.devops.model.quality.tables.THistory
import com.tencent.devops.model.quality.tables.records.THistoryRecord
import com.tencent.devops.quality.pojo.enum.RuleInterceptResult
import org.jooq.DSLContext
import org.jooq.Result
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository@Suppress("ALL")
class HistoryDao {
    fun create(
        dslContext: DSLContext,
        projectId: String,
        ruleId: Long,
        pipelineId: String,
        buildId: String,
        result: String,
        interceptList: String,
        createTime: LocalDateTime,
        updateTime: LocalDateTime
    ): Long {
        with(THistory.T_HISTORY) {
            val record = dslContext.insertInto(
                this,
                PROJECT_ID,
                RULE_ID,
                PIPELINE_ID,
                BUILD_ID,
                RESULT,
                INTERCEPT_LIST,
                CREATE_TIME,
                UPDATE_TIME
            ).values(
                projectId,
                ruleId,
                pipelineId,
                buildId,
                result,
                interceptList,
                createTime,
                updateTime
            )
                .returning(ID)
                .fetchOne()

            // 更新projectNum
            val projectNum = dslContext.selectCount()
                .from(this)
                .where(PROJECT_ID.eq(projectId).and(ID.lt(record.id)))
                .fetchOne(0, Long::class.java) + 1
            dslContext.update(this)
                .set(PROJECT_NUM, projectNum)
                .where(ID.eq(record.id))
                .execute()
            return record.id
        }
    }

    fun listByRuleId(
        dslContext: DSLContext,
        projectId: String,
        ruleId: Long,
        offset: Int,
        limit: Int
    ): Result<THistoryRecord> {
        with(THistory.T_HISTORY) {
            return dslContext.selectFrom(this)
                .where(RULE_ID.eq(ruleId).and(PROJECT_ID.eq(projectId)))
                .orderBy(PROJECT_NUM.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
        }
    }

    fun list(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String?,
        ruleId: Long?,
        result: String?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        offset: Int,
        limit: Int
    ): Result<THistoryRecord> {
        with(THistory.T_HISTORY) {
            val step1 = dslContext.selectFrom(this).where(PROJECT_ID.eq(projectId))
            val step2 = if (pipelineId == null) step1 else step1.and(PIPELINE_ID.eq(pipelineId))
            val step3 = if (ruleId == null) step2 else step2.and(RULE_ID.eq(ruleId))
            val step4 = if (result == null) step3 else step3.and(RESULT.eq(result))
            val step5 = if (startTime == null) step4 else step4.and(CREATE_TIME.gt(startTime))
            val step6 = if (endTime == null) step5 else step5.and(CREATE_TIME.lt(endTime))
            return step6.orderBy(PROJECT_NUM.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
        }
    }

    fun listIntercept(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String?,
        ruleId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        offset: Int,
        limit: Int
    ): Result<THistoryRecord> {
        return list(
            dslContext,
            projectId,
            pipelineId,
            ruleId,
            RuleInterceptResult.FAIL.name,
            startTime,
            endTime,
            offset,
            limit
        )
    }

    fun listByBuildIdAndResult(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String,
        buildId: String,
        result: String
    ): Result<THistoryRecord> {
        with(THistory.T_HISTORY) {
            return dslContext.selectFrom(this)
                .where(PROJECT_ID.eq(projectId))
                .and(PIPELINE_ID.eq(pipelineId))
                .and(BUILD_ID.eq(buildId))
                .and(RESULT.eq(result))
                .fetch()
        }
    }

    fun listByBuildId(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String,
        buildId: String
    ): Result<THistoryRecord> {
        with(THistory.T_HISTORY) {
            return dslContext.selectFrom(this)
                .where(PROJECT_ID.eq(projectId))
                .and(PIPELINE_ID.eq(pipelineId))
                .and(BUILD_ID.eq(buildId))
                .fetch()
        }
    }

    fun count(dslContext: DSLContext, ruleId: Long): Long {
        with(THistory.T_HISTORY) {
            return dslContext.selectCount()
                .from(this)
                .where(RULE_ID.eq(ruleId))
                .fetchOne(0, Long::class.java)
        }
    }

    fun count(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String?,
        ruleId: Long?,
        result: String?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?
    ): Long {
        with(THistory.T_HISTORY) {
            val step1 = dslContext.selectCount().from(this).where(PROJECT_ID.eq(projectId))
            val step2 = if (pipelineId == null) step1 else step1.and(PIPELINE_ID.eq(pipelineId))
            val step3 = if (ruleId == null) step2 else step2.and(RULE_ID.eq(ruleId))
            val step4 = if (result == null) step3 else step3.and(RESULT.eq(result))
            val step5 = if (startTime == null) step4 else step4.and(CREATE_TIME.gt(startTime))
            val step6 = if (endTime == null) step5 else step5.and(CREATE_TIME.lt(endTime))
            return step6.fetchOne(0, Long::class.java)
        }
    }

    fun countIntercept(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String?,
        ruleId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?
    ): Long {
        return count(dslContext, projectId, pipelineId, ruleId, RuleInterceptResult.FAIL.name, startTime, endTime)
    }
}
