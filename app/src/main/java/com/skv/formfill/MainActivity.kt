package com.skv.formfill

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 24)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = "FormFill Autofill"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "Save your details securely on this device. Autofill suggestions appear only when a supported app or browser requests them."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })
        val profile = SecureProfileStore.load(this)
        fun addSection(title: String, sectionFields: Map<String, String>) {
            content.addView(TextView(this).apply {
                text = title
                textSize = 20f
                setPadding(0, 16, 0, 8)
            })
            sectionFields.forEach { (key, label) ->
                content.addView(TextView(this).apply { text = label; textSize = 14f })
                val input = EditText(this).apply {
                    setSingleLine(true)
                    setText(profile.values()[key].orEmpty())
                    hint = label
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                }
                fields[key] = input
                content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        addSection("Pilgrim Details", pilgrimFields)
        addSection("General Details", generalFields)
        content.addView(Button(this).apply {
            text = "Save securely"
            setOnClickListener {
                val v = fields.mapValues { it.value.text.toString().trim() }
                SecureProfileStore.save(this@MainActivity, PilgrimProfile(
                    v["fullName"].orEmpty(), v["age"].orEmpty(), v["gender"].orEmpty(),
                    v["mobile"].orEmpty(), v["email"].orEmpty(), v["dateOfBirth"].orEmpty(),
                    v["idType"].orEmpty(), v["idNumber"].orEmpty(), v["address"].orEmpty(),
                    v["city"].orEmpty(), v["state"].orEmpty(), v["postalCode"].orEmpty()
                ))
                Toast.makeText(this@MainActivity, "Profile saved on this device", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(Button(this).apply {
            text = "Enable FormFill in Android settings"
            setOnClickListener {
                val manager = getSystemService(AutofillManager::class.java)
                if (manager != null && !manager.hasEnabledAutofillServices()) {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                        android.net.Uri.parse("package:$packageName")))
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Clear saved details"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear saved details?")
                    .setMessage("Your locally stored profile will be deleted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        SecureProfileStore.clear(this@MainActivity)
                        fields.values.forEach { it.text.clear() }
                        Toast.makeText(this@MainActivity, "Saved details cleared", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        })
        scroll.addView(content)
        outer.addView(scroll)
        setContentView(outer)
    }
}
