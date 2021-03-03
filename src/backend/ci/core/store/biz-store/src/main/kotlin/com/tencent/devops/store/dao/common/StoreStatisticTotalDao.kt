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

package com.tencent.devops.store.dao.common

import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.model.store.tables.TStoreStatisticsTotal
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Suppress("ALL")
@Repository
class StoreStatisticTotalDao {

    fun updateStatisticData(
        dslContext: DSLContext,
        storeCode: String,
        storeType: Byte,
        downloads: Int,
        comments: Int,
        score: Int,
        scoreAverage: Double
    ) {
        with(TStoreStatisticsTotal.T_STORE_STATISTICS_TOTAL) {
            val record =
                dslContext.selectFrom(this).where(STORE_CODE.eq(storeCode)).and(STORE_TYPE.eq(storeType)).fetchOne()
            if (null == record) {
                dslContext.insertInto(this).columns(
                    ID,
                    STORE_CODE,
                    STORE_TYPE,
                    DOWNLOADS,
                    COMMITS,
                    SCORE,
                    SCORE_AVERAGE
                ).values(
                    UUIDUtil.generate(),
                    storeCode,
                    storeType,
                    downloads,
                    comments,
                    score,
                    scoreAverage.toBigDecimal()
                ).execute()
            } else {
                dslContext.update(this)
                    .set(DOWNLOADS, downloads)
                    .set(COMMITS, comments)
                    .set(SCORE, score)
                    .set(SCORE_AVERAGE, scoreAverage.toBigDecimal())
                    .set(UPDATE_TIME, LocalDateTime.now())
                    .where(STORE_CODE.eq(storeCode))
                    .and(STORE_TYPE.eq(storeType))
                    .execute()
            }
        }
    }

    fun deleteStoreStatisticTotal(dslContext: DSLContext, storeCode: String, storeType: Byte) {
        with(TStoreStatisticsTotal.T_STORE_STATISTICS_TOTAL) {
            dslContext.deleteFrom(this)
                .where(STORE_CODE.eq(storeCode))
                .and(STORE_TYPE.eq(storeType))
                .execute()
        }
    }
}
