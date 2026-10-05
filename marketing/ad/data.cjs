// Bundles the recordings into assets/data.js, which ad.html loads as a script (a page opened from
// file:// cannot fetch JSON), with how many frames clips.sh extracted from each clip. Checks them
// first, since the scenes are timed around them: a recording that no longer fits fails here, not
// on screen. Run after clips.sh.   node data.cjs
const fs = require('fs');
const dir = __dirname;
const read = (f) => JSON.parse(fs.readFileSync(`${dir}/assets/${f}`, 'utf8'));
const fail = (why) => { console.error('data.cjs:', why); process.exit(1); };
const count = (c) => { try { return fs.readdirSync(`${dir}/frames/clips/${c}`).filter((f) => f.endsWith('.jpg')).length; } catch { return 0; } };

const data = {
  stream: read('stream.json'),
  chat: read('clips/chat.json'),
  asleep: read('clips/asleep.json'),
  download: read('clips/download.json'),
  clips: { chat: { fps: 25, count: count('chat') }, asleep: { fps: 25, count: count('asleep') }, start: { fps: 30, count: count('start') }, download: { fps: 15, count: count('download') } },
};
for (const [c, v] of Object.entries(data.clips)) if (!v.count) fail(`no frames for ${c}: run clips.sh first`);

const { stream, chat, asleep, download } = data;
if (!stream.tokens?.length) fail('stream.json has no tokens');
if (!stream.tokens.every((k, i) => Number.isFinite(k.t) && k.t >= 0 && (i === 0 || k.t >= stream.tokens[i - 1].t))) fail('stream.json token times are not finite and in order');
// The code scene replays the tokens 1.6 s after its third beat and must finish within its 3 bars.
const BEAT = 60 / 112;
if (stream.tokens.at(-1).t > 3 * 4 * BEAT - 2 * BEAT - 1.6 - 1.2) fail(`the code stream runs ${stream.tokens.at(-1).t} s, too long for its scene`);
for (const [name, c] of [['chat', chat], ['asleep', asleep]]) {
  const m = c.marks || {};
  if (!(m.connected < m.sent && m.sent < m.firstText && m.firstText < m.done)) fail(`${name}.json marks are missing or out of order`);
  if (!c.reply?.trim() || !c.address) fail(`${name}.json lacks its reply or address`);
}
if (!(download.expand < download.get && download.get < download.installed)) fail('download.json marks are missing or out of order');
// Each clip must run as long as its scene asks; a short one would freeze on its last frame.
const BAR = 4 * BEAT, bar = (n, b = 0) => (n * 4 + b) * BEAT;
const needs = {
  chat: Math.max(chat.marks.done + 2, bar(15) - (bar(13) - chat.marks.firstText)),
  asleep: Math.min(asleep.marks.done + 3, bar(19) - (bar(17) - asleep.marks.firstText)),
  download: download.installed + 1,
  start: 9.9 + (bar(11) - bar(9, 2)),
};
for (const [c, need] of Object.entries(needs)) {
  const have = data.clips[c].count / data.clips[c].fps;
  if (have < need) fail(`${c} has ${have.toFixed(1)} s of frames; its scene needs ${need.toFixed(1)} s`);
}
const asleepState = (s) => s?.locked && s?.wakefulness === 'Dozing';
if (!(asleepState(asleep.phone?.before) && asleepState(asleep.phone?.after))) fail('asleep.json does not show the phone locked and dozing before and after the take');

fs.writeFileSync(`${dir}/assets/data.js`, `// Written by data.cjs from the recordings next to it.\nwindow.AD_DATA = ${JSON.stringify(data)};\n`);
console.log('wrote assets/data.js');
