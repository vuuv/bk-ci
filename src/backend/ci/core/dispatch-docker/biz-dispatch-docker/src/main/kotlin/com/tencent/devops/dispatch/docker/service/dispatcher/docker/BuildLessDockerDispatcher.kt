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

package com.tencent.devops.dispatch.docker.service.dispatcher.docker

import com.tencent.devops.common.pipeline.type.docker.DockerDispatchType
import com.tencent.devops.dispatch.docker.service.DockerHostBuildService
import com.tencent.devops.dispatch.docker.service.dispatcher.BuildLessDispatcher
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.process.pojo.mq.PipelineBuildLessShutdownDispatchEvent
import com.tencent.devops.process.pojo.mq.PipelineBuildLessStartupDispatchEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class BuildLessDockerDispatcher @Autowired constructor(
    private val buildLogPrinter: BuildLogPrinter,
    private val dockerHostBuildService: DockerHostBuildService
) : BuildLessDispatcher {
    override fun canDispatch(event: PipelineBuildLessStartupDispatchEvent) =
        event.dispatchType is DockerDispatchType

    override fun startUp(event: PipelineBuildLessStartupDispatchEvent) {
        val dockerDispatch = event.dispatchType as DockerDispatchType
        val dockerBuildVersion = dockerDispatch.dockerBuildVersion
        buildLogPrinter.addLine(
            buildId = event.buildId,
            message = "Start buildLessDocker $dockerBuildVersion for the build",
            tag = "",
            jobId = event.containerHashId,
            executeCount = event.executeCount ?: 1
        )
        dockerHostBuildService.buildLessDockerHost(event)
    }

    override fun shutdown(event: PipelineBuildLessShutdownDispatchEvent) {
        dockerHostBuildService.finishBuildLessDockerHost(event.buildId, event.vmSeqId, event.userId, event.buildResult)
    }
}
