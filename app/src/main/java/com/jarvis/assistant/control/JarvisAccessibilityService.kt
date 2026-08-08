package com.jarvis.assistant.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.util.AppLogger

private const val TAG = "Accessibility"

/**
 * Outcome of trying to read the screen.
 *
 * Typed rather than a plain String on purpose: the previous version returned
 * its error message as ordinary text, a caller labelled it "[Screen
 * contents]" and handed it to the model, and the model — told that an error
 * message was the screen — invented a plausible screen instead. Failures
 * must be impossible to mistake for content.
 */
sealed interface ScreenRead {
    data class Text(val content: String) : ScreenRead

    /** The user hasn't turned the service on in Android's Accessibility settings. */
    data object NotEnabled : ScreenRead

    /** Service is on, but there's nothing readable (no window, or no text in it). */
    data object Empty : ScreenRead
}

/**
 * Gives Jarvis the ability to actually see and operate the screen, rather
 * than only launching apps via intents: read the text currently on screen,
 * press Back/Home/Recents, open notifications and quick settings, scroll,
 * and tap a control by its visible label.
 *
 * The user must enable this explicitly in Android's Accessibility settings
 * (the Settings screen deep-links there) — it can't be granted at runtime
 * like a normal permission, by design, since it's a powerful capability.
 *
 * Note this also gives on-device and text-only backends a way to "see" the
 * screen without any vision model at all: [readScreen] walks the view
 * hierarchy and returns real labels, which is often more reliable than
 * describing a screenshot.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility service connected")
    }

    // Nothing to do per-event: this service is driven on demand by the tool
    // calls below, not by reacting to a stream of UI events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        AppLogger.i(TAG, "Accessibility service disconnected")
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /**
         * Phrased for a model reading it as a *tool result*, and deliberately
         * explicit that not seeing the screen means not describing it — small
         * models otherwise treat an apology as permission to improvise.
         */
        private const val NOT_ENABLED =
            "FAILED: screen control is not enabled, so you cannot see the screen. " +
                "Tell the user to turn on \"Jarvis screen control\" in Settings > " +
                "Screen control. Do not describe or guess what is on their screen."

        /** Everything readable on the current screen. */
        fun readScreen(): ScreenRead {
            val service = instance ?: return ScreenRead.NotEnabled
            val root = service.rootInActiveWindow ?: return ScreenRead.Empty
            val builder = StringBuilder()
            collectText(root, builder, 0)
            val text = builder.toString().trim()
            return if (text.isBlank()) ScreenRead.Empty else ScreenRead.Text(text.take(4000))
        }

        /**
         * String form for the model-invoked `readScreen` tool, where an error
         * genuinely is the tool's result rather than something masquerading
         * as screen contents.
         */
        fun readScreenForTool(): String = when (val result = readScreen()) {
            is ScreenRead.Text -> result.content
            ScreenRead.NotEnabled -> NOT_ENABLED
            ScreenRead.Empty ->
                "FAILED: nothing readable on screen right now. Say so — do not guess."
        }

        private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
            if (node == null || depth > 25) return
            val label = node.text?.toString()?.trim().orEmpty().ifBlank {
                node.contentDescription?.toString()?.trim().orEmpty()
            }
            if (label.isNotBlank()) {
                out.append(" ".repeat(depth.coerceAtMost(8))).append(label)
                if (node.isClickable) out.append("  [tappable]")
                out.append('\n')
            }
            for (i in 0 until node.childCount) collectText(node.getChild(i), out, depth + 1)
        }

        /** Performs a global navigation action: back, home, recents, notifications, quick settings. */
        fun performGlobal(action: String): String {
            val service = instance ?: return NOT_ENABLED
            // Qualified rather than bare: these are Java statics on the
            // service class, and the companion object doesn't inherit them.
            val (code, label) = when (action.lowercase().trim()) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK to "Went back."
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME to "Went to the home screen."
                "recents", "recent apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Opened recent apps."
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Opened notifications."
                "quick settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Opened quick settings."
                // API 28+; on older devices performGlobalAction simply returns false.
                "lock", "lock screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locked the screen."
                else -> return "I don't know the screen action \"$action\"."
            }
            AppLogger.i(TAG, "performGlobal($action)")
            return if (service.performGlobalAction(code)) label else "That action didn't go through."
        }

        /** Finds a control whose visible label matches [label] and taps it. */
        fun tapByLabel(label: String): String {
            val service = instance ?: return NOT_ENABLED
            val root = service.rootInActiveWindow ?: return "Nothing on screen to tap."
            AppLogger.i(TAG, "tapByLabel(\"$label\")")
            val target = findClickable(root, label.trim().lowercase())
                ?: return "I couldn't find \"$label\" on screen."
            return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "Tapped \"$label\"."
            } else {
                "I found \"$label\" but couldn't tap it."
            }
        }

        /**
         * Prefers an exact label match over a partial one, and walks up to the
         * nearest clickable ancestor — the visible text is very often on a
         * non-clickable child of the actual button.
         */
        private fun findClickable(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
            val matches = mutableListOf<Pair<AccessibilityNodeInfo, Boolean>>() // node to isExact
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 25) return
                val text = (node.text?.toString() ?: node.contentDescription?.toString())
                    ?.trim()?.lowercase()
                if (!text.isNullOrBlank() && (text == wanted || text.contains(wanted))) {
                    clickableAncestor(node)?.let { matches += it to (text == wanted) }
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(root, 0)
            return matches.firstOrNull { it.second }?.first ?: matches.firstOrNull()?.first
        }

        private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var hops = 0
            while (current != null && hops < 6) {
                if (current.isClickable) return current
                current = current.parent
                hops++
            }
            return null
        }

        /** Scrolls the first scrollable container on screen. */
        fun scroll(direction: String): String {
            val service = instance ?: return NOT_ENABLED
            val root = service.rootInActiveWindow ?: return "Nothing on screen to scroll."
            val action = when (direction.lowercase().trim()) {
                "up", "back" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else -> return "Scroll direction should be up or down."
            }
            val scrollable = findScrollable(root) ?: return "Nothing on this screen scrolls."
            return if (scrollable.performAction(action)) "Scrolled $direction." else "Couldn't scroll."
        }

        private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
            if (node == null || depth > 25) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                findScrollable(node.getChild(i), depth + 1)?.let { return it }
            }
            return null
        }
    }
}
