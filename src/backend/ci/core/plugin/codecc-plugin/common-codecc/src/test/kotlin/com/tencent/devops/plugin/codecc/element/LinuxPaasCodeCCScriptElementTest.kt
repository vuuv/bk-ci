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

package com.tencent.devops.plugin.codecc.element

import com.tencent.devops.common.pipeline.enums.BuildScriptType
import com.tencent.devops.common.pipeline.pojo.element.agent.LinuxPaasCodeCCScriptElement
import com.tencent.devops.plugin.codecc.pojo.coverity.ProjectLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxPaasCodeCCScriptElementTest {

    @Test
    fun cleanUp() {
        var element = LinuxPaasCodeCCScriptElement(
            name = "exe",
            id = "1",
            status = "1",
            script = "echo hello",
            scanType = "1",
            scriptType = BuildScriptType.SHELL,
            codeCCTaskCnName = "demo",
            codeCCTaskId = "123",
            asynchronous = false,
            path = "/tmp/codecc",
            languages = listOf(ProjectLanguage.JAVA)
        )
        element.cleanUp()
        assertEquals(element.codeCCTaskId, null)
        assertEquals(element.codeCCTaskName, null)

        element = LinuxPaasCodeCCScriptElement(
            scriptType = BuildScriptType.BAT,
            name = "exe", scanType = "1", codeCCTaskCnName = "demo", codeCCTaskId = "123",
            languages = listOf(ProjectLanguage.JAVA)
        )
        element.scriptType = BuildScriptType.PYTHON2
        element.scriptType = BuildScriptType.PYTHON3
        element.scriptType = BuildScriptType.POWER_SHELL
        element.cleanUp()
        assertEquals(element.codeCCTaskId, null)
        assertEquals(element.codeCCTaskName, null)
    }

    @Test
    fun getClassType() {
        val element = LinuxPaasCodeCCScriptElement(
            scriptType = BuildScriptType.POWER_SHELL,
            name = "exe", scanType = "1", codeCCTaskCnName = "demo", codeCCTaskId = "123",
            languages = listOf(ProjectLanguage.JAVA)
        )
        assertEquals(
            element.getClassType(),
            LinuxPaasCodeCCScriptElement.classType
        )
    }
}
