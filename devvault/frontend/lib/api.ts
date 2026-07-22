import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from "./auth";
import type { AuthToken } from "./types";

let refreshRequest: Promise<boolean> | null = null;

async function refreshAccessToken() {
  if (refreshRequest) return refreshRequest;
  refreshRequest = refreshTokens();
  try {
    return await refreshRequest;
  } finally {
    refreshRequest = null;
  }
}

async function refreshTokens() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  const response = await fetch("/api/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken })
  });
  if (!response.ok) {
    clearTokens();
    return false;
  }
  saveTokens((await response.json()) as AuthToken);
  return true;
}

export async function apiFetch(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getAccessToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (!(init.body instanceof FormData) && init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });
  if (response.status === 401 && retry && await refreshAccessToken()) {
    return apiFetch(path, init, false);
  }
  return response;
}

function uploadRequest(path: string, body: FormData, onProgress: (loaded: number, total: number) => void) {
  return new Promise<Response>((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("POST", path);
    const token = getAccessToken();
    if (token) request.setRequestHeader("Authorization", `Bearer ${token}`);
    request.upload.onprogress = event => onProgress(event.loaded, event.lengthComputable ? event.total : 0);
    request.onerror = () => reject(new TypeError("네트워크 연결이 끊어졌습니다."));
    request.onabort = () => reject(new DOMException("업로드가 취소되었습니다.", "AbortError"));
    request.onload = () => resolve(new Response(
      request.status === 204 ? null : request.responseText,
      { status: request.status, statusText: request.statusText }
    ));
    request.send(body);
  });
}

export async function apiUpload(
  path: string,
  body: FormData,
  onProgress: (loaded: number, total: number) => void,
  retry = true
): Promise<Response> {
  const response = await uploadRequest(path, body, onProgress);
  if (response.status === 401 && retry && await refreshAccessToken()) {
    onProgress(0, 0);
    return apiUpload(path, body, onProgress, false);
  }
  return response;
}

export async function apiJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(path, init);
  if (!response.ok) throw new Error((await response.text()) || `요청에 실패했습니다. (${response.status})`);
  return response.json() as Promise<T>;
}
