package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.util.Date

/**
 * Device-control actions Jarvis can take. Each method is exposed to the
 * on-device model as a callable tool via the `@Tool`/`@ToolParam`
 * annotations (see LiteRT-LM's Kotlin tool-calling API).
 *
 * Actions that could surprise or cost the user (sending a text, placing a
 * call) deliberately hand off to the matching system app with the fields
 * pre-filled (SMS composer, dialer) instead of sending/calling directly, so
 * a hallucinated tool call can never actually message or ring someone
 * without the user tapping "send"/"call" themselves.
 */
class JarvisTools(private val context: Context) : ToolSet {

    @Tool(description = "Open an app installed on the phone by its name, e.g. 'Spotify' or 'Camera'.")
    fun openApp(
        @ToolParam(description = "The app's display name as it appears on the home screen.") appName: String
    ): String {
        val pm = context.packageManager
        val launchables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        val match = launchables.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
        } ?: launchables.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        } ?: return "I couldn't find an app called \"$appName\"."

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            ?: return "\"$appName\" doesn't have anything to open."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return "Opening ${pm.getApplicationLabel(match)}."
    }

    @Tool(description = "Set a clock alarm for a specific time of day.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24-hour format, 0-23.") hour: Int,
        @ToolParam(description = "Minute, 0-59.") minute: Int,
        @ToolParam(description = "Optional label for the alarm.") label: String? = null,
    ): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Alarm set for %02d:%02d.".format(hour, minute)
        } else "This phone doesn't have a clock app that supports setting alarms."
    }

    @Tool(description = "Start a countdown timer.")
    fun setTimer(
        @ToolParam(description = "Timer length in seconds.") seconds: Int,
        @ToolParam(description = "Optional label for the timer.") label: String? = null,
    ): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Timer started for $seconds seconds."
        } else "This phone doesn't have a clock app that supports timers."
    }

    @Tool(description = "Search the web for a query and show the results in the browser.")
    fun searchWeb(
        @ToolParam(description = "What to search for.") query: String
    ): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Searching the web for \"$query\"."
    }

    @Tool(description = "Open the messaging app with a text message pre-filled to a phone number, ready for the user to send.")
    fun draftTextMessage(
        @ToolParam(description = "Recipient phone number.") phoneNumber: String,
        @ToolParam(description = "Message body to pre-fill.") message: String,
    ): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "I've drafted that message to $phoneNumber — tap send when you're ready."
        } else "This phone doesn't have a messaging app set up."
    }

    @Tool(description = "Open the phone dialer with a number pre-filled, ready for the user to call.")
    fun dialNumber(
        @ToolParam(description = "Phone number to dial.") phoneNumber: String
    ): String {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Dialer open for $phoneNumber — tap call to connect."
        } else "This phone doesn't have a dialer app available."
    }

    @Tool(description = "Turn the phone's flashlight/torch on or off.")
    fun setFlashlight(
        @ToolParam(description = "true to turn the flashlight on, false to turn it off.") on: Boolean
    ): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone doesn't have a flashlight."
            cameraManager.setTorchMode(cameraId, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't control the flashlight: ${e.message}"
        }
    }

    @Tool(description = "Open the calendar app to create a new event, with the title pre-filled. The user picks the exact date/time.")
    fun draftCalendarEvent(
        @ToolParam(description = "Event title.") title: String,
        @ToolParam(description = "Optional free-text description, e.g. 'tomorrow at 3pm' — shown to the user, not parsed.") whenDescription: String? = null,
    ): String {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            whenDescription?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Opening a new calendar event for \"$title\" — pick the date and time to save it."
        } else "This phone doesn't have a calendar app available."
    }

    @Tool(description = "Open turn-by-turn navigation to a destination.")
    fun navigateTo(
        @ToolParam(description = "Destination address or place name.") destination: String
    ): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Starting navigation to $destination."
        } else {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(webIntent)
            "Showing directions to $destination."
        }
    }

    @Tool(description = "Get the current date and time on the phone. Use this instead of guessing when the user asks what time or day it is.")
    fun getCurrentDateTime(): String {
        val now = Date()
        val timeFormat = DateFormat.getTimeFormat(context)
        val dateFormat = DateFormat.getLongDateFormat(context)
        return "${dateFormat.format(now)}, ${timeFormat.format(now)}"
    }

    @Tool(description = "Open the phone's system settings app.")
    fun openSettings(): String {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening settings."
    }
}
