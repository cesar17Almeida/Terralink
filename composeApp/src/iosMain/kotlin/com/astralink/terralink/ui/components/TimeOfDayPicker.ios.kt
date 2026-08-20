package com.astralink.terralink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.UIKit.UIApplication
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonItemStyle
import platform.UIKit.UIColor
import platform.UIKit.UIDatePicker
import platform.UIKit.UIDatePickerMode
import platform.UIKit.UIDatePickerStyle
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UINavigationController
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UIViewController
import platform.UIKit.navigationItem
import platform.UIKit.sheetPresentationController
import platform.UIKit.systemBackgroundColor
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberTimeOfDayPicker(onPick: (hour: Int, minute: Int) -> Unit): (Int, Int) -> Unit {
    // Hold the coordinator while the sheet is up: it is the bar buttons' target, and
    // UIKit keeps only a weak reference to it (same trap as the document picker).
    var keeper by remember { mutableStateOf<TimePickerCoordinator?>(null) }

    return remember(onPick) {
        { hour, minute ->
            val host = topViewController()
            if (host != null) {
                val coordinator = TimePickerCoordinator(
                    onPick = onPick,
                    onDismissed = { keeper = null },
                )
                keeper = coordinator
                coordinator.present(host, hour, minute)
            }
        }
    }
}

/**
 * A UIDatePicker in `time` mode on a medium sheet, with the system Cancel/Done bar
 * buttons. The picker runs in UTC on purpose: only the hour:minute label matters
 * here (the station applies its own utc_offset_min), so pinning the zone keeps the
 * value the user sees identical to the one we read back, with no DST edge case.
 */
@OptIn(ExperimentalForeignApi::class)
private class TimePickerCoordinator(
    private val onPick: (Int, Int) -> Unit,
    private val onDismissed: () -> Unit,
) : NSObject() {

    private val picker = UIDatePicker()
    private var presented: UIViewController? = null

    private val formatter = NSDateFormatter().apply {
        dateFormat = "HH:mm"
        locale = NSLocale("en_US_POSIX")            // fixed parsing, never the user's calendar
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    }

    fun present(host: UIViewController, hour: Int, minute: Int) {
        picker.datePickerMode = UIDatePickerMode.UIDatePickerModeTime
        picker.preferredDatePickerStyle = UIDatePickerStyle.UIDatePickerStyleWheels
        picker.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        picker.minuteInterval = 1
        val start = formatter.dateFromString(fmt(hour, minute))
        if (start != null) picker.setDate(start)
        picker.translatesAutoresizingMaskIntoConstraints = false

        val content = UIViewController(nibName = null, bundle = null)
        content.view.backgroundColor = UIColor.systemBackgroundColor
        content.title = "Hora de predicción"
        content.view.addSubview(picker)
        picker.centerXAnchor.constraintEqualToAnchor(content.view.centerXAnchor).setActive(true)
        picker.centerYAnchor.constraintEqualToAnchor(content.view.centerYAnchor).setActive(true)

        content.navigationItem.leftBarButtonItem = UIBarButtonItem(
            title = "Cancelar",
            style = UIBarButtonItemStyle.UIBarButtonItemStylePlain,
            target = this,
            action = NSSelectorFromString("cancelTapped"),
        )
        content.navigationItem.rightBarButtonItem = UIBarButtonItem(
            title = "Listo",
            style = UIBarButtonItemStyle.UIBarButtonItemStyleDone,
            target = this,
            action = NSSelectorFromString("doneTapped"),
        )

        val nav = UINavigationController(rootViewController = content)
        nav.modalPresentationStyle = UIModalPresentationPageSheet
        nav.sheetPresentationController?.detents =
            listOf(UISheetPresentationControllerDetent.mediumDetent())
        presented = nav
        host.presentViewController(nav, animated = true, completion = null)
    }

    @ObjCAction
    fun doneTapped() {
        val text = formatter.stringFromDate(picker.date)
        val hour = text.substringBefore(':').toIntOrNull()
        val minute = text.substringAfter(':').toIntOrNull()
        if (hour != null && minute != null) onPick(hour, minute)
        dismiss()
    }

    @ObjCAction
    fun cancelTapped() = dismiss()

    private fun dismiss() {
        presented?.dismissViewControllerAnimated(true, completion = null)
        presented = null
        onDismissed()
    }

    private fun fmt(hour: Int, minute: Int): String =
        hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
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
