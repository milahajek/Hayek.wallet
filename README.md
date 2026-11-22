# Hayek Wallet

**Bitcoinová peněženka pro Android, která se připojuje jen k vašemu vlastnímu uzlu a klíče vytváří z hodů kostkou.**
*A Bitcoin wallet for Android that talks only to your own node and creates keys from dice rolls.* ([English below](#english))

> ⚠️ **Experimentální software bez auditu.** Používejte jen na malé částky. Software je poskytován „tak, jak je“, bez jakékoli záruky (viz `LICENSE`).

## Co umí

- **Obnovovací fráze z hodů kostkou** – 24 slov (BIP39) z 99+ hodů šestistěnnou kostkou. Entropie = SHA-256 z textu hodů (znaky `1`–`6`), stejná metoda jako Coldcard a SeedSigner, takže výsledek lze nezávisle ověřit. Jiný způsob vytvoření nové peněženky aplikace nemá.
- **Jen váš uzel** – připojení výhradně k vašemu Electrum serveru (Fulcrum / ElectRS). Žádný výchozí ani záložní veřejný server. Aplikace ověřuje, že server je Bitcoin mainnet, a **připíná jeho certifikát** (při prvním spojení si zapamatuje otisk a jiný certifikát odmítne).
- **Standardní adresy** BIP84 (Native SegWit, `bc1q…`) – peněženku obnovíte v jakékoli běžné peněžence (Sparrow, BlueWallet…).
- **Coin control** – ruční výběr mincí (UTXO) k utracení a jejich trvalé zmrazení.
- **Sledovací režim** (watch-only) – import zpub/ypub/xpub nebo deskriptoru, bez soukromých klíčů v telefonu; export veřejného deskriptoru.
- **Zabezpečení** – fráze zašifrovaná klíčem v Android Keystore, odemykání otiskem/PINem, **odeslání platby, zobrazení slov a smazání jen otiskem prstu**, automatické zamčení na pozadí i po 5 minutách nečinnosti, zákaz snímků obrazovky, anonymní klávesnice pro slova, kontrola zálohy.
- Odkazy na transakce vedou do **vašeho vlastního Mempoolu** (lze vypnout).
- Tmavý / světlý vzhled.

Aplikace nemá žádnou analytiku, reklamy ani telemetrii a nekomunikuje s nikým jiným než s vaším uzlem.

## Požadavky

- Android 11 nebo novější, nastavený zámek obrazovky (PIN / otisk).
- Vlastní Bitcoin uzel s Electrum serverem (např. RaspiBlitz, Umbrel, Start9 s Fulcrum nebo ElectRS) dostupný z telefonu (domácí síť nebo VPN).

## Sestavení

```bash
./gradlew assembleRelease
```

Bez podpisového klíče vznikne nepodepsané APK v `app/build/outputs/apk/release/`.
Pro podepsané sestavení vložte `release.jks` do kořene projektu a nastavte proměnnou `KS_PASS`.

## Ověření metody kostek

Slova z hodů si můžete ověřit nezávisle, např. v Pythonu (offline!):

```python
import hashlib
from mnemonic import Mnemonic          # pip install mnemonic
rolls = "3521661452..."                 # vaše hody (min. 99)
print(Mnemonic("english").to_mnemonic(hashlib.sha256(rolls.encode()).digest()))
```

## Licence

GNU General Public License v3.0 nebo novější – viz [`LICENSE`](LICENSE). Licence knihoven viz [`NOTICE.md`](NOTICE.md).

---

## English

> ⚠️ **Experimental, unaudited software.** Use with small amounts only. Provided "as is", without any warranty (see `LICENSE`). The user interface is currently in Czech.

**Features**

- **Seed from dice rolls only** – 24-word BIP39 mnemonic from 99+ rolls of a six-sided die. Entropy = SHA-256 of the roll string (characters `1`–`6`), the same method as Coldcard and SeedSigner, so results can be verified independently.
- **Your node only** – connects exclusively to your own Electrum server (Fulcrum / ElectRS). No default or fallback public servers; verifies the server is Bitcoin mainnet and **pins its TLS certificate** on first use.
- Standard **BIP84** (native SegWit) – restore in any common wallet.
- **Coin control** – pick UTXOs to spend and freeze coins persistently.
- **Watch-only mode** – import zpub/ypub/xpub or a descriptor; export your public descriptor.
- **Security** – seed encrypted with an Android Keystore key, biometric/PIN unlock, **fingerprint-only** confirmation of payments, seed reveal and wallet deletion, auto-lock (background and 5 min idle), screenshots blocked, incognito keyboard for seed words, backup verification.
- Transaction links open in **your own Mempool** instance (optional).
- No analytics, ads or telemetry.

**Build:** `./gradlew assembleRelease` (unsigned without the author's keystore).

**License:** GPL-3.0-or-later. Third-party licenses in [`NOTICE.md`](NOTICE.md).
