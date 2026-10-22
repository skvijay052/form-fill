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
import android.util.Log

class PilgrimAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        val profile = SecureProfileStore.load(this)
        val values = profile.values()
        if (values.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val fields = mutableListOf<Pair<AutofillId, String>>()
        request.fillContexts.forEach { context ->
            val structure = context.structure
            for (i in 0 until structure.windowNodeCount) {
                walk(structure.getWindowNodeAt(i).rootViewNode, fields, values)
            }
        }
        if (fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Fill saved details")
        }
        val builder = Dataset.Builder(presentation)
        fields.forEach { (id, value) -> builder.setValue(id, AutofillValue.forText(value), presentation) }
        callback.onSuccess(FillResponse.Builder().addDataset(builder.build()).build())
    }

    private fun walk(node: AssistStructure.ViewNode, output: MutableList<Pair<AutofillId, String>>, values: Map<String, String>) {
        val id = node.autofillId
        if (id != null && node.isEnabled && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
            val field = identify(node)
            val value = field?.let { values[it] }
            if (!value.isNullOrBlank() && output.none { it.first == id }) output.add(id to value)
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), output, values)
    }

    /** Conservative matching: exact standard hints first, then specific aliases only. */
    private fun identify(node: AssistStructure.ViewNode): String? {
        val hints = node.autofillHints?.map { it.lowercase() } ?: emptyList()
        fun has(vararg terms: String) = hints.any { hint -> terms.any { hint == it } }
        if (has("name", "personname", "fullname", "given-name", "name-given", "name-full")) return "fullName"
        if (has("email", "emailaddress", "email-address")) return "email"
        if (has("phone", "phonenumber", "phone-number", "tel")) return "mobile"
        if (has("birthdate", "bday", "dateofbirth", "date-of-birth")) return "dateOfBirth"
        if (has("postalcode", "postal-code", "zip", "zip-code")) return "postalCode"
        if (has("address", "street-address", "address-line1")) return "address"
        if (has("address-level2", "city", "locality")) return "city"
        if (has("address-level1", "state", "region")) return "state"
        val text = listOfNotNull(node.hint, node.text?.toString(), node.idEntry, node.htmlInfo?.attributes?.joinToString(" ") { it.name + "=" + it.value })
            .joinToString(" ").lowercase()
        // Do not use generic "name" substring; avoid username, account and ID fields.
        return when {
            listOf("full name", "pilgrim name", "passenger name", "applicant name").any { text.contains(it) } -> "fullName"
            listOf("mobile number", "phone number", "contact number").any { text.contains(it) } -> "mobile"
            listOf("email address", "e-mail").any { text.contains(it) } -> "email"
            listOf("date of birth", "dob").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) } -> "dateOfBirth"
            listOf("postal code", "pin code", "pincode", "zip code").any { text.contains(it) } -> "postalCode"
            listOf("city", "district").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) } -> "city"
            listOf("state").any { Regex("(^|[^a-z])state([^a-z]|$)").containsMatchIn(text) } -> "state"
            listOf("address").any { Regex("(^|[^a-z])address([^a-z]|$)").containsMatchIn(text) } -> "address"
            listOf("age").any { Regex("(^|[^a-z])age([^a-z]|$)").containsMatchIn(text) } -> "age"
            listOf("gender", "sex").any { Regex("(^|[^a-z])(gender|sex)([^a-z]|$)").containsMatchIn(text) } -> "gender"
            listOf("id number", "id card number", "document number").any { text.contains(it) } -> "idNumber"
            else -> null
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        // Intentionally do not save arbitrary form values. Users edit their profile in the app.
        callback.onSuccess()
    }
}
