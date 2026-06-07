package com.example.utils

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.example.model.*
import java.util.UUID

enum class DuplicateStatus {
    NEW,
    POSSIBLE_DUPLICATE,
    WILL_UPDATE,
    SKIPPED
}

data class ImportContactCandidate(
    val id: String = UUID.randomUUID().toString(),
    val firstName: String = "",
    val lastName: String = "",
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val companyName: String? = null,
    val jobTitle: String? = null,
    val addresses: List<Address> = emptyList(),
    val birthday: String? = null,
    val notes: String? = null,
    val source: String = "Телефонная книга",
    var duplicateStatus: DuplicateStatus = DuplicateStatus.NEW,
    var selectedForImport: Boolean = true,
    var matchedContactId: String? = null
)

object ContactImporter {

    fun getDeviceContacts(context: Context): List<ImportContactCandidate> {
        val candidates = mutableMapOf<String, ImportContactCandidate>()

        val contentResolver = context.contentResolver
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            null,
            null,
            null
        )

        cursor?.use {
            val contactIdIdx = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeTypeIdx = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
            
            // Name
            val data1Idx = it.getColumnIndex(ContactsContract.Data.DATA1)
            val data2Idx = it.getColumnIndex(ContactsContract.Data.DATA2)
            val data3Idx = it.getColumnIndex(ContactsContract.Data.DATA3)
            val data4Idx = it.getColumnIndex(ContactsContract.Data.DATA4) // sometimes used
            
            while (it.moveToNext()) {
                val contactId = it.getString(contactIdIdx)
                val mimeType = it.getString(mimeTypeIdx)

                val candidate = candidates.getOrPut(contactId) {
                    ImportContactCandidate(id = "device_contact_$contactId")
                }

                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        val givenName = it.getString(data2Idx)
                        val familyName = it.getString(data3Idx)
                        val displayName = it.getString(data1Idx)
                        
                        val first = givenName ?: if (!familyName.isNullOrBlank()) "" else displayName ?: ""
                        val last = familyName ?: ""
                        
                        candidates[contactId] = candidate.copy(
                            firstName = candidate.firstName.takeIf { it.isNotBlank() } ?: first.trim(),
                            lastName = candidate.lastName.takeIf { it.isNotBlank() } ?: last.trim()
                        )
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val phoneNumber = it.getString(data1Idx)
                        val type = it.getInt(data2Idx)
                        if (!phoneNumber.isNullOrBlank()) {
                            val phoneType = when (type) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> PhoneType.MOBILE
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> PhoneType.WORK
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> PhoneType.HOME
                                else -> PhoneType.OTHER
                            }
                            val cp = ContactPhone(
                                id = UUID.randomUUID().toString(),
                                contactId = candidate.id,
                                number = phoneNumber,
                                type = phoneType,
                                isPrimary = candidate.phones.isEmpty()
                            )
                            candidates[contactId] = candidate.copy(phones = candidate.phones + cp)
                        }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = it.getString(data1Idx)
                        val type = it.getInt(data2Idx)
                        if (!email.isNullOrBlank()) {
                            val emailType = when (type) {
                                ContactsContract.CommonDataKinds.Email.TYPE_WORK -> EmailType.WORK
                                ContactsContract.CommonDataKinds.Email.TYPE_HOME -> EmailType.PERSONAL
                                else -> EmailType.OTHER
                            }
                            val ce = ContactEmail(
                                id = UUID.randomUUID().toString(),
                                contactId = candidate.id,
                                email = email,
                                type = emailType,
                                isPrimary = candidate.emails.isEmpty()
                            )
                            candidates[contactId] = candidate.copy(emails = candidate.emails + ce)
                        }
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        val company = it.getString(data1Idx)
                        val title = it.getString(data4Idx)
                        candidates[contactId] = candidate.copy(
                            companyName = company,
                            jobTitle = title
                        )
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val streetIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)
                        val cityIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)
                        val countryIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)
                        val formattedAddrIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)

                        val street = if (streetIdx >= 0) it.getString(streetIdx) else null
                        val city = if (cityIdx >= 0) it.getString(cityIdx) else null
                        val country = if (countryIdx >= 0) it.getString(countryIdx) else null
                        val formattedAddress = if (formattedAddrIdx >= 0) it.getString(formattedAddrIdx) else null
                        
                        val addrLine = street ?: formattedAddress ?: ""
                        if (addrLine.isNotBlank() || !city.isNullOrBlank() || !country.isNullOrBlank()) {
                            val addr = Address(
                                id = UUID.randomUUID().toString(),
                                addressLine = addrLine,
                                city = city ?: "",
                                country = country ?: "",
                                ownerId = candidate.id,
                                ownerType = AddressOwnerType.CONTACT,
                                addressType = AddressType.HOME
                            )
                            candidates[contactId] = candidate.copy(addresses = candidate.addresses + addr)
                        }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val type = it.getInt(data2Idx)
                        if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                            val date = it.getString(data1Idx)
                            if (!date.isNullOrBlank()) {
                                candidates[contactId] = candidate.copy(birthday = date)
                            }
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val text = it.getString(data1Idx)
                        if (!text.isNullOrBlank()) {
                            candidates[contactId] = candidate.copy(notes = text)
                        }
                    }
                }
            }
        }
        
        return candidates.values.toList()
    }
}
