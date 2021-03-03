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

package com.tencent.devops.repository.service.github

import com.tencent.devops.common.api.util.AESUtil
import com.tencent.devops.repository.dao.GithubTokenDao
import com.tencent.devops.repository.pojo.github.GithubToken
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class GithubTokenService @Autowired constructor(
    private val dslContext: DSLContext,
    private val githubTokenDao: GithubTokenDao
) {
    @Value("\${aes.github:#{null}}")
    private val aesKey = ""

    fun createAccessToken(userId: String, accessToken: String, tokenType: String, scope: String) {
        val encryptedAccessToken = AESUtil.encrypt(aesKey, accessToken)
        if (getAccessToken(userId) == null) {
            githubTokenDao.create(dslContext, userId, encryptedAccessToken, tokenType, scope)
        } else {
            githubTokenDao.update(dslContext, userId, encryptedAccessToken, tokenType, scope)
        }
    }

    fun deleteAccessToken(userId: String) {
        githubTokenDao.delete(dslContext, userId)
    }

    fun getAccessToken(userId: String): GithubToken? {
        val githubTokenRecord = githubTokenDao.getOrNull(dslContext, userId) ?: return null
        logger.info("github aesKey:$aesKey")
        return GithubToken(
            AESUtil.decrypt(aesKey, githubTokenRecord.accessToken),
            githubTokenRecord.tokenType,
            githubTokenRecord.scope
        )
    }

    companion object {
        val logger = LoggerFactory.getLogger(this::class.java)
    }
}
