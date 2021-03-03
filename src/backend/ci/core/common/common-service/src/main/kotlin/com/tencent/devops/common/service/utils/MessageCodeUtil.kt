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

package com.tencent.devops.common.service.utils

import com.tencent.devops.common.api.constant.BCI_CODE_PREFIX
import com.tencent.devops.common.api.pojo.MessageCodeDetail
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.redis.RedisOperation
import org.slf4j.LoggerFactory
import java.text.MessageFormat

/**
 * code信息工具类
 * @since: 2018-11-10
 */
object MessageCodeUtil {
    private val logger = LoggerFactory.getLogger(MessageCodeUtil::class.java)

    /**
     * 生成请求响应对象
     * @param messageCode 状态码
     */
    fun <T> generateResponseDataObject(
        messageCode: String
    ): Result<T> = generateResponseDataObject(messageCode = messageCode, params = null, data = null)

    /**
     * 生成请求响应对象
     * @param messageCode 状态码
     * @param data 数据对象
     */
    fun <T> generateResponseDataObject(
        messageCode: String,
        data: T?
    ): Result<T> = generateResponseDataObject(messageCode = messageCode, params = null, data = data)

    /**
     * 生成请求响应对象
     * @param messageCode 状态码
     * @param params 替换状态码描述信息占位符的参数数组
     */
    fun <T> generateResponseDataObject(
        messageCode: String,
        params: Array<String>?
    ): Result<T> = generateResponseDataObject(messageCode, params, data = null)

    /**
     * 生成请求响应对象
     * @param messageCode 状态码
     * @param params 替换状态码描述信息占位符的参数数组
     * @param data 数据对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> generateResponseDataObject(
        messageCode: String,
        params: Array<String>?,
        data: T?,
        defaultMessage: String? = null
    ): Result<T> {
        val message = getCodeMessage(messageCode, params) ?: "[$messageCode]$defaultMessage"
        ?: "[$messageCode] System service busy, please try again later"
        return Result(messageCode.toInt(), message, data) // 生成Result对象`
    }

    /**
     * 获取code对应的中英文信息
     * @param messageCode code
     */
    fun getCodeLanMessage(messageCode: String, defaultMessage: String? = null): String {
        return getCodeMessage(messageCode, params = null) ?: defaultMessage ?: messageCode
    }

    /**
     * 获取code对应的中英文信息
     * @param messageCode code
     * @param params 替换描述信息占位符的参数数组
     */
    fun getCodeMessage(messageCode: String, params: Array<String>?): String? {
        var message: String? = null
        try {
            val redisOperation: RedisOperation = SpringContextUtil.getBean(RedisOperation::class.java)
            // 根据code从redis中获取该状态码对应的信息信息(BCI_CODE_PREFIX前缀保证code码在redis中的唯一性)
            val messageCodeDetailStr = redisOperation.get(BCI_CODE_PREFIX + messageCode)
                ?: return message
            val messageCodeDetail =
                JsonUtil.getObjectMapper().readValue(messageCodeDetailStr, MessageCodeDetail::class.java)
            message = getMessageByLocale(messageCodeDetail) // 根据字符集取出对应的状态码描述信息
            if (null != params) {
                val mf = MessageFormat(message)
                message = mf.format(params) // 根据参数动态替换状态码描述里的占位符
            }
        } catch (ignored: Exception) {
            logger.error("$messageCode get message error is :$ignored", ignored)
        }
        return message
    }

    private fun getMessageByLocale(messageCodeDetail: MessageCodeDetail): String {
        return when (CommonUtils.getBkLocale()) {
            "ZH_CN" -> messageCodeDetail.messageDetailZhCn // 简体中文描述
            "ZH_TW" -> messageCodeDetail.messageDetailZhTw ?: "" // 繁体中文描述
            else -> messageCodeDetail.messageDetailEn ?: "" // 英文描述
        }
    }

    fun getMessageByLocale(chinese: String, english: String?): String {
        return when (CommonUtils.getBkLocale()) {
            "ZH_CN" -> chinese // 简体中文描述
            "ZH_TW" -> chinese // 繁体中文描述
            else -> english ?: "" // 英文描述
        }
    }
}
