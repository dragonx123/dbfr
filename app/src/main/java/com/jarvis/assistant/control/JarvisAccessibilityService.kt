package com.jarvis.assistant.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.util.AppLogger

private const val TAG = "Accessibility"

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
 * screen without any vision model at all: [readScreenText] walks the view
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

        private const val NOT_ENABLED =
            "Screen control isn't enabled. Ask the user to turn on Jarvis in " +
                "Settings > Diagnostics > Screen control, which opens Android's " +
                "Accessibility settings."

        /** Everything readable on the current screen, as indented text. */
        fun readScreenText(): String {
            val service = instance ?: return NOT_ENABLED
            val root = service.rootInActiveWindow ?: return "Nothing readable on screen right now."
            val builder = StringBuilder()
            collectText(root, builder, 0)
            val text = builder.toString().trim()
            return if (text.isBlank()) "The screen has no readable text." else text.take(4000)
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
