const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const source = fs.readFileSync("lib/subtitles.ts", "utf8");
const code = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
const sandbox = { module: { exports: {} }, exports: {} };
sandbox.exports = sandbox.module.exports;
vm.runInNewContext(code, sandbox);
const { findMatchingSubtitle, subtitleToVtt } = sandbox.module.exports;

const video = { originalName: "Movie.2026.mp4", folderId: 7 };
const matching = findMatchingSubtitle(video, [
  { originalName: "Movie.2026.SRT", folderId: 7 },
  { originalName: "Movie.2026.vtt", folderId: 8 }
]);
assert.equal(matching.originalName, "Movie.2026.SRT");
assert.equal(findMatchingSubtitle(video, [{ originalName: "other.srt", folderId: 7 }]), undefined);

const srt = `1
00:00:01,500 --> 00:00:03,000
첫 번째 자막

2
00:00:04,000 --> 00:00:06,250
두 번째 자막`;
const srtVtt = subtitleToVtt("sample.srt", srt);
assert.match(srtVtt, /^WEBVTT/);
assert.match(srtVtt, /00:00:01\.500 --> 00:00:03\.000/);
assert.match(srtVtt, /첫 번째 자막/);

const smi = `<SAMI><BODY>
<SYNC Start=1000><P Class=KRCC>안녕하세요<br>두 번째 줄
<SYNC Start=3500><P Class=KRCC>&nbsp;
</BODY></SAMI>`;
const smiVtt = subtitleToVtt("sample.smi", smi);
assert.match(smiVtt, /00:00:01\.000 --> 00:00:03\.500/);
assert.match(smiVtt, /안녕하세요\n두 번째 줄/);

console.log("subtitles: ok");
