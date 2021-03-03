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

package com.tencent.devops.dispatch.service

import com.tencent.devops.common.api.exception.InvalidParamException
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.dispatch.dao.DispatchPipelineBuildDao
import com.tencent.devops.dispatch.pojo.PipelineBuild
import com.tencent.devops.dispatch.service.dispatcher.Dispatcher
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.dispatch.exception.ErrorCodeEnum
import com.tencent.devops.dispatch.pojo.enums.JobQuotaVmType
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.pojo.mq.PipelineAgentShutdownEvent
import com.tencent.devops.process.pojo.mq.PipelineAgentStartupEvent
import org.jooq.DSLContext
import org.reflections.Reflections
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.ws.rs.NotFoundException

@Service@Suppress("ALL")
class PipelineDispatchService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val buildLogPrinter: BuildLogPrinter,
    private val dispatchPipelineBuildDao: DispatchPipelineBuildDao,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val jobQuotaBusinessService: JobQuotaBusinessService
) {

    private var dispatchers: Set<Dispatcher>? = null

    private fun getDispatchers(): Set<Dispatcher> {
        if (dispatchers == null) {
            synchronized(this) {
                if (dispatchers == null) {
                    val reflections = Reflections("com.tencent.devops.dispatch.service.dispatcher")
                    val dispatcherClasses = reflections.getSubTypesOf(Dispatcher::class.java)
                    if (dispatcherClasses == null || dispatcherClasses.isEmpty()) {
                        logger.error("The dispatcher is empty $dispatcherClasses")
                        throw InvalidParamException("Dispatcher is empty")
                    }
                    logger.info("Get the dispatch classes $dispatcherClasses")
                    dispatchers = dispatcherClasses.map {
                        SpringContextUtil.getBean(it)
                    }.toSet()
                }
            }
        }
        return dispatchers!!
    }

    fun startUp(pipelineAgentStartupEvent: PipelineAgentStartupEvent) {
        logger.info("${pipelineAgentStartupEvent.pipelineId}|${pipelineAgentStartupEvent.buildId}|VM_START" +
            "|seq=${pipelineAgentStartupEvent.vmSeqId})")
        // Check if the pipeline is running
        val record = client.get(ServicePipelineResource::class).isPipelineRunning(
            pipelineAgentStartupEvent.projectId,
            pipelineAgentStartupEvent.buildId,
            ChannelCode.valueOf(pipelineAgentStartupEvent.channelCode)
        )
        if (record.isNotOk() || record.data == null) {
            logger.warn("Fail to check if pipeline is running because of ${record.message}")
            return
        }

        if (!record.data!!) {
            logger.warn("The build($pipelineAgentStartupEvent) is not running")
            return
        }

        if (pipelineAgentStartupEvent.retryTime == 0) {
            buildLogPrinter.addLine(
                buildId = pipelineAgentStartupEvent.buildId,
                message = "构建环境准备中...",
                tag = VMUtils.genStartVMTaskId(pipelineAgentStartupEvent.containerId),
                jobId = pipelineAgentStartupEvent.containerHashId,
                executeCount = pipelineAgentStartupEvent.executeCount ?: 1
            )
        }

        logger.info("Get the dispatch ${pipelineAgentStartupEvent.dispatchType}")

        getDispatchers().forEach {
            if (it.canDispatch(pipelineAgentStartupEvent)) {
                // JOB配额判断
                if (!jobQuotaBusinessService.checkJobQuota(pipelineAgentStartupEvent, buildLogPrinter)) {
                    it.onFailBuild(
                        client = client,
                        buildLogPrinter = buildLogPrinter,
                        event = pipelineAgentStartupEvent,
                        errorType = ErrorType.USER,
                        errorCode = ErrorCodeEnum.JOB_QUOTA_EXCESS.errorCode,
                        errorMsg = "系统JOB配额超限"
                    )
                    return
                }
                it.startUp(pipelineAgentStartupEvent)
                // 到这里说明JOB已经启动成功(但是不代表Agent启动成功)，开始累加使用额度
                val vmType = JobQuotaVmType.parse(pipelineAgentStartupEvent.dispatchType)
                if (null != vmType) {
                    jobQuotaBusinessService.insertRunningJob(
                        projectId = pipelineAgentStartupEvent.projectId,
                        vmType = vmType,
                        buildId = pipelineAgentStartupEvent.buildId,
                        vmSeqId = pipelineAgentStartupEvent.vmSeqId
                    )
                }
                return
            }
        }
        throw InvalidParamException("Not Found dispatcher for the build ${pipelineAgentStartupEvent.dispatchType}")
    }

    fun shutdown(pipelineAgentShutdownEvent: PipelineAgentShutdownEvent) {
            logger.info("Start to finish the pipeline build($pipelineAgentShutdownEvent)")
            getDispatchers().forEach {
                try {
                    it.shutdown(pipelineAgentShutdownEvent)
                } finally {
                    // 不管shutdown成功失败，都要回收配额；这里回收job，将自动累加agent执行时间
                    jobQuotaBusinessService.deleteRunningJob(
                        projectId = pipelineAgentShutdownEvent.projectId,
                        buildId = pipelineAgentShutdownEvent.buildId,
                        vmSeqId = pipelineAgentShutdownEvent.vmSeqId
                    )
                }
            }
    }

    fun reDispatch(pipelineAgentStartupEvent: PipelineAgentStartupEvent) {
        getDispatchers().forEach {
            if (it.canDispatch(pipelineAgentStartupEvent)) {
                it.retry(client, buildLogPrinter, pipelineEventDispatcher, pipelineAgentStartupEvent)
                return
            }
        }
    }

    fun queryPipelineByBuildAndSeqId(buildId: String, vmSeqId: String): PipelineBuild {
        val list = dispatchPipelineBuildDao.getPipelineByBuildIdOrNull(dslContext, buildId, vmSeqId)
        if (list.isEmpty()) {
            throw throw NotFoundException("VM pipeline[$buildId,$vmSeqId] is not exist")
        }
        return dispatchPipelineBuildDao.convert(list[0])
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineDispatchService::class.java)
    }
}
