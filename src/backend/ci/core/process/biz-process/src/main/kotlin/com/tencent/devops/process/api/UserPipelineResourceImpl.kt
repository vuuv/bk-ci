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

package com.tencent.devops.process.api

import com.tencent.devops.common.api.exception.InvalidParamException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.process.api.user.UserPipelineResource
import com.tencent.devops.process.engine.pojo.PipelineInfo
import com.tencent.devops.process.engine.utils.PipelineUtils
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.pojo.Permission
import com.tencent.devops.process.pojo.Pipeline
import com.tencent.devops.process.pojo.PipelineCopy
import com.tencent.devops.process.pojo.PipelineId
import com.tencent.devops.process.pojo.PipelineName
import com.tencent.devops.process.pojo.PipelineRemoteToken
import com.tencent.devops.process.pojo.PipelineSortType
import com.tencent.devops.process.pojo.PipelineStageTag
import com.tencent.devops.process.pojo.PipelineStatus
import com.tencent.devops.process.pojo.app.PipelinePage
import com.tencent.devops.process.pojo.classify.PipelineViewAndPipelines
import com.tencent.devops.process.pojo.classify.PipelineViewPipelinePage
import com.tencent.devops.process.pojo.setting.PipelineModelAndSetting
import com.tencent.devops.process.pojo.setting.PipelineRunLockType
import com.tencent.devops.process.pojo.setting.PipelineSetting
import com.tencent.devops.process.service.PipelineInfoFacadeService
import com.tencent.devops.process.service.PipelineListFacadeService
import com.tencent.devops.process.service.PipelineRemoteAuthService
import com.tencent.devops.process.service.StageTagService
import com.tencent.devops.process.service.label.PipelineGroupService
import com.tencent.devops.process.service.pipeline.PipelineSettingFacadeService
import com.tencent.devops.process.utils.PIPELINE_SETTING_MAX_QUEUE_SIZE_MAX
import com.tencent.devops.process.utils.PIPELINE_SETTING_MAX_QUEUE_SIZE_MIN
import com.tencent.devops.process.utils.PIPELINE_SETTING_WAIT_QUEUE_TIME_MINUTE_MAX
import com.tencent.devops.process.utils.PIPELINE_SETTING_WAIT_QUEUE_TIME_MINUTE_MIN
import org.springframework.beans.factory.annotation.Autowired
import javax.ws.rs.core.Response

@Suppress("ALL")
@RestResource
class UserPipelineResourceImpl @Autowired constructor(
    private val pipelineListFacadeService: PipelineListFacadeService,
    private val pipelineSettingFacadeService: PipelineSettingFacadeService,
    private val pipelineGroupService: PipelineGroupService,
    private val pipelineRemoteAuthService: PipelineRemoteAuthService,
    private val pipelinePermissionService: PipelinePermissionService,
    private val stageTagService: StageTagService,
    private val pipelineInfoFacadeService: PipelineInfoFacadeService
) : UserPipelineResource {

    override fun hasCreatePermission(userId: String, projectId: String): Result<Boolean> {
        checkParam(userId, projectId)
        return Result(pipelinePermissionService.checkPipelinePermission(
            userId = userId,
            projectId = projectId,
            permission = AuthPermission.CREATE
        ))
    }

    override fun pipelineExist(userId: String, projectId: String, pipelineName: String): Result<Boolean> {
        checkParam(userId, projectId)
        return Result(
            data = pipelineInfoFacadeService.isPipelineExist(
                projectId = projectId, name = pipelineName, channelCode = ChannelCode.BS
            )
        )
    }

    override fun hasPermissionList(
        userId: String,
        projectId: String,
        permission: Permission,
        excludePipelineId: String?,
        page: Int?,
        pageSize: Int?
    ): Result<Page<Pipeline>> {
        checkParam(userId, projectId)
        val bkAuthPermission = when (permission) {
            Permission.DEPLOY -> AuthPermission.DEPLOY
            Permission.DOWNLOAD -> AuthPermission.DOWNLOAD
            Permission.EDIT -> AuthPermission.EDIT
            Permission.EXECUTE -> AuthPermission.EXECUTE
            Permission.DELETE -> AuthPermission.DELETE
            Permission.VIEW -> AuthPermission.VIEW
            Permission.CREATE -> AuthPermission.CREATE
            Permission.LIST -> AuthPermission.LIST
        }
        val result = pipelineListFacadeService.hasPermissionList(
            userId,
            projectId,
            bkAuthPermission,
            excludePipelineId,
            page,
            pageSize
        )
        return Result(Page(page ?: 0, pageSize ?: -1, result.count, result.records))
    }

    override fun create(userId: String, projectId: String, pipeline: Model): Result<PipelineId> {
        checkParam(userId, projectId)
        val pipelineId = PipelineId(
            id = pipelineInfoFacadeService.createPipeline(
                userId = userId, projectId = projectId, model = pipeline, channelCode = ChannelCode.BS
            )
        )
        return Result(pipelineId)
    }

    override fun hasPermission(
        userId: String,
        projectId: String,
        pipelineId: String,
        permission: Permission
    ): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        val bkAuthPermission = when (permission) {
            Permission.DEPLOY -> AuthPermission.DEPLOY
            Permission.DOWNLOAD -> AuthPermission.DOWNLOAD
            Permission.EDIT -> AuthPermission.EDIT
            Permission.EXECUTE -> AuthPermission.EXECUTE
            Permission.DELETE -> AuthPermission.DELETE
            Permission.VIEW -> AuthPermission.VIEW
            Permission.CREATE -> AuthPermission.CREATE
            Permission.LIST -> AuthPermission.LIST
        }

        return Result(
            pipelinePermissionService.checkPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = bkAuthPermission
            )
        )
    }

    override fun copy(
        userId: String,
        projectId: String,
        pipelineId: String,
        pipeline: PipelineCopy
    ): Result<PipelineId> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        if (pipeline.name.isBlank()) {
            throw ParamBlankException("Invalid pipeline name")
        }
        val pid = PipelineId(
            pipelineInfoFacadeService.copyPipeline(
                userId,
                projectId,
                pipelineId,
                pipeline.name,
                pipeline.desc,
                ChannelCode.BS
            )
        )
        return Result(pid)
    }

    override fun edit(userId: String, projectId: String, pipelineId: String, pipeline: Model): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        checkName(pipeline.name)
        PipelineUtils.checkPipelineDescLength(pipeline.desc)
        pipelineInfoFacadeService.editPipeline(userId, projectId, pipelineId, pipeline, ChannelCode.BS)
        // pipelineGroupService.setPipelineGroup(userId, pipelineId,projectId,pipeline.group)
        return Result(true)
    }

    override fun saveAll(
        userId: String,
        projectId: String,
        pipelineId: String,
        modelAndSetting: PipelineModelAndSetting
    ): Result<Boolean> {
        checkParam(userId, projectId)
        checkParam(modelAndSetting.setting)
        checkPipelineId(pipelineId)
        checkName(modelAndSetting.model.name)
        PipelineUtils.checkPipelineDescLength(modelAndSetting.model.desc)
        pipelineInfoFacadeService.saveAll(
            userId,
            projectId,
            pipelineId,
            modelAndSetting.model,
            modelAndSetting.setting,
            ChannelCode.BS
        )
        return Result(true)
    }

    override fun saveSetting(
        userId: String,
        projectId: String,
        pipelineId: String,
        setting: PipelineSetting
    ): Result<Boolean> {
        checkParam(userId, projectId)
        checkParam(setting)
        checkPipelineId(pipelineId)
        pipelineSettingFacadeService.saveSetting(userId = userId, setting = setting, checkPermission = true)
        return Result(true)
    }

    override fun rename(userId: String, projectId: String, pipelineId: String, name: PipelineName): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        pipelineInfoFacadeService.renamePipeline(userId, projectId, pipelineId, name.name, ChannelCode.BS)
        return Result(true)
    }

    override fun get(userId: String, projectId: String, pipelineId: String): Result<Model> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        return Result(pipelineInfoFacadeService.getPipeline(userId, projectId, pipelineId, ChannelCode.BS))
    }

    override fun generateRemoteToken(
        userId: String,
        projectId: String,
        pipelineId: String
    ): Result<PipelineRemoteToken> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        return Result(pipelineRemoteAuthService.generateAuth(pipelineId, projectId, userId))
    }

    override fun softDelete(userId: String, projectId: String, pipelineId: String): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        pipelineInfoFacadeService.deletePipeline(userId, projectId, pipelineId, ChannelCode.BS)
        return Result(true)
    }

    override fun trueDelete(userId: String, projectId: String, pipelineId: String): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        pipelineInfoFacadeService.deletePipeline(userId, projectId, pipelineId, delete = true)
        return Result(true)
    }

    override fun restore(userId: String, projectId: String, pipelineId: String): Result<Boolean> {
        checkParam(userId, projectId)
        checkPipelineId(pipelineId)
        pipelineInfoFacadeService.restorePipeline(userId, projectId, pipelineId, ChannelCode.BS)
        return Result(true)
    }

    override fun recycleList(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType?
    ): Result<PipelineViewPipelinePage<PipelineInfo>> {
        checkParam(userId, projectId)
        return Result(
            pipelineListFacadeService.listDeletePipelineIdByProject(
                userId, projectId, page,
                pageSize, sortType ?: PipelineSortType.CREATE_TIME, ChannelCode.BS
            )
        )
    }

    override fun listViewSettingAndPipelines(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?
    ): Result<PipelineViewAndPipelines> {
        checkParam(userId, projectId)
        return Result(pipelineListFacadeService.listViewAndPipelines(userId, projectId, page, pageSize))
    }

    override fun listViewPipelines(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType?,
        filterByPipelineName: String?,
        filterByCreator: String?,
        filterByLabels: String?,
        viewId: String
    ): Result<PipelineViewPipelinePage<Pipeline>> {
        checkParam(userId, projectId)
        return Result(
            pipelineListFacadeService.listViewPipelines(
                userId = userId,
                projectId = projectId,
                page = page,
                pageSize = pageSize,
                sortType = sortType ?: PipelineSortType.CREATE_TIME,
                channelCode = ChannelCode.BS,
                viewId = viewId,
                checkPermission = true,
                filterByPipelineName = filterByPipelineName,
                filterByCreator = filterByCreator,
                filterByLabels = filterByLabels
            )
        )
    }

    override fun list(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType?
    ): Result<PipelinePage<Pipeline>> {
        checkParam(userId, projectId)
        return Result(
            pipelineListFacadeService.listPermissionPipeline(
                userId, projectId, page,
                pageSize, sortType ?: PipelineSortType.CREATE_TIME, ChannelCode.BS, true
            )
        )
    }

    override fun getPipelineStatus(
        userId: String,
        projectId: String,
        pipelines: Set<String>
    ): Result<Map<String, PipelineStatus>> {
        checkParam(userId, projectId)
        val status = pipelineListFacadeService.getPipelineStatus(userId, projectId, pipelines)
        val currentTimestamp = System.currentTimeMillis()
        return Result(status.map {
            it.pipelineId to PipelineStatus(
                it.taskCount,
                it.buildCount,
                it.lock,
                it.canManualStartup,
                it.latestBuildStartTime,
                it.latestBuildEndTime,
                it.latestBuildStatus,
                it.latestBuildNum,
                it.latestBuildTaskName,
                it.latestBuildEstimatedExecutionSeconds,
                it.latestBuildId,
                currentTimestamp,
                it.runningBuildCount,
                it.hasCollect
            )
        }.toMap())
    }

    override fun getStageTag(userId: String): Result<List<PipelineStageTag>> {
        if (userId.isBlank()) throw ParamBlankException("Invalid userId")
        return stageTagService.getAllStageTag()
    }

    override fun favor(userId: String, projectId: String, pipelineId: String, favor: Boolean): Result<Boolean> {
        return Result(pipelineGroupService.favorPipeline(userId, projectId, pipelineId, favor))
    }

    override fun exportPipeline(userId: String, projectId: String, pipelineId: String): Response {
        return pipelineInfoFacadeService.exportPipeline(userId, projectId, pipelineId)
    }

    override fun uploadPipeline(
        userId: String,
        pipelineInfo: PipelineModelAndSetting,
        projectId: String
    ): Result<String?> {
        return Result(pipelineInfoFacadeService.uploadPipeline(
            userId = userId,
            projectId = projectId,
            pipelineModelAndSetting = pipelineInfo
        ))
    }

    private fun checkParam(userId: String, projectId: String) {
        if (userId.isBlank()) {
            throw ParamBlankException("Invalid userId")
        }
        if (projectId.isBlank()) {
            throw ParamBlankException("Invalid projectId")
        }
    }

    private fun checkPipelineId(pipelineId: String) {
        if (pipelineId.isBlank()) {
            throw ParamBlankException("Invalid pipelineId")
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank()) {
            throw ParamBlankException("Invalid pipeline name")
        }
    }

    private fun checkParam(setting: PipelineSetting) {
        if (setting.runLockType == PipelineRunLockType.SINGLE ||
            setting.runLockType == PipelineRunLockType.SINGLE_LOCK) {
            if (setting.waitQueueTimeMinute < PIPELINE_SETTING_WAIT_QUEUE_TIME_MINUTE_MIN ||
                setting.waitQueueTimeMinute > PIPELINE_SETTING_WAIT_QUEUE_TIME_MINUTE_MAX
            ) {
                throw InvalidParamException("最大排队时长非法")
            }
            if (setting.maxQueueSize < PIPELINE_SETTING_MAX_QUEUE_SIZE_MIN ||
                setting.maxQueueSize > PIPELINE_SETTING_MAX_QUEUE_SIZE_MAX
            ) {
                throw InvalidParamException("最大排队数量非法")
            }
        }
    }
}
