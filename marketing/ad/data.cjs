// Bundles the phone's recordings (record.py, capture.cjs) into assets/data.js, which ad.html
// loads as a script: a page opened from file:// cannot fetch JSON. Checks them first, since the
// ad's scenes are timed around them: a recording that no longer fits fails here, not on screen.
//   node data.cjs
const fs = require('fs');
const read = (f) => JSON.parse(fs.readFileSync(`${__dirname}/assets/${f}`, 'utf8'));
const data = { stream: read('stream.json'), anthropic: read('anthropic.json'), cache: read('cache.json'), models: read('models.json'), web: read('web/frames.json') };
const BEAT = 60 / 124;
const fail = (why) => { console.error('data.cjs:', why); process.exit(1); };
const { stream, anthropic, cache, web } = data;
if (!stream.tokens?.length) fail('stream.json has no tokens');
if (!stream.tokens.every((k, i) => Number.isFinite(k.t) && k.t >= 0 && (i === 0 || k.t >= stream.tokens[i - 1].t))) fail('stream.json token times are not finite and in order');
// The stream starts two beats into its scene and must finish before the Anthropic line, five beats later.
if (stream.tokens.at(-1).t > 5 * BEAT - 0.3) fail(`the stream runs ${stream.tokens.at(-1).t} s, longer than its ${(5 * BEAT - 0.3).toFixed(2)} s`);
for (const k of ['predicted_n', 'prompt_per_second', 'predicted_per_second']) if (!(stream.timings?.[k] > 0)) fail(`stream.json lacks timings.${k}`);
if (!anthropic.text?.trim()) fail('anthropic.json has no text');
if (!(cache.cold?.ttft_s > 0 && cache.warm?.ttft_s > 0)) fail('cache.json lacks cold and warm times');
if (cache.cold.cached_tokens !== 0) fail('the cache recording\'s first turn was not cold');
if (!(cache.cold.prompt_tokens > 0 && cache.warm.cached_tokens > 0)) fail('cache.json lacks its token counts');
if (!web.frames.every((f, i) => Number.isFinite(f.t) && (i === 0 || f.t >= web.frames[i - 1].t))) fail('web/frames.json frame times are not in order');
if (!web.frames?.length || !web.address) fail('web/frames.json lacks frames or the address');
// The browser plays its frames from 0.45 s into its bar and must finish within it.
if (web.frames.at(-1).t > 4 * BEAT - 0.6) fail(`the browser capture runs ${web.frames.at(-1).t} s, longer than its bar`);
fs.writeFileSync(`${__dirname}/assets/data.js`, `// Written by data.cjs from the recordings next to it.\nwindow.AD_DATA = ${JSON.stringify(data)};\n`);
console.log('wrote assets/data.js');
