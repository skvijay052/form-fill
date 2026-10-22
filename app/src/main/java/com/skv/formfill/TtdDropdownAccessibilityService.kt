package com.skv.formfill

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.abs

/**
 * Optional helper for TTD custom dropdowns.
 * Android restricts this service to the official TTD package; no screen data is logged,
 * stored, or sent over the network. It only attempts to select saved Gender/ID values.
 */
class TtdDropdownAccessibilityService : AccessibilityService() {
    private data class Target(
        val field: String,
        val node: AccessibilityNodeInfo,
        val top: Int,
        val left: Int,
        val value: String
    )

    private data class Task(val field: String, val ordinal: Int, val value: String)

    private val handler = Handler(Looper.getMainLooper())
    private val tasks = ArrayDeque<Task>()
    private var scanScheduled = false
    private var filling = false
    private var lastSignature = ""
    private var lastAttemptAt = 0L

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf(TTD_PACKAGE)
            notificationTimeout = 300
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TTD_PACKAGE || filling || scanScheduled) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        scanScheduled = true
        handler.postDelayed({
            scanScheduled = false
            tryStartFill()
        }, 500)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun tryStartFill() {
        if (filling) return
        val root = rootInActiveWindow ?: return
        val profiles = SecureProfileStore.loadPilgrims(this)
        val found = findTargets(root)
        if (profiles.isEmpty() || found.isEmpty()) return

        val planned = mutableListOf<Task>()
        listOf("gender", "idType").forEach { field ->
            found.filter { it.field == field }
                .sortedWith(compareBy<Target> { it.top }.thenBy { it.left })
                .forEachIndexed { ordinal, target ->
                    val value = profiles.getOrNull(ordinal)?.values()?.get(field).orEmpty()
                    if (value.isNotBlank() && isPlaceholder(target.value)) {
                        planned.add(Task(field, ordinal, value))
                    }
                }
        }
        if (planned.isEmpty()) return

        planned.sortBy { task ->
            found.firstOrNull { it.field == task.field && it.value.let(::isPlaceholder) }?.top ?: Int.MAX_VALUE
        }
        val signature = planned.joinToString("|") { "${it.field}:${it.ordinal}:${canonical(it.field, it.value)}" }
        val now = SystemClock.elapsedRealtime()
        if (signature == lastSignature && now - lastAttemptAt < 8000L) return
        lastSignature = signature
        lastAttemptAt = now
        tasks.clear()
        tasks.addAll(planned)
        filling = true
        processNextTask()
    }

    private fun processNextTask() {
        val task = tasks.removeFirstOrNull()
        if (task == null) {
            filling = false
            lastSignature = ""
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            stopWithMessage()
            return
        }
        val target = findTargets(root).filter { it.field == task.field }
            .sortedWith(compareBy<Target> { it.top }.thenBy { it.left })
            .getOrNull(task.ordinal)

        if (target == null || !isPlaceholder(target.value)) {
            handler.post { processNextTask() }
            return
        }
        if (!click(target.node)) {
            stopWithMessage()
            return
        }

        handler.postDelayed({
            val activeRoot = rootInActiveWindow
            val option = activeRoot?.let { findOption(it, task.field, task.value) }
            if (option == null || !click(option)) {
                stopWithMessage()
                return@postDelayed
            }
            handler.postDelayed({ processNextTask() }, 400)
        }, 500)
    }

    private fun stopWithMessage() {
        tasks.clear()
        filling = false
        Toast.makeText(this, "TTD dropdown could not be selected automatically. Please select it manually.", Toast.LENGTH_SHORT).show()
    }

    private fun findTargets(root: AccessibilityNodeInfo): List<Target> {
        val output = mutableListOf<Target>()
        fun walk(node: AccessibilityNodeInfo) {
            val field = identifyField(metadata(node))
            if (field != null) {
                val control = resolveControl(node)
                if (control != null && control.isVisibleToUser) {
                    val rect = Rect()
                    control.getBoundsInScreen(rect)
                    val target = Target(field, control, rect.top, rect.left, visibleValue(control))
                    if (output.none {
                        it.field == target.field && abs(it.top - target.top) < 12 && abs(it.left - target.left) < 18
                    }) output.add(target)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return output.sortedWith(compareBy<Target> { it.top }.thenBy { it.left })
    }

    private fun identifyField(raw: String): String? {
        val text = raw.lowercase()
        val compact = normalize(text)
        if (listOf("photo id number", "photo id no", "id proof number", "id number", "document number")
                .any { text.contains(it) } ||
            listOf("photoidnumber", "photoidno", "idproofnumber", "idnumber", "documentnumber")
                .any { compact.contains(it) }) return null

        if (Regex("(^|[^a-z])(gender|sex)([^a-z]|$)").containsMatchIn(text) ||
            compact == "gender" || compact.contains("genderfield")) return "gender"

        if (listOf("photo id proof", "photo-id-proof", "id proof", "identity proof", "document type", "identity document")
                .any { text.contains(it) } ||
            listOf("photoidproof", "idproof", "identityproof", "documenttype", "identitydocument")
                .any { compact.contains(it) }) return "idType"
        return null
    }

    private fun resolveControl(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isClickable(label)) return label
        val parent = label.parent ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child == label) continue
            firstClickable(child, 4)?.takeIf { it.isVisibleToUser }?.let { candidates.add(it) }
        }
        val labelBounds = Rect()
        label.getBoundsInScreen(labelBounds)
        val nearby = candidates.minByOrNull { candidate ->
            val bounds = Rect()
            candidate.getBoundsInScreen(bounds)
            abs(bounds.centerY() - labelBounds.centerY()) + abs(bounds.centerX() - labelBounds.centerX()) / 2
        }
        return nearby ?: parent.takeIf { isClickable(it) }
    }

    private fun firstClickable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (isClickable(node)) return node
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstClickable(child, depth - 1)?.let { return it }
        }
        return null
    }

    private fun isClickable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findOption(root: AccessibilityNodeInfo, field: String, desired: String): AccessibilityNodeInfo? {
        val wanted = canonical(field, desired)
        if (wanted.isBlank()) return null
        val candidates = mutableListOf<Pair<Long, AccessibilityNodeInfo>>()
        fun walk(node: AccessibilityNodeInfo) {
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (node.isVisibleToUser && labels.any { canonical(field, it) == wanted }) {
                val target = if (isClickable(node)) node else node.parent?.takeIf { isClickable(it) }
                if (target != null) {
                    val bounds = Rect()
                    target.getBoundsInScreen(bounds)
                    val area = bounds.width().toLong() * bounds.height().toLong()
                    candidates.add(area to target)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return candidates.minByOrNull { it.first }?.second
    }

    private fun visibleValue(node: AccessibilityNodeInfo): String {
        val pieces = mutableListOf<String>()
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 2) return
            current.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { pieces.add(it) }
            current.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { pieces.add(it) }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(node, 0)
        return pieces.distinct().joinToString(" ")
    }

    private fun isPlaceholder(value: String): Boolean {
        val text = normalize(value)
        if (text.isBlank()) return true
        return text in setOf(
            "select", "selectgender", "selectphotoidproof", "selectidproof",
            "choose", "chooseone", "pleasechoose", "pleaseselect", "notselected",
            "gender", "sex", "photoidproof", "idproof", "documenttype"
        ) || text.endsWith("selectgender") || text.endsWith("selectphotoidproof") ||
            text.endsWith("selectidproof") || text.endsWith("pleaseselect")
    }

    private fun canonical(field: String, value: String): String {
        val text = normalize(value)
        return when (field) {
            "gender" -> when {
                text == "m" || text == "male" || text == "man" -> "male"
                text == "f" || text == "female" || text == "woman" -> "female"
                text == "trans" || text.contains("transgender") -> "transgender"
                else -> text
            }
            "idType" -> when {
                text.contains("aadhaar") || text.contains("aadhar") -> "aadhaar"
                text.contains("passport") -> "passport"
                text.contains("voter") && text.contains("id") -> "voterid"
                text.contains("driving") && (text.contains("license") || text.contains("licence")) -> "drivinglicense"
                text.contains("ration") && text.contains("card") -> "rationcard"
                text.contains("pan") && text.contains("card") -> "pancard"
                else -> text
            }
            else -> text
        }
    }

    private fun metadata(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName,
        node.hintText?.toString(), node.tooltipText?.toString()
    ).joinToString(" ")

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    companion object {
        private const val TTD_PACKAGE = "com.ttdapp"
    }
}
