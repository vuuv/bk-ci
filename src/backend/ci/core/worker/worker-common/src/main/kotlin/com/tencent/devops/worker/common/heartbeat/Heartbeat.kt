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

package com.tencent.devops.worker.common.heartbeat

import com.tencent.devops.common.api.constant.HTTP_500
import com.tencent.devops.common.api.exception.RemoteServiceException
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.worker.common.logger.LoggerService
import com.tencent.devops.worker.common.service.ProcessService
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object Heartbeat {
    private const val EXIT_AFTER_FAILURE = 12 // Worker will exist after 12 fail heart
    private val logger = LoggerFactory.getLogger(Heartbeat::class.java)
    private val executor = Executors.newScheduledThreadPool(2)
    private var running = false

    @Synchronized
    fun start(jobTimeoutMills: Long = TimeUnit.MINUTES.toMillis(900)) {
        if (running) {
            logger.warn("The heartbeat task already started")
            return
        }
        var failCnt = 0
        executor.scheduleWithFixedDelay({
            if (running) {
                try {
                    logger.info("Start to do the heartbeat")
                    ProcessService.heartbeat()
                    failCnt = 0
                } catch (e: Exception) {
                    logger.warn("Fail to do the heartbeat", e)
                    if (e is RemoteServiceException) {
                        handleRemoteServiceException(e)
                    }
                    failCnt++
                    if (failCnt >= EXIT_AFTER_FAILURE) {
                        logger.error("Heartbeat has been failed for $failCnt times, worker exit")
                        exitProcess(-1)
                    }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)

        /*
            #2043 由worker-agent.jar 运行时进行自监控，当达到Job超时时，自行上报错误信息并结束构建
         */
        executor.scheduleWithFixedDelay({
            if (running) {
                LoggerService.addRedLine("Job timout: ${TimeUnit.MILLISECONDS.toMinutes(jobTimeoutMills)}min")
                ProcessService.timeout()
                exitProcess(99)
            }
        }, jobTimeoutMills, jobTimeoutMills, TimeUnit.MILLISECONDS)
        running = true
    }

    private fun handleRemoteServiceException(e: RemoteServiceException) {
        if (e.httpStatus == HTTP_500) {
            val responseContent = e.responseContent
            if (responseContent != null) {
                if (responseContent.startsWith("{") && responseContent.endsWith("}")) {
                    try {
                        val responseMap = JsonUtil.toMap(responseContent)
                        val errorCode = responseMap["errorCode"]
                        // 流水线构建结束则正常结束进程，不再重试
                        if (errorCode == 2101182) {
                            logger.error("build end, worker exit")
                            exitProcess(0)
                        }
                    } catch (t: Throwable) {
                        logger.warn("responseContent covert map fail", e)
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        executor.shutdown()
    }
}
