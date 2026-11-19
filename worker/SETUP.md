# TijdBeeld API — Cloudflare Worker

Deze Worker verzorgt de openbare fotocatalogus en de afgeschermde beheer-API.
De oude GitHub-catalogus blijft als alleen-lezen terugvalbron beschikbaar totdat
de eerste publicatie via de nieuwe beheerfunctie plaatsvindt.

## Benodigde Cloudflare-instellingen

Maak twee KV-namespace's aan in Cloudflare:
- `TIJDBEELD_SESSIONS`
- `TIJDBEELD_CATALOG`

Maak daarna de Worker `tijdbeeld` aan en koppel de namespaces als bindings:
- binding `SESSIONS` → namespace `TIJDBEELD_SESSIONS`
- binding `CATALOG` → namespace `TIJDBEELD_CATALOG`

Stel de volgende Worker-variabelen/secrets in:
- `ADMIN_EMAIL` (tekstvariabele): `leeuw008@gmail.com`
- `ADMIN_PASSWORD_HASH` (secret): uitvoer van `node scripts/create-admin-password-hash.mjs`
- `RESEND_API_KEY` (secret): API-sleutel uit Resend

Plaats nooit het gewone wachtwoord in GitHub, de app of een bestand in de repository.
De hash-generator vraagt het wachtwoord interactief en toont alleen de PBKDF2-hash.

## API

- `GET /health` — statuscontrole.
- `POST /auth/login` — JSON `{ "email": "...", "password": "..." }`; geeft een willekeurige sessietoken terug.
- `POST /auth/logout` — beëindigt de huidige sessie.
- `POST /auth/forgot-password` — verstuurt een herstelcode via Resend naar het beheerdersadres.
- `POST /auth/reset-password` — controleert de code en stelt een nieuw wachtwoord in.
- `GET /photos` — openbare catalogus, zonder login.
- `POST /photos` — publiceert de catalogus; vereist `Authorization: Bearer <sessietoken>`.

Herstelcodes bestaan uit acht cijfers en zijn 15 minuten geldig. Na vijf onjuiste codes moet een nieuwe code worden aangevraagd. Na een geslaagde wachtwoordwijziging worden bestaande sessies ingetrokken. Het nieuwe wachtwoord wordt gehasht opgeslagen in KV; het gewone wachtwoord wordt nergens bewaard.

Beheersessies verlopen niet automatisch: de KV-records krijgen geen TTL en blijven geldig
tot expliciet uitloggen of een geslaagde wachtwoordreset. Mislukte inlogpogingen worden per
IP-adres tijdelijk begrensd. De Worker bewaart sessietokens alleen gehasht in KV. Het
wachtwoord wordt gecontroleerd met PBKDF2-HMAC-SHA-256 en een unieke salt.

## Nog uit te voeren vóór gebruik

1. Maak de KV-namespace's en Worker aan in Cloudflare.
2. Voeg de bindings en instellingen hierboven toe.
3. Stel het initiële wachtwoord in met de hash-generator en sla de uitvoer op als Worker-secret.
4. Voeg `RESEND_API_KEY` toe als Worker-secret. Maak de sleutel aan in Resend; gebruik geen sleutel in de Android-app of repository.
5. Controleer `https://tijdbeeld.leeuw008.workers.dev/health`.
6. Bouw en installeer daarna de Android-versie die deze API gebruikt.

De Worker-code staat in `worker/src/index.js`. Deze repository bevat geen Cloudflare-
accounttoken en kan de Worker dus niet zelfstandig in jouw account publiceren.
