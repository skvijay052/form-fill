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
            .filterKeys { it in setOf("email", "mobile", "dateOfBirth", "address", "city", "state", "country", "postalCode") }
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
        val pilgrimTargets = targets.filter { it.field in setOf("fullName", "age", "gender", "idType", "idNumber") }
        val occurrences = pilgrimTargets.groupBy { it.field }
        val hasRepeatedPilgrimFields = occurrences.values.any { it.size >= 2 }

        if (hasRepeatedPilgrimFields && pilgrims.isNotEmpty()) {
            // When the booking page contains multiple pilgrim sections at once,
            // fill each section from the matching saved profile by occurrence order.
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Fill all saved pilgrim and general details")
            }
            val dataset = Dataset.Builder(presentation)
            var matched = 0
            targets.forEach { target ->
                if (target.field in setOf("email", "mobile", "dateOfBirth", "address", "city", "state", "country", "postalCode")) {
                    val value = general[target.field]
                    if (!value.isNullOrBlank() && setTextValue(dataset, target, value, presentation)) matched++
                }
            }
            occurrences.forEach { (field, fieldTargets) ->
                fieldTargets.forEachIndexed { personIndex, target ->
                    val pilgrim = pilgrims.getOrNull(personIndex) ?: return@forEachIndexed
                    val value = pilgrim.values()[field]
                    if (!value.isNullOrBlank() && setTargetValue(dataset, target, value, presentation)) matched++
                }
            }
            if (matched > 0) builder.addDataset(dataset.build())
            else {
                callback.onSuccess(null)
                return
            }
        } else {
            // If the site displays one pilgrim form at a time, offer one saved profile per dataset.
            pilgrims.forEachIndexed { index, pilgrim ->
                val values = pilgrim.values() + general
                val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                    setTextViewText(android.R.id.text1, "Pilgrim ${index + 1}: ${pilgrim.fullName.ifBlank { "Saved details" }}")
                }
                val dataset = Dataset.Builder(presentation)
                var matched = 0
                targets.forEach { target ->
                    val value = values[target.field]
                    if (!value.isNullOrBlank() && setTargetValue(dataset, target, value, presentation)) matched++
                }
                if (matched > 0) builder.addDataset(dataset.build())
            }
        }
        callback.onSuccess(builder.build())
    }

    private fun setTargetValue(
        dataset: Dataset.Builder,
        target: Target,
        value: String,
        presentation: RemoteViews
    ): Boolean {
        if (target.autofillType == android.view.View.AUTOFILL_TYPE_LIST) {
            val selectedIndex = findOptionIndex(target.field, value, target.options)
            if (selectedIndex < 0) return false
            dataset.setValue(target.id, AutofillValue.forList(selectedIndex), presentation)
            return true
        }
        if (target.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
            dataset.setValue(target.id, AutofillValue.forText(value), presentation)
            return true
        }
        return false
    }

    private fun setTextValue(
        dataset: Dataset.Builder,
        target: Target,
        value: String,
        presentation: RemoteViews
    ): Boolean {
        if (target.autofillType != android.view.View.AUTOFILL_TYPE_TEXT) return false
        dataset.setValue(target.id, AutofillValue.forText(value), presentation)
        return true
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
                normalizedValue.contains("driving") || normalizedValue.contains("licence") || normalizedValue.contains("license") ->
                    setOf("drivinglicense", "drivinglicence", "drivinglicencecard", "drivinglicensecard")
                normalizedValue.contains("voter") || normalizedValue.contains("election") ->
                    setOf("voterid", "voteridentitycard", "electioncard")
                normalizedValue.contains("pan") -> setOf("pan", "pancard", "permanentaccountnumber")
                normalizedValue.contains("national") || normalizedValue.contains("identity") ->
                    setOf("nationalid", "identitycard", "nationalidentitycard")
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
            node.contentDescription?.toString(),
            node.idEntry,
            node.htmlInfo?.attributes?.joinToString(" ") { it.first + "=" + it.second }
        ).joinToString(" ").lowercase()
        // HTML-backed forms may expose camelCase IDs such as photoIdProof instead
        // of human-readable labels. Compare a compact form as well as visible text.
        val compactText = normalize(text)
        val optionsText = node.autofillOptions?.joinToString(" ") { it.toString() }?.lowercase().orEmpty()
        val combined = "$text $optionsText"
        val explicitNameText = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.idEntry,
            node.htmlInfo?.attributes?.joinToString(" ") { it.second }
        ).joinToString(" ").lowercase()
        val hintText = node.hint?.toString()?.trim()?.lowercase().orEmpty()
        fun containsLabel(vararg terms: String): Boolean = terms.any { term ->
            Regex("(^|[^a-z])" + Regex.escape(term) + "([^a-z]|$)").containsMatchIn(text)
        }
        fun hasOptions(vararg terms: String): Boolean = terms.any { term ->
            Regex("(^|[^a-z])" + Regex.escape(term) + "([^a-z]|$)").containsMatchIn(optionsText)
        }

        // Specific labels first, so a generic "name" hint cannot turn every field into a name.
        return when {
            // ID-number field names seen across HTML forms, Android hints and booking portals.
            listOf("photo id number", "photo id no", "photo id card no", "id proof number", "id proof no",
                "proof id number", "id number", "id no", "id card number", "document number",
                "identity number", "identification number", "document id", "aadhaar number",
                "aadhar number", "passport number", "pan number", "voter id number", "license number",
                "licence number").any { text.contains(it) } ||
                listOf("photoidnumber", "photoidno", "photoidcardno", "idproofnumber", "idproofno",
                    "proofidnumber", "idnumber", "idno", "idcardnumber", "documentnumber",
                    "identitynumber", "identificationnumber", "documentid", "aadhaarnumber",
                    "aadharnumber", "aadharnumber", "passportnumber", "pannumber", "voteridnumber",
                    "licensenumber", "licencenumber").any { compactText.contains(it) } -> "idNumber"
            // ID-proof/type field labels and common developer-assigned IDs.
            listOf("photo id proof", "photo id type", "id proof", "id proof type", "identity proof",
                "identity type", "document type", "identity document", "id document", "proof type",
                "photo identity", "type of id", "type of document").any { text.contains(it) } ||
                listOf("photoidproof", "photoidtype", "photoidprooftype", "idproof", "idprooftype",
                    "idproofkind", "idproofname", "idtype", "identityproof", "identitytype",
                    "identitydocumenttype", "documenttype", "documentproof", "identitydocument",
                    "iddocument", "prooftype", "typeofid", "typeofdocument", "photoidentity").any { compactText.contains(it) } -> "idType"
            containsLabel("email", "e-mail") || text.contains("email address") -> "email"
            containsLabel("mobile", "phone", "telephone", "contact number") || text.contains("mobile number") || text.contains("phone number") -> "mobile"
            containsLabel("date of birth", "dob", "birth date") || text.contains("date-of-birth") -> "dateOfBirth"
            containsLabel("postal code", "pin code", "pincode", "zip code", "zipcode") -> "postalCode"
            containsLabel("country", "country name") -> "country"
            containsLabel("city", "district", "locality") -> "city"
            containsLabel("state", "region") -> "state"
            containsLabel("address", "street address", "address line") -> "address"
            containsLabel("age") -> "age"
            // Gender fields may be called gender, sex, personGender or genderCode in the page markup.
            containsLabel("gender", "sex") ||
                listOf("gender", "sex", "persongender", "gendercode", "genderid", "gendername",
                    "selectgender", "applicantgender", "pilgrimgender", "passengergender").any { compactText.contains(it) } ||
                (listOf("male", "female").all { combined.contains(it) } && hasOptions("male", "female")) -> "gender"
            containsLabel("photo id proof", "id proof", "identity proof", "document type", "identity document") ||
                (hasOptions("aadhaar", "aadhar", "passport", "driving licence", "driving license", "voter id", "pan card", "national id") && !text.contains("number")) -> "idType"
            containsLabel("full name", "pilgrim name", "passenger name", "applicant name", "first name", "given name", "last name", "surname") ||
                Regex("(^|[^a-z])name([^a-z]|$)").containsMatchIn(explicitNameText) ||
                hintText in setOf("name", "full name", "pilgrim name") -> "fullName"
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
                    // Try multiple possible HTML autocomplete/hint spellings for these three fields.
                    hints.any { it in setOf("gender", "sex", "persongender", "gendercode", "genderid", "gendername",
                        "selectgender", "applicantgender", "pilgrimgender", "passengergender") } -> "gender"
                    hints.any { it in setOf("photoidnumber", "photoidno", "photoidcardno", "idnumber", "idno",
                        "idproofnumber", "idproofno", "identitynumber", "identificationnumber", "documentnumber",
                        "identitydocumentnumber", "documentid", "idcardnumber", "aadhaarnumber", "aadharnumber",
                        "passportnumber", "pannumber", "voteridnumber", "licensenumber", "licencenumber") } -> "idNumber"
                    hints.any { it in setOf("photoidproof", "photoidtype", "photoidprooftype", "idproof",
                        "idprooftype", "idproofkind", "idproofname", "idtype", "identityproof", "identitytype",
                        "identitydocumenttype", "documenttype", "documentproof", "identitydocument",
                        "iddocument", "prooftype", "typeofid", "typeofdocument", "photoidentity") } -> "idType"
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
