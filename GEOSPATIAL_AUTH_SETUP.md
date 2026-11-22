# ARCore Geospatial-autorisatie voor AR-TijdBeeld

De melding `Earth=ERROR_NOT_AUTHORIZED` is geen GPS-nauwkeurigheidsprobleem. ARCore weigert de autorisatie van de app. Google noemt hiervoor met name: ARCore API niet ingeschakeld, een ongeldige of ontbrekende API-key, een API-key die de app niet toestaat, of een onjuiste OAuth-client bij keyless-authenticatie.

## Configuratie die bij deze repository hoort

- Android application ID: `nl.leeuw008.tijdbeeld.diagnose32stable`
- Manifestmetadata: `com.google.android.ar.API_KEY`
- GitHub Actions secret dat in de manifest-placeholder wordt gezet: `ARCORE_API_KEY`
- Verwachte SHA-1 van de blijvende Android-ondertekeningssleutel: `1C:E6:0A:16:73:FA:7B:5A:1F:5E:E3:D7:C6:DE:42:60:07:80:6D:3A`

De workflowstap **Restore original Android signing key** schrijft de werkelijke SHA-1 ook naar de buildlog. Gebruik bij twijfel de SHA-1 uit de nieuwste succesvolle buildlog; die is leidend.

## Controle in Google Cloud

1. Open [Google Cloud – API's en services – Bibliotheek](https://console.cloud.google.com/apis/library/arcore.googleapis.com) en controleer dat **ARCore API** is ingeschakeld in hetzelfde project als de API-key.
2. Open [Google Cloud – API's en services – Referenties](https://console.cloud.google.com/apis/credentials) en open de API-key die als GitHub-secret `ARCORE_API_KEY` is opgeslagen.
3. Als de key een Android-applicatiebeperking heeft, voeg exact deze combinatie toe:
   - Pakketnaam: `nl.leeuw008.tijdbeeld.diagnose32stable`
   - SHA-1: `1C:E6:0A:16:73:FA:7B:5A:1F:5E:E3:D7:C6:DE:42:60:07:80:6D:3A`
4. Controleer bij **API-beperkingen** dat **ARCore API** is toegestaan.
5. Sla de wijziging op. Bouw daarna opnieuw met de workflow; de key blijft uitsluitend in GitHub Secrets en wordt niet in de repository opgeslagen.

## Belangrijk over build 231

Build 231 is met een andere tijdelijke signing-certificaat gebouwd. De huidige workflow gebruikt de blijvende signing key. Daardoor kan Android een nieuw APK-bestand weigeren als update over build 231. Maak eerst via **Beheer → Back-up maken / importeren** een back-up. Als Android een handtekeningconflict meldt, moet de oude app na controle van die back-up eenmalig worden verwijderd voordat de nieuwe APK kan worden geïnstalleerd. De back-up moet vóór verwijderen zijn gemaakt.

## Betekenis van de fout

- `ERROR_NOT_AUTHORIZED`: autorisatie/configuratie in Google Cloud of signing-identiteit klopt niet.
- `PAUSED/STOPPED`: Earth-tracking is daardoor niet actief; dit wordt niet opgelost door alleen GPS opnieuw op te vragen.
- Een succesvolle build bewijst alleen dat de key in de APK is opgenomen, niet dat Google Cloud die key voor dit pakket en certificaat accepteert.

Bron: [ARCore – Earth state](https://developers.google.com/ar/reference/c/group/ar-earth) en [ARCore – Geospatial API inschakelen](https://developers.google.com/ar/develop/java/geospatial/enable).
