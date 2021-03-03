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

package com.tencent.devops.common.pipeline.pojo

import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.pipeline.enums.BuildFormPropertyType
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

@ApiModel("构建模型-表单元素属性")
data class BuildFormProperty(
    @ApiModelProperty("元素ID-标识符", required = true)
    var id: String,
    @ApiModelProperty("是否必须", required = true)
    var required: Boolean,
    @ApiModelProperty("元素类型", required = true)
    val type: BuildFormPropertyType,
    @ApiModelProperty("默认值", required = true)
    var defaultValue: Any,
    @ApiModelProperty("下拉框列表", required = false)
    var options: List<BuildFormValue>?,
    @ApiModelProperty("描述", required = false)
    var desc: String?,

    // 针对 SVN_TAG 新增字段
    @ApiModelProperty("repoHashId", required = false)
    val repoHashId: String?,
    @ApiModelProperty("relativePath", required = false)
    val relativePath: String?,
    @ApiModelProperty("代码库类型下拉", required = false)
    val scmType: ScmType?,
    @ApiModelProperty("构建机类型下拉", required = false)
    val containerType: BuildContainerType?,

    @ApiModelProperty("自定义仓库通配符", required = false)
    val glob: String?,
    @ApiModelProperty("文件元数据", required = false)
    val properties: Map<String, String>?,
    @ApiModelProperty("元素标签", required = false)
    var label: String? = null,
    @ApiModelProperty("元素placeholder", required = false)
    var placeholder: String? = null,
    // 区分构建信息、构建版本和流水线参数
    @ApiModelProperty("元素模块", required = false)
    var propertyType: String? = null
)
