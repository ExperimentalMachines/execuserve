// Bundles the recordings into assets/data.js, which ad.html loads as a script (a page opened from
// file:// cannot fetch JSON), with how many frames clips.sh extracted from each clip. Checks them
// first, since the scenes are timed around them: a recording that no longer fits fails here, not
// on screen. Run after clips.sh.   node data.cjs
const fs = require('fs');
const dir = __dirname;
const read = (f) => JSON.parse(fs.readFileSync(`${dir}/assets/${f}`, 'utf8'));
const fail = (why) => { console.error('data.cjs:', why); process.exit(1); };
const count = (c) => { try { return fs.readdirSync(`${dir}/frames/clips/${c}`).filter((f) => f.endsWith('.jpg')).length; } catch { return 0; } };

// The Snapdragon take is the phone's own screen recording, so its moments come from the screen:
// the status dot turns blue (Working) when Send is tapped and green again when the reply is done,
// read frame by frame at 20 fps. The footer is the app's own measurement of the same reply.
const npuJson = read('clips/npu-sm8850-chat.json');
const npu = { ...npuJson, marks: { sent: 7.75, done: 11.0 } };

const data = {
  npu,
  asleep: read('clips/npu-poco-asleep.json'),
  clips: { asleep: { fps: 25, count: count('asleep') }, start: { fps: 30, count: count('start') }, npu: { fps: 30, count: count('npu') } },
};
for (const [c, v] of Object.entries(data.clips)) if (!v.count) fail(`no frames for ${c}: run clips.sh first`);

const { asleep } = data;
const m = asleep.marks || {};
if (!(m.connected < m.sent && m.sent < m.firstText && m.firstText <= m.done)) fail('npu-poco-asleep.json marks are missing or out of order');
if (!asleep.reply?.trim() || !asleep.address) fail('npu-poco-asleep.json lacks its reply or address');
if (/\*\*|`/.test(asleep.reply)) fail("npu-poco-asleep.json's reply has Markdown; pick a prompt with a plain-text answer");
const dozing = (s) => s?.locked && s?.wakefulness === 'Dozing';
if (!(dozing(asleep.phone?.before) && dozing(asleep.phone?.after))) fail('npu-poco-asleep.json does not show the phone locked and dozing before and after the take');
if (!npu.footer || !npu.reply?.trim()) fail('npu-sm8850-chat.json lacks its footer or reply');

// Each clip must run as long as its scene asks; a short one would freeze on its last frame
// (the asleep scene holds its last frame on purpose, so it needs only the answer and a beat).
const BEAT = 60 / 112, bar = (n, b = 0) => (n * 4 + b) * BEAT;
const needs = {
  npu: npu.marks.sent + (bar(15) - bar(12)) - 0.5,
  asleep: asleep.marks.done + 1,
  start: 9.9 + (bar(11) - bar(10)),
};
for (const [c, need] of Object.entries(needs)) {
  const have = data.clips[c].count / data.clips[c].fps;
  if (have < need) fail(`${c} has ${have.toFixed(1)} s of frames; its scene needs ${need.toFixed(1)} s`);
}

fs.writeFileSync(`${dir}/assets/data.js`, `// Written by data.cjs from the recordings next to it.\nwindow.AD_DATA = ${JSON.stringify(data)};\n`);
console.log('wrote assets/data.js');
