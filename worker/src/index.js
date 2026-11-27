const LOGIN_WINDOW_SECONDS = 15 * 60;
const MAX_LOGIN_ATTEMPTS = 10;
const RESET_TTL_SECONDS = 15 * 60;
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const RESEND_API_URL = "https://api.resend.com/emails";
const RESET_FROM = "TijdBeeld <noreply@tijdbeeld.leeuw008.nl>";
const LEGACY_CATALOG_URL =
  "https://raw.githubusercontent.com/leeuw008-nl/TijdBeeld-data/main/photos.json";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    },
  });
}

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function bytesToBase64(value) {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function constantTimeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function hashPassword(password, onStage = () => {}) {
  // Cloudflare Workers currently rejects PBKDF2 iteration counts above 100,000.
  const iterations = 100000;
  onStage("hash-new-password-generate-salt");
  const salt = crypto.getRandomValues(new Uint8Array(16));
  onStage("hash-new-password-import-key");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  onStage("hash-new-password-derive-bits");
  const derived = new Uint8Array(await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key, 256
  ));
  onStage("hash-new-password-encode");
  return "pbkdf2$" + iterations + "$" + bytesToBase64(salt) + "$" + bytesToBase64(derived);
}

async function verifyPassword(password, encoded) {
  // Format: pbkdf2$iterations$salt-base64$derived-key-base64
  const parts = encoded.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 100000 || iterations > 1000000) return false;
  try {
    const key = await crypto.subtle.importKey(
      "raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]
    );
    const derived = new Uint8Array(await crypto.subtle.deriveBits(
      { name: "PBKDF2", salt: base64ToBytes(parts[2]), iterations, hash: "SHA-256" },
      key,
      base64ToBytes(parts[3]).length * 8
    ));
    return constantTimeEqual(derived, base64ToBytes(parts[3]));
  } catch {
    return false;
  }
}

function catalogValidationError(value) {
  if (!value || ![1, 2].includes(value.schemaVersion) || !Array.isArray(value.photos) || value.photos.length > 2000) {
    return "catalogusheader of fotolijst ontbreekt of is ongeldig";
  }
  for (let index = 0; index < value.photos.length; index++) {
    const photo = value.photos[index];
    const label = photo?.title || photo?.id || `object ${index + 1}`;
    if (!photo || typeof photo.id !== "string" || !photo.id ||
        typeof photo.title !== "string" || !photo.title ||
        !Number.isFinite(photo.objectLat) || photo.objectLat < -90 || photo.objectLat > 90 ||
        !Number.isFinite(photo.objectLng) || photo.objectLng < -180 || photo.objectLng > 180) {
      return `"${label}": titel, object-ID of GPS-positie van het object ontbreekt of is ongeldig`;
    }
    const type = photo.type || "PHOTO";
    if (type === "MODEL") {
      if (!(typeof photo.modelUrl === "string" && photo.modelUrl.startsWith("https://")) &&
          !(typeof photo.modelAsset === "string" && /^[a-zA-Z0-9._-]+\\.glb$/i.test(photo.modelAsset))) {
        return `"${label}": geldige GLB-URL of modelbestandsnaam ontbreekt`;
      }
      if (!["OBJECT", "TEST"].includes(photo.displayLocation || "OBJECT")) {
        return `"${label}": weergavelocatie moet OBJECT of TEST zijn`;
      }
      if ((photo.displayLocation || "OBJECT") === "TEST" &&
          (!Number.isFinite(photo.testLat) || photo.testLat < -90 || photo.testLat > 90 ||
           !Number.isFinite(photo.testLng) || photo.testLng < -180 || photo.testLng > 180)) {
        return `"${label}": de GPS-positie van de 3D-model-testlocatie ontbreekt of is ongeldig`;
      }
      continue;
    }
    if (type !== "PHOTO" ||
        typeof photo.imageUrl !== "string" || !photo.imageUrl.startsWith("https://") ||
        !Number.isFinite(photo.cameraLat) || photo.cameraLat < -90 || photo.cameraLat > 90 ||
        !Number.isFinite(photo.cameraLng) || photo.cameraLng < -180 || photo.cameraLng > 180) {
      return `"${label}": foto-URL, type of GPS-positie van de fotograaf ontbreekt of is ongeldig`;
    }
  }
  return null;
}

function validCatalog(value) {
  return catalogValidationError(value) === null;

async function getSession(request, env) {
  const authorization = request.headers.get("Authorization") || "";
  const match = authorization.match(/^Bearer ([A-Za-z0-9_-]{40,})$/);
  if (!match) return null;
  const key = "session:" + await sha256Hex(match[1]);
  const session = await env.SESSIONS.get(key, "json");
  if (!session || session.email !== env.ADMIN_EMAIL) return null;
  // Accept existing legacy tokens once; keep-session upgrades them to a renewable TTL.
  if (Number.isFinite(session.expiresAt) && session.expiresAt <= Date.now()) {
    await env.SESSIONS.delete(key);
    return null;
  }
  return { key, session };
}

async function handle(request, env) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  }});

  if (url.pathname === "/health" && request.method === "GET") {
    return json({ ok: true, service: "tijdbeeld-api" });
  }

  // Temporary, non-secret configuration diagnostics for production troubleshooting.
  if (url.pathname === "/_diagnostics" && request.method === "GET") {
    let kvHash = null;
    let sessionsReadOk = false;
    try {
      sessionsReadOk = Boolean(env.SESSIONS);
      if (env.SESSIONS) kvHash = await env.SESSIONS.get("admin-password-hash");
      sessionsReadOk = Boolean(env.SESSIONS);
    } catch {
      sessionsReadOk = false;
    }
    const selectedHash = kvHash || env.ADMIN_PASSWORD_HASH || "";
    const hashParts = selectedHash.split("$");
    const iterations = hashParts.length === 4 ? Number(hashParts[1]) : null;
    return json({
      diagnostic: true,
      sessionsBindingPresent: Boolean(env.SESSIONS),
      sessionsReadOk,
      adminEmailConfigured: Boolean(env.ADMIN_EMAIL),
      adminPasswordHashConfigured: Boolean(env.ADMIN_PASSWORD_HASH),
      resendApiKeyConfigured: Boolean(env.RESEND_API_KEY),
      kvPasswordHashPresent: Boolean(kvHash),
      selectedHashSource: kvHash ? "KV" : (env.ADMIN_PASSWORD_HASH ? "ADMIN_PASSWORD_HASH" : "none"),
      selectedHashFormatValid: hashParts.length === 4 && hashParts[0] === "pbkdf2" &&
        Number.isInteger(iterations) && iterations >= 100000 && iterations <= 1000000,
      selectedHashIterations: Number.isInteger(iterations) ? iterations : null
    });
  }

  if (url.pathname === "/auth/login" && request.method === "POST") {
    let diagnosticStage = "check-login-configuration";
    try {
      if (!env.SESSIONS || !env.ADMIN_EMAIL) {
        return json({ error: "De beheerlogin is nog niet ingesteld." }, 503);
      }
      diagnosticStage = "read-client-ip";
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const attemptKey = "login-attempt:" + await sha256Hex(ip);
      diagnosticStage = "read-login-attempt-counter";
      const attempts = Number(await env.SESSIONS.get(attemptKey) || "0");
      if (attempts >= MAX_LOGIN_ATTEMPTS) {
        return json({ error: "Te veel inlogpogingen. Probeer het over 15 minuten opnieuw." }, 429);
      }
      diagnosticStage = "read-request-body";
      let body;
      try { body = await request.json(); } catch { return json({ error: "Ongeldige aanvraag." }, 400); }
      const email = String(body.email || "").trim().toLowerCase();
      const password = String(body.password || "");
      const emailMatches = email === String(env.ADMIN_EMAIL).trim().toLowerCase();
      diagnosticStage = "read-password-hash";
      const kvHash = await env.SESSIONS.get("admin-password-hash");
      const storedHash = kvHash || env.ADMIN_PASSWORD_HASH;
      if (!storedHash) return json({ error: "De beheerlogin is nog niet ingesteld." }, 503);
      diagnosticStage = "verify-password";
      const passwordMatches = await verifyPassword(password, storedHash);
      console.info("TijdBeeld login check", { emailMatches, hashPresent: Boolean(storedHash), hashSource: kvHash ? "KV" : "ADMIN_PASSWORD_HASH", hashFormatValid: Boolean(storedHash && storedHash.startsWith("pbkdf2$")), passwordMatches });
      if (!emailMatches || !passwordMatches) {
        diagnosticStage = "record-failed-login";
        await env.SESSIONS.put(attemptKey, String(attempts + 1), { expirationTtl: LOGIN_WINDOW_SECONDS });
        return json({ error: "E-mailadres of wachtwoord is niet juist." }, 401);
      }
      diagnosticStage = "clear-failed-login-counter";
      await env.SESSIONS.delete(attemptKey);
      diagnosticStage = "generate-session-token";
      const random = crypto.getRandomValues(new Uint8Array(32));
      const token = Array.from(random, (b) => b.toString(16).padStart(2, "0")).join("");
      diagnosticStage = "hash-session-token";
      const tokenKey = "session:" + await sha256Hex(token);
      diagnosticStage = "write-session";
      // Keep the administrator session valid until explicit logout or password reset.
      const expiresAt = Date.now() + SESSION_TTL_SECONDS * 1000;
      await env.SESSIONS.put(tokenKey, JSON.stringify({
        email: env.ADMIN_EMAIL,
        createdAt: new Date().toISOString(),
        expiresAt,
      }), { expirationTtl: SESSION_TTL_SECONDS });
      diagnosticStage = "complete";
      return json({ success: true, token, expiresIn: SESSION_TTL_SECONDS });
    } catch (error) {
      console.error("TijdBeeld login diagnostic", {
        stage: diagnosticStage,
        errorName: error instanceof Error ? error.name : "UnknownError",
        errorMessage: error instanceof Error ? error.message : String(error)
      });
      const diagnosticReason = error instanceof Error
        ? error.name + ": " + error.message
        : String(error);
      return json({
        error: "Inloggen is mislukt.",
        diagnosticStage: diagnosticStage + ": " + diagnosticReason.slice(0, 180)
      }, 500);
    }
  }

  if (url.pathname === "/auth/forgot-password" && request.method === "POST") {
    if (!env.SESSIONS || !env.ADMIN_EMAIL || !env.RESEND_API_KEY) {
      return json({ error: "Wachtwoordherstel is nog niet ingesteld." }, 503);
    }
    let diagnosticStage = "request-body";
    try {
    let body;
    try { body = await request.json(); } catch { return json({ error: "Ongeldige aanvraag." }, 400); }
    const email = String(body.email || "").trim().toLowerCase();
    if (!email || email.length > 254) return json({ error: "Vul een geldig e-mailadres in." }, 400);
    if (email === String(env.ADMIN_EMAIL).trim().toLowerCase()) {
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const limitKey = "reset-attempt:" + await sha256Hex(ip);
      diagnosticStage = "read-reset-rate-limit";
      const attempts = Number(await env.SESSIONS.get(limitKey) || "0");
      if (attempts < 5) {
        diagnosticStage = "write-reset-rate-limit";
        await env.SESSIONS.put(limitKey, String(attempts + 1), { expirationTtl: RESET_TTL_SECONDS });
        const code = Array.from(crypto.getRandomValues(new Uint8Array(8))).map((b) => (b % 10).toString()).join("");
        const codeHash = await sha256Hex(email + ":" + code);
        const resetKey = "password-reset:" + await sha256Hex(email);
        diagnosticStage = "save-reset-code";
        await env.SESSIONS.put(resetKey, JSON.stringify({ codeHash, createdAt: Date.now() }), { expirationTtl: RESET_TTL_SECONDS });
        diagnosticStage = "resend-request";
        const mail = await fetch(RESEND_API_URL, {
          method: "POST",
          headers: { "Authorization": "Bearer " + env.RESEND_API_KEY, "Content-Type": "application/json" },
          body: JSON.stringify({
            from: RESET_FROM, to: [email], subject: "TijdBeeld – wachtwoord herstellen",
            text: "Je TijdBeeld-herstelcode is: " + code + "\n\nDe code is 15 minuten geldig. Heb je deze aanvraag niet gedaan, negeer deze e-mail."
          })
        });
        if (!mail.ok) {
          diagnosticStage = "resend-rejected-http-" + mail.status;
          await env.SESSIONS.delete(resetKey);
          return json({ error: "De herstelmail kon niet worden verstuurd. Probeer het later opnieuw." }, 502);
        }
      }
    }
    diagnosticStage = "complete";
    return json({ success: true, message: "Als dit het beheerdersadres is, is een herstelcode per e-mail verstuurd." });
    } catch (error) {
      console.error("TijdBeeld forgot-password diagnostic", {
        stage: diagnosticStage,
        errorName: error instanceof Error ? error.name : "UnknownError"
      });
      return json({ error: "Wachtwoordherstel is mislukt.", diagnosticStage }, 500);
    }
  }

  if (url.pathname === "/auth/reset-password" && request.method === "POST") {
    if (!env.SESSIONS || !env.ADMIN_EMAIL) return json({ error: "Wachtwoordherstel is nog niet ingesteld." }, 503);
    let diagnosticStage = "request-body";
    try {
      let body;
      try { body = await request.json(); } catch { return json({ error: "Ongeldige aanvraag." }, 400); }
      const email = String(body.email || "").trim().toLowerCase();
      const code = String(body.code || "").trim();
      const password = String(body.password || "");
      if (email !== String(env.ADMIN_EMAIL).trim().toLowerCase()) return json({ error: "Herstelcode of e-mailadres is niet juist." }, 400);
      if (!/^\d{8}$/.test(code)) return json({ error: "Vul de herstelcode van 8 cijfers in." }, 400);
      if (password.length < 14) return json({ error: "Kies een nieuw wachtwoord van minimaal 14 tekens." }, 400);
      diagnosticStage = "read-reset-code";
      const resetKey = "password-reset:" + await sha256Hex(email);
      const reset = await env.SESSIONS.get(resetKey, "json");
      if (!reset || Date.now() - reset.createdAt > RESET_TTL_SECONDS * 1000) {
        return json({ error: "De herstelcode is verlopen. Vraag een nieuwe code aan." }, 400);
      }
      diagnosticStage = "verify-reset-code";
      const submittedHash = await sha256Hex(email + ":" + code);
      if (!constantTimeEqual(submittedHash, reset.codeHash)) {
        const failuresKey = resetKey + ":failures";
        diagnosticStage = "read-reset-failures";
        const failures = Number(await env.SESSIONS.get(failuresKey) || "0") + 1;
        diagnosticStage = "write-reset-failures";
        await env.SESSIONS.put(failuresKey, String(failures), { expirationTtl: RESET_TTL_SECONDS });
        if (failures >= 5) {
          diagnosticStage = "delete-invalid-reset-code";
          await env.SESSIONS.delete(resetKey);
        }
        return json({ error: failures >= 5 ? "Te veel onjuiste codes. Vraag een nieuwe herstelcode aan." : "Herstelcode is niet juist." }, 400);
      }
      diagnosticStage = "hash-new-password";
      const newHash = await hashPassword(password, (stage) => { diagnosticStage = stage; });
      diagnosticStage = "save-new-password";
      await env.SESSIONS.put("admin-password-hash", newHash);
      diagnosticStage = "delete-reset-code";
      await env.SESSIONS.delete(resetKey);
      diagnosticStage = "delete-reset-failures";
      await env.SESSIONS.delete(resetKey + ":failures");
      diagnosticStage = "list-active-sessions";
      const sessions = await env.SESSIONS.list({ prefix: "session:" });
      for (const item of sessions.keys) {
        diagnosticStage = "delete-active-session";
        await env.SESSIONS.delete(item.name);
      }
      return json({ success: true, message: "Je wachtwoord is gewijzigd. Je kunt nu opnieuw inloggen." });
    } catch (error) {
      console.error("TijdBeeld reset-password diagnostic", {
        stage: diagnosticStage,
        errorName: error instanceof Error ? error.name : "UnknownError"
      });
      return json({ error: "Wachtwoord wijzigen is mislukt.", diagnosticStage }, 500);
    }
  }

  if (url.pathname === "/auth/keep-session" && request.method === "POST") {
    const current = await getSession(request, env);
    if (!current) return json({ error: "Je sessie is verlopen. Log opnieuw in." }, 401);
    // Sliding 30-day session; each successful keep-alive renews the server-side TTL.
    const renewed = { ...current.session, expiresAt: Date.now() + SESSION_TTL_SECONDS * 1000 };
    await env.SESSIONS.put(current.key, JSON.stringify(renewed), { expirationTtl: SESSION_TTL_SECONDS });
    return json({ success: true, persistent: false, expiresIn: SESSION_TTL_SECONDS });
  }

  if (url.pathname === "/auth/logout" && request.method === "POST") {
    const current = await getSession(request, env);
    if (current) await env.SESSIONS.delete(current.key);
    return json({ success: true });
  }

  if (url.pathname === "/photos" && request.method === "GET") {
    // KV blijft leidend voor bestaande foto-ID's; ontbrekende basisfoto's uit de
    // centrale catalogus worden alleen aan het antwoord toegevoegd. We schrijven
    // deze samenvoeging nadrukkelijk NIET terug naar KV.
    const stored = await env.CATALOG.get("public-catalog", "json");
    const hasStoredPhotos = stored && Array.isArray(stored.photos) && stored.photos.length > 0;

    let legacyCatalog = null;
    try {
      const legacy = await fetch(LEGACY_CATALOG_URL, { cf: { cacheTtl: 300 } });
      if (legacy.ok) {
        const candidate = await legacy.json();
        if (validCatalog(candidate)) legacyCatalog = candidate;
      }
    } catch (error) {
      console.warn("TijdBeeld legacy catalog fetch failed", {
        error: error instanceof Error ? error.message : String(error)
      });
    }

    if (hasStoredPhotos) {
      if (!legacyCatalog) return json(stored);

      const mergedPhotos = [...stored.photos];
      const knownIds = new Set(
        stored.photos.filter((photo) => photo && typeof photo.id === "string").map((photo) => photo.id)
      );
      for (const photo of legacyCatalog.photos) {
        if (!knownIds.has(photo.id)) {
          mergedPhotos.push(photo);
          knownIds.add(photo.id);
        }
      }

      // Houd de Worker binnen dezelfde cataloguslimiet als bij publicatie.
      if (mergedPhotos.length > 2000) return json(stored);

      const merged = { ...stored, photos: mergedPhotos };
      const featuredIds = new Set(mergedPhotos.map((photo) => photo.id));
      if (!featuredIds.has(merged.featuredPhotoId)) {
        merged.featuredPhotoId = featuredIds.has(legacyCatalog.featuredPhotoId)
          ? legacyCatalog.featuredPhotoId
          : mergedPhotos[0].id;
      }
      return json(merged);
    }

    // Zonder bruikbare KV-foto's blijft de centrale catalogus de terugvalbron.
    if (!legacyCatalog) {
      return json({ error: "De openbare catalogus kon niet worden geladen." }, 502);
    }
    return json(legacyCatalog);
  }

  if (url.pathname === "/photos" && request.method === "POST") {
    const current = await getSession(request, env);
    if (!current) return json({ error: "Je sessie is verlopen. Log opnieuw in." }, 401);
    let catalog;
    try { catalog = await request.json(); } catch { return json({ error: "Ongeldige catalogusgegevens." }, 400); }
    const validationError = catalogValidationError(catalog);
    if (validationError) {
      return json({ error: "De catalogus voldoet niet aan het vereiste formaat: " + validationError + "." }, 400);
    }
    // Fail-safe: een publicatie mag nooit bestaande openbare foto-ID's laten verdwijnen.
    // Verwijderen moet later via een afzonderlijke, expliciete en bevestigde beheeractie.
    if (catalog.photos.length === 0) {
      return json({ error: "Publiceren geweigerd: de catalogus bevat geen foto's. De bestaande openbare foto's zijn behouden." }, 400);
    }
    const existing = await env.CATALOG.get("public-catalog", "json");
    if (existing !== null && !validCatalog(existing)) {
      return json({
        error: "Publiceren geblokkeerd: de bestaande openbare catalogus kan niet veilig worden gecontroleerd. Er is niets gewijzigd."
      }, 409);
    }

    // De centrale catalogus is eveneens een beschermde bron. Zonder een
    // succesvolle, geldige controle daarvan mag KV nooit worden overschreven.
    let legacyCatalog;
    try {
      const legacyResponse = await fetch(LEGACY_CATALOG_URL, { cf: { cacheTtl: 300 } });
      if (!legacyResponse.ok) {
        return json({
          error: "Publiceren geblokkeerd: de centrale fotocatalogus is tijdelijk niet controleerbaar. Er is niets gewijzigd."
        }, 503);
      }
      legacyCatalog = await legacyResponse.json();
    } catch {
      return json({
        error: "Publiceren geblokkeerd: de centrale fotocatalogus is tijdelijk niet controleerbaar. Er is niets gewijzigd."
      }, 503);
    }
    if (!validCatalog(legacyCatalog)) {
      return json({
        error: "Publiceren geblokkeerd: de centrale fotocatalogus heeft een ongeldig formaat. Er is niets gewijzigd."
      }, 503);
    }

    const incomingIds = new Set(catalog.photos.map((photo) => photo.id));
    const protectedIds = new Set([
      ...(existing ? existing.photos.map((photo) => photo.id) : []),
      ...legacyCatalog.photos.map((photo) => photo.id),
    ]);
    const missingProtected = [...protectedIds].filter((id) => !incomingIds.has(id));
    if (missingProtected.length > 0) {
      return json({
        error: "Publiceren geblokkeerd: " + missingProtected.length +
          " bestaande foto('s) uit de openbare of centrale catalogus ontbreken in deze publicatie. Er is niets gewijzigd. Vernieuw eerst de fotolijst."
      }, 409);
    }

    catalog.updatedAt = new Date().toISOString();
    await env.CATALOG.put("public-catalog", JSON.stringify(catalog));
    const cacheKey = new Request(new URL("/photos", url.origin).toString(), { method: "GET" });
    await caches.default.delete(cacheKey);
    return json({ success: true, photoCount: catalog.photos.length });
  }

  return json({ error: "Niet gevonden." }, 404);
}

export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (error) {
      // Log the route and exception message for diagnosis; never log secrets or request bodies.
      console.error("TijdBeeld Worker exception", {
        path: new URL(request.url).pathname,
        method: request.method,
        error: error instanceof Error ? error.message : String(error),
        stack: error instanceof Error ? error.stack : undefined,
      });
      return json({ error: "Er is een interne serverfout opgetreden." }, 500);
    }
  },
};
