package com.skv.formfill

data class PilgrimProfile(
    val fullName: String = "",
    val age: String = "",
    val gender: String = "",
    val mobile: String = "",
    val email: String = "",
    val dateOfBirth: String = "",
    val idType: String = "",
    val idNumber: String = "",
    val address: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = ""
) {
    fun values(): Map<String, String> = mapOf(
        "fullName" to fullName, "age" to age, "gender" to gender,
        "mobile" to mobile, "email" to email, "dateOfBirth" to dateOfBirth,
        "idType" to idType, "idNumber" to idNumber, "address" to address,
        "city" to city, "state" to state, "postalCode" to postalCode
    ).filterValues { it.isNotBlank() }
}
