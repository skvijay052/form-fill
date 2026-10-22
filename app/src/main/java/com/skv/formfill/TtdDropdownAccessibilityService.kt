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
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Optional fallback for TTD's custom Gender and Photo ID Proof controls.
 * It inspects and acts only while com.ttdapp is the foreground event source.
 * No screen text is logged or sent off-device.
 */
class TtdDropdownAccessibilityService : AccessibilityService() {
    private data class Target(
        val field: String,
        val node: AccessibilityNodeInfo,
        val top: Int,
        val left: Int,
        val currentValue: String
    )

    private data class Task(
        val field: String,
        val ordinal: Int,
        val value: String,
        val top: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tasks = ArrayDeque<Task>()
    private var scanScheduled = false
    private var filling = false
    private var lastAttemptSignature = ""
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
            notificationTimeout = 250
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
        }, 450)
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
        if (profiles.isEmpty()) return

        val found = findDropdownTargets(root)
        if (found.isEmpty()) return

        val fieldOrder = listOf("gender", "idType")
        val planned = mutableListOf<Task>()
        fieldOrder.forEach { field ->
            found.filter { it.field == field }.sortedWith(compareBy<Target> { it.top }.thenBy { it.left })
                .forEachIndexed { ordinal, target ->
                    val value = profiles.getOrNull(ordinal)?.values()?.get(field).orEmpty()
                    if (value.isNotBlank() && isPlaceholder(target.currentValue)) {
                        planned.add(Task(field, ordinal, value, target.top))
                    }
                }
        }
        if (planned.isEmpty()) return

        planned.sortBy { it.top }
        val signature = planned.joinToString("|") { it.field + ":" + it.ordinal + ":" + canonical(it.field, it.value) }
        val now = SystemClock.elapsedRealtime()
        if (signature == lastAttemptSignature && now - lastAttemptAt < 8000L) return
        lastAttemptSignature = signature
        lastAttemptAt = now
        tasks.clear()
        tasks.addAll(planned)
        filling = true
        processNextTask()
    }

    private fun processNextTask() {
        val task = tasks.pollFirst()
        if (task == null) {
            filling = false
            lastAttemptSignature = ""
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            stopWithMessage()
            return
        }

        val target = findDropdownTargets(root).filter { it.field == task.field }
            .sortedWith(compareBy<Target> { it.top }.thenBy { it.left })
            .getOrNull(task.ordinal)

        if (target == null || !isPlaceholder(target.currentValue)) {
            handler.post { processNextTask() }
            return
        }

        if (!performClick(target.node)) {
            stopWithMessage()
            return
        }

        handler.postDelayed({
            val activeRoot = rootInActiveWindow
            val option = activeRoot?.let { findOption(it, task.field, task.value) }
            if (option == null || !performClick(option)) {
                stopWithMessage()
                return@postDelayed
            }
            handler.postDelayed({ processNextTask() }, 350)
        }, 450)
    }

    private fun stopWithMessage() {
        tasks.clear()
        filling = false
        Toast.makeText(
            this,
            "TTD dropdown not accessible here. Please select it manually.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun findDropdownTargets(root: AccessibilityNodeInfo): List<Target> {
        val output = mutableListOf<Target>()
        fun walk(node: AccessibilityNodeInfo) {
            val field = fieldFromMetadata(metadata(node))
            if (field != null) {
                val control = resolveControl(node)
                if (control != null) {
                    val rect = Rect()
                    control.getBoundsInScreen(rect)
                    val target = Target(field, control, rect.top, rect.left, visibleValue(control))
                    val duplicate = output.any {
                        it.field == target.field && abs(it.top - target.top) < 12 &&
                            abs(it.left - target.left) < 18
                    }
                    if (!duplicate && control.isVisibleToUser) output.add(target)
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

    private fun fieldFromMetadata(raw: String): String? {
        val text = raw.lowercase()
        val compact = normalize(text)
        if (listOf("photo id number", "photo id no", "id proof number", "id number", "document number")
                .any { text.contains(it) } ||
            listOf("photoidnumber", "photoidno", "idproofnumber", "idnumber", "documentnumber")
                .any { compact.contains(it) }) return null

        if (Regex("(^|[^a-z])(gender|sex)([^a-z]|$)").containsMatchIn(text) ||
            compact.contains("genderfield") || compact == "gender") return "gender"

        val isIdProof = listOf("photo id proof", "photo-id-proof", "id proof", "identity proof",
            "document type", "identity document").any { text.contains(it) } ||
            listOf("photoidproof", "idproof", "identityproof", "documenttype", "identitydocument")
                .any { compact.contains(it) }
        if (isIdProof) return "idType"
        return null
    }

    private fun resolveControl(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (canClick(label)) return label
        val parent = label.parent ?: return null
        val siblings = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child == label) continue
            val control = firstClickable(child, 3)
            if (control != null && control.isVisibleToUser) siblings.add(control)
        }
        val labelRect = Rect()
        label.getBoundsInScreen(labelRect)
        val nearest = siblings.minByOrNull { candidate ->
            val rect = Rect()
            candidate.getBoundsInScreen(rect)
            abs(rect.centerY() - labelRect.centerY()) + abs(rect.centerX() - labelRect.centerX()) / 2
        }
        if (nearest != null) return nearest
        if (canClick(parent)) return parent
        return null
    }

    private fun firstClickable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (canClick(node)) return node
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = firstClickable(child, depth - 1)
            if (found != null) return found
        }
        return null
    }

    private fun canClick(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent
        return parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findOption(
        root: AccessibilityNodeInfo,
        field: String,
        desired: String
    ): AccessibilityNodeInfo? {
        val wanted = canonical(field, desired)
        if (wanted.isBlank()) return null
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val ownTexts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim() }.filter { it.isNotBlank() }
            if (ownTexts.any { canonical(field, it) == wanted } && node.isVisibleToUser) {
                val clickTarget = if (canClick(node)) node else node.parent?.takeIf { canClick(it) }
                if (clickTarget != null) {
                    val rect = Rect()
                    clickTarget.getBoundsInScreen(rect)
                    candidates.add((rect.width() * rect.height()) to clickTarget)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
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
        return text.isBlank() || listOf(
            "select", "selectgender", "selectphotoidproof", "selectidproof",
            "choose", "chooseone", "pleasechoose", "pleaseselect", "notselected",
            "gender", "sex", "photoidproof", "idproof", "documenttype"
        ).any { text == it || text.contains(it) }
    }

    private fun canonical(field: String, value: String): String {
        val text = normalize(value)
        if (text.isBlank()) return ""
        return when (field) {
            "gender" -> when {
                text == "m" || text == "male" || text == "man" -> "male"
                text == "f" || text == "female" || text == "woman" -> "female"
                text.contains("transgender") || text == "trans" -> "transgender"
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
        node.text?.toString(), node.contentDescription?.toString(),
        node.viewIdResourceName, node.hintText?.toString(), node.tooltipText?.toString()
    ).joinToString(" ")

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    companion object {
        private const val TTD_PACKAGE = "com.ttdapp"
    }
}
