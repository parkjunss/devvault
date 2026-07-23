import type { AuthToken } from "./types";

const ACCESS_TOKEN = "devvault.accessToken";
const REFRESH_TOKEN = "devvault.refreshToken";

export function getAccessToken() {
  return typeof window === "undefined" ? null : localStorage.getItem(ACCESS_TOKEN);
}

export function getRefreshToken() {
  return typeof window === "undefined" ? null : localStorage.getItem(REFRESH_TOKEN);
}

export function saveTokens(tokens: AuthToken) {
  localStorage.setItem(ACCESS_TOKEN, tokens.accessToken);
  localStorage.setItem(REFRESH_TOKEN, tokens.refreshToken);
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN);
  localStorage.removeItem(REFRESH_TOKEN);
}

export function tokenSubject() {
  const payload = tokenPayload();
  return typeof payload?.sub === "string" ? payload.sub : null;
}

export function tokenHasRole(role: string) {
  const roles = tokenPayload()?.roles;
  return Array.isArray(roles) && roles.includes(role);
}

function tokenPayload(): Record<string, unknown> | null {
  const token = getAccessToken();
  if (!token) return null;
  try {
    const encoded = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=")));
  } catch {
    return null;
  }
}
