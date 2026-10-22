package com.skv.formfill

import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Dataset
import android.view.autofill.AutofillValue
import android.view.autofill.AutofillId
import android.app.assist.AssistStructure
import android.widget.RemoteViews
import android.os.CancellationSignal

class PilgrimAutofillService : AutofillService() {
    private data class Target(
        val id: AutofillId,
        val field: String,
        val autofillType: Int,
        val options: List<String>
    )

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        val pilgrims = SecureProfileStore.loadPilgrims(this)
        val general = SecureProfileStore.loadGeneral(this).values()
        val targets = mutableListOf<Target>()
        request.fillContexts.forEach { context ->
            val structure = context.structure
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, targets)
        }
        if (targets.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val builder = FillResponse.Builder()
        var datasetCount = 0
        pilgrims.forEachIndexed { index, pilgrim ->
            val values = pilgrim.values() + general
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Pilgrim ${index + 1}: ${pilgrim.fullName.ifBlank { "Saved details" }}")
            }
            val dataset = Dataset.Builder(presentation)
            var matched = 0
            targets.forEach { target ->
                val value = values[target.field]
                if (!value.isNullOrBlank()) {
                    if (target.autofillType == android.view.View.AUTOFILL_TYPE_LIST) {
                        val selectedIndex = findOptionIndex(target.field, value, target.options)
                        if (selectedIndex >= 0) {
                            dataset.setValue(target.id, AutofillValue.forList(selectedIndex), presentation)
                            matched++
                        }
                    } else if (target.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
                        dataset.setValue(target.id, AutofillValue.forText(value), presentation)
                        matched++
                    }
                }
            }
            if (matched > 0) {
                builder.addDataset(dataset.build())
                datasetCount++
            }
        }
        if (datasetCount == 0) callback.onSuccess(null)
        else callback.onSuccess(builder.build())
    }

    private fun findOptionIndex(field: String, value: String, options: List<String>): Int {
        val normalizedValue = normalize(value)
        val aliases = when (field) {
            "gender" -> when (normalizedValue) {
                "m", "male", "man" -> setOf("male", "m")
                "f", "female", "woman" -> setOf("female", "f")
                "transgender", "trans" -> setOf("transgender", "trans")
                else -> setOf(normalizedValue)
            }
            "idType" -> when {
                normalizedValue.contains("aadhaar") || normalizedValue.contains("aadhar") -> setOf("aadhaarcard", "aadharcard", "aadhaar", "aadhar")
                normalizedValue.contains("passport") -> setOf("passport")
                else -> setOf(normalizedValue)
            }
            else -> setOf(normalizedValue)
        }
        return options.indexOfFirst { option ->
            val normalizedOption = normalize(option)
            normalizedOption in aliases || aliases.any { alias -> alias.length >= 4 && (normalizedOption.contains(alias) || alias.contains(normalizedOption)) }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    private fun walk(node: AssistStructure.ViewNode, output: MutableList<Target>) {
        val id = node.autofillId
        val type = node.autofillType
        if (id != null && node.isEnabled &&
            (type == android.view.View.AUTOFILL_TYPE_TEXT || type == android.view.View.AUTOFILL_TYPE_LIST)) {
            val field = identify(node)
            if (field != null && output.none { it.id == id }) {
                output.add(Target(id, field, type, node.autofillOptions?.map { it.toString() } ?: emptyList()))
            }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), output)
    }

    private fun identify(node: AssistStructure.ViewNode): String? {
        // Prefer the actual field label/id/options over generic browser autofill hints.
        // Some booking pages expose the same generic "name" hint on several inputs.
        val text = listOfNotNull(
            node.hint?.toString(),
            node.text?.toString(),
            node.idEntry,
            node.htmlInfo?.attributes?.joinToString(" ") { it.first + "=" + it.second }
        ).joinToString(" ").lowercase()
        val optionsText = node.autofillOptions?.joinToString(" ") { it.toString() }?.lowercase().orEmpty()
        val combined = "$text $optionsText"
        fun containsLabel(vararg terms: String): Boolean = terms.any { term ->
            Regex("(^|[^a-z])" + Regex.escape(term) + "([^a-z]|$)").containsMatchIn(text)
        }

        // Specific labels first, so a generic "name" hint cannot turn every field into a name.
        return when {
            listOf("photo id proof", "id proof", "identity proof", "document type", "identity document").any { text.contains(it) } -> "idType"
            listOf("photo id number", "id number", "id card number", "document number", "identity number").any { text.contains(it) } -> "idNumber"
            containsLabel("email", "e-mail") || text.contains("email address") -> "email"
            containsLabel("mobile", "phone", "telephone", "contact number") || text.contains("mobile number") || text.contains("phone number") -> "mobile"
            containsLabel("date of birth", "dob", "birth date") || text.contains("date-of-birth") -> "dateOfBirth"
            containsLabel("postal code", "pin code", "pincode", "zip code", "zipcode") -> "postalCode"
            containsLabel("country", "country name") -> "country"
            containsLabel("city", "district", "locality") -> "city"
            containsLabel("state", "region") -> "state"
            containsLabel("address", "street address", "address line") -> "address"
            containsLabel("age") -> "age"
            containsLabel("gender", "sex") ||
                (node.autofillType == android.view.View.AUTOFILL_TYPE_LIST &&
                    listOf("male", "female").all { combined.contains(it) }) -> "gender"
            containsLabel("full name", "pilgrim name", "passenger name", "applicant name", "first name", "given name", "last name", "surname") -> "fullName"
            // Only use hints after checking visible metadata. Do not accept generic "name" alone.
            else -> {
                val hints = node.autofillHints?.map { it.lowercase().replace("_", "").replace("-", "") } ?: emptyList()
                when {
                    hints.any { it in setOf("email", "emailaddress") } -> "email"
                    hints.any { it in setOf("phone", "phonenumber", "tel", "telephone") } -> "mobile"
                    hints.any { it in setOf("birthdate", "bday", "dateofbirth") } -> "dateOfBirth"
                    hints.any { it in setOf("postalcode", "zip", "zipcode") } -> "postalCode"
                    hints.any { it in setOf("address", "streetaddress", "addressline1") } -> "address"
                    hints.any { it in setOf("addresslocality", "city", "locality") } -> "city"
                    hints.any { it in setOf("addressregion", "state", "region") } -> "state"
                    hints.any { it in setOf("addresscountry", "country", "countryname") } -> "country"
                    hints.any { it in setOf("age") } -> "age"
                    hints.any { it in setOf("gender", "sex") } -> "gender"
                    // Never map a generic "name" hint to fullName: some websites
                    // incorrectly expose it on every input, which fills Vijay into all fields.
                    // Name is mapped only when the field's own label/ID explicitly identifies it.
                    else -> null
                }
            }
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        callback.onSuccess()
    }
}
