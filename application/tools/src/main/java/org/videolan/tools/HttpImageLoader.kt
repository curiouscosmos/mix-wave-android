/*
 * ************************************************************************
 *  HttpImageLoader.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.SimpleArrayMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.max

object HttpImageLoader {

    private const val MAX_DISK_CACHE_SIZE = 50L * 1024L * 1024L
    private val currentJobs = SimpleArrayMap<String, CompletableDeferred<Bitmap?>>()
    private val jobsLocker = Mutex()
    @Volatile
    private var diskCacheDir: File? = null

    fun initialize(context: android.content.Context) {
        diskCacheDir = File(context.applicationContext.cacheDir, "images").apply { mkdirs() }
    }

    suspend fun downloadBitmap(imageUrl: String): Bitmap? {
        val icon = BitmapCache.getBitmapFromMemCache(imageUrl) ?: jobsLocker.withLock {
            currentJobs[imageUrl]?.takeIf { it.isActive }
        }?.await()
        if (icon != null) return icon
        return withContext(Dispatchers.IO) {
            val (pending, owner) = jobsLocker.withLock {
                currentJobs[imageUrl]?.let { it to false }
                    ?: CompletableDeferred<Bitmap?>().also { currentJobs.put(imageUrl, it) } to true
            }
            if (!owner) return@withContext pending.await()
            try {
                val diskFile = cacheFile(imageUrl)
                decodeBitmap(diskFile)?.also {
                    diskFile.setLastModified(System.currentTimeMillis())
                    BitmapCache.addBitmapToMemCache(imageUrl, it)
                } ?: fetchAndCache(imageUrl, diskFile).also { BitmapCache.addBitmapToMemCache(imageUrl, it) }
            } catch (ignored: IOException) {
                ErrorReporter.error("", ignored.message, ignored)
                null
            } catch (ignored: IllegalArgumentException) {
                ErrorReporter.error("", ignored.message, ignored)
                null
            } finally {
                pending.complete(BitmapCache.getBitmapFromMemCache(imageUrl))
                jobsLocker.withLock { currentJobs.remove(imageUrl) }
            }
        }
    }

    private fun fetchAndCache(imageUrl: String, destination: File): Bitmap? {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        return try {
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            val bitmap = decodeBitmap(temporary)
            if (bitmap != null) {
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trimDiskCache()
            } else {
                temporary.delete()
            }
            bitmap
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun decodeBitmap(file: File): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        val ratio = max(bounds.outHeight, bounds.outWidth).toFloat() / 150.dp.toFloat()
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (ratio > 1) floor(ratio).toInt() else 1
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun cacheFile(imageUrl: String): File {
        val directory = diskCacheDir ?: return File.createTempFile("image", ".cache")
        directory.mkdirs()
        return File(directory, "${imageUrl.sha256()}.img")
    }

    private fun trimDiskCache() {
        val directory = diskCacheDir ?: return
        val files = directory.listFiles { file -> file.extension == "img" }?.sortedBy { it.lastModified() }.orEmpty()
        var size = files.sumOf { it.length() }
        files.forEach { file ->
            if (size > MAX_DISK_CACHE_SIZE) {
                size -= file.length()
                file.delete()
            }
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
