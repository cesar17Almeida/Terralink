package com.astralink.terralink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astralink.terralink.ble.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIViewController
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFirmwareFilePicker(onPick: (PickedFile) -> Unit): () -> Unit {
    // Keep the delegate alive while the picker is presented. Without this
    // strong reference iOS deallocates it and the delegate methods never fire.
    var keeper by remember { mutableStateOf<DocumentPickerCoordinator?>(null) }

    return remember(onPick) {
        {
            val host = topViewController()
            if (host != null) {
                val coordinator = DocumentPickerCoordinator(onPick)
                keeper = coordinator
                coordinator.present(host)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class DocumentPickerCoordinator(
    private val onPick: (PickedFile) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    fun present(host: UIViewController) {
        // "public.data" matches any binary file; the Pi-side validates sha256.
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
        )
        picker.delegate = this
        picker.allowsMultipleSelection = false
        picker.modalPresentationStyle = UIModalPresentationFormSheet
        host.presentViewController(picker, animated = true, completion = null)
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        // Picker hands us a security-scoped URL; failing to wrap reads with
        // start/stopAccessingSecurityScopedResource returns nil data on iOS 15+.
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data: NSData = NSData.dataWithContentsOfURL(url) ?: return
            val name = url.lastPathComponent ?: "firmware.bin"
            onPick(PickedFile(name = name, bytes = data.toByteArray()))
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        // No-op: the user closed the picker without choosing anything.
    }
}

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
