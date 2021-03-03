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

package com.tencent.devops.dockerhost.docker

import com.github.dockerjava.api.model.Volume
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.dispatch.docker.pojo.DockerHostBuildInfo
import com.tencent.devops.dockerhost.docker.annotation.VolumeGenerator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException

object DockerVolumeLoader {

    private val logger: Logger = LoggerFactory.getLogger(DockerVolumeLoader::class.java)

    @Suppress("UNCHECKED_CAST")
    fun loadVolumes(dockerHostBuildInfo: DockerHostBuildInfo): List<Volume> {

        val volumeList = mutableListOf<Volume>()
        try {
            val generators: List<DockerVolumeGenerator> =
                SpringContextUtil.getBeansWithAnnotation(VolumeGenerator::class.java) as List<DockerVolumeGenerator>
            generators.forEach { generator ->
                volumeList.addAll(generator.generateVolumes(dockerHostBuildInfo))
            }
        } catch (notFound: BeansException) {
            logger.warn("[${dockerHostBuildInfo.buildId}]|not found volume generator| ex=$notFound")
        } catch (ignored: Throwable) {
            logger.error("[${dockerHostBuildInfo.buildId}]|Docker_loadVolume_fail|", ignored)
        }

        return volumeList
    }
}
