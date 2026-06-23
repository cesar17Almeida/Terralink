package com.astralink.terralink.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class Exporter {
    actual fun shareText(content: String, fileName: String, mimeType: String) {
        val fileUrl = exportFileUrl(fileName) ?: return
        NSString.create(string = content).writeToURL(
            url = fileUrl, atomically = true,
            encoding = NSUTF8StringEncoding, error = null,
        )
        present(fileUrl)
    }

    actual fun shareBytes(content: ByteArray, fileName: String, mimeType: String) {
        val fileUrl = exportFileUrl(fileName) ?: return
        content.toNSData().writeToURL(fileUrl, true)
        present(fileUrl)
    }

    private fun exportFileUrl(fileName: String): NSURL? {
        val fm = NSFileManager.defaultManager
        val cachesUrl = fm.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: return null
        val exportsUrl = cachesUrl.URLByAppendingPathComponent("exports", true) ?: return null
        fm.createDirectoryAtURL(exportsUrl, true, null, null)
        return exportsUrl.URLByAppendingPathComponent(fileName)
    }

    private fun present(fileUrl: NSURL) {
        val host = topViewController() ?: return
        val activityVc = UIActivityViewController(
            activityItems = listOf(fileUrl),
            applicationActivities = null,
        )
        // iPhone-only target: present modally. iPad would need
        // popoverPresentationController anchoring; revisit if we ship an
        // iPad-capable build.
        host.presentViewController(activityVc, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), this.size.toULong())
    }
}

@Composable
actual fun rememberExporter(): Exporter = remember { Exporter() }

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    val window = UIApplication.sharedApplication.keyWindow ?: return null
    var vc: UIViewController? = window.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
