package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class WalletsTest {
    @Test fun migratesExistingSingleWallet() {
        val l = WalletRegistry.loadOrMigrate(null, hasSeed = true, defaultName = "Kapesní")
        assertEquals(listOf(WalletInfo("w1", "Kapesní", "seed", "hot")), l)
        assertEquals("wallet.sqlite3", WalletRegistry.dbName("w1"))
        assertEquals("notes", WalletRegistry.notesPrefs("w1")); assertEquals("coin_tags", WalletRegistry.tagPrefs("w1"))
        assertEquals("settings", WalletRegistry.walletPrefs("w1"))
        assertTrue(WalletRegistry.loadOrMigrate(null, hasSeed = false, defaultName = "x").isEmpty())
    }

    @Test fun roundTripAndIds() {
        val l = listOf(WalletInfo("w1", "", "seed", "hot"), WalletInfo("w2", "Trezor \"CC\"", "wallet_w2", "watch"))
        assertEquals(l, WalletRegistry.fromJson(WalletRegistry.toJson(l)))
        assertEquals(l, WalletRegistry.loadOrMigrate(WalletRegistry.toJson(l), hasSeed = true, defaultName = "x"))
        assertEquals("w3", WalletRegistry.newId(l))
        assertEquals("w4", WalletRegistry.newId(listOf(WalletInfo("w1", "a", "seed", "hot"), WalletInfo("w3", "b", "x", "hot"))))
        assertEquals("wallet_w2.sqlite3", WalletRegistry.dbName("w2")); assertEquals("notes_w2", WalletRegistry.notesPrefs("w2"))
        assertTrue(WalletRegistry.fromJson("""[{"id":"../evil","name":"x"}]""").isEmpty())
        assertTrue(WalletRegistry.fromJson("rozbité").isEmpty())
    }
}
