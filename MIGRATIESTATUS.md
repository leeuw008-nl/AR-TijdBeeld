# Migratiestatus AR-TijdBeeld

Deze repository is opgebouwd uit de gekozen broncodebasis van `TijdBeeld` (branch `feature/user-admin-modes`, commit `e74107698cad6dd47dbc801717d7916ed2df9e23`).

## Overgezet
- Android-broncode, Gradle-configuratie en GitHub Actions-workflows.
- Historische bronbestanden en bestaande Ommerschans-modelstudies.
- Het GLB-model als `app/src/main/assets/ommerschans.glb`, afkomstig uit `feature/ommerschans-glb-ar`.
- Fotocatalogus en Worker-configuratie uit `TijdBeeld-data`, onder `catalog-data/`.

## Nog niet overgezet
- `app-debug.apk` (4,258,828 bytes).
- `historische-bronnen/ommerschans/Hoofdgebouw-Ommerschans.pdf` (4,205,896 bytes).

De GitHub-bestandskoppeling levert voor deze twee grote binaire bestanden geen inhoud terug. Ze blijven daarom voorlopig veilig in de oude repository staan:
https://github.com/leeuw008-nl/TijdBeeld/tree/feature/user-admin-modes

De oude repositories mogen pas worden verwijderd nadat deze twee bestanden alsnog zijn overgezet en de nieuwe Android-build succesvol is gecontroleerd.
