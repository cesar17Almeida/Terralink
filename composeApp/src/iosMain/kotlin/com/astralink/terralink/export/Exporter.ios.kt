package com.astralink.terralink.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
actual class Exporter {
    actual fun shareText(content: String, fileName: String, mimeType: String) {
        val fm = NSFileManager.defaultManager
        val cachesUrl = fm.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: return
        val exportsUrl = cachesUrl.URLByAppendingPathComponent("exports", true)
            ?: return
        fm.createDirectoryAtURL(exportsUrl, true, null, null)
        val fileUrl = exportsUrl.URLByAppendingPathComponent(fileName)
            ?: return

        val nsString = NSString.create(string = content)
        nsString.writeToURL(
            url = fileUrl,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )

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
