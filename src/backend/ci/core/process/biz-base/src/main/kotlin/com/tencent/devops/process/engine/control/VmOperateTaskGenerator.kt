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

package com.tencent.devops.process.engine.control

import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.EnvControlTaskType
import com.tencent.devops.common.pipeline.pojo.element.ElementAdditionalOptions
import com.tencent.devops.common.pipeline.pojo.element.ElementPostInfo
import com.tencent.devops.common.pipeline.pojo.element.RunCondition
import com.tencent.devops.common.pipeline.type.BuildType
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import org.springframework.stereotype.Component
import javax.xml.bind.Element

/**
 * 生成运行环境操作的插件任务
 */
@Suppress("ALL")
@Component
class VmOperateTaskGenerator {

    val startVmTaskAtom = "dispatchVMStartupTaskAtom"
    val shutdownVmTaskAtom = "dispatchVMShutdownTaskAtom"
    val startNormalTaskAtom = "dispatchBuildLessDockerStartupTaskAtom"
    val shutdownNormalTaskAtom = "dispatchBuildLessDockerShutdownTaskAtom"

    /**
     * 生成编译环境的开机插件任务
     */
    fun makeStartVMContainerTask(
        projectId: String,
        pipelineId: String,
        buildId: String,
        stageId: String,
        container: Container,
        taskSeq: Int,
        userId: String,
        executeCount: Int
    ): PipelineBuildTask {

        val taskParams = container.genTaskParams()
        taskParams["elements"] = emptyList<Element>() // elements可能过多导致存储问题
        val buildType = (container as VMBuildContainer).dispatchType?.buildType()?.name ?: BuildType.DOCKER.name
        val baseOS = container.baseOS.name

        return PipelineBuildTask(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            stageId = stageId,
            containerId = container.id!!,
            containerHashId = container.containerId,
            containerType = container.getClassType(),
            taskSeq = taskSeq,
            taskId = VMUtils.genStartVMTaskId(container.id!!),
            taskName = "Prepare_Job#${container.id!!}",
            taskType = EnvControlTaskType.VM.name,
            taskAtom = startVmTaskAtom,
            status = BuildStatus.QUEUE,
            taskParams = taskParams,
            executeCount = executeCount,
            starter = userId,
            approver = null,
            subBuildId = null,
            additionalOptions = null,
            atomCode = "$startVmTaskAtom-$buildType-$baseOS"
        )
    }

    /**
     * 生成准备停机插件任务列表
     */
    fun makeShutdownVMContainerTasks(
        projectId: String,
        pipelineId: String,
        buildId: String,
        stageId: String,
        container: Container,
        containerSeq: Int,
        taskSeq: Int,
        userId: String,
        executeCount: Int
    ): List<PipelineBuildTask> {

        val list: MutableList<PipelineBuildTask> = mutableListOf()

        val containerId = container.id!!
        val containerType = container.getClassType()
        val endTaskSeq = VMUtils.genVMSeq(containerSeq, taskSeq - 1)

        // end-1xxx 无后续任务的结束节点
        list.add(
            PipelineBuildTask(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                containerId = containerId,
                containerHashId = container.containerId ?: "",
                containerType = containerType,
                taskSeq = endTaskSeq,
                taskId = VMUtils.genEndPointTaskId(endTaskSeq),
                taskName = "Wait_Finish_Job#$containerId",
                taskType = EnvControlTaskType.VM.name,
                taskAtom = "",
                status = BuildStatus.QUEUE,
                taskParams = mutableMapOf(),
                executeCount = executeCount,
                starter = userId,
                approver = null,
                subBuildId = null,
                additionalOptions = container.opts(taskName = "Prepare_Job#${container.id!!}(N)", taskSeq = taskSeq),
                atomCode = "$shutdownVmTaskAtom-FINISH"
            )
        )

        // stopVM-1xxx 停止虚拟机节点
        val stopVMTaskSeq = VMUtils.genVMSeq(containerSeq, taskSeq)
        val taskParams = container.genTaskParams()
        taskParams["elements"] = emptyList<Element>() // elements可能过多导致存储问题

        list.add(
            PipelineBuildTask(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                containerId = containerId,
                containerHashId = container.containerId ?: "",
                containerType = containerType,
                taskSeq = stopVMTaskSeq,
                taskId = VMUtils.genStopVMTaskId(stopVMTaskSeq),
                taskName = "Clean_Job#$containerId",
                taskType = EnvControlTaskType.VM.name,
                taskAtom = shutdownVmTaskAtom,
                status = BuildStatus.QUEUE,
                taskParams = taskParams,
                executeCount = executeCount,
                starter = userId,
                approver = null,
                subBuildId = null,
                additionalOptions = container.opts(taskName = "Prepare_Job#${container.id!!}", taskSeq = taskSeq),
                atomCode = "$shutdownVmTaskAtom-FINISH"
            )
        )

        return list
    }

    /**
     * 生成启动无编译环境的插件任务
     */
    fun makeStartNormalContainerTask(
        projectId: String,
        pipelineId: String,
        buildId: String,
        stageId: String,
        container: Container,
        taskSeq: Int,
        userId: String,
        executeCount: Int
    ): PipelineBuildTask {

        val taskParams = container.genTaskParams()
        taskParams["elements"] = emptyList<Element>() // elements在此无用，还可能因为过多导致存储溢出问题，清0
        return PipelineBuildTask(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            stageId = stageId,
            containerId = container.id!!,
            containerHashId = container.containerId ?: "",
            containerType = container.getClassType(),
            taskSeq = taskSeq,
            taskId = VMUtils.genStartVMTaskId(container.id!!),
            taskName = "Prepare_Job#${container.id!!}(N)",
            taskType = EnvControlTaskType.NORMAL.name,
            taskAtom = startNormalTaskAtom,
            status = BuildStatus.QUEUE,
            taskParams = taskParams,
            executeCount = executeCount,
            starter = userId,
            approver = null,
            subBuildId = null,
            additionalOptions = null,
            atomCode = startNormalTaskAtom
        )
    }

    /**
     * 生成准备无编译环境停机插件任务列表
     */
    fun makeShutdownNormalContainerTasks(
        projectId: String,
        pipelineId: String,
        buildId: String,
        stageId: String,
        container: Container,
        containerSeq: Int,
        taskSeq: Int,
        userId: String,
        executeCount: Int
    ): List<PipelineBuildTask> {

        val list: MutableList<PipelineBuildTask> = mutableListOf()

        val containerId = container.id!!
        val containerType = container.getClassType()
        val endTaskSeq = VMUtils.genVMSeq(containerSeq, taskSeq - 1)

        // end-1xxx 无后续任务的结束节点
        list.add(
            PipelineBuildTask(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                containerId = containerId,
                containerHashId = container.containerId ?: "",
                containerType = containerType,
                taskSeq = endTaskSeq,
                taskId = VMUtils.genEndPointTaskId(endTaskSeq),
                taskName = "Wait_Finish_Job#$containerId(N)",
                taskType = EnvControlTaskType.NORMAL.name,
                taskAtom = "",
                status = BuildStatus.QUEUE,
                taskParams = mutableMapOf(),
                executeCount = executeCount,
                starter = userId,
                approver = null,
                subBuildId = null,
                additionalOptions = container.opts(taskName = "Prepare_Job#${container.id!!}(N)", taskSeq = taskSeq),
                atomCode = "$shutdownNormalTaskAtom-FINISH"
            )
        )

        // stopVM-1xxx 停止虚拟机节点
        val stopVMTaskSeq = VMUtils.genVMSeq(containerSeq, taskSeq)
        val taskParams = container.genTaskParams()
        taskParams["elements"] = emptyList<Element>() // elements可能过多导致存储问题
        list.add(
            PipelineBuildTask(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                stageId = stageId,
                containerId = containerId,
                containerHashId = container.containerId ?: "",
                containerType = containerType,
                taskSeq = stopVMTaskSeq,
                taskId = VMUtils.genStopVMTaskId(stopVMTaskSeq),
                taskName = "Clean_Job#$containerId(N)",
                taskType = EnvControlTaskType.NORMAL.name,
                taskAtom = shutdownNormalTaskAtom,
                status = BuildStatus.QUEUE,
                taskParams = taskParams,
                executeCount = executeCount,
                starter = userId,
                approver = null,
                subBuildId = null,
                additionalOptions = container.opts(taskName = "Prepare_Job#${container.id!!}(N)", taskSeq = taskSeq),
                atomCode = "$shutdownNormalTaskAtom-CLEAN"
            )
        )

        return list
    }

    private fun Container.opts(taskName: String, taskSeq: Int) = ElementAdditionalOptions(
        continueWhenFailed = true,
        timeout = 5, // 5分钟超时
        runCondition = RunCondition.PRE_TASK_FAILED_BUT_CANCEL,
        elementPostInfo = ElementPostInfo(
            parentElementId = VMUtils.genStartVMTaskId(id!!),
            postCondition = "always",
            postEntryParam = "",
            parentElementName = taskName,
            parentElementJobIndex = taskSeq
        )
    )
}
