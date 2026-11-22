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
- Build 146, 147 en 148 lazen de bestaande keystore uit de secrets en hadden allemaal certificaat SHA-1:
  `1ce60a1673fa7b5a1f5ee3d7c6de426007806d3a`
- Build 231 (commit `c3cc4ff90298469986515cfb870b4910ec552772`) was ondertekend met een andere certificaat SHA-1:
  `2459666eaa0479748efd255bcb705a488c8caeca`
- Conclusie: de huidige `ANDROID_KEYSTORE_BASE64` is de sleutel van builds 146–148, niet van build 231. Deze bestaande keystore kan build 231 dus niet bijwerken.
- De build231-sleutel is in die workflow-run nieuw gegenereerd; de private sleutel is niet in de APK opgenomen. Er is geen bevestiging dat die private sleutel elders is bewaard. Zonder de oorspronkelijke private sleutel kan Android build 231 niet in-place worden bijgewerkt door een anders ondertekende APK.
- Build 239 gebruikte opnieuw een nieuwe diagnostische debug-keystore (SHA-1 `3ab19be147dc97b092b3f57b146bb514992efb9e`), dus die APK mag niet over build 231 worden geïnstalleerd.
- Build 239 compileerde succesvol en leverde een APK-artifact op; de emulator-installatietest faalde tijdens ADB/emulatorstart en bewees dus geen update-compatibiliteit.
- Op 22 november 2026 zijn `app/build.gradle.kts` en `.github/workflows/android-build.yml` aangepast om de bestaande secret-keystore te decoderen en te gebruiken. De workflow controleert SHA-1 tegen build 231 en stopt veilig bij verschil.
- Build 241 (`45046867134`) bevestigde dat de secret-keystore SHA-1 `1ce60a1673fa7b5a1f5ee3d7c6de426007806d3a` heeft. De workflow stopte terecht vóór compilatie omdat dit niet overeenkomt met build 231.
- Signing-correctiecommits:
  - `6a60d5347dec736241ceee6d2b2b5c6635d400a9` — Gradle signing-config
  - `1afeeb0c01f7de7b55a14fe923fb530c8063326d` — workflow gebruikt bestaande secret-keystore
- Veilig vervolg: óf de echte oorspronkelijke build231-keystore terugvinden (als die nog ergens als JKS/keystore-bestand bestaat), óf een eenmalige schone installatie voorbereiden met backup/export van appgegevens, verwijderen van build 231 en installatie van een build die met de bestaande secret-keystore is ondertekend. Laat de gebruiker niet build 239 of een andere afwijkend ondertekende APK over build 231 installeren.
- Als een nieuwe APK wordt gebouwd met de bestaande secrets, controleer dat de APK SHA-1 exact `1ce60a1673fa7b5a1f5ee3d7c6de426007806d3a` is en label hem niet als update voor build 231.

## App-wijzigingen in recente werkbranch
Laatste inhoudelijke wijzigingscommit vóór de signing-correctie:
- `7f4c93a07c305120dbf423abf16258e3cd9be82` — verbeterde foto-volgorde, sessie-refresh en 3D-orbitbediening.
- Versienummer alleen rechts in de kop van de Startpagina; verwijderd uit andere schermkoppen.
- Ommerschans-foto wordt onderaan de fotolijst geplaatst; overige foto's op afstand gesorteerd als locatie beschikbaar is.
- Beheer-sessie wordt bij terugkeer naar Beheer opnieuw gevalideerd/ververst; lokale vervaldatum wordt 30 dagen vooruit gezet.
- 3D-preview heeft schermknoppen voor draaien, zoomen en reset; toetsenbord WASD/pijltjes en plus/min; touch-draaien, pinch-zoom en pannen blijven behouden.
- Deze functies moeten in een nieuwe build opnieuw worden gecontroleerd.

## Werkwijze / communicatie
- Controleer dit werklogboek vóór vragen stellen of wijzigingen uitvoeren.
- Vraag niet opnieuw naar de vier Android signing secrets: de gebruiker heeft bevestigd dat ze alle vier bestaan.
- Eén concrete stap of vraag tegelijk; geen herhaling van al beantwoorde vragen.
- Geen screenshots vragen als logs of repository-inspectie volstaan.
- Geen installatieadvies geven voordat signing-certificaat en buildstatus zijn gecontroleerd.
- Bescherm bestaande functies; voer geen onnodige reset of branch-terugzetting uit.
- Rapporteer alleen uitgevoerde en gecontroleerde resultaten; geen ongefundeerde succesclaims.
- Houd het logboek na iedere relevante wijziging bij.


## 22 november 2026 — vervolg na installatie build 231
- Gebruiker heeft build 231 daadwerkelijk geïnstalleerd.
- De vier gevraagde UI/functionaliteitswijzigingen zijn al aanwezig in commit 7f4c93a: versie rechts in Start-header; Ommerschans onderaan; sessie-refresh; 3D orbit/zoom/touch/toetsen.
- Geospatial-herstel: manifestmetadata `com.google.android.ar.API_KEY` opnieuw toegevoegd; Gradle injecteert `ARCORE_API_KEY` vanuit de bestaande GitHub Secret.
- Android-workflow geeft `ARCORE_API_KEY` nu mee aan Gradle en controleert dat de secret aanwezig is.
- Build 231 is met een tijdelijke, niet-bewaarde sleutel ondertekend (SHA-1 2459666eaa0479748efd255bcb705a488c8caeca). Bestaande persistent signing secret heeft SHA-1 1ce60a1673fa7b5a1f5ee3d7c6de426007806d3a. Een update over build 231 is dus technisch onmogelijk zonder de oorspronkelijke private sleutel.
- Workflow gebruikt voortaan de bestaande persistent signing secret en doet geen onjuiste claim dat de nieuwe APK over build 231 kan installeren. De gebruiker zal vóór overstap appgegevens moeten veiligstellen en eenmalig schoon installeren.
- Laatste commits: manifest 35ecf8c; Gradle 4ab7a5c; workflow 2a3760c.
- Nog te controleren: Android workflowresultaat, APK-signature, ARCore Earth status op toestel (moet ENABLED zijn). Codewijziging alleen bewijst nog geen werkende Google Cloud API-keyrestrictie.
