# Werklog AR-TijdBeeld

Laatste bijgewerkt: 22 november 2026

Dit bestand is het vaste projectlogboek. Raadpleeg het vóór iedere nieuwe werksessie en werk het na iedere relevante wijziging bij. Stel geen vragen opnieuw als het antwoord hier al staat. Vraag nooit om geheime waarden; noteer uitsluitend secretnamen en bevestigde aanwezigheid.

## Repository en werkbranch
- Repository: https://github.com/leeuw008-nl/AR-TijdBeeld
- Actieve werkbranch: `release/werkende-versie-alle-6-punten`
- Live app-project: AR-TijdBeeld (Android).

## Android signing — kritiek
- De gebruiker heeft bevestigd dat de volgende GitHub Repository Secrets bestaan:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- Ook aanwezig volgens de getoonde GitHub Secrets-lijst:
  - `ARCORE_API_KEY`
  - `CLOUDFLARE_API_TOKEN`
- Deel of vraag nooit om de waarden van secrets in chat.
- Build 231 (commit `c3cc4ff90298469986515cfb870b4910ec552772`) was ondertekend met certificaat SHA-1:
  `2459666eaa0479748efd255bcb705a488c8caeca`
- De private signing key kan niet uit een APK worden teruggewonnen. De bestaande `ANDROID_KEYSTORE_BASE64` is de aangewezen bron om de oorspronkelijke signing key terug te gebruiken.
- Build 239 gebruikte per ongeluk een nieuwe diagnostische debug-keystore (SHA-1 `3ab19be147dc97b092b3f57b146bb514992efb9e`), dus die APK mag niet over build 231 worden geïnstalleerd.
- Build 239 compileerde succesvol en leverde een APK-artifact op; de emulator-installatietest faalde tijdens ADB/emulatorstart en bewees dus geen update-compatibiliteit.
- Op 22 november 2026 zijn de bestanden `app/build.gradle.kts` en `.github/workflows/android-build.yml` aangepast om de bestaande keystore te decoderen en te gebruiken. De workflow controleert vooraf of de SHA-1 exact overeenkomt met build 231; bij afwijking stopt de workflow vóór het maken van een APK.
- Nieuwe commits:
  - `6a60d5347dec736241ceee6d2b2b5c6635d400a9` — Gradle signing-config
  - `1afeeb0c01f7de7b55a14fe923fb530c8063326d` — workflow gebruikt originele keystore
- Eerstvolgende controle: bekijk de nieuwe Android build-run. Vereist: SHA-1-check slaagt en APK-certificaat SHA-1 is exact `2459666eaa0479748efd255bcb705a488c8caeca`. Pas daarna mag de APK als update voor build 231 worden aangeboden.
- Als SHA-1 klopt maar emulator-test faalt, onderscheid dan emulator/ADB-probleem van een echte signature mismatch. Geen herinstallatie adviseren voordat dit is vastgesteld.

## App-wijzigingen in recente werkbranch
Laatste inhoudelijke wijzigingscommit vóór de signing-correctie:
- `7f4c93a07c305120dbf423abf16258e3cd9be82` — verbeterde foto-volgorde, sessie-refresh en 3D-orbitbediening.
- Versienummer alleen rechts in de kop van de Startpagina; verwijderd uit andere schermkoppen.
- Ommerschans-foto wordt onderaan de fotolijst geplaatst; overige foto's op afstand gesorteerd als locatie beschikbaar is.
- Beheer-sessie wordt bij terugkeer naar Beheer opnieuw gevalideerd/ververst; lokale vervaldatum wordt 30 dagen vooruit gezet.
- 3D-preview heeft schermknoppen voor draaien, zoomen en reset; toetsenbord WASD/pijltjes en plus/min; touch-draaien, pinch-zoom en pannen blijven behouden.
- Deze functies moeten in de nieuwe build opnieuw worden gecontroleerd.

## Werkwijze / communicatie
- Controleer dit werklogboek vóór vragen stellen of wijzigingen uitvoeren.
- Vraag niet opnieuw naar de vier Android signing secrets: de gebruiker heeft bevestigd dat ze alle vier bestaan.
- Eén concrete stap of vraag tegelijk; geen herhaling van al beantwoorde vragen.
- Geen screenshots vragen als logs of repository-inspectie volstaan.
- Geen installatieadvies geven voordat signing-certificaat en buildstatus zijn gecontroleerd.
- Bescherm bestaande functies; voer geen onnodige reset of branch-terugzetting uit.
- Rapporteer alleen uitgevoerde en gecontroleerde resultaten; geen ongefundeerde succesclaims.
- Houd het logboek na iedere relevante wijziging bij.
