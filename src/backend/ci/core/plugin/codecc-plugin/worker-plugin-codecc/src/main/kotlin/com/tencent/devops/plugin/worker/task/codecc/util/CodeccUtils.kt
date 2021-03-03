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

package com.tencent.devops.plugin.worker.task.codecc.util

import com.tencent.devops.common.api.enums.OSType
import com.tencent.devops.common.pipeline.enums.BuildScriptType
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.plugin.codecc.pojo.coverity.CoverityProjectType
import com.tencent.devops.plugin.worker.pojo.CodeccExecuteConfig
import com.tencent.devops.plugin.worker.task.codecc.LinuxCodeccConstants.COV_TOOLS
import com.tencent.devops.plugin.worker.task.codecc.LinuxCodeccConstants.GO_CI_LINT_PATH
import com.tencent.devops.plugin.worker.task.codecc.LinuxCodeccConstants.PHPCS_TOOL_PATH
import com.tencent.devops.plugin.worker.task.codecc.LinuxCodeccConstants.STYLE_TOOL_PATH
import com.tencent.devops.plugin.worker.task.codecc.LinuxCodeccConstants.SVN_PASSWORD
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.addCommonParams
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getCovToolPath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getGoMetaLinterPath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getGoRootPath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getJdkPath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getKlocToolPath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getNodePath
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getPyLint2Path
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getPyLint3Path
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getPython2Path
import com.tencent.devops.plugin.worker.task.codecc.util.CodeccParamsHelper.getPython3Path
import com.tencent.devops.process.utils.PIPELINE_TURBO_TASK_ID
import com.tencent.devops.worker.common.CommonEnv
import com.tencent.devops.worker.common.env.AgentEnv
import com.tencent.devops.worker.common.env.BuildEnv
import com.tencent.devops.worker.common.logger.LoggerService
import com.tencent.devops.worker.common.utils.BatScriptUtil
import com.tencent.devops.worker.common.utils.ShellUtil
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.max

@Suppress("ALL")
open class CodeccUtils {

    private lateinit var coverityStartFile: String
    private lateinit var toolsStartFile: String
    private lateinit var codeccStartFile: String

    private val logger = LoggerFactory.getLogger(CodeccUtils::class.java)

    init {
        LoggerService.addNormalLine("AgentEnv.isProd(): ${AgentEnv.isProd()}")
        // 第三方构建机
        if (BuildEnv.isThirdParty()) {
            LoggerService.addNormalLine("检测到这是非公共构建机")
        }
    }

    fun executeCommand(codeccExecuteConfig: CodeccExecuteConfig): String {
        val codeccWorkspace = getCodeccWorkspace(codeccExecuteConfig)
        try {
            initData(codeccExecuteConfig.scriptType, codeccWorkspace)
            return doRun(codeccExecuteConfig, codeccWorkspace)
        } finally {
            codeccWorkspace.deleteRecursively()
        }
    }

    private fun getCodeccWorkspace(codeccExecuteConfig: CodeccExecuteConfig): File {
        val buildId = codeccExecuteConfig.buildVariables.buildId
        val workspace = codeccExecuteConfig.workspace

        // Copy the nfs coverity file to workspace
        LoggerService.addNormalLine("get the workspace: ${workspace.canonicalPath}")
        LoggerService.addNormalLine(
            "get the workspace parent: ${workspace.parentFile?.canonicalPath} | '${File.separatorChar}'")
        LoggerService.addNormalLine("get the workspace parent string: ${workspace.parent}")

        val tempDir = File(workspace, ".temp")
        LoggerService.addNormalLine("get the workspace path parent: ${tempDir.canonicalPath}")
        val codeccWorkspace = File(tempDir, "codecc_coverity_$buildId")
        if (!codeccWorkspace.exists()) {
            codeccWorkspace.mkdirs()
        }
        return codeccWorkspace
    }

    private fun doRun(codeccExecuteConfig: CodeccExecuteConfig, codeccWorkspace: File): String {
        val scriptType = codeccExecuteConfig.scriptType
        return if (scriptType == BuildScriptType.BAT) {
            CodeccExecuteHelper.executeCodecc(
                codeccExecuteConfig = codeccExecuteConfig,
                covFun = this::doCoverityCommand,
                toolFun = this::doCodeccToolCommand
            )
        } else {
            codeccStartFile = CodeccScriptUtils().downloadScriptFile(codeccWorkspace).canonicalPath
            doCodeccSingleCommand(codeccExecuteConfig)
        }
    }

    private fun initData(scriptType: BuildScriptType, codeccWorkspace: File) {
        coverityStartFile = CodeccParamsHelper.getCovPyFile(scriptType, codeccWorkspace)
        toolsStartFile = CodeccParamsHelper.getToolPyFile(scriptType, codeccWorkspace)
        LoggerService.addNormalLine(
            "Get the coverity start file($coverityStartFile), " +
                "tools start file($toolsStartFile)"
        )
    }

    open fun coverityPreExecute(list: MutableList<String>) {
        list.add("export PATH=${getPython2Path(BuildScriptType.SHELL)}:\$PATH\n")
        list.add("export LANG=zh_CN.UTF-8\n")
        CommonEnv.getCommonEnv().forEach { (key, value) ->
            list.add("export $key=$value\n")
        }
        list.add("python -V\n")
        list.add("pwd\n")
    }

    private fun doCoverityCommand(codeccExecuteConfig: CodeccExecuteConfig): String {
        val workspace = codeccExecuteConfig.workspace
        val taskParams = codeccExecuteConfig.buildTask.params ?: mapOf()
        val script = taskParams["script"] ?: ""
        val scriptType = codeccExecuteConfig.scriptType
        val scriptFile = getScriptFile(codeccExecuteConfig, script)
        logger.info("Start to execute the script file for script($script)")

        val scanTools = if (codeccExecuteConfig.filterTools.isNotEmpty()) {
            codeccExecuteConfig.filterTools
        } else {
            codeccExecuteConfig.tools
        }
        val finalScanTools = scanTools.filter { it in COV_TOOLS }

        val list = mutableListOf<String>()
        coverityPreExecute(list)
        list.add("python")
        list.add(coverityStartFile)

        // 添加公共参数
        addCommonParams(list, codeccExecuteConfig)

        // 添加具体业务参数
        list.add("-DIS_SPEC_CONFIG=true")
        list.add("-DSCAN_TOOLS=${finalScanTools.joinToString(",").toLowerCase()}")
        list.add("-DCOVERITY_RESULT_PATH=${File(coverityStartFile).parent}")

        val buildCmd = when (CodeccParamsHelper.getProjectType(taskParams["languages"]!!)) {
            CoverityProjectType.UN_COMPILE -> {
                "--no-command --fs-capture-search ."
            }
            CoverityProjectType.COMPILE -> scriptFile.canonicalPath
            CoverityProjectType.COMBINE -> "--fs-capture-search . ${scriptFile.canonicalPath}"
        }

        // 工蜂开源扫描就不做限制
        val channelCode = codeccExecuteConfig.buildVariables.variables["pipeline.start.channel"] ?: ""
        val coreCount = if (channelCode == ChannelCode.GONGFENGSCAN.name) Runtime.getRuntime().availableProcessors()
        else max(Runtime.getRuntime().availableProcessors() / 2, 1) // 用一半的核

        list.add("-DPROJECT_BUILD_COMMAND=\"--parallel-translate=$coreCount $buildCmd\"")
        if (!BuildEnv.isThirdParty()) list.add("-DCOVERITY_HOME_BIN=${getCovToolPath(scriptType)}/bin")
        list.add("-DPROJECT_BUILD_PATH=${workspace.canonicalPath}")
        list.add("-DSYNC_TYPE=${taskParams["asynchronous"] != "true"}")
        if (!BuildEnv.isThirdParty() && scanTools.contains("KLOCWORK")) list.add(
            "-DKLOCWORK_HOME_BIN=${getKlocToolPath(
                scriptType
            )}"
        )
        if (taskParams.containsKey("goPath")) list.add("-DGO_PATH=${taskParams["goPath"]}")
        list.add("-DSUB_PATH=${getGoRootPath(scriptType)}:$GO_CI_LINT_PATH")

        // 开始执行
        val tag = if (scanTools.contains("COVERITY") && scanTools.contains("KLOCWORK")) "[cov&kw]"
        else if (scanTools.contains("KLOCWORK")) "[kw]"
        else "[cov]"
        printLog(list, tag)

        return executeScript(codeccExecuteConfig, list, "[cov] ")
    }

    open fun toolPreExecute(list: MutableList<String>) {
        list.add("export PATH=${getPython3Path(BuildScriptType.SHELL)}:\$PATH\n")
        list.add("export LANG=zh_CN.UTF-8\n")
        list.add("export PATH=/data/bkdevops/apps/codecc/go/bin:/data/bkdevops/apps/codecc/gometalinter/bin:\$PATH\n")

        CommonEnv.getCommonEnv().forEach { (key, value) ->
            list.add("export $key=$value\n")
        }
    }

    private fun doCodeccToolCommand(codeccExecuteConfig: CodeccExecuteConfig): String {
        val workspace = codeccExecuteConfig.workspace
        val scriptType = codeccExecuteConfig.scriptType

        val scanTools = if (codeccExecuteConfig.filterTools.isNotEmpty()) {
            codeccExecuteConfig.filterTools
        } else {
            codeccExecuteConfig.tools
        }
        if (scanTools.isEmpty()) return "scan tools is empty"

        val finalScanTools = scanTools.minus(COV_TOOLS)

        val list = mutableListOf<String>()
        toolPreExecute(list)
        list.add("python -V\n")
        list.add("pwd\n")
        list.add("python")
        list.add(toolsStartFile)

        // 添加公共参数
        addCommonParams(list, codeccExecuteConfig)

        // 添加具体业务参数
        list.add("-DSCAN_TOOLS=${finalScanTools.joinToString(",").toLowerCase()}")
        list.add("-DOFFLINE=true")
        list.add("-DDATA_ROOT_PATH=${File(toolsStartFile).parent}")
        list.add("-DSTREAM_CODE_PATH=${workspace.canonicalPath}")
        list.add("-DPY27_PATH=${getPython2Path(scriptType)}")
        list.add("-DPY35_PATH=${getPython3Path(scriptType)}")
        if (finalScanTools.contains("PYLINT")) {
            list.add("-DPY27_PYLINT_PATH=${getPyLint2Path(scriptType)}")
            list.add("-DPY35_PYLINT_PATH=${getPyLint3Path(scriptType)}")
        } else {
            // 两个参数是必填的
            // 把路径配置成其他可用路径就可以
            list.add("-DPY27_PYLINT_PATH=${workspace.canonicalPath}")
            list.add("-DPY35_PYLINT_PATH=${workspace.canonicalPath}")
        }
        var subPath = if (BuildEnv.isThirdParty()) {
            ""
        } else {
            "/usr/local/svn/bin:/usr/local/bin:/data/bkdevops/apps/coverity:"
        }
        subPath = "$subPath${getJdkPath(scriptType)}:${getNodePath(scriptType)}:" +
            "${getGoMetaLinterPath(scriptType)}:${getGoRootPath(scriptType)}:$STYLE_TOOL_PATH:$PHPCS_TOOL_PATH"
        list.add("-DSUB_PATH=$subPath")
        list.add("-DGOROOT=/data/bkdevops/apps/codecc/go")

        // 打印日志
        printLog(list, "[tools]")

        return executeScript(codeccExecuteConfig, list, "[tool] ")
    }

    open fun doPreCodeccSingleCommand(command: MutableList<String>) {
        command.add("export PATH=${getPython3Path(BuildScriptType.SHELL)}:\$PATH\n")
        command.add("export LANG=zh_CN.UTF-8\n")
        command.add(
            "export PATH=/data/bkdevops/apps/codecc/go/bin:/data/bkdevops/apps/codecc/gometalinter/bin:\$PATH\n")

        CommonEnv.getCommonEnv().forEach { (key, value) ->
            command.add("export $key=$value\n")
        }

        command.add("python -V\n")
        command.add("pwd\n")
    }

    fun doCodeccSingleCommand(codeccExecuteConfig: CodeccExecuteConfig): String {
        val command = mutableListOf<String>()
        doPreCodeccSingleCommand(command)

        val workspace = codeccExecuteConfig.workspace
        val taskParams = codeccExecuteConfig.buildTask.params ?: mapOf()
        val script = taskParams["script"] ?: ""
        val scriptType = codeccExecuteConfig.scriptType
        val scriptFile = getScriptFile(codeccExecuteConfig, script)
        logger.info("Start to execute the script file for script($script)")

        val scanTools = if (codeccExecuteConfig.filterTools.isNotEmpty()) {
            codeccExecuteConfig.filterTools
        } else {
            codeccExecuteConfig.tools
        }
        if (scanTools.isEmpty()) return "scan tools is empty"

        command.add("python")
        command.add(codeccStartFile)

        // 添加公共参数
        addCommonParams(command, codeccExecuteConfig)

        // 添加coverity/klockwork参数
        command.add("-DIS_SPEC_CONFIG=true")
        command.add("-DSCAN_TOOLS=${scanTools.joinToString(",").toLowerCase()}")
        command.add("-DCOVERITY_RESULT_PATH=${File(coverityStartFile).parent}")

        val buildCmd = when (CodeccParamsHelper.getProjectType(taskParams["languages"])) {
            CoverityProjectType.UN_COMPILE -> {
                "--no-command --fs-capture-search ."
            }
            CoverityProjectType.COMPILE -> scriptFile.canonicalPath
            CoverityProjectType.COMBINE -> "--fs-capture-search . ${scriptFile.canonicalPath}"
        }

        // 工蜂开源扫描就不做限制
        val channelCode = codeccExecuteConfig.buildVariables.variables["pipeline.start.channel"] ?: ""
        val coreCount = if (channelCode == ChannelCode.GONGFENGSCAN.name) Runtime.getRuntime().availableProcessors()
        else max(Runtime.getRuntime().availableProcessors() / 2, 1) // 用一半的核

        command.add("-DPROJECT_BUILD_COMMAND=\"--parallel-translate=$coreCount $buildCmd\"")
        if (!BuildEnv.isThirdParty()) command.add("-DCOVERITY_HOME_BIN=${getCovToolPath(scriptType)}/bin")
        command.add("-DPROJECT_BUILD_PATH=${workspace.canonicalPath}")
        command.add("-DSYNC_TYPE=${taskParams["asynchronous"] != "true"}")
        if (!BuildEnv.isThirdParty() && scanTools.contains("KLOCWORK")) command.add(
            "-DKLOCWORK_HOME_BIN=${getKlocToolPath(
                scriptType
            )}"
        )
        if (taskParams.containsKey("goPath")) command.add("-DGO_PATH=${taskParams["goPath"]}")

        // 多工具
        command.add("-DOFFLINE=true")
        command.add("-DDATA_ROOT_PATH=${File(toolsStartFile).parent}")
        command.add("-DSTREAM_CODE_PATH=${workspace.canonicalPath}")
        command.add("-DPY27_PATH=${getPython2Path(scriptType)}")
        command.add("-DPY35_PATH=${getPython3Path(scriptType)}")
        if (scanTools.contains("PYLINT")) {
            command.add("-DPY27_PYLINT_PATH=${getPyLint2Path(scriptType)}")
            command.add("-DPY35_PYLINT_PATH=${getPyLint3Path(scriptType)}")
        } else {
            // 两个参数是必填的
            // 把路径配置成其他可用路径就可以
            command.add("-DPY27_PYLINT_PATH=${workspace.canonicalPath}")
            command.add("-DPY35_PYLINT_PATH=${workspace.canonicalPath}")
        }
        var subPath = if (BuildEnv.isThirdParty()) {
            ""
        } else {
            "/usr/local/svn/bin:/data/bkdevops/apps/coverity"
        }
        subPath = "$subPath:${getJdkPath(scriptType)}:" +
            "${getNodePath(scriptType)}:${getGoMetaLinterPath(scriptType)}:${getGoRootPath(scriptType)}:" +
            "$STYLE_TOOL_PATH:$PHPCS_TOOL_PATH:${getGoRootPath(scriptType)}:$GO_CI_LINT_PATH"
        command.add("-DSUB_PATH=$subPath")
        command.add("-DGOROOT=/data/bkdevops/apps/codecc/go")

        printLog(command, "[codecc] ")

        return executeScript(
            codeccExecuteConfig = codeccExecuteConfig,
            list = command,
            prefix = "[codecc] "
        )
    }

    private fun printLog(list: List<String>, tag: String) {
        LoggerService.addNormalLine("$tag command content: ")
        list.forEach {
            if (!it.startsWith("-DSSH_PRIVATE_KEY") &&
                !it.startsWith("-DKEY_PASSWORD") &&
                !it.startsWith("-D$SVN_PASSWORD")
            ) {
                LoggerService.addNormalLine("$tag $it")
            }
        }
    }

    private fun takeBuildEnvs(coverConfig: CodeccExecuteConfig): List<com.tencent.devops.store.pojo.app.BuildEnv> {
        val turboTaskId = coverConfig.buildTask.buildVariable?.get(PIPELINE_TURBO_TASK_ID)
        return if (turboTaskId.isNullOrBlank()) {
            coverConfig.buildVariables.buildEnvs
        } else { // 设置编译加速路径
            coverConfig.buildVariables.buildEnvs.plus(
                com.tencent.devops.store.pojo.app.BuildEnv(
                    name = "turbo",
                    version = "1.0",
                    binPath = "",
                    env = mapOf()
                )
            )
        }
    }

    private fun getScriptFile(codeccExecuteConfig: CodeccExecuteConfig, script: String): File {
        return if (AgentEnv.getOS() == OSType.WINDOWS) {
            BatScriptUtil.getCommandFile(
                buildId = codeccExecuteConfig.buildTask.buildId,
                script = script,
                dir = codeccExecuteConfig.workspace,
                runtimeVariables = codeccExecuteConfig.buildVariables.variables
            )
        } else {
            ShellUtil.getCommandFile(
                buildId = codeccExecuteConfig.buildTask.buildId,
                script = script,
                dir = codeccExecuteConfig.workspace,
                buildEnvs = codeccExecuteConfig.buildVariables.buildEnvs,
                runtimeVariables = codeccExecuteConfig.buildVariables.variables
            )
        }
    }

    private fun executeScript(
        codeccExecuteConfig: CodeccExecuteConfig,
        list: MutableList<String>,
        prefix: String
    ): String {
        val variables =
            codeccExecuteConfig.buildVariables.variables.plus(codeccExecuteConfig.buildTask.buildVariable ?: mapOf())
        return if (AgentEnv.getOS() == OSType.WINDOWS) {
            BatScriptUtil.execute(
                buildId = codeccExecuteConfig.buildTask.buildId,
                script = list.joinToString(" "),
                dir = codeccExecuteConfig.workspace,
                runtimeVariables = variables,
                prefix = prefix)
        } else {
            ShellUtil.execute(
                buildId = codeccExecuteConfig.buildTask.buildId,
                script = list.joinToString(" "),
                dir = codeccExecuteConfig.workspace,
                buildEnvs = takeBuildEnvs(codeccExecuteConfig),
                runtimeVariables = variables,
                prefix = prefix
            )
        }
    }
}
