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
package com.tencent.devops.openapi.aspect

import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.openapi.filter.ApiFilter
import com.tencent.devops.openapi.service.op.AppCodeService
import com.tencent.devops.openapi.utils.ApiGatewayUtil
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before

import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Aspect
@Component
@Suppress("ALL")
class ApiAspect(
    private val appCodeService: AppCodeService,
    private val apiGatewayUtil: ApiGatewayUtil
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ApiFilter::class.java)
    }

    /**
     * 前置增强：目标方法执行之前执行
     *
     * @param jp
     */
    @Before(
        "execution(* com.tencent.devops.openapi.resources.apigw.*.*(..))" +
            "||execution(* com.tencent.devops.openapi.resources.apigw.v2.*.*(..))" +
            "||execution(* com.tencent.devops.openapi.resources.apigw.v3.*.*(..))" +
            "||execution(* com.tencent.devops.openapi.resources.apigw.v2.app.*.*(..))" +
            "||execution(* com.tencent.devops.openapi.resources.apigw.v2.user.*.*(..))"
    ) // 所有controller包下面的所有方法的所有参数
    fun beforeMethod(jp: JoinPoint) {
        if (!apiGatewayUtil.isAuth()) {
            return
        }

        // 参数value
        val parameterValue = jp.args
        // 参数key
        val parameterNames = (jp.signature as MethodSignature).parameterNames
        var projectId: String? = null
        var appCode: String? = null
        var apigwType: String? = null

        for (index in parameterValue.indices) {
            when (parameterNames[index]) {
                "projectId" -> projectId = parameterValue[index]?.toString()
                "appCode" -> appCode = parameterValue[index]?.toString()
                "apigwType" -> apigwType = parameterValue[index]?.toString()
                else -> Unit
            }
        }

        if (logger.isDebugEnabled) {

            val methodName: String = jp.signature.name
            logger.debug("【前置增强】the method 【{}】", methodName)

            parameterNames.forEach {
                logger.debug("参数名[{}]", it)
            }

            parameterValue.forEach {
                logger.debug("参数值[{}]", it)
            }
            logger.debug("ApiAspect|apigwType[$apigwType],appCode[$appCode],projectId[$projectId]")
        }

        if (projectId != null && appCode != null && (apigwType == "apigw-app")) {
            if (!appCodeService.validAppCode(appCode, projectId)) {
                throw PermissionForbiddenException(
                    message = "Permission denied: apigwType[$apigwType],appCode[$appCode],ProjectId[$projectId]"
                )
            }
        }
    }
}
