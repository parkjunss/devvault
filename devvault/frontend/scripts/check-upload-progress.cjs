const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("lib/api.ts", "utf8");
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
let request;
class FakeXMLHttpRequest {
  constructor() {
    request = this;
    this.upload = {};
    this.headers = {};
  }
  open(method, path) { this.method = method; this.path = path; }
  setRequestHeader(name, value) { this.headers[name] = value; }
  send(body) {
    this.body = body;
    this.upload.onprogress({ loaded: 70, total: 100, lengthComputable: true });
    this.status = 201;
    this.statusText = "Created";
    this.responseText = "{}";
    this.onload();
  }
}
const auth = {
  getAccessToken: () => "access-token",
  getRefreshToken: () => null,
  saveTokens: () => undefined,
  clearTokens: () => undefined
};
const sandbox = {
  module: { exports: {} }, FormData, Headers, Response, DOMException, TypeError, Promise,
  XMLHttpRequest: FakeXMLHttpRequest
};
sandbox.exports = sandbox.module.exports;
sandbox.require = id => id === "./auth" ? auth : {};
vm.runInNewContext(code, sandbox);

const progress = [];
sandbox.module.exports.apiUpload("/api/files", new FormData(), (loaded, total) => {
  progress.push([loaded, total]);
}).then(response => {
  assert.equal(response.status, 201);
  assert.deepEqual(progress, [[70, 100]]);
  assert.equal(request.method, "POST");
  assert.equal(request.path, "/api/files");
  assert.equal(request.headers.Authorization, "Bearer access-token");
  console.log("upload progress: ok");
});
