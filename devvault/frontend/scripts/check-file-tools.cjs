const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("components/vault-app.tsx", "utf8");
assert.match(source, /event\.currentTarget\.track\.mode = "showing"/);
assert.match(source, /async function attachTag/);
assert.match(source, /\/api\/files\/\$\{selected\.id\}\/tags/);

const formatSource = source.slice(source.indexOf("function formatSize"), source.indexOf("function formatStorage"));
const code = ts.transpileModule(`${formatSource}\nglobalThis.formatSize = formatSize;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText;
const sandbox = {};
vm.runInNewContext(code, sandbox);
assert.equal(sandbox.formatSize(5 * 1024 ** 3), "5.0GB");
assert.equal(sandbox.formatSize(2 * 1024 ** 4), "2.0TB");

console.log("file tools: ok");
