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

package com.tencent.devops.sign.utils

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

@Suppress("ALL")
object IpaFileUtil {
    private const val bufferSize = 8 * 1024

    /*
    * 复制流到目标文件，并计算md5
    * */
    fun copyInputStreamToFile(
        inputStream: InputStream,
        target: File
    ): String? {
        var outputStream: OutputStream? = null
        try {
            // 如果文件存在，则删除
            if (target.exists()) {
                target.delete()
            }
            outputStream = target.outputStream()
            val md5 = MessageDigest.getInstance("MD5")
            var bytesCopied: Long = 0
            val buffer = ByteArray(bufferSize)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                outputStream.write(buffer, 0, bytes)
                md5.update(buffer, 0, bytes)
                bytesCopied += bytes
                bytes = inputStream.read(buffer)
            }
            return Hex.encodeHexString(md5.digest())
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /*
    *  创建目录
    * */
    fun mkdirs(dir: File): Boolean {
        if (!dir.exists()) {
            dir.mkdirs()
            return true
        }
        if (dir.exists() && !dir.isDirectory) {
            dir.deleteOnExit()
            dir.mkdirs()
            return true
        }
        if (dir.exists() && dir.isDirectory && !dir.canWrite()) {
            dir.deleteOnExit()
            dir.mkdirs()
            return true
        }
        return true
    }

    /**
     * 获取文件MD5值
     * @param file 文件对象
     * @return 文件MD5值
     */
    fun getMD5(file: File): String {
        if (!file.exists()) return ""
        return file.inputStream().use {
            DigestUtils.md5Hex(it)
        }
    }
}
