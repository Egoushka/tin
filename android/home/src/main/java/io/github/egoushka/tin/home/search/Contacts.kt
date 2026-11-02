package io.github.egoushka.tin.home.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Contact(val id: Long, val lookupKey: String, val name: String, val hasPhone: Boolean) {
    val key: String get() = "$id/$lookupKey"
}

/**
 * People for the search bar: every contact by name; a number is read only when the phone icon is tapped.
 * No visible-group filter: contacts synced by a messenger account are never in one, so it would hide all of them.
 */
class ContactRepository(private val context: Context) {
    private val resolver = context.contentResolver

    fun granted(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(): List<Contact> {
        if (!granted()) return emptyList()
        val out = ArrayList<Contact>()
        resolver.query(Contacts.CONTENT_URI, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)
                if (name.isNullOrBlank()) continue
                out.add(Contact(c.getLong(0), c.getString(1) ?: continue, name, c.getInt(3) != 0))
            }
        }
        return out
    }

    /** Emits once now and again on every change to the address book. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        trySend(Unit)
        resolver.registerContentObserver(Contacts.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    fun uri(contact: Contact): Uri = Contacts.getLookupUri(contact.id, contact.lookupKey)

    /** The contact's preferred number, or the first one. */
    fun phone(contact: Contact): String? = resolver.query(
        Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(contact.id.toString()),
        "${Phone.IS_SUPER_PRIMARY} DESC",
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private companion object {
        val PROJECTION = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.HAS_PHONE_NUMBER)
    }
}
