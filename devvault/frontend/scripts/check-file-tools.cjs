const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("components/vault-app.tsx", "utf8");
const playerSource = fs.readFileSync("components/video-player.tsx", "utf8");
assert.match(source, /<VideoPlayer/);
assert.doesNotMatch(source, /<video key=/);
assert.match(playerSource, /accept="\.vtt,\.srt,\.smi,\.sami/);
assert.match(playerSource, /event\.currentTarget\.track\.mode = "showing"/);
assert.match(playerSource, /for \(const track of Array\.from\(video\.textTracks\)\)/);
assert.match(playerSource, /aria-label="자막 크기"/);
assert.match(playerSource, /aria-label="자막 배경"/);
assert.match(playerSource, /subtitleSizeLarge/);
assert.match(playerSource, /subtitleBgNone/);
assert.match(source, /async function attachTag/);
assert.match(source, /\/api\/files\/\$\{selected\.id\}\/tags/);
assert.match(source, /usedPercent >= 80/);
assert.match(source, /setUploadFailure\(/);
assert.match(source, /aria-label="알림 목록"/);
assert.match(source, /parentId: currentFolder\?\.id \?\? null/);
assert.match(source, /const \[folderTrail, setFolderTrail\]/);
assert.match(source, /aria-label="폴더 경로"/);
assert.match(source, /openChildFolder\(folder\)/);
assert.match(source, /folderTrail\.map\(\(folder, index\)/);

const formatSource = source.slice(source.indexOf("function formatSize"), source.indexOf("function formatStorage"));
const code = ts.transpileModule(`${formatSource}\nglobalThis.formatSize = formatSize;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText;
const sandbox = {};
vm.runInNewContext(code, sandbox);
assert.equal(sandbox.formatSize(5 * 1024 ** 3), "5.0GB");
assert.equal(sandbox.formatSize(2 * 1024 ** 4), "2.0TB");

console.log("file tools: ok");
