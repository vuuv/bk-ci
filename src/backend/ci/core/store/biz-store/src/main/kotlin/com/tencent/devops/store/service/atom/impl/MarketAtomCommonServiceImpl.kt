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

package com.tencent.devops.store.service.atom.impl

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.constant.INIT_VERSION
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.model.store.tables.records.TAtomRecord
import com.tencent.devops.store.constant.StoreMessageCode
import com.tencent.devops.store.dao.atom.AtomDao
import com.tencent.devops.store.dao.atom.MarketAtomDao
import com.tencent.devops.store.dao.atom.MarketAtomEnvInfoDao
import com.tencent.devops.store.pojo.atom.AtomEnvRequest
import com.tencent.devops.store.pojo.atom.AtomPostInfo
import com.tencent.devops.store.pojo.atom.GetAtomConfigResult
import com.tencent.devops.store.pojo.atom.enums.AtomStatusEnum
import com.tencent.devops.store.pojo.common.ATOM_POST
import com.tencent.devops.store.pojo.common.ATOM_POST_CONDITION
import com.tencent.devops.store.pojo.common.ATOM_POST_ENTRY_PARAM
import com.tencent.devops.store.pojo.common.ATOM_POST_FLAG
import com.tencent.devops.store.pojo.common.ATOM_POST_NORMAL_PROJECT_FLAG_KEY_PREFIX
import com.tencent.devops.store.pojo.common.ATOM_POST_VERSION_TEST_FLAG_KEY_PREFIX
import com.tencent.devops.store.pojo.common.TASK_JSON_NAME
import com.tencent.devops.store.pojo.common.enums.ReleaseTypeEnum
import com.tencent.devops.store.service.atom.MarketAtomCommonService
import com.tencent.devops.store.service.common.StoreCommonService
import com.tencent.devops.store.utils.VersionUtils
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils

@Suppress("ALL")
@Service
class MarketAtomCommonServiceImpl : MarketAtomCommonService {

    @Autowired
    private lateinit var dslContext: DSLContext

    @Autowired
    private lateinit var redisOperation: RedisOperation

    @Autowired
    private lateinit var atomDao: AtomDao

    @Autowired
    private lateinit var marketAtomDao: MarketAtomDao

    @Autowired
    private lateinit var marketAtomEnvInfoDao: MarketAtomEnvInfoDao

    @Autowired
    private lateinit var storeCommonService: StoreCommonService

    private val logger = LoggerFactory.getLogger(MarketAtomCommonServiceImpl::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun validateAtomVersion(
        atomRecord: TAtomRecord,
        releaseType: ReleaseTypeEnum,
        osList: ArrayList<String>,
        version: String
    ): Result<Boolean> {
        val dbVersion = atomRecord.version
        if (INIT_VERSION == dbVersion && releaseType == ReleaseTypeEnum.NEW) {
            return MessageCodeUtil.generateResponseDataObject(CommonMessageCode.PARAMETER_IS_EXIST, arrayOf(version))
        }
        val dbOsList = if (!StringUtils.isEmpty(atomRecord.os)) JsonUtil.getObjectMapper().readValue(
            atomRecord.os,
            List::class.java
        ) as List<String> else null
        // 支持的操作系统减少必须采用大版本升级方案
        val requireReleaseType =
            if (null != dbOsList && !osList.containsAll(dbOsList)) {
                ReleaseTypeEnum.INCOMPATIBILITY_UPGRADE // 最近的版本处于上架中止状态，重新升级版本号不变
            } else releaseType
        val cancelFlag = atomRecord.atomStatus == AtomStatusEnum.GROUNDING_SUSPENSION.status.toByte()
        val requireVersion =
            if (cancelFlag && releaseType == ReleaseTypeEnum.CANCEL_RE_RELEASE) {
                dbVersion
            } else storeCommonService.getRequireVersion(
                dbVersion = dbVersion,
                releaseType = requireReleaseType
            )
        if (version != requireVersion) {
            return MessageCodeUtil.generateResponseDataObject(
                StoreMessageCode.USER_ATOM_VERSION_IS_INVALID,
                arrayOf(version, requireVersion)
            )
        }
        if (dbVersion.isNotBlank()) {
            // 判断最近一个插件版本的状态，只有处于审核驳回、已发布、上架中止和已下架的状态才允许添加新的版本
            val atomFinalStatusList = listOf(
                AtomStatusEnum.AUDIT_REJECT.status.toByte(),
                AtomStatusEnum.RELEASED.status.toByte(),
                AtomStatusEnum.GROUNDING_SUSPENSION.status.toByte(),
                AtomStatusEnum.UNDERCARRIAGED.status.toByte()
            )
            if (!atomFinalStatusList.contains(atomRecord.atomStatus)) {
                return MessageCodeUtil.generateResponseDataObject(
                    StoreMessageCode.USER_ATOM_VERSION_IS_NOT_FINISH,
                    arrayOf(atomRecord.name, atomRecord.version)
                )
            }
        }
        return Result(true)
    }

    @Suppress("UNCHECKED_CAST")
    override fun parseBaseTaskJson(
        taskJsonStr: String,
        atomCode: String,
        version: String,
        userId: String
    ): GetAtomConfigResult {
        val taskDataMap = try {
            JsonUtil.toMap(taskJsonStr)
        } catch (e: Exception) {
            throw ErrorCodeException(
                errorCode = StoreMessageCode.USER_ATOM_CONF_INVALID,
                params = arrayOf(TASK_JSON_NAME)
            )
        }
        val taskAtomCode = taskDataMap["atomCode"] as? String
        if (atomCode != taskAtomCode) {
            // 如果用户输入的插件代码和其代码库配置文件的不一致，则抛出错误提示给用户
            return GetAtomConfigResult(
                StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_NOT_MATCH,
                arrayOf("atomCode"), null, null
            )
        }
        val executionInfoMap = taskDataMap["execution"] as? Map<String, Any>
        var atomPostInfo: AtomPostInfo? = null
        if (null != executionInfoMap) {
            val target = executionInfoMap["target"]
            if (StringUtils.isEmpty(target)) {
                // 执行入口为空则校验失败
                return GetAtomConfigResult(
                    StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_NULL,
                    arrayOf("target"), null, null
                )
            }
            val atomPostMap = executionInfoMap[ATOM_POST] as? Map<String, Any>
            if (null != atomPostMap) {
                try {
                    val postCondition = atomPostMap[ATOM_POST_CONDITION] as? String
                        ?: throw ErrorCodeException(
                            errorCode = StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_INVALID,
                            params = arrayOf(ATOM_POST_CONDITION)
                        )
                    val postEntryParam = atomPostMap[ATOM_POST_ENTRY_PARAM] as? String
                        ?: throw ErrorCodeException(
                            errorCode = StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_INVALID,
                            params = arrayOf(ATOM_POST_ENTRY_PARAM)
                        )
                    atomPostInfo = AtomPostInfo(
                        atomCode = atomCode,
                        version = version,
                        postEntryParam = postEntryParam,
                        postCondition = postCondition
                    )
                } catch (e: Exception) {
                    throw ErrorCodeException(
                        errorCode = StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_INVALID,
                        params = arrayOf(ATOM_POST_CONDITION)
                    )
                }
            }
        } else {
            // 抛出错误提示
            return GetAtomConfigResult(
                StoreMessageCode.USER_REPOSITORY_TASK_JSON_FIELD_IS_NULL,
                arrayOf("execution"), null, null
            )
        }

        val atomEnvRequest = AtomEnvRequest(
            userId = userId,
            pkgPath = "",
            language = executionInfoMap["language"] as? String,
            minVersion = executionInfoMap["minimumVersion"] as? String,
            target = executionInfoMap["target"] as String,
            shaContent = null,
            preCmd = JsonUtil.toJson(executionInfoMap["demands"] ?: ""),
            atomPostInfo = atomPostInfo
        )
        return GetAtomConfigResult("0", arrayOf(""), taskDataMap, atomEnvRequest)
    }

    override fun checkEditCondition(atomCode: String): Boolean {
        // 查询插件的最新记录
        val newestAtomRecord = atomDao.getNewestAtomByCode(dslContext, atomCode)
        logger.info("checkEditCondition newestAtomRecord is :$newestAtomRecord")
        if (null == newestAtomRecord) {
            throw ErrorCodeException(errorCode = CommonMessageCode.PARAMETER_IS_INVALID, params = arrayOf(atomCode))
        }
        val atomFinalStatusList = listOf(
            AtomStatusEnum.AUDIT_REJECT.status.toByte(),
            AtomStatusEnum.RELEASED.status.toByte(),
            AtomStatusEnum.GROUNDING_SUSPENSION.status.toByte(),
            AtomStatusEnum.UNDERCARRIAGED.status.toByte()
        )
        // 判断最近一个插件版本的状态，只有处于审核驳回、已发布、上架中止和已下架的状态才允许修改基本信息
        return atomFinalStatusList.contains(newestAtomRecord.atomStatus)
    }

    override fun getNormalUpgradeFlag(atomCode: String, status: Int): Boolean {
        val releaseTotalNum = marketAtomDao.countReleaseAtomByCode(dslContext, atomCode)
        val currentNum = if (status == AtomStatusEnum.RELEASED.status) 1 else 0
        return releaseTotalNum > currentNum
    }

    override fun handleAtomPostCache(atomId: String, atomCode: String, version: String, atomStatus: Byte) {
        if (atomStatus == AtomStatusEnum.RELEASED.status.toByte()) {
            val atomEnv = marketAtomEnvInfoDao.getMarketAtomEnvInfoByAtomId(dslContext, atomId)
                ?: throw ErrorCodeException(
                    errorCode = CommonMessageCode.PARAMETER_IS_INVALID,
                    params = arrayOf(atomId)
                )
            val postEntryParam = atomEnv.postEntryParam
            val postCondition = atomEnv.postCondition
            val postFlag = !StringUtils.isEmpty(postEntryParam) && !StringUtils.isEmpty(postEntryParam)
            val atomPostMap = mapOf(
                ATOM_POST_FLAG to postFlag,
                ATOM_POST_ENTRY_PARAM to postEntryParam,
                ATOM_POST_CONDITION to postCondition
            )
            redisOperation.hset(
                key = "$ATOM_POST_NORMAL_PROJECT_FLAG_KEY_PREFIX:$atomCode",
                hashKey = version,
                values = JsonUtil.toJson(atomPostMap)
            )
            // 更新xxx.latest这种版本号的post缓存信息
            redisOperation.hset(
                key = "$ATOM_POST_NORMAL_PROJECT_FLAG_KEY_PREFIX:$atomCode",
                hashKey = VersionUtils.convertLatestVersion(version),
                values = JsonUtil.toJson(atomPostMap)
            )
            // 更新插件当前大版本内是否有测试版本标识
            redisOperation.hset(
                key = "$ATOM_POST_VERSION_TEST_FLAG_KEY_PREFIX:$atomCode",
                hashKey = VersionUtils.convertLatestVersion(version),
                values = "false"
            )
        }
    }
}
