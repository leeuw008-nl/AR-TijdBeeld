# TijdBeeld

**Het verleden terug op zijn oorspronkelijke plek.**

TijdBeeld is een zelfstandige Android-app die historische foto's op hun oorspronkelijke geografische locatie als ruimtelijke AR-beelden wil tonen.

## Eerste prototype

- Eerste testlocatie: Kantongerechtsgebouw, Ommen.
- Gebouwlocatie: `52.518349, 6.420889`.
- Geschatte fotografenpositie: `52.518263, 6.421917`.
- Berekende kijkrichting: circa `278°`.
- Historische foto en onderschrift vormen samen één AR-object.
- Onderschrift onder de foto: `1910 - Kantongerechtsgebouw`.

## Technische uitgangspunten

- Native Android-app; geen PWA en geen koppeling met andere Ommen-projecten.
- Kotlin en ARCore.
- ARCore is optioneel gedeclareerd, zodat de app ook netjes kan starten op een toestel zonder ARCore-ondersteuning.
- De app controleert tijdens runtime of ARCore beschikbaar is.
- GPS/kompas zijn niet nauwkeurig genoeg voor lokale tracking. ARCore verzorgt de lokale tracking; geografische uitlijning vraagt aparte kalibratie.

## Status

Startrepository. De eerste stap is een reproduceerbare Android-build en een controle van ARCore-beschikbaarheid op het testtoestel. Daarna volgen de cameraview en de eerste verankerde foto.


## Geographic AR prototype

The first geographic placement prototype uses device GPS and compass heading to place the Kantongerechtsgebouw image as a distant AR display. The display is intentionally enlarged for visibility during initial testing. GPS/compass alignment is approximate and must be calibrated on site; this is not yet a surveyed or VPS-anchored placement.


## Photo administration prototype

The launcher opens a local administration screen. Each photo has its own editable record with a thumbnail, caption, image URL, object coordinates and photographer-position coordinates. Both map pins can be dragged on an OpenStreetMap-based map; the numeric fields and pins stay synchronized. Records are stored locally on the device. The camera-test action opens the AR camera with the selected photo and object coordinates. The photographer position is retained as calibration metadata for the photo record.

## Deployment configuration check
This documentation-only commit verifies the Cloudflare Pages/Workers Git integration for the `feature/user-admin-modes` production branch. It does not change application or Worker behavior.
