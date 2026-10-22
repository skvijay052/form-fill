package com.skv.formfill

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val pilgrimInputs = mutableListOf<LinkedHashMap<String, EditText>>()
    private val generalInputs = linkedMapOf<String, EditText>()
    private val purple = Color.rgb(92, 45, 145)
    private val ink = Color.rgb(32, 41, 51)
    private val muted = Color.rgb(104, 116, 128)
    private val page = Color.rgb(247, 248, 251)
    private lateinit var content: LinearLayout
    private lateinit var pilgrimsContainer: LinearLayout

    private val pilgrimFields = linkedMapOf("fullName" to "Name", "age" to "Age", "gender" to "Gender",
        "idType" to "Photo ID Proof", "idNumber" to "Photo ID Number")
    private val generalFields = linkedMapOf("email" to "Email Address", "mobile" to "Mobile Number",
        "city" to "City", "state" to "State", "country" to "Country", "postalCode" to "PIN Code",
        "address" to "Address", "dateOfBirth" to "Date of Birth (DD/MM/YYYY)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = purple
        window.navigationBarColor = page
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(page) }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(22)) }

        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(22), dp(20), dp(22)); background = rounded(purple, 20) }
        hero.addView(TextView(this).apply { text = "FormFill"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        hero.addView(TextView(this).apply { text = "Save details for everyone travelling with you."; textSize = 15f; setTextColor(Color.rgb(244,237,252)); setPadding(0, dp(5), 0, 0) })
        content.addView(hero, params(14))

        val intro = card()
        intro.addView(TextView(this).apply { text = "Multiple pilgrims"; textSize = 19f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        intro.addView(TextView(this).apply { text = "Add one card for each person. Contact and address details are shared across the booking."; textSize = 13f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
        content.addView(intro, params(14))

        val savedPilgrims = SecureProfileStore.loadPilgrims(this)
        val savedGeneral = SecureProfileStore.loadGeneral(this)
        pilgrimsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pilgrimsContainer)
        savedPilgrims.forEach { addPilgrim(it) }
        if (savedPilgrims.isEmpty()) addPilgrim(PilgrimProfile())

        val addButton = Button(this).apply {
            text = "+ Add another pilgrim"; isAllCaps = false; textSize = 15f; setTextColor(purple)
            background = rounded(Color.WHITE, 14, Color.rgb(220,210,235))
            setOnClickListener { addPilgrim(PilgrimProfile()); Toast.makeText(this@MainActivity, "Pilgrim added", Toast.LENGTH_SHORT).show() }
        }
        content.addView(addButton, params(16))

        val generalCard = card()
        generalCard.addView(TextView(this).apply { text = "General details"; textSize = 19f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        generalCard.addView(TextView(this).apply { text = "Contact and address information for the booking."; textSize = 13f; setTextColor(muted); setPadding(0, dp(3), 0, dp(10)) })
        generalFields.forEach { (key, label) -> generalCard.addView(makeField(key, label, savedGeneral.values()[key].orEmpty(), generalInputs)) }
        content.addView(generalCard, params(14))

        content.addView(Button(this).apply {
            text = "Save all details securely"; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            background = rounded(purple, 14); setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener {
                val pilgrims = pilgrimInputs.map { map ->
                    val v = map.mapValues { it.value.text.toString().trim() }
                    PilgrimProfile(fullName=v["fullName"].orEmpty(), age=v["age"].orEmpty(), gender=v["gender"].orEmpty(),
                        idType=v["idType"].orEmpty(), idNumber=v["idNumber"].orEmpty())
                }
                val g = generalInputs.mapValues { it.value.text.toString().trim() }
                val general = PilgrimProfile(email=g["email"].orEmpty(), mobile=g["mobile"].orEmpty(),
                    dateOfBirth=g["dateOfBirth"].orEmpty(), address=g["address"].orEmpty(), city=g["city"].orEmpty(),
                    state=g["state"].orEmpty(), country=g["country"].orEmpty(), postalCode=g["postalCode"].orEmpty())
                SecureProfileStore.savePilgrims(this@MainActivity, pilgrims, general)
                Toast.makeText(this@MainActivity, "${pilgrims.size} pilgrim profile(s) saved securely", Toast.LENGTH_SHORT).show()
            }
        }, params(10))

        content.addView(Button(this).apply {
            text = "Set FormFill as Autofill service"; isAllCaps = false; textSize = 14f; setTextColor(purple)
            background = rounded(Color.WHITE, 14, Color.rgb(220,210,235))
            setOnClickListener {
                val manager = getSystemService(AutofillManager::class.java)
                if (manager != null && !manager.hasEnabledAutofillServices()) {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:$packageName")))
                } else startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }, params(8))

        content.addView(TextView(this).apply {
            text = "Your saved profiles are encrypted on this device. Autofill options depend on TTD form support."
            textSize = 12f; setTextColor(muted); gravity = android.view.Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(10))
        })
        content.addView(TextView(this).apply {
            text = "Clear all saved details"; textSize = 14f; gravity = android.view.Gravity.CENTER; setTextColor(muted)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Clear all saved details?")
                    .setMessage("This deletes every pilgrim and the shared contact details from this device.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        SecureProfileStore.clear(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Saved details cleared. Reopen FormFill to start again.", Toast.LENGTH_LONG).show()
                        finish(); startActivity(intent)
                    }.show()
            }
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addPilgrim(profile: PilgrimProfile) {
        val index = pilgrimInputs.size + 1
        val inputs = linkedMapOf<String, EditText>()
        pilgrimInputs.add(inputs)
        val section = card()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Pilgrim $index"; textSize = 18f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (pilgrimInputs.size > 1) header.addView(TextView(this).apply {
            text = "Remove"; textSize = 13f; setTextColor(Color.rgb(176, 55, 65)); setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setMessage("Remove Pilgrim $index?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                        val at = pilgrimInputs.indexOf(inputs)
                        if (at >= 0) pilgrimInputs.removeAt(at)
                        pilgrimsContainer.removeView(section)
                        refreshPilgrimTitles()
                    }.show()
            }
        })
        section.addView(header)
        section.addView(TextView(this).apply { text = "Required details for this person"; textSize = 13f; setTextColor(muted); setPadding(0, dp(3), 0, dp(8)) })
        profile.values()
        pilgrimFields.forEach { (key, label) -> section.addView(makeField(key, label, profile.values()[key].orEmpty(), inputs)) }
        pilgrimsContainer.addView(section, params(14))
    }

    private fun refreshPilgrimTitles() {
        for (i in 0 until pilgrimsContainer.childCount) {
            val section = pilgrimsContainer.getChildAt(i) as? LinearLayout ?: continue
            val header = section.getChildAt(0) as? LinearLayout ?: continue
            (header.getChildAt(0) as? TextView)?.text = "Pilgrim ${i + 1}"
        }
    }

    private fun makeField(key: String, label: String, value: String, bucket: MutableMap<String, EditText>): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(7), 0, dp(3)) })
        val input = EditText(this).apply {
            setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(Color.rgb(151,158,167))
            setPadding(dp(12), 0, dp(12), 0); setText(value)
            hint = when (key) { "age" -> "Enter age"; "email" -> "name@example.com"; "mobile" -> "Enter mobile number"; "postalCode" -> "Enter PIN code"; "dateOfBirth" -> "DD/MM/YYYY"; else -> "Enter $label" }
            background = rounded(Color.rgb(248,249,252), 10, Color.rgb(226,230,237))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES; contentDescription = label
        }
        bucket[key] = input
        wrap.addView(input, LinearLayout.LayoutParams(-1, dp(50)))
        return wrap
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, 16, Color.rgb(235,237,242)); elevation = dp(1).toFloat()
    }
    private fun params(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
}
