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

package com.tencent.devops.store.dao.template

import com.tencent.devops.model.store.tables.TTemplate
import com.tencent.devops.store.dao.common.AbstractStoreCommonDao
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Result
import org.springframework.stereotype.Repository

@Repository(value = "TEMPLATE_COMMON_DAO")
class TemplateCommonDao : AbstractStoreCommonDao() {

    override fun getStoreNameById(dslContext: DSLContext, storeId: String): String? {
        return with(TTemplate.T_TEMPLATE) {
            dslContext.select(TEMPLATE_NAME).from(this).where(ID.eq(storeId)).fetchOne(0, String::class.java)
        }
    }

    override fun getNewestStoreNameByCode(dslContext: DSLContext, storeCode: String): String? {
        return with(TTemplate.T_TEMPLATE) {
            dslContext.select(TEMPLATE_NAME).from(this)
                .where(TEMPLATE_CODE.eq(storeCode))
                .orderBy(CREATE_TIME.desc())
                .limit(1)
                .fetchOne(0, String::class.java)
        }
    }

    override fun getStoreCodeListByName(dslContext: DSLContext, storeName: String): Result<out Record>? {
        return with(TTemplate.T_TEMPLATE) {
            dslContext.select(TEMPLATE_CODE.`as`("storeCode")).from(this)
                .where(TEMPLATE_NAME.contains(storeName))
                .groupBy(TEMPLATE_CODE)
                .fetch()
        }
    }

    override fun getLatestStoreInfoListByCodes(
        dslContext: DSLContext,
        storeCodeList: List<String>
    ): Result<out Record>? {
        return with(TTemplate.T_TEMPLATE) {
            dslContext.select(
                TEMPLATE_CODE.`as`("storeCode"),
                VERSION.`as`("version")
            ).from(this)
                .where(TEMPLATE_CODE.`in`(storeCodeList))
                .and(LATEST_FLAG.eq(true))
                .fetch()
        }
    }

    override fun getStoreDevLanguages(dslContext: DSLContext, storeCode: String): List<String>? {
        return null
    }
}
