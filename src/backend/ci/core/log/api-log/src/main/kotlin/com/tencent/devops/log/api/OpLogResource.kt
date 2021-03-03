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

package com.tencent.devops.log.api

import com.tencent.devops.common.api.pojo.Result
import io.swagger.annotations.Api
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

/**
 *
 * Powered By Tencent
 */
@Api(tags = ["OP_LOG"], description = "管理-日志资源")
@Path("/op/logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface OpLogResource {

    @ApiParam("设置构建清理过期时长")
    @PUT
    @Path("/v2/build/clean/expire")
    fun setBuildExpire(
        @ApiParam("时间, 单位(天)", required = true)
        @QueryParam("expire")
        expire: Int
    ): Result<Boolean>

    @ApiParam("获取构建清理过期时长")
    @GET
    @Path("/v2/build/clean/expire")
    fun getBuildExpire(): Result<Int>

    @ApiParam("设置ES索引过期时长")
    @PUT
    @Path("/v2/es/index/expire")
    fun setESExpire(
        @ApiParam("时间, 单位(天)", required = true)
        @QueryParam("expire")
        expire: Int
    ): Result<Boolean>

    @ApiParam("获取ES索引过期时长")
    @GET
    @Path("/v2/es/index/expire")
    fun getESExpire(): Result<Int>

    @ApiParam("重新打开索引")
    @PUT
    @Path("/v2/es/index/reopen")
    fun reopenIndex(
        @ApiParam("构建ID", required = true)
        @QueryParam("buildId")
        buildId: String
    ): Result<Boolean>
}
