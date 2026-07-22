const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("lib/api.ts", "utf8");
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
let tokens = { accessToken: "expired", refreshToken: "refresh-1" };
let refreshCalls = 0;
let clearCalls = 0;
const auth = {
  getAccessToken: () => tokens?.accessToken ?? null,
  getRefreshToken: () => tokens?.refreshToken ?? null,
  saveTokens: value => { tokens = value; },
  clearTokens: () => { clearCalls += 1; tokens = null; }
};
const fetch = async path => {
  if (path !== "/api/auth/refresh") return { ok: false, status: 401, text: async () => "" };
  refreshCalls += 1;
  await new Promise(resolve => setTimeout(resolve, 10));
  return { ok: true, status: 200, json: async () => ({ accessToken: "new", refreshToken: "refresh-2" }) };
};
const sandbox = { module: { exports: {} }, fetch, FormData, Headers, setTimeout, Promise };
sandbox.exports = sandbox.module.exports;
sandbox.require = id => id === "./auth" ? auth : {};
vm.runInNewContext(code, sandbox);

Promise.all(["/api/folders", "/api/files", "/api/dashboard"].map(path => sandbox.module.exports.apiFetch(path))).then(() => {
  assert.equal(refreshCalls, 1);
  assert.equal(clearCalls, 0);
  assert.equal(tokens.refreshToken, "refresh-2");
  console.log("refresh single-flight: ok");
});
