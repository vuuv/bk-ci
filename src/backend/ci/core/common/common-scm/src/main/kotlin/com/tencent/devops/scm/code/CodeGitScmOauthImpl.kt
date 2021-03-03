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

package com.tencent.devops.scm.code

import com.tencent.devops.common.api.constant.RepositoryMessageCode
import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.scm.IScm
import com.tencent.devops.scm.code.git.CodeGitCredentialSetter
import com.tencent.devops.scm.code.git.api.GitOauthApi
import com.tencent.devops.scm.config.GitConfig
import com.tencent.devops.scm.exception.ScmException
import com.tencent.devops.scm.pojo.RevisionInfo
import com.tencent.devops.scm.utils.code.git.GitUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory

class CodeGitScmOauthImpl constructor(
    override val projectName: String,
    override val branchName: String?,
    override val url: String,
    private val privateKey: String?,
    private val passPhrase: String?,
    private val token: String,
    private val gitConfig: GitConfig,
    private val event: String? = null
) : IScm {

    private val apiUrl = GitUtils.getGitApiUrl(apiUrl = gitConfig.gitApiUrl, repoUrl = url)

    override fun getLatestRevision(): RevisionInfo {
        val branch = branchName ?: "master"
        val gitBranch = gitOauthApi.getBranch(
            host = apiUrl,
            token = token,
            projectName = projectName,
            branchName = branch
        )
        return RevisionInfo(
            revision = gitBranch.commit.id,
            updatedMessage = gitBranch.commit.message,
            branchName = branch
        )
    }

    override fun getBranches() =
        gitOauthApi.listBranches(gitConfig.gitApiUrl, token, projectName)

    override fun getTags() =
        gitOauthApi.listTags(gitConfig.gitApiUrl, token, projectName)

    override fun checkTokenAndPrivateKey() {
        if (privateKey == null) {
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.SERCRT_EMPTY),
                ScmType.CODE_GIT.name
            )
        }
        // Check if token legal
        try {
            getBranches()
        } catch (ignored: Throwable) {
            logger.warn("Fail to list all branches", ignored)
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_WRONG),
                ScmType.CODE_GIT.name
            )
        }

        try {
            // Check the private key
            val command = Git.lsRemoteRepository()
            val credentialSetter = CodeGitCredentialSetter(privateKey, passPhrase)
            credentialSetter.setGitCredential(command)
            command.setRemote(url)
                .call()
        } catch (ignored: Throwable) {
            logger.warn("Fail to check the private key of git", ignored)
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_SERCRT_WRONG),
                ScmType.CODE_GIT.name
            )
        }
    }

    override fun checkTokenAndUsername() {
        if (privateKey == null) {
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.PWD_EMPTY),
                ScmType.CODE_GIT.name
            )
        }

        // Check if token legal
        try {
            getBranches()
        } catch (ignored: Throwable) {
            logger.warn("Fail to list all branches", ignored)
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_WRONG),
                ScmType.CODE_GIT.name
            )
        }

        try {
            val command = Git.lsRemoteRepository()
            command.setRemote(url)
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider(privateKey, passPhrase))
            command.call()
        } catch (ignored: Throwable) {
            logger.warn("Fail to check the username and password of git", ignored)
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_LOGIN_FAIL),
                ScmType.CODE_GIT.name
            )
        }
    }

    override fun addWebHook(hookUrl: String) {
        if (token.isEmpty()) {
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_EMPTY),
                ScmType.CODE_GIT.name
            )
        }
        if (hookUrl.isEmpty()) {
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_HOOK_URL_EMPTY),
                ScmType.CODE_GIT.name
            )
        }
        try {
            gitOauthApi.addWebhook(gitConfig.gitApiUrl, token, projectName, hookUrl, event)
        } catch (e: ScmException) {
            throw ScmException(
                MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_FAIL),
                ScmType.CODE_GIT.name
            )
        }
    }

    override fun addCommitCheck(
        commitId: String,
        state: String,
        targetUrl: String,
        context: String,
        description: String,
        block: Boolean
    ) {
        if (token.isEmpty()) {
            throw RuntimeException(MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_EMPTY))
        }
        try {
            gitOauthApi.addCommitCheck(
                host = apiUrl,
                token = token,
                projectName = projectName,
                commitId = commitId,
                state = state,
                detailUrl = targetUrl,
                context = context,
                description = description,
                block = block
            )
        } catch (e: ScmException) {
            throw RuntimeException(MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.GIT_TOKEN_FAIL))
        }
    }

    override fun addMRComment(mrId: Long, comment: String) {
        gitOauthApi.addMRComment(apiUrl, token, projectName, mrId, comment)
    }

    override fun lock(repoName: String, applicant: String, subpath: String) {
        logger.info("Git oauth can not lock")
    }

    override fun unlock(repoName: String, applicant: String, subpath: String) {
        logger.info("Git oauth can not unlock")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CodeGitScmOauthImpl::class.java)
        private val gitOauthApi = GitOauthApi()
    }
}
