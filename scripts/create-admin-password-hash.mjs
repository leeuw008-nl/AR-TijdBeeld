import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { randomBytes, webcrypto } from "node:crypto";

const rl = createInterface({ input: stdin, output: stdout });
try {
  const password = await rl.question("Nieuw TijdBeeld-beheerderswachtwoord: ");
  const confirmation = await rl.question("Herhaal het wachtwoord: ");
  if (password !== confirmation) throw new Error("De wachtwoorden zijn niet gelijk.");
  if (password.length < 14) throw new Error("Gebruik minimaal 14 tekens.");
  const salt = randomBytes(16);
  const key = await webcrypto.subtle.importKey(
    "raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]
  );
  const derived = new Uint8Array(await webcrypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations: 310000, hash: "SHA-256" }, key, 256
  ));
  const b64 = (bytes) => Buffer.from(bytes).toString("base64");
  console.log("\nKopieer uitsluitend deze regel naar Cloudflare als ADMIN_PASSWORD_HASH-secret:\n");
  console.log("pbkdf2$310000$" + b64(salt) + "$" + b64(derived));
} finally {
  rl.close();
}
