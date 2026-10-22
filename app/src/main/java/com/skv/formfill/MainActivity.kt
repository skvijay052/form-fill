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
    private val fields = linkedMapOf<String, EditText>()
    private val purple = Color.rgb(92, 45, 145)
    private val ink = Color.rgb(32, 41, 51)
    private val muted = Color.rgb(104, 116, 128)
    private val surface = Color.WHITE
    private val page = Color.rgb(247, 248, 251)

    private val pilgrimFields = linkedMapOf(
        "fullName" to "Name",
        "age" to "Age",
        "gender" to "Gender",
        "idType" to "Photo ID Proof",
        "idNumber" to "Photo ID Number"
    )
    private val generalFields = linkedMapOf(
        "email" to "Email Address",
        "mobile" to "Mobile Number",
        "city" to "City",
        "state" to "State",
        "country" to "Country",
        "postalCode" to "PIN Code",
        "address" to "Address",
        "dateOfBirth" to "Date of Birth (DD/MM/YYYY)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = purple
        window.navigationBarColor = page
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(page)
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = rounded(purple, 20)
        }
        hero.addView(TextView(this).apply {
            text = "FormFill"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        hero.addView(TextView(this).apply {
            text = "Your TTD details, ready when you need them."
            textSize = 15f
            setTextColor(Color.rgb(244, 237, 252))
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(hero, marginParams(bottom = 14))

        val profile = SecureProfileStore.load(this)
        val filledCount = profile.values().size
        val progressCard = card()
        progressCard.addView(TextView(this).apply {
            text = "YOUR PROFILE"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
        })
        progressCard.addView(TextView(this).apply {
            text = if (filledCount == 0) "Let’s get your details ready" else "$filledCount details saved"
            textSize = 18f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(5), 0, dp(3))
        })
        progressCard.addView(TextView(this).apply {
            text = "Your information is encrypted and stored on this device."
            textSize = 13f
            setTextColor(muted)
        })
        content.addView(progressCard, marginParams(bottom = 14))

        fun addSection(title: String, subtitle: String, sectionFields: Map<String, String>) {
            val section = card()
            section.addView(TextView(this).apply {
                text = title
                textSize = 19f
                setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            section.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(3), 0, dp(12))
            })
            sectionFields.forEach { (key, label) ->
                val labelView = TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(7), 0, dp(3))
                }
                section.addView(labelView)
                val input = EditText(this).apply {
                    setSingleLine(true)
                    textSize = 15f
                    setTextColor(ink)
                    setHintTextColor(Color.rgb(151, 158, 167))
                    setPadding(dp(12), 0, dp(12), 0)
                    setText(profile.values()[key].orEmpty())
                    hint = when (key) {
                        "age" -> "Enter age"
                        "email" -> "name@example.com"
                        "mobile" -> "Enter mobile number"
                        "postalCode" -> "Enter PIN code"
                        "dateOfBirth" -> "DD/MM/YYYY"
                        else -> "Enter $label"
                    }
                    background = rounded(Color.rgb(248, 249, 252), 10, Color.rgb(226, 230, 237))
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                    contentDescription = label
                }
                fields[key] = input
                section.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            }
            content.addView(section, marginParams(bottom = 14))
        }
        addSection("Pilgrim details", "Enter the details shown in your TTD booking form.", pilgrimFields)
        addSection("General details", "Contact and address information.", generalFields)

        val saveButton = Button(this).apply {
            text = "Save my details"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(purple, 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener {
                val v = fields.mapValues { it.value.text.toString().trim() }
                SecureProfileStore.save(this@MainActivity, PilgrimProfile(
                    fullName = v["fullName"].orEmpty(),
                    age = v["age"].orEmpty(),
                    gender = v["gender"].orEmpty(),
                    mobile = v["mobile"].orEmpty(),
                    email = v["email"].orEmpty(),
                    dateOfBirth = v["dateOfBirth"].orEmpty(),
                    idType = v["idType"].orEmpty(),
                    idNumber = v["idNumber"].orEmpty(),
                    address = v["address"].orEmpty(),
                    city = v["city"].orEmpty(),
                    state = v["state"].orEmpty(),
                    country = v["country"].orEmpty(),
                    postalCode = v["postalCode"].orEmpty()
                ))
                Toast.makeText(this@MainActivity, "Details saved securely on this device", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(saveButton, marginParams(bottom = 10))

        val enableButton = Button(this).apply {
            text = "Set FormFill as Autofill service"
            isAllCaps = false
            textSize = 14f
            setTextColor(purple)
            background = rounded(Color.WHITE, 14, Color.rgb(220, 210, 235))
            setOnClickListener {
                val manager = getSystemService(AutofillManager::class.java)
                if (manager != null && !manager.hasEnabledAutofillServices()) {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                        android.net.Uri.parse("package:$packageName")))
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
        }
        content.addView(enableButton, marginParams(bottom = 8))

        val clearButton = TextView(this).apply {
            text = "Clear saved details"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear saved details?")
                    .setMessage("This permanently deletes your saved profile from this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear details") { _, _ ->
                        SecureProfileStore.clear(this@MainActivity)
                        fields.values.forEach { it.text.clear() }
                        Toast.makeText(this@MainActivity, "Saved details cleared", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        }
        content.addView(clearButton)
        content.addView(TextView(this).apply {
            text = "Autofill availability depends on whether the TTD app or website supports Android Autofill."
            textSize = 12f
            setTextColor(muted)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(10))
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surface, 16, Color.rgb(235, 237, 242))
        elevation = dp(1).toFloat()
    }

    private fun marginParams(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }
}
