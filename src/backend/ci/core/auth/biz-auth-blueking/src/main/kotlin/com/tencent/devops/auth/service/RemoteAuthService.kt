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

package com.tencent.devops.auth.service

import com.tencent.bk.sdk.iam.service.impl.TokenServiceImpl
import com.tencent.devops.auth.utils.StringUtils
import com.tencent.devops.common.redis.RedisOperation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class RemoteAuthService @Autowired constructor(
    val redisOperation: RedisOperation,
    val tokenServiceImpl: TokenServiceImpl
) {

    @Value("\${auth.iamCallBackUser}")
    val iamClientName = ""

    fun checkToken(token: String): Boolean {
        val pair = StringUtils.decodeAuth(token)
        if (pair.first != iamClientName) {
            logger.warn("iam tokenCheck: userName error ${pair.first}")
            return false
        }
        val redisToken = redisOperation.get(TOKEN_REDIS_KEY)
        if (redisToken.isNullOrEmpty()) {
            logger.info("iam tokenCheck: redis is empty")
            val remoteToken = getRemoteToken()
            return if (remoteToken == pair.second) {
                redisOperation.set(TOKEN_REDIS_KEY, remoteToken)
                true
            } else {
                false
            }
        }
        logger.info("iam tokenCheck: redis data $redisToken")
        if (pair.second == redisToken) {
            return true
        }

        logger.info("iam tokenCheck: redis notEqual input, redis[$redisToken], input[${pair.second}]")
        // 最终验证权在auth服务端
        val remoteToken = getRemoteToken()
        if (pair.second == remoteToken) {
            return true
        }
        logger.info("iam tokenCheck fail]")
        return false
    }

    private fun getRemoteToken(): String {
        val token = tokenServiceImpl.token
        logger.info("get iam token: $token")
        return token
    }

    companion object {
        const val TOKEN_REDIS_KEY = "_BK:AUTH:V3:TOKEN_"
        val logger = LoggerFactory.getLogger(this::class.java)
    }
}
