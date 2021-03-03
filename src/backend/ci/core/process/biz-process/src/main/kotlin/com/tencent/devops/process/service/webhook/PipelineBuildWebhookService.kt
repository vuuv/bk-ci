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

package com.tencent.devops.process.service.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.enums.RepositoryTypeNew
import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.log.pojo.message.LogMessage
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.BuildParameters
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeGitGenericWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeGitWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeGithubWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeGitlabWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeSVNWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeTGitWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.enums.CodeEventType
import com.tencent.devops.common.pipeline.pojo.element.trigger.enums.CodeType
import com.tencent.devops.plugin.api.pojo.GitCommitCheckEvent
import com.tencent.devops.plugin.api.pojo.GithubPrEvent
import com.tencent.devops.process.api.service.ServiceScmWebhookResource
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.engine.service.PipelineWebHookQueueService
import com.tencent.devops.process.engine.service.PipelineWebhookBuildLogContext
import com.tencent.devops.process.engine.service.PipelineWebhookService
import com.tencent.devops.process.engine.service.code.GitWebhookUnlockDispatcher
import com.tencent.devops.process.engine.service.code.ScmWebhookMatcherBuilder
import com.tencent.devops.process.engine.service.code.ScmWebhookParamsFactory
import com.tencent.devops.process.engine.utils.RepositoryUtils
import com.tencent.devops.process.pojo.code.ScmWebhookMatcher
import com.tencent.devops.process.pojo.code.WebhookCommit
import com.tencent.devops.process.pojo.code.git.GitEvent
import com.tencent.devops.process.pojo.code.git.GitMergeRequestEvent
import com.tencent.devops.process.pojo.code.git.GitPushEvent
import com.tencent.devops.process.pojo.code.github.GithubCreateEvent
import com.tencent.devops.process.pojo.code.github.GithubEvent
import com.tencent.devops.process.pojo.code.github.GithubPullRequestEvent
import com.tencent.devops.process.pojo.code.github.GithubPushEvent
import com.tencent.devops.process.pojo.code.svn.SvnCommitEvent
import com.tencent.devops.process.pojo.scm.code.GitlabCommitEvent
import com.tencent.devops.process.service.pipeline.PipelineBuildService
import com.tencent.devops.process.utils.PIPELINE_START_TASK_ID
import com.tencent.devops.process.utils.PipelineVarUtil
import com.tencent.devops.repository.api.ServiceRepositoryResource
import com.tencent.devops.scm.code.git.api.GITHUB_CHECK_RUNS_STATUS_IN_PROGRESS
import com.tencent.devops.scm.code.git.api.GIT_COMMIT_CHECK_STATE_PENDING
import com.tencent.devops.scm.utils.code.git.GitUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Suppress("ALL")
@Service
class PipelineBuildWebhookService @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val client: Client,
    private val pipelineWebhookService: PipelineWebhookService,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineBuildService: PipelineBuildService,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val scmWebhookMatcherBuilder: ScmWebhookMatcherBuilder,
    private val gitWebhookUnlockDispatcher: GitWebhookUnlockDispatcher,
    private val pipelineWebHookQueueService: PipelineWebHookQueueService,
    private val buildLogPrinter: BuildLogPrinter
) {

    private val logger = LoggerFactory.getLogger(PipelineBuildWebhookService::class.java)

    fun externalCodeSvnBuild(e: String): Boolean {
        logger.info("Trigger code svn build - $e")

        val event = try {
            objectMapper.readValue(e, SvnCommitEvent::class.java)
        } catch (e: Exception) {
            logger.warn("Fail to parse the svn web hook commit event", e)
            return false
        }

        val svnWebHookMatcher = scmWebhookMatcherBuilder.createSvnWebHookMatcher(event, pipelineWebhookService)

        return startProcessByWebhook(CodeSVNWebHookTriggerElement.classType, svnWebHookMatcher)
    }

    fun externalCodeGitBuild(codeRepositoryType: String, e: String): Boolean {
        logger.info("Trigger code git build($e)")

        val event = try {
            objectMapper.readValue<GitEvent>(e)
        } catch (e: Exception) {
            logger.warn("Fail to parse the git web hook commit event", e)
            return false
        }

        when (event) {
            is GitPushEvent -> {
                if (event.total_commits_count <= 0) {
                    logger.info("Git web hook no commit(${event.total_commits_count})")
                    return true
                }
                if (GitUtils.isPrePushBranch(event.ref)) {
                    logger.info("Git web hook is pre-push event|branchName=${event.ref}")
                    return true
                }
            }
            is GitMergeRequestEvent -> {
                if (event.object_attributes.action == "close" ||
                    (event.object_attributes.action == "update" &&
                        event.object_attributes.extension_action != "push-update")
                ) {
                    logger.info("Git web hook is ${event.object_attributes.action} merge request")
                    return true
                }
            }
        }

        val gitWebHookMatcher = scmWebhookMatcherBuilder.createGitWebHookMatcher(event)

        return startProcessByWebhook(codeRepositoryType, gitWebHookMatcher)
    }

    fun externalGitlabBuild(e: String): Boolean {
        logger.info("Trigger gitlab build($e)")

        val event = try {
            objectMapper.readValue(e, GitlabCommitEvent::class.java)
        } catch (e: Exception) {
            logger.warn("Fail to parse the gitlab web hook commit event", e)
            return false
        }

        val gitlabWebHookMatcher = scmWebhookMatcherBuilder.createGitlabWebHookMatcher(event)

        return startProcessByWebhook(CodeGitlabWebHookTriggerElement.classType, gitlabWebHookMatcher)
    }

    fun externalCodeGithubBuild(eventType: String, guid: String, signature: String, body: String): Boolean {
        logger.info("Trigger code github build (event=$eventType, guid=$guid, signature=$signature, body=$body)")

        val event: GithubEvent = when (eventType) {
            GithubPushEvent.classType -> objectMapper.readValue<GithubPushEvent>(body)
            GithubCreateEvent.classType -> objectMapper.readValue<GithubCreateEvent>(body)
            GithubPullRequestEvent.classType -> objectMapper.readValue<GithubPullRequestEvent>(body)
            else -> {
                logger.info("Github event($eventType) is ignored")
                return true
            }
        }

        when (event) {
            is GithubPushEvent -> {
                if (event.commits.isEmpty()) {
                    logger.info("Github web hook no commit")
                    return true
                }
            }
            is GithubPullRequestEvent -> {
                if (!(event.action == "opened" || event.action == "reopened" || event.action == "synchronize")) {
                    logger.info("Github pull request no open or update")
                    return true
                }
            }
        }

        val githubWebHookMatcher = scmWebhookMatcherBuilder.createGithubWebHookMatcher(event)

        return startProcessByWebhook(CodeGithubWebHookTriggerElement.classType, githubWebHookMatcher)
    }

    private fun startProcessByWebhook(codeRepositoryType: String, matcher: ScmWebhookMatcher): Boolean {
        val watcher = Watcher("${matcher.getRepoName()}|${matcher.getRevision()}|webhook trigger")
        PipelineWebhookBuildLogContext.addRepoInfo(repoName = matcher.getRepoName(), commitId = matcher.getRevision())
        try {
            watcher.start("getWebhookPipelines")
            logger.info("startProcessByWebhook|repo(${matcher.getRepoName()})|type($codeRepositoryType)")
            val pipelines = pipelineWebhookService.getWebhookPipelines(
                name = matcher.getRepoName(),
                type = codeRepositoryType
            ).toSet()

            if (pipelines.isEmpty()) {
                return false
            }

            watcher.start("webhookTriggerPipelineBuild")
            pipelines.forEach outside@{ pipelineId ->
                try {
                    logger.info("pipelineId is $pipelineId")
                    val model = pipelineRepositoryService.getModel(pipelineId) ?: run {
                        logger.info("pipeline does not exists, ignore")
                        return@outside
                    }

                    /**
                     * 验证流水线参数构建启动参数
                     */
                    val triggerContainer = model.stages[0].containers[0] as TriggerContainer
                    val canWebhookStartup = canWebhookStartup(triggerContainer, codeRepositoryType)

                    if (!canWebhookStartup) {
                        logger.info("can not start by $codeRepositoryType, ignore")
                        return@outside
                    }

                    if (webhookTriggerPipelineBuild(pipelineId, codeRepositoryType, matcher)) return@outside
                } catch (e: Throwable) {
                    logger.error("[$pipelineId]|webhookTriggerPipelineBuild fail: $e", e)
                }
            }
            /* #3131,当对mr的commit check有强依赖，但是蓝盾与git的commit check交互存在一定的时延，可以增加双重锁。
                git发起mr时锁住mr,称为webhook锁，由蓝盾主动发起解锁，解锁有三种情况：
                1. 仓库没有配置蓝盾的流水线，需要解锁
                2. 仓库配置了蓝盾流水线，但是流水线都不需要锁住mr，需要解锁
                3. 仓库配置了蓝盾流水线并且需要锁住mr，需要等commit check发送完成，再解锁
                 @see com.tencent.devops.plugin.service.git.CodeWebhookService.addGitCommitCheck
             */
            gitWebhookUnlockDispatcher.dispatchUnlockHookLockEvent(matcher)
            return true
        } finally {
            logger.info("repo(${matcher.getRepoName()})|webhook trigger|watcher=$watcher")
        }
    }

    private fun canWebhookStartup(
        triggerContainer: TriggerContainer,
        codeRepositoryType: String
    ): Boolean {
        var canWebhookStartup = false
        run lit@{
            triggerContainer.elements.forEach {
                when (codeRepositoryType) {
                    CodeSVNWebHookTriggerElement.classType -> {
                        if ((it is CodeSVNWebHookTriggerElement && it.isElementEnable()) ||
                            canGitGenericWebhookStartUp(it)
                        ) {
                            canWebhookStartup = true
                            return@lit
                        }
                    }
                    CodeGitWebHookTriggerElement.classType -> {
                        if ((it is CodeGitWebHookTriggerElement && it.isElementEnable()) ||
                            canGitGenericWebhookStartUp(it)) {
                            canWebhookStartup = true
                            return@lit
                        }
                    }
                    CodeGithubWebHookTriggerElement.classType -> {
                        if ((it is CodeGithubWebHookTriggerElement && it.isElementEnable()) ||
                            canGitGenericWebhookStartUp(it)
                        ) {
                            canWebhookStartup = true
                            return@lit
                        }
                    }
                    CodeGitlabWebHookTriggerElement.classType -> {
                        if ((it is CodeGitlabWebHookTriggerElement && it.isElementEnable()) ||
                            canGitGenericWebhookStartUp(it)
                        ) {
                            canWebhookStartup = true
                            return@lit
                        }
                    }
                    CodeTGitWebHookTriggerElement.classType -> {
                        if ((it is CodeTGitWebHookTriggerElement && it.isElementEnable()) ||
                            canGitGenericWebhookStartUp(it)
                        ) {
                            canWebhookStartup = true
                            return@lit
                        }
                    }
                }
            }
        }
        return canWebhookStartup
    }

    private fun canGitGenericWebhookStartUp(
        element: Element
    ): Boolean {
        if (element is CodeGitGenericWebHookTriggerElement && element.isElementEnable()) {
            return true
        }
        return false
    }

    fun webhookTriggerPipelineBuild(
        pipelineId: String,
        codeRepositoryType: String,
        matcher: ScmWebhookMatcher
    ): Boolean {
        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(pipelineId)
            ?: return false

        val model = pipelineRepositoryService.getModel(pipelineId)
        if (model == null) {
            logger.warn("[$pipelineId]| Fail to get the model")
            return false
        }

        val projectId = pipelineInfo.projectId
        val userId = pipelineInfo.lastModifyUser
        val variables = mutableMapOf<String, String>()
        val container = model.stages[0].containers[0] as TriggerContainer
        // 解析变量
        container.params.forEach { param ->
            variables[param.id] = param.defaultValue.toString()
        }

        // 寻找代码触发原子
        container.elements.forEach elements@{ element ->
            if (!element.isElementEnable()) {
                logger.info("Trigger element is disable, can not start pipeline")
                return@elements
            }
            val webHookParams = ScmWebhookParamsFactory.getWebhookElementParams(element, variables) ?: return@elements
            val repositoryConfig = webHookParams.repositoryConfig
            if (repositoryConfig.getRepositoryId().isBlank()) {
                logger.info("repositoryHashId is blank for code trigger pipeline $pipelineId ")
                return@elements
            }

            logger.info("Get the code trigger pipeline $pipelineId branch ${webHookParams.branchName}")
            // #2958 如果仓库找不到,会抛出404异常,就不会继续往下遍历
            val repo = try {
                if (element is CodeGitGenericWebHookTriggerElement &&
                    element.data.input.repositoryType == RepositoryTypeNew.URL
                ) {
                    RepositoryUtils.buildRepository(
                        projectId = pipelineInfo.projectId,
                        userName = pipelineInfo.lastModifyUser,
                        scmType = ScmType.valueOf(element.data.input.scmType),
                        repositoryUrl = repositoryConfig.repositoryName!!,
                        credentialId = element.data.input.credentialId
                    )
                } else {
                    client.get(ServiceRepositoryResource::class)
                        .get(
                            projectId,
                            repositoryConfig.getURLEncodeRepositoryId(),
                            repositoryConfig.repositoryType
                        ).data
                }
            } catch (e: Exception) {
                null
            }
            if (repo == null) {
                logger.warn("pipeline:$pipelineId|repo[$repositoryConfig] does not exist")
                return@elements
            }

            val matchResult = matcher.isMatch(projectId, pipelineId, repo, webHookParams)
            if (matchResult.isMatch) {
                try {
                    val webhookCommit = WebhookCommit(
                        userId = userId,
                        pipelineId = pipelineId,
                        params = ScmWebhookParamsFactory.getStartParams(
                            projectId = projectId,
                            element = element,
                            repo = repo,
                            matcher = matcher,
                            variables = variables,
                            params = webHookParams,
                            matchResult = matchResult
                        ),
                        repositoryConfig = repositoryConfig,
                        repoName = matcher.getRepoName(),
                        commitId = matcher.getRevision(),
                        block = webHookParams.block,
                        eventType = matcher.getEventType(),
                        codeType = matcher.getCodeType()
                    )
                    val buildId =
                        client.getGateway(ServiceScmWebhookResource::class).webhookCommit(projectId, webhookCommit).data

                    PipelineWebhookBuildLogContext.addLogBuildInfo(
                        projectId = projectId,
                        pipelineId = pipelineId,
                        taskId = element.id!!,
                        taskName = element.name,
                        success = true,
                        triggerResult = buildId
                    )
                    logger.info("$pipelineId|$buildId|webhook trigger|(${element.name}|repo(${matcher.getRepoName()})")
                } catch (ignore: Exception) {
                    logger.warn("$pipelineId|webhook trigger|(${element.name}|repo(${matcher.getRepoName()})", ignore)
                }
                return false
            } else {
                PipelineWebhookBuildLogContext.addLogBuildInfo(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    taskId = element.id!!,
                    taskName = element.name,
                    success = false,
                    triggerResult = matchResult.failedReason
                )
            }
        }
        return false
    }

    /**
     * webhookCommitTriggerPipelineBuild 方法是webhook事件触发最后执行方法
     * @link webhookTriggerPipelineBuild 方法接收webhook事件后通过调用网关接口进行分发，从而区分正式和灰度服务
     * @param projectId 项目ID
     * @param webhookCommit webhook事件信息
     *
     */
    fun webhookCommitTriggerPipelineBuild(projectId: String, webhookCommit: WebhookCommit): String {
        val userId = webhookCommit.userId
        val pipelineId = webhookCommit.pipelineId
        val startParams = webhookCommit.params

        val repoName = webhookCommit.repoName

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(pipelineId)
            ?: throw RuntimeException("Pipeline($pipelineId) not found")

        val model = pipelineRepositoryService.getModel(pipelineId)
        if (model == null) {
            logger.warn("[$pipelineId]| Fail to get the model")
            return ""
        }

        // 兼容从旧v1版本下发过来的请求携带旧的变量命名
        val params = mutableMapOf<String, Any>()
        val startParamsWithType = mutableListOf<BuildParameters>()
        startParams.forEach {
            // 从旧转新: 兼容从旧入口写入的数据转到新的流水线运行
            val newVarName = PipelineVarUtil.oldVarToNewVar(it.key)
            if (newVarName == null) { // 为空表示该变量是新的，或者不需要兼容，直接加入，能会覆盖旧变量转换而来的新变量
                params[it.key] = it.value
                startParamsWithType.add(BuildParameters(it.key, it.value))
            } else if (!params.contains(newVarName)) { // 新变量还不存在，加入
                params[newVarName] = it.value
                startParamsWithType.add(BuildParameters(newVarName, it.value))
            }
        }

        val startEpoch = System.currentTimeMillis()
        try {
            val buildId = pipelineBuildService.startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = pipelineInfo,
                startType = StartType.WEB_HOOK,
                startParamsWithType = startParamsWithType,
                channelCode = pipelineInfo.channelCode,
                isMobile = false,
                model = model,
                signPipelineVersion = pipelineInfo.version,
                frequencyLimit = false
            )
            dispatchCommitCheck(projectId = projectId, webhookCommit = webhookCommit, buildId = buildId)
            pipelineWebHookQueueService.onWebHookTrigger(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                variables = webhookCommit.params
            )
            // #2958 webhook触发在触发原子上输出变量
            buildLogPrinter.addLines(buildId = buildId, logMessages = startParamsWithType.map {
                LogMessage(
                    message = "${it.key}=${it.value}",
                    timestamp = System.currentTimeMillis(),
                    tag = startParams[PIPELINE_START_TASK_ID]?.toString() ?: ""
                )
            })
            return buildId
        } catch (ignore: Exception) {
            logger.warn("[$pipelineId]| webhook trigger fail to start repo($repoName): ${ignore.message}", ignore)
            return ""
        } finally {
            logger.info("$pipelineId|WEBHOOK_TRIGGER|repo=$repoName|time=${System.currentTimeMillis() - startEpoch}")
        }
    }

    private fun dispatchCommitCheck(
        projectId: String,
        webhookCommit: WebhookCommit,
        buildId: String
    ) {
        with(webhookCommit) {
            when {
                webhookCommit.eventType == CodeEventType.MERGE_REQUEST &&
                    (webhookCommit.codeType == CodeType.GIT || webhookCommit.codeType == CodeType.TGIT) -> {
                    logger.info("$buildId|WebHook_ADD_GIT_COMMIT_CHECK|$pipelineId|$repositoryConfig|$commitId]")
                    pipelineEventDispatcher.dispatch(
                        GitCommitCheckEvent(
                            source = "codeWebhook_pipeline_build_trigger",
                            userId = userId,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            buildId = buildId,
                            repositoryConfig = repositoryConfig,
                            commitId = commitId,
                            state = GIT_COMMIT_CHECK_STATE_PENDING,
                            block = block
                        )
                    )
                }
                webhookCommit.eventType == CodeEventType.PULL_REQUEST && webhookCommit.codeType == CodeType.GITHUB -> {
                    logger.info("$buildId|WebHook_ADD_GITHUB_COMMIT_CHECK|$pipelineId|$repositoryConfig|$commitId]")
                    pipelineEventDispatcher.dispatch(
                        GithubPrEvent(
                            source = "codeWebhook_pipeline_build_trigger",
                            userId = userId,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            buildId = buildId,
                            repositoryConfig = repositoryConfig,
                            commitId = commitId,
                            status = GITHUB_CHECK_RUNS_STATUS_IN_PROGRESS,
                            startedAt = LocalDateTime.now().timestamp(),
                            conclusion = null,
                            completedAt = null
                        )
                    )
                }
                else -> Unit
            }
        }
    }
}
