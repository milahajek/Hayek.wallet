# Zásady ochrany soukromí – Hayek Wallet

*Platné od 25. 11. 2026*

Hayek Wallet je bitcoinová peněženka s otevřeným zdrojovým kódem. **Vývojář aplikace nesbírá, neukládá ani nezpracovává žádné vaše údaje.** Aplikace nemá žádný vlastní server, účty, analytiku, reklamy ani telemetrii.

## Co zůstává jen ve vašem telefonu
- Obnovovací slova (seed), případné heslo navíc (passphrase) a přístupové údaje k vašemu Lightning uzlu – zašifrované klíčem v Android Keystore (pokud to telefon umí, v čipu StrongBox).
- Adresy, transakce, zůstatky, poznámky, značky mincí a kontakty.
- Systémové zálohy Androidu jsou pro aplikaci vypnuté. Nic z toho se neodesílá vývojáři ani nikomu jinému.

## Kam se aplikace připojuje
Aplikace se připojuje **jen tam, kam ji sami nasměrujete**:
- **Váš Electrum server** (vlastní uzel), nebo **veřejný Electrum server**, který si výslovně vyberete. Server se dozví adresy vaší peněženky, jejich zůstatky a historii a vaši IP adresu. Nikdy nedostane vaše slova ani klíče. U veřejného serveru na to aplikace před výběrem upozorní; jeho provozovatel je třetí strana, na kterou vývojář nemá vliv.
- **Váš Lightning uzel (LND)**, pokud si ho připojíte.
- **Odkazy na transakce** se otevírají v prohlížeči (ve vašem vlastním průzkumníku Mempool, pokud ho nastavíte).

Sama od sebe se aplikace nepřipojuje k žádnému serveru.

## Oprávnění
- **Síť:** spojení se serverem, který jste zvolili.
- **Fotoaparát:** čtení QR kódů. Obraz se zpracovává jen v telefonu a nikam se neodesílá.
- **NFC:** podepisování plateb hardwarovou peněženkou Coldcard.
- **Otisk prstu / zámek obrazovky:** odemykání a potvrzování plateb. Aplikace k otiskům nemá přístup, ověřuje je systém Android.
- **Oznámení:** upozornění na příchozí platbu (volitelné).

## Smazání údajů
Všechny údaje smažete v aplikaci (Nastavení → Smazat peněženku) nebo odinstalováním. Vývojář žádné údaje nemá, takže nemá co mazat.

## Děti
Aplikace není určena osobám mladším 18 let.

## Kontakt
Dotazy a hlášení chyb: https://github.com/milahajek/Hayek.wallet/issues

---

# Privacy Policy – Hayek Wallet

*Effective 25 November 2026*

Hayek Wallet is an open-source Bitcoin wallet. **The developer does not collect, store or process any of your data.** The app has no server of its own, no accounts, no analytics, no ads and no telemetry.

## What stays only on your phone
- Recovery words (seed), an optional passphrase and the credentials for your Lightning node – encrypted with a key in Android Keystore (in the StrongBox chip where the phone supports it).
- Addresses, transactions, balances, notes, coin tags and contacts.
- Android system backups are disabled for the app. None of this is sent to the developer or anyone else.

## Where the app connects
The app connects **only where you point it**:
- **Your own Electrum server** (your node), or a **public Electrum server** you explicitly choose. The server learns your wallet's addresses, their balances and history, and your IP address. It never receives your words or keys. The app warns you about this before you choose a public server; its operator is a third party the developer has no control over.
- **Your Lightning node (LND)**, if you connect one.
- **Transaction links** open in the browser (in your own Mempool explorer if you set one).

The app never connects to any server on its own.

## Permissions
- **Network:** connection to the server you chose.
- **Camera:** scanning QR codes. Images are processed on the phone only and never sent anywhere.
- **NFC:** signing payments with a Coldcard hardware wallet.
- **Fingerprint / screen lock:** unlocking and confirming payments. The app has no access to fingerprints; Android verifies them.
- **Notifications:** incoming payment alerts (optional).

## Deleting your data
Delete everything in the app (Settings → Delete wallet) or by uninstalling it. The developer holds no data, so there is nothing to delete on their side.

## Children
The app is not intended for anyone under 18.

## Contact
Questions and bug reports: https://github.com/milahajek/Hayek.wallet/issues
