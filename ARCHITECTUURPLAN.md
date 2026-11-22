# TijdBeeld — architectuurplan gebruikers- en beheerdersmodus

Status: ontwerp, nog geen functionele wijzigingen  
Basis: `feature/geospatial-anchor-prototype` op commit `7743bc941787302e2626d5a7aae9d2d72b6065ad`  
Nieuwe werkbranch: `feature/user-admin-modes`

## 1. Uitgangspunten

- Eén Android-app met een openbare gebruikersmodus en een afgeschermde beheerdersmodus.
- Bezoekers hoeven geen account te hebben.
- Alleen beheerders melden zich aan.
- De huidige werkende ARCore Geospatial-renderer blijft behouden en wordt niet onnodig herschreven.
- Bij ontbreken van ARCore Geospatial valt de app terug op een eenvoudige cameramodus met richting en afstand.
- De gebruiker kiest in de gebruikersmodus tussen een fotolijst en een topografische kaart.
- De fotolijst wordt op basis van de actuele gebruikerslocatie gesorteerd: dichtstbijzijnde eerst.
- De bestaande backupbranch `backup/werkende-geospatial-foto-2026-11-12` blijft onaangeroerd.

## 2. Scherm- en navigatiestructuur

### Startscherm
- Knop **Foto's bekijken** → gebruikersmodus.
- Afzonderlijke ingang **Beheerder** → aanmeldscherm.
- Geen beheerfuncties of beheersleutels in de openbare gebruikersinterface.

### Gebruikersmodus
- Keuze **Foto-overzicht** of **Topografische kaart**.
- Fotolijst: afstand tot de historische objectlocatie, oplopend gesorteerd.
- Kaart: alle gepubliceerde locaties als selecteerbare markeringen.
- Selectie van een foto opent de detailweergave en vervolgens de AR-/cameramodus.
- Zonder locatietoestemming blijven foto's en kaart bruikbaar; de lijst toont dan een neutrale sortering en vraagt om locatie-toestemming voor afstandssortering.

### AR-/cameramodus
1. Controleer of ARCore beschikbaar is en of `Config.GeospatialMode.ENABLED` wordt ondersteund.
2. Zo ja: start de bestaande Geospatial-implementatie.
3. Zo nee: open de cameramodus zonder ARCore. Gebruik GPS en kompas voor een richtingspijl en afstand; presenteer dit nadrukkelijk niet als een geografisch verankerde AR-foto.

### Beheerdersmodus
- Afgeschermde aanmelding.
- Foto's toevoegen, wijzigen, verwijderen en publiceren/depubliceren.
- Afbeeldings-URL, titel/bijschrift, objectcoördinaten, opname-/kijkpositie en afbeeldingsbreedte beheren.
- Kaart gebruiken om coördinaten te kiezen.
- Beheeracties worden door de server geautoriseerd.

## 3. Voorgesteld gegevensmodel

De publieke catalogus bevat uitsluitend gepubliceerde foto's. Afstand en sortering zijn gebruikersspecifiek en worden lokaal berekend; ze worden dus niet als vaste eigenschap in de catalogus opgeslagen.

Voorbeeldrecord:

```json
{
  "id": "uuid",
  "title": "1910 - Kantongerechtsgebouw",
  "imageUrl": "https://…",
  "object": {
    "latitude": 52.518349,
    "longitude": 6.420889,
    "altitudeMeters": null
  },
  "viewpoint": {
    "latitude": 52.518263,
    "longitude": 6.421917
  },
  "imageWidthMeters": 3.0,
  "published": true,
  "updatedAt": "2026-11-13T00:00:00Z"
}
```

Afspraken:
- `id` is stabiel en uniek; selecties verwijzen niet uitsluitend naar een lijstindex.
- `object` is de geografische positie van het historische object/anker.
- `viewpoint` is de positie van waaruit de historische foto is genomen en helpt bij de oriëntatie.
- `altitudeMeters` blijft optioneel totdat de hoogte-invoer en het referentiesysteem zijn vastgesteld.
- `published=false` betekent dat het record niet in de openbare catalogus verschijnt.
- De bestaande velden `objectLat`, `objectLng`, `cameraLat`, `cameraLng` en `imageWidthMeters` worden bij migratie naar dit model behouden en gecontroleerd om dataverlies te voorkomen.

## 4. Backend en beveiliging

- Publieke catalogus: alleen-lezen endpoint, zonder login.
- Beheer-API: aparte endpoints voor aanmelden en beheeracties.
- De server controleert bij iedere beheeractie of de sessie/token een beheerder vertegenwoordigt.
- Geen permanente beheersleutel of wachtwoord in de APK, broncode of publieke catalogus.
- Wachtwoorden worden niet als leesbare tekst opgeslagen; sessies/tokens krijgen vervaldatum en kunnen worden ingetrokken.
- Bestaande Worker-publicatie en GitHub JSON-catalogus worden eerst onderzocht en daarna gecontroleerd gemigreerd; er wordt niets verwijderd voordat de nieuwe route getest is.

De keuze voor de concrete authenticatieprovider en opslag (bijvoorbeeld Cloudflare Worker met passende opslag) wordt vóór implementatie vastgelegd. Een gedeelde geheime sleutel die in de app is ingebouwd, geldt niet als veilige beheerderslogin.

## 5. Ontwikkel- en testvolgorde

1. Startscherm en gescheiden navigatie; huidige AR-activiteit blijft intact.
2. Publieke fotolijst en afstandssortering, inclusief toestemming geweigerd en GPS niet beschikbaar.
3. Topografische kaart met dezelfde gepubliceerde catalogus.
4. Beheerderslogin en serverzijdige autorisatie.
5. Beheerfuncties koppelen aan de centrale catalogus.
6. Geospatial-compatibiliteitscontrole en eenvoudige cameraterugval.
7. Regressietest op de huidige ondersteunde telefoon en afzonderlijke test op een toestel zonder Geospatial.

## 6. Beschermingsregels

- Niet werken op `main` of op de backupbranch.
- Deze featurebranch is de enige werkbranch voor deze herbouw.
- Eerst kleine, afzonderlijke commits; na iedere relevante stap een Android-build controleren.
- De bestaande Geospatial-werking moet aantoonbaar behouden blijven voordat onderdelen worden samengevoegd.
- Geen migratie of verwijdering van bestaande catalogusdata zonder gecontroleerde kopie en terugvalmogelijkheid.

## 7. Nog te bevestigen vóór backend-implementatie

- Of er één beheerder is of meerdere beheerders.
- Of historische afbeeldingsbestanden op hun huidige externe locaties blijven staan of naar centrale objectopslag verhuizen.
- Welke concrete Cloudflare-opslag en authenticatievoorziening wordt gebruikt.
