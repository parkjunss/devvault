const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("lib/auth.ts", "utf8");
const code = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  },
}).outputText;

let request;
const fetch = async (path, init) => {
  request = { path, init };
  return {
    ok: true,
    json: async () => ({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      tokenType: "Bearer",
      expiresIn: 900,
    }),
  };
};
const sandbox = {
  module: { exports: {} },
  fetch,
  JSON,
};
sandbox.exports = sandbox.module.exports;
vm.runInNewContext(code, sandbox);

sandbox.module.exports.exchangeOAuthCode("one-time-code").then(tokens => {
  assert.equal(request.path, "/api/auth/oauth/exchange");
  assert.equal(request.init.method, "POST");
  assert.deepEqual(JSON.parse(request.init.body), { code: "one-time-code" });
  assert.equal(tokens.accessToken, "access-token");
  console.log("oauth one-time exchange: ok");
});
