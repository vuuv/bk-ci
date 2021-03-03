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

package com.tencent.devops.common.pipeline.type

import com.fasterxml.jackson.annotation.JsonProperty
import com.tencent.devops.common.pipeline.type.docker.ImageType

/**
 * @Description
 * @Date 2019/11/12
 * @Version 1.0
 */
abstract class StoreDispatchType(
    @JsonProperty("value") open var dockerBuildVersion: String?,
    routeKeySuffix: DispatchRouteKeySuffix? = null,
    open var imageType: ImageType? = ImageType.BKDEVOPS,
    open var credentialId: String? = "",
    open var credentialProject: String? = "",
    // 商店镜像代码
    open var imageCode: String? = "",
    // 商店镜像版本
    open var imageVersion: String? = "",
    // 商店镜像名称
    open var imageName: String? = "",
    // 商店镜像公共标识
    open var imagePublicFlag: Boolean? = false,
    // 商店镜像研发来源c
    open var imageRDType: String? = "",
    // 商店镜像是否推荐
    open var recommendFlag: Boolean? = true
) : DispatchType((if (dockerBuildVersion.isNullOrBlank()) imageCode else dockerBuildVersion)
    ?: "StoreDispatchType empty image", routeKeySuffix)
