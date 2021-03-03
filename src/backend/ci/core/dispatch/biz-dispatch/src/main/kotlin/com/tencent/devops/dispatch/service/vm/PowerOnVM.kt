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

package com.tencent.devops.dispatch.service.vm

import com.tencent.devops.dispatch.service.ProjectSnapshotService
import com.vmware.vim25.VirtualMachinePowerState
import com.vmware.vim25.VirtualMachineSnapshotTree
import com.vmware.vim25.mo.Task
import com.vmware.vim25.mo.VirtualMachineSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component@Suppress("ALL")
class PowerOnVM(
    private val vmCache: VMCache,
    private val projectSnapshotService: ProjectSnapshotService
) {

    fun powerOn(vmId: Long): Boolean {
        val vm = vmCache.getVM(vmId)
        if (vm == null) {
            logger.error("ShutdownVM: Cannot find vm $vmId")
            return false
        }
        val result = vm.powerOnVM_Task(null).waitForTask()

        if (result == Task.SUCCESS) {
            return true
        }
        return false
    }

    fun powerOn(projectId: String, vmId: Long, snapshotKey: String): Boolean {
        val vm = vmCache.getVM(vmId)
        if (vm == null) {
            logger.error("PowerOn: Cannot find vm $vmId")
            return false
        }

        if (vm.runtime?.powerState != VirtualMachinePowerState.poweredOff) {
            logger.warn("The vm($vmId) is not power off - ${vm.runtime?.powerState}")
            return false
        }

        // 首先从快照树中间删除原来的快照
        try {
            val snapInfo = vm.snapshot
            if (snapInfo == null) {
                val result = vm.powerOnVM_Task(null).waitForTask()

                return result == Task.SUCCESS
            }

            val snapRootTree = snapInfo.getRootSnapshotList()

            // 先找匹配工程的快照
            val startupSnapshot = projectSnapshotService.getProjectStartupSnapshot(projectId)
            var snapshot = getMatchedSnapShot(projectId, snapRootTree, snapshotKey, null)

            if (snapshot == null) {
                // Trying to find the back up snap key
                snapshot = getMatchedSnapShot(projectId, snapRootTree, "$snapshotKey.bak", null)
                if (snapshot == null) {
                    snapshot = getMatchedSnapShot(projectId, snapRootTree, null, startupSnapshot)
                    if (snapshot == null && startupSnapshot != null) {
                        // Try to get the 'init' snapshot
                        snapshot = getMatchedSnapShot(projectId, snapRootTree, null, null)
                    }
                } else {
                    logger.info("Get the backup snapshot of $snapshotKey")
                }
            }

            if (snapshot == null) {
                logger.info("Can't find any snapshot")
                return vm.powerOnVM_Task(null).waitForTask() == Task.SUCCESS
            }

            var result = VirtualMachineSnapshot(vm.serverConnection,
                snapshot.snapshot).revertToSnapshot_Task(null).waitForTask()
            if (result != Task.SUCCESS) {
                return false
            }

            result = vm.powerOnVM_Task(null).waitForTask()

            logger.info("Revert the snapshot(${snapshot.name}) and start vm for snapshot($snapshotKey)")
            if (result == Task.SUCCESS) {
                return true
            }
            // Wait 10 seconds to check its status is power on
            for (i in 1..10) {
                logger.warn("Fail revert snapshot and the vm status ${vm.runtime.powerState}")
                if (vm.runtime.powerState == VirtualMachinePowerState.poweredOn) {
                    return true
                }
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            logger.warn("Fail to power on vm - $vmId", e)
        }
        return false
    }

    private fun getMatchedSnapShot(
        projectId: String,
        tree: Array<VirtualMachineSnapshotTree>,
        snapshotKey: String?,
        startupSnapshot: String?
    ): VirtualMachineSnapshotTree? {
        tree.forEach {
            val snapshotName = it.getName()
            val matched = when (snapshotKey) {
                null -> {
                    snapshotName == startupSnapshot ?: "init"
                }
                else -> snapshotName == "p_$snapshotKey"
            }
            if (matched) {
                logger.info("Get the match snapshot($snapshotName) for project($projectId)")
                return it
            } else {
                val children = it.getChildSnapshotList() ?: return@forEach
                if (children.isEmpty()) {
                    return@forEach
                }
                val snap = getMatchedSnapShot(projectId, children, snapshotKey, startupSnapshot)
                if (snap != null) {
                    return snap
                }
            }
        }
        return null
    }

    private fun getMatchedSnapShot(
        tree: Array<VirtualMachineSnapshotTree>,
        snapshotKey: String
    ): VirtualMachineSnapshotTree? {
        tree.forEach foreach@{
            val snapshotName = it.getName()
            val matched = snapshotName == "p_$snapshotKey"
            if (matched) {
                return it
            } else {
                val children = it.getChildSnapshotList() ?: return@foreach
                val snap = getMatchedSnapShot(children, snapshotKey)
                if (snap != null) {
                    return snap
                }
            }
        }
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PowerOnVM::class.java)
    }
}
