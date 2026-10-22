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
        return options.indexOfFirst { normalize(it) in aliases }
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
        val hints = node.autofillHints?.map { it.lowercase() } ?: emptyList()
        fun has(vararg terms: String) = hints.any { hint -> terms.any { hint == it } }
        if (has("name", "personname", "fullname", "given-name", "name-given", "name-full")) return "fullName"
        if (has("email", "emailaddress", "email-address")) return "email"
        if (has("phone", "phonenumber", "phone-number", "tel")) return "mobile"
        if (has("birthdate", "bday", "dateofbirth", "date-of-birth")) return "dateOfBirth"
        if (has("postalcode", "postal-code", "zip", "zip-code")) return "postalCode"
        if (has("country", "country-name", "countryname")) return "country"
        if (has("sex", "gender")) return "gender"
        if (has("address", "street-address", "address-line1")) return "address"
        if (has("address-level2", "city", "locality")) return "city"
        if (has("address-level1", "state", "region")) return "state"
        val text = listOfNotNull(node.hint, node.idEntry, node.htmlInfo?.attributes?.joinToString(" ") { it.first + "=" + it.second })
            .joinToString(" ").lowercase()
        val optionsText = node.autofillOptions?.joinToString(" ") { it.toString() }?.lowercase().orEmpty()
        val combined = "$text $optionsText"
        return when {
            listOf("photo id proof", "id proof", "identity proof", "document type", "identity document").any { text.contains(it) } -> "idType"
            listOf("photo id number", "id number", "id card number", "document number", "identity number").any { text.contains(it) } -> "idNumber"
            listOf("gender", "sex").any { Regex("(^|[^a-z])(gender|sex)([^a-z]|$)").containsMatchIn(text) } ||
                (node.autofillType == android.view.View.AUTOFILL_TYPE_LIST &&
                    listOf("male", "female", "transgender").all { combined.contains(it) }) -> "gender"
            listOf("full name", "pilgrim name", "passenger name", "applicant name").any { text.contains(it) } -> "fullName"
            listOf("mobile number", "phone number", "contact number").any { text.contains(it) } -> "mobile"
            listOf("email address", "e-mail").any { text.contains(it) } -> "email"
            listOf("date of birth", "dob").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) } -> "dateOfBirth"
            listOf("postal code", "pin code", "pincode", "zip code").any { text.contains(it) } -> "postalCode"
            listOf("country", "country name").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) } -> "country"
            listOf("city", "district").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) } -> "city"
            Regex("(^|[^a-z])state([^a-z]|$)").containsMatchIn(text) -> "state"
            Regex("(^|[^a-z])address([^a-z]|$)").containsMatchIn(text) -> "address"
            Regex("(^|[^a-z])age([^a-z]|$)").containsMatchIn(text) -> "age"
            listOf("photo id proof", "id proof", "identity proof", "document type").any { text.contains(it) } -> "idType"
            listOf("id number", "photo id number", "id card number", "document number").any { text.contains(it) } -> "idNumber"
            else -> null
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        callback.onSuccess()
    }
}
