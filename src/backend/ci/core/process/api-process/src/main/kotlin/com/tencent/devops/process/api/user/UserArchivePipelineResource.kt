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

package com.tencent.devops.process.api.user

import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.pojo.Result
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

@Api(tags = ["USER_PIPELINE_ARCHIVE"], description = "服务-流水线资源")
@Path("/user/archive/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface UserArchivePipelineResource {

    @ApiOperation("获取某个项目的所有流水线")
    @GET
    // @Path("/projects/{projectId}/getAllPipelines")
    @Path("/{projectId}/getAllPipelines")
    @Deprecated("use getDownloadAllPipelines instead")
    fun getAllPipelines(
        @ApiParam(value = "用户id", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam(value = "项目id", required = true)
        @PathParam(value = "projectId")
        projectId: String
    ): Result<List<Map<String, String>>>

    @ApiOperation("获取某条流水线所有构建号")
    @GET
    // @Path("/projects/{projectId}/pipelines/{pipelineId}/getAllBuildNo")
    @Path("/{projectId}/pipelines/{pipelineId}/getAllBuildNo")
    fun getAllBuildNo(
        @ApiParam(value = "用户id", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam(value = "流水线id", required = true)
        @PathParam(value = "pipelineId")
        pipelineId: String,
        @ApiParam(value = "项目id", required = true)
        @PathParam(value = "projectId")
        projectId: String
    ): Result<List<Map<String, String>>>

    @ApiOperation("获取某个项目用户可以下载归档的所有流水线")
    @GET
    // @Path("/projects/{projectId}/getDownloadAllPipelines")
    @Path("/{projectId}/getDownloadAllPipelines")
    fun getDownloadAllPipelines(
        @ApiParam(value = "用户id", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam(value = "项目id", required = true)
        @PathParam(value = "projectId")
        projectId: String
    ): Result<List<Map<String, String>>>
}
