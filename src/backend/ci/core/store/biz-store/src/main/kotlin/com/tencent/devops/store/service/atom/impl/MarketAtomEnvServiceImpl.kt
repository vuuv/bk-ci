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
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.store.dao.atom.AtomDao
import com.tencent.devops.store.dao.atom.MarketAtomDao
import com.tencent.devops.store.dao.atom.MarketAtomEnvInfoDao
import com.tencent.devops.store.dao.common.StoreProjectRelDao
import com.tencent.devops.store.pojo.atom.AtomEnv
import com.tencent.devops.store.pojo.atom.AtomEnvRequest
import com.tencent.devops.store.pojo.atom.AtomPostInfo
import com.tencent.devops.store.pojo.atom.enums.AtomStatusEnum
import com.tencent.devops.store.pojo.atom.enums.JobTypeEnum
import com.tencent.devops.store.pojo.common.ATOM_POST_CONDITION
import com.tencent.devops.store.pojo.common.ATOM_POST_ENTRY_PARAM
import com.tencent.devops.store.pojo.common.ATOM_POST_FLAG
import com.tencent.devops.store.pojo.common.ATOM_POST_NORMAL_PROJECT_FLAG_KEY_PREFIX
import com.tencent.devops.store.pojo.common.KEY_CREATE_TIME
import com.tencent.devops.store.pojo.common.KEY_UPDATE_TIME
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import com.tencent.devops.store.service.atom.AtomService
import com.tencent.devops.store.service.atom.MarketAtomEnvService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils
import java.time.LocalDateTime

/**
 * 插件执行环境逻辑类
 *
 * since: 2019-01-04
 */
@Suppress("ALL")
@Service
class MarketAtomEnvServiceImpl @Autowired constructor(
    private val dslContext: DSLContext,
    private val marketAtomEnvInfoDao: MarketAtomEnvInfoDao,
    private val storeProjectRelDao: StoreProjectRelDao,
    private val atomDao: AtomDao,
    private val marketAtomDao: MarketAtomDao,
    private val atomService: AtomService,
    private val redisOperation: RedisOperation
) : MarketAtomEnvService {

    private val logger = LoggerFactory.getLogger(MarketAtomEnvServiceImpl::class.java)

    /**
     * 根据插件代码和版本号查看插件执行环境信息
     */
    override fun getMarketAtomEnvInfo(projectCode: String, atomCode: String, version: String): Result<AtomEnv?> {
        logger.info("getMarketAtomEnvInfo projectCode is :$projectCode,atomCode is :$atomCode,version is :$version")
        val atomResult = atomService.getPipelineAtom(projectCode, atomCode, version) // 判断插件查看的权限
        if (atomResult.isNotOk()) {
            return Result(atomResult.status, atomResult.message ?: "")
        }
        val atom = atomResult.data ?: return Result(data = null)
        val initProjectCode = storeProjectRelDao.getInitProjectCodeByStoreCode(
            dslContext = dslContext,
            storeCode = atomCode,
            storeType = StoreTypeEnum.ATOM.type.toByte()
        )
        logger.info("the initProjectCode is :$initProjectCode")
        var atomStatusList: List<Byte>? = null
        // 普通项目的查已发布、下架中和已下架（需要兼容那些还在使用已下架插件插件的项目）的插件
        val normalStatusList = listOf(
            AtomStatusEnum.RELEASED.status.toByte(),
            AtomStatusEnum.UNDERCARRIAGING.status.toByte(),
            AtomStatusEnum.UNDERCARRIAGED.status.toByte()
        )
        if (version.contains("*")) {
            atomStatusList = normalStatusList.toMutableList()
            val releaseCount = marketAtomDao.countReleaseAtomByCode(dslContext, atomCode, version)
            if (releaseCount > 0) {
                // 如果当前大版本内还有已发布的版本，则xx.latest只对应最新已发布的版本
                atomStatusList = mutableListOf(AtomStatusEnum.RELEASED.status.toByte())
            }
            val flag = storeProjectRelDao.isInitTestProjectCode(dslContext, atomCode, StoreTypeEnum.ATOM, projectCode)
            logger.info("the isInitTestProjectCode flag is :$flag")
            if (flag) {
                // 原生项目或者调试项目有权查处于测试中、审核中的插件
                atomStatusList.addAll(
                    listOf(
                        AtomStatusEnum.TESTING.status.toByte(),
                        AtomStatusEnum.AUDITING.status.toByte()
                    )
                )
            }
        }
        val atomDefaultFlag = atom.defaultFlag == true
        val atomEnvInfoRecord = marketAtomEnvInfoDao.getProjectMarketAtomEnvInfo(
            dslContext = dslContext,
            projectCode = projectCode,
            atomCode = atomCode,
            version = version,
            atomDefaultFlag = atomDefaultFlag,
            atomStatusList = atomStatusList
        )
        logger.info("the atomEnvInfoRecord is :$atomEnvInfoRecord")
        return Result(
            if (atomEnvInfoRecord == null) {
                null
            } else {
                val atomStatus = atomEnvInfoRecord["atomStatus"] as Byte
                val createTime = atomEnvInfoRecord[KEY_CREATE_TIME] as LocalDateTime
                val updateTime = atomEnvInfoRecord[KEY_UPDATE_TIME] as LocalDateTime
                val postEntryParam = atomEnvInfoRecord[ATOM_POST_ENTRY_PARAM] as? String
                val postCondition = atomEnvInfoRecord[ATOM_POST_CONDITION] as? String
                var postFlag = true
                val atomPostInfo = if (!StringUtils.isEmpty(postEntryParam) && !StringUtils.isEmpty(postEntryParam)) {
                    AtomPostInfo(
                        atomCode = atomCode,
                        version = version,
                        postEntryParam = postEntryParam!!,
                        postCondition = postCondition!!
                    )
                } else {
                    postFlag = false
                    null
                }
                val atomPostMap = mapOf(
                    ATOM_POST_FLAG to postFlag,
                    ATOM_POST_ENTRY_PARAM to postEntryParam,
                    ATOM_POST_CONDITION to postCondition
                )
                if (atomStatus in normalStatusList) {
                    val normalProjectPostKey = "$ATOM_POST_NORMAL_PROJECT_FLAG_KEY_PREFIX:$atomCode"
                    if (redisOperation.hget(normalProjectPostKey, version) == null) {
                        redisOperation.hset(
                            key = normalProjectPostKey,
                            hashKey = version,
                            values = JsonUtil.toJson(atomPostMap)
                        )
                    }
                }
                val jobType = atomEnvInfoRecord["jobType"] as? String
                AtomEnv(
                    atomId = atomEnvInfoRecord["atomId"] as String,
                    atomCode = atomEnvInfoRecord["atomCode"] as String,
                    atomName = atomEnvInfoRecord["atomName"] as String,
                    atomStatus = AtomStatusEnum.getAtomStatus(atomStatus.toInt()),
                    creator = atomEnvInfoRecord["creator"] as String,
                    version = atomEnvInfoRecord["version"] as String,
                    summary = atomEnvInfoRecord["summary"] as? String,
                    docsLink = atomEnvInfoRecord["docsLink"] as? String,
                    props = atomEnvInfoRecord["props"] as? String,
                    buildLessRunFlag = atomEnvInfoRecord["buildLessRunFlag"] as? Boolean,
                    createTime = createTime.timestampmilli(),
                    updateTime = updateTime.timestampmilli(),
                    projectCode = initProjectCode,
                    pkgPath = atomEnvInfoRecord["pkgPath"] as String,
                    language = atomEnvInfoRecord["language"] as? String,
                    minVersion = atomEnvInfoRecord["minVersion"] as? String,
                    target = atomEnvInfoRecord["target"] as String,
                    shaContent = atomEnvInfoRecord["shaContent"] as? String,
                    preCmd = atomEnvInfoRecord["preCmd"] as? String,
                    jobType = if (jobType == null) null else JobTypeEnum.valueOf(jobType),
                    atomPostInfo = atomPostInfo
                )
            }
        )
    }

    /**
     * 更新插件执行环境信息
     */
    override fun updateMarketAtomEnvInfo(
        projectCode: String,
        atomCode: String,
        version: String,
        atomEnvRequest: AtomEnvRequest
    ): Result<Boolean> {
        val atomResult = atomService.getPipelineAtom(projectCode, atomCode, version) // 判断插件查看的权限
        val status = atomResult.status
        if (0 != status) {
            return Result(atomResult.status, atomResult.message ?: "", false)
        }
        val atomRecord = atomDao.getPipelineAtom(dslContext, atomCode, version)
        return if (null != atomRecord) {
            marketAtomEnvInfoDao.updateMarketAtomEnvInfo(dslContext, atomRecord.id, atomEnvRequest)
            Result(true)
        } else {
            MessageCodeUtil.generateResponseDataObject(
                messageCode = CommonMessageCode.PARAMETER_IS_INVALID,
                params = arrayOf("$atomCode+$version"),
                data = false
            )
        }
    }
}
