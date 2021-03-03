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

package com.tencent.devops.common.pipeline.enums

/**
 * [statusName] 状态中文名
 * [visible] 是否对用户可见
 */
enum class BuildStatus(val statusName: String, val visible: Boolean) {
    SUCCEED("成功", true), // 0 成功
    FAILED("失败", true), // 1 失败
    CANCELED("取消", true), // 2 取消
    RUNNING("运行中", true), // 3 运行中
    TERMINATE("终止", true), // 4 终止
    REVIEWING("审核中", true), // 5 审核中
    REVIEW_ABORT("审核驳回", true), // 6 审核驳回
    REVIEW_PROCESSED("审核通过", true), // 7 审核通过
    HEARTBEAT_TIMEOUT("心跳超时", true), // 8 心跳超时
    PREPARE_ENV("准备环境中", true), // 9 准备环境中
    UNEXEC("从未执行", false), // 10 从未执行（未使用）
    SKIP("跳过", true), // 11 跳过
    QUALITY_CHECK_FAIL("质量红线检查失败", true), // 12 质量红线检查失败
    QUEUE("排队", true), // 13 排队（新）
    LOOP_WAITING("轮循等待", true), // 14 轮循等待 --运行中状态（新）
    CALL_WAITING("等待回调", true), // 15 等待回调 --运行中状态（新）
    TRY_FINALLY("补偿任务", false), // 16 不可见的后台状态（新）为前面失败的任务做补偿的任务的原子状态，执行中如果前面有失败，则该种状态的任务才会执行。
    QUEUE_TIMEOUT("排队超时", true), // 17 排队超时
    EXEC_TIMEOUT("执行超时", true), // 18 执行超时
    QUEUE_CACHE("队列待处理", true), // 19 队列待处理，瞬间状态。只有在启动和取消过程中存在的中间状态
    RETRY("重试", true), // 20 重试
    PAUSE("暂停执行", true), // 21 暂停执行，等待事件
    STAGE_SUCCESS("阶段性完成", true), // 22 流水线阶段性完成,
    QUOTA_FAILED("配额不够失败", true), // 23 失败
    DEPENDENT_WAITING("依赖等待", true), // 24 依赖等待
    UNKNOWN("未知状态", false); // 99

    fun isFinish(): Boolean = isFailure() || isSuccess() || isCancel()

    fun isFailure(): Boolean = this == FAILED || isPassiveStop() || isTimeout() || this == QUOTA_FAILED

    fun isSuccess(): Boolean = this == SUCCEED || this == SKIP || this == REVIEW_PROCESSED

    fun isCancel(): Boolean = this == CANCELED

    fun isRunning(): Boolean =
        this == RUNNING ||
            this == LOOP_WAITING ||
            this == REVIEWING ||
            this == PREPARE_ENV ||
            this == CALL_WAITING ||
            this == PAUSE

    fun isReview(): Boolean = this == REVIEW_ABORT || this == REVIEW_PROCESSED

    fun isReadyToRun(): Boolean = this == QUEUE || this == QUEUE_CACHE || this == RETRY

    fun isPassiveStop(): Boolean = this == TERMINATE || this == REVIEW_ABORT || this == QUALITY_CHECK_FAIL

    fun isPause(): Boolean = this == PAUSE

    fun isTimeout(): Boolean = this == QUEUE_TIMEOUT || this == EXEC_TIMEOUT || this == HEARTBEAT_TIMEOUT

    companion object {

        fun parse(statusName: String?): BuildStatus {
            return try {
                if (statusName == null) UNKNOWN else valueOf(statusName)
            } catch (ignored: Exception) {
                UNKNOWN
            }
        }
        @Deprecated(replaceWith = ReplaceWith("isFailure"), message = "replace by isFailure")
        fun isFailure(status: BuildStatus) = status.isFailure()
        @Deprecated(replaceWith = ReplaceWith("isFinish"), message = "replace by isFinish")
        fun isFinish(status: BuildStatus) = status.isFinish()
        @Deprecated(replaceWith = ReplaceWith("isSuccess"), message = "replace by isSuccess")
        fun isSuccess(status: BuildStatus) = status.isSuccess()
        @Deprecated(replaceWith = ReplaceWith("isRunning"), message = "replace by isRunning")
        fun isRunning(status: BuildStatus) = status.isRunning()
        @Deprecated(replaceWith = ReplaceWith("isCancel"), message = "replace by isCancel")
        fun isCancel(status: BuildStatus) = status.isCancel()
        @Deprecated(replaceWith = ReplaceWith("isReadyToRun"), message = "replace by isReadyToRun")
        fun isReadyToRun(status: BuildStatus) = status.isReadyToRun()
    }
}
