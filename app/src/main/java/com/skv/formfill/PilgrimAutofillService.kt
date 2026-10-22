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
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        val pilgrims = SecureProfileStore.loadPilgrims(this)
        val general = SecureProfileStore.loadGeneral(this).values()
        val targets = mutableListOf<Pair<AutofillId, String>>()
        request.fillContexts.forEach { context ->
            val structure = context.structure
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, targets)
        }
        if (targets.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val builder = FillResponse.Builder()
        var count = 0
        pilgrims.forEachIndexed { index, pilgrim ->
            val values = pilgrim.values() + general
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Pilgrim ${index + 1}: ${pilgrim.fullName.ifBlank { "Saved details" }}")
            }
            val dataset = Dataset.Builder(presentation)
            var matched = 0
            targets.forEach { (id, field) ->
                val value = values[field]
                if (!value.isNullOrBlank()) {
                    dataset.setValue(id, AutofillValue.forText(value), presentation)
                    matched++
                }
            }
            if (matched > 0) {
                builder.addDataset(dataset.build())
                count++
            }
        }
        if (count == 0) callback.onSuccess(null)
        else callback.onSuccess(builder.build())
    }

    private fun walk(node: AssistStructure.ViewNode, output: MutableList<Pair<AutofillId, String>>) {
        val id = node.autofillId
        if (id != null && node.isEnabled && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
            val field = identify(node)
            if (field != null && output.none { it.first == id }) output.add(id to field)
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
        return when {
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
            Regex("(^|[^a-z])(gender|sex)([^a-z]|$)").containsMatchIn(text) -> "gender"
            listOf("photo id proof", "id proof", "identity proof", "document type").any { text.contains(it) } -> "idType"
            listOf("id number", "photo id number", "id card number", "document number").any { text.contains(it) } -> "idNumber"
            else -> null
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        callback.onSuccess()
    }
}
