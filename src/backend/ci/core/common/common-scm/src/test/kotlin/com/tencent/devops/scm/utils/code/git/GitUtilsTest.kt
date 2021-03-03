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

package com.tencent.devops.scm.utils.code.git

import org.junit.Assert.assertEquals
import org.junit.Test

class GitUtilsTest {

    private val domain = "github.com"
    private val repoName = "Tencent/bk-ci"

    @Test
    fun getDomainAndRepoName4Http() {
        var domainAndRepoName = GitUtils.getDomainAndRepoName("https://github.com/Tencent/bk-ci.git")
        assertEquals(domain, domainAndRepoName.first)
        assertEquals(repoName, domainAndRepoName.second)
        domainAndRepoName = GitUtils.getDomainAndRepoName("http://github.com/Tencent/bk-ci.git")
        assertEquals(domain, domainAndRepoName.first)
        assertEquals(repoName, domainAndRepoName.second)
    }

    @Test
    fun getGitApiUrl() {
        val apiUrl = "http://aaa.com/api/v3"
        val repoApiUrl = "http://github.com/api/v3"
        var actual = GitUtils.getGitApiUrl(apiUrl, "http://github.com/Tencent/bk-ci.git")
        assertEquals(repoApiUrl, actual)
        actual = GitUtils.getGitApiUrl(apiUrl, "http://aaa.com/Tencent/bk-ci.git")
        assertEquals(apiUrl, actual)
        val errorApiUrl = "api/v3"
        actual = GitUtils.getGitApiUrl(errorApiUrl, "http://aaa.com/Tencent/bk-ci.git")
        assertEquals(apiUrl, actual)
    }

    @Test
    fun getDomainAndRepoName4SSH() {
        val domainAndRepoName = GitUtils.getDomainAndRepoName("git@github.com:Tencent/bk-ci.git")
        assertEquals(domain, domainAndRepoName.first)
        assertEquals(repoName, domainAndRepoName.second)
    }

    @Test
    fun getProjectName() {
        var projectName = GitUtils.getProjectName("git@github.com:Tencent/bk-ci.git")
        assertEquals(repoName, projectName)
        projectName = GitUtils.getProjectName("git@git.xxx.com:Tencent/bk-ci.git")
        assertEquals(repoName, projectName)
        projectName = GitUtils.getProjectName("https://github.com/Tencent/bk-ci.git")
        assertEquals(repoName, projectName)
        projectName = GitUtils.getProjectName("http://github.com/Tencent/bk-ci.git")
        assertEquals(repoName, projectName)
    }
}
