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

package com.tencent.devops.dispatch.dao

import com.tencent.devops.common.api.util.SecurityUtil
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.common.service.utils.ByteUtils
import com.tencent.devops.dispatch.pojo.VM
import com.tencent.devops.dispatch.pojo.VMCreate
import com.tencent.devops.model.dispatch.tables.TDispatchMachine
import com.tencent.devops.model.dispatch.tables.TDispatchVm
import com.tencent.devops.model.dispatch.tables.TDispatchVmType
import com.tencent.devops.model.dispatch.tables.records.TDispatchVmRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Result
import org.springframework.stereotype.Repository
import org.springframework.util.StringUtils
import java.time.LocalDateTime

@Repository@Suppress("ALL")
class VMDao {

    fun findAllVms(dslContext: DSLContext): Result<TDispatchVmRecord> {
        return dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                .fetch()
    }

    fun count(dslContext: DSLContext) = dslContext.selectCount()
                .from(TDispatchVm.T_DISPATCH_VM)
                .fetchOne(0, Int::class.java)

    fun findVms(
        dslContext: DSLContext,
        ip: String?,
        name: String?,
        typeId: Int?,
        os: String?,
        osVersion: String?,
        offset: Int?,
        limit: Int?
    ): Result<out Record>? {
        val a = TDispatchVm.T_DISPATCH_VM.`as`("a")
        val b = TDispatchMachine.T_DISPATCH_MACHINE.`as`("b")
        val c = TDispatchVmType.T_DISPATCH_VM_TYPE.`as`("c")
        val conditions = mutableListOf<Condition>()
        if (!StringUtils.isEmpty(ip)) conditions.add(a.VM_IP.like("%$ip%"))
        if (!StringUtils.isEmpty(name)) conditions.add(a.VM_NAME.like("%$name%"))
        if (!StringUtils.isEmpty(os)) conditions.add(a.VM_OS.like("%$os%"))
        if (!StringUtils.isEmpty(osVersion)) conditions.add(a.VM_OS_VERSION.like("%$osVersion%"))
        if (!StringUtils.isEmpty(typeId)) conditions.add(a.VM_TYPE_ID.eq(typeId))
        val baseStep = dslContext.select(
                a.VM_ID.`as`("id"),
                a.VM_MACHINE_ID.`as`("machineId"),
                b.MACHINE_NAME.`as`("machineName"),
                a.VM_TYPE_ID.`as`("typeId"),
                c.TYPE_NAME.`as`("typeName"),
                a.VM_IP.`as`("ip"),
                a.VM_NAME.`as`("name"),
                a.VM_OS.`as`("os"),
                a.VM_OS_VERSION.`as`("osVersion"),
                a.VM_CPU.`as`("cpu"),
                a.VM_MEMORY.`as`("memory"),
                a.VM_MAINTAIN.`as`("inMaintain"),
                a.VM_MANAGER_USERNAME.`as`("vmManagerUsername"),
                a.VM_MANAGER_PASSWD.`as`("vmManagerPassword"),
                a.VM_USERNAME.`as`("vmUsername"),
                a.VM_PASSWD.`as`("vmPassword"),
                a.VM_CREATED_TIME.`as`("createdTime"),
                a.VM_UPDATED_TIME.`as`("updatedTime")
        ).from(a).join(b).on(a.VM_MACHINE_ID.eq(b.MACHINE_ID)).join(c).on(a.VM_TYPE_ID.eq(c.TYPE_ID))
            .where(conditions).orderBy(a.VM_ID.desc())
        if (offset != null) {
            baseStep.offset(offset)
        }
        if (limit != null) {
            baseStep.limit(limit)
        }
        return baseStep.fetch()
    }

    fun findVmsWithOffset(dslContext: DSLContext, offset: Int) =
            dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                    .offset(offset)
                    .fetch()

    fun findVmsWithLimit(dslContext: DSLContext, offset: Int, limit: Int) =
            dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                    .limit(offset, limit)
                    .fetch()

    fun findVMById(dslContext: DSLContext, id: Long): TDispatchVmRecord? {
        return dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                .where(TDispatchVm.T_DISPATCH_VM.VM_ID.eq(id))
                .fetchAny()
    }

    fun findVMByIds(dslContext: DSLContext, ids: Set<Long>): Result<TDispatchVmRecord> {
        return dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                .where(TDispatchVm.T_DISPATCH_VM.VM_ID.`in`(ids))
                .fetch()
    }

    fun findVMByName(dslContext: DSLContext, name: String): TDispatchVmRecord? {
        return dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                .where(TDispatchVm.T_DISPATCH_VM.VM_NAME.eq(name))
                .fetchAny()
    }

    fun findVMByIp(dslContext: DSLContext, ip: String): TDispatchVmRecord? {
        return dslContext.selectFrom(TDispatchVm.T_DISPATCH_VM)
                .where(TDispatchVm.T_DISPATCH_VM.VM_IP.eq(ip))
                .fetchAny()
    }

    fun findVMsByMachine(dslContext: DSLContext, machineId: Int): Result<TDispatchVmRecord> {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.selectFrom(this)
                    .where(VM_MACHINE_ID.eq(machineId))
                    .fetch()
        }
    }

    fun maintainVM(dslContext: DSLContext, vmId: Long, maintain: Boolean) {
        with(TDispatchVm.T_DISPATCH_VM) {
            dslContext.update(this)
                    .set(VM_MAINTAIN, ByteUtils.bool2Byte(maintain))
                    .where(VM_ID.eq(vmId))
                    .execute()
        }
    }

    fun countByIp(dslContext: DSLContext, ip: String): Int {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.selectCount().from(this).where(VM_IP.eq(ip)).fetchOne(0, Int::class.java)
        }
    }

    fun countByName(dslContext: DSLContext, name: String): Int {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.selectCount().from(this).where(VM_NAME.eq(name)).fetchOne(0, Int::class.java)
        }
    }

    fun addVM(dslContext: DSLContext, vm: VMCreate) {
        with(TDispatchVm.T_DISPATCH_VM) {
            val now = LocalDateTime.now()
            dslContext.insertInto(this,
                    VM_IP,
                    VM_NAME,
                    VM_MACHINE_ID,
                    VM_OS,
                    VM_OS_VERSION,
                    VM_CPU,
                    VM_MEMORY,
                    VM_TYPE_ID,
                    VM_MAINTAIN,
                    VM_MANAGER_USERNAME,
                    VM_MANAGER_PASSWD,
                    VM_USERNAME,
                    VM_PASSWD,
                    VM_CREATED_TIME,
                    VM_UPDATED_TIME)
                    .values(
                        vm.ip,
                        vm.name,
                        vm.machineId,
                        vm.os,
                        vm.osVersion,
                        vm.cpu,
                        vm.memory,
                        vm.typeId,
                        ByteUtils.bool2Byte(vm.inMaintain),
                        vm.vmManagerUsername,
                        SecurityUtil.encrypt(vm.vmManagerPassword),
                        vm.vmUsername,
                        SecurityUtil.encrypt(vm.vmPassword),
                        now,
                        now
                    )
                    .execute()
        }
    }

    fun deleteVM(dslContext: DSLContext, id: Long): Int {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.deleteFrom(this)
                    .where(VM_ID.eq(id))
                    .execute()
        }
    }

    fun updateVM(dslContext: DSLContext, vm: VMCreate): Int {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.update(this)
                    .set(VM_MACHINE_ID, vm.machineId)
                    .set(VM_IP, vm.ip)
                    .set(VM_NAME, vm.name)
                    .set(VM_OS, vm.os)
                    .set(VM_CPU, vm.cpu)
                    .set(VM_MEMORY, vm.memory)
                    .set(VM_TYPE_ID, vm.typeId)
                    .set(VM_MAINTAIN, ByteUtils.bool2Byte(vm.inMaintain))
                    .set(VM_MANAGER_USERNAME, vm.vmManagerUsername)
                    .set(VM_MANAGER_PASSWD, SecurityUtil.encrypt(vm.vmManagerPassword))
                    .set(VM_USERNAME, vm.vmUsername)
                    .set(VM_PASSWD, SecurityUtil.encrypt(vm.vmPassword))
                    .set(VM_UPDATED_TIME, LocalDateTime.now())
                    .where(VM_ID.eq(vm.id))
                    .execute()
        }
    }

    fun deleteVMsByMachine(dslContext: DSLContext, machineId: Int): Int {
        with(TDispatchVm.T_DISPATCH_VM) {
            return dslContext.deleteFrom(this)
                    .where(VM_MACHINE_ID.eq(machineId))
                    .execute()
        }
    }

    fun parseVM(record: TDispatchVmRecord?): VM? {
        return if (record == null) {
            null
        } else VM(record.vmId,
                record.vmMachineId,
                record.vmTypeId,
                record.vmIp,
                record.vmName,
                record.vmOs,
                record.vmOsVersion,
                record.vmCpu,
                record.vmMemory,
                ByteUtils.byte2Bool(record.vmMaintain),
                record.vmManagerUsername,
                SecurityUtil.decrypt(record.vmManagerPasswd),
                record.vmUsername,
                SecurityUtil.decrypt(record.vmPasswd),
                record.vmCreatedTime.timestamp(),
                record.vmUpdatedTime.timestamp())
    }
}
