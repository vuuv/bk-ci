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

package com.tencent.devops.process.service

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.utils.SkipElementUtils
import com.tencent.devops.process.dao.BuildStartupParamDao
import com.tencent.devops.process.utils.BUILD_NO
import com.tencent.devops.process.utils.PIPELINE_BUILD_MSG
import com.tencent.devops.process.utils.PIPELINE_RETRY_COUNT
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Suppress("ALL")
@Service
class BuildStartupParamService @Autowired constructor(
    private val dslContext: DSLContext,
    private val buildStartupParamDao: BuildStartupParamDao
) {

    fun addParam(projectId: String, pipelineId: String, buildId: String, param: String) =
        buildStartupParamDao.add(
            dslContext = dslContext,
            buildId = buildId,
            param = param,
            projectId = projectId,
            pipelineId = pipelineId
        )

    fun getParam(buildId: String) =
        buildStartupParamDao.get(dslContext, buildId)

    fun deletePipelineBuildParam(projectId: String, pipelineId: String) {
        buildStartupParamDao.deletePipelineBuildParams(
            dslContext = dslContext,
            projectId = projectId,
            pipelineId = pipelineId
        )
    }

    /**
     * 如果是重试，不应该更新启动参数, 直接返回
     */
    fun writeStartParam(
        allVariable: Map<String, String>,
        projectId: String,
        pipelineId: String,
        buildId: String,
        model: Model
    ) {
        //  如果是重试，不应该更新启动参数, 直接返回
        if (allVariable[PIPELINE_RETRY_COUNT] != null) return

        val triggerContainer = model.stages[0].containers[0] as TriggerContainer
        val params = allVariable.filter {
            it.key.startsWith(SkipElementUtils.prefix) ||
                it.key == BUILD_NO ||
                it.key == PIPELINE_RETRY_COUNT ||
                it.key == PIPELINE_BUILD_MSG
        }.toMutableMap()

        if (triggerContainer.params.isNotEmpty()) {
            // 只有在构建参数中的才设置
            params.putAll(
                triggerContainer.params.map {
                    // 做下真实传值的替换
                    if (allVariable.containsKey(it.id)) it.id to allVariable[it.id].toString()
                    else it.id to it.defaultValue.toString()
                }.toMap()
            )
            addParam(projectId = projectId, pipelineId = pipelineId, buildId = buildId,
                param = JsonUtil.getObjectMapper().writeValueAsString(params)
            )
        }
    }
}
