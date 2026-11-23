package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class ContactsTest {
    private val a1 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val a2 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"

    @Test fun jsonRoundTripAndValidation() {
        val l = listOf(Contact("Brácha", a1), Contact("Petr \"P\"", a2))
        assertEquals(l, Contacts.fromJson(Contacts.toJson(l)))
        assertEquals(emptyList<Contact>(), Contacts.fromJson("""[{"name":"X","address":"nesmysl"},{"name":"","address":"$a1"}]"""))
        assertEquals(emptyList<Contact>(), Contacts.fromJson("rozbité"))
    }

    @Test fun upsertRenamesAndSorts() {
        var l = Contacts.upsert(emptyList(), Contact("Zdeněk", a1))
        l = Contacts.upsert(l, Contact("Adam", a2))
        assertEquals(listOf("Adam", "Zdeněk"), l.map { it.name })
        l = Contacts.upsert(l, Contact("Brácha", a1))
        assertEquals(listOf("Adam", "Brácha"), l.map { it.name })
        assertEquals(a1, Contacts.normalize("BITCOIN:" + a1.uppercase()))
    }
}
