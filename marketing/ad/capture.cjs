// The phone's own browser chat, captured on a laptop through the forwarded port: a real
// question to the model on the phone, the reply streaming in (a frame every 80 ms, with the
// moment each was taken), and the finished exchange. The key goes into the page's password
// field and is never drawn.
//   EXECUSERVE_BASE=http://127.0.0.1:8090 EXECUSERVE_KEY=... node capture.cjs
// Writes assets/web/{stream-*.jpg,frames.json,done.png}.
const { chromium } = require('playwright');
const fs = require('fs');

const BASE = process.env.EXECUSERVE_BASE || 'http://127.0.0.1:8080';
const KEY = process.env.EXECUSERVE_KEY;
const MODEL = process.env.EXECUSERVE_MODEL || 'qwen3-1.7b-8da4w-gptq-4k';
const PROMPT = 'Write a haiku about a phone that serves AI to the laptop next to it.';
const OUT = __dirname + '/assets/web';
const STEP_MS = 80;

(async () => {
  if (!KEY) throw new Error('set EXECUSERVE_KEY');
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5, colorScheme: 'dark' })).newPage();
  await p.goto(`${BASE}/models/${MODEL}/`, { waitUntil: 'networkidle' });
  // Connect first, so the stream that follows is all the model.
  await p.locator('#connection').click();
  await p.locator('#api-key').fill(KEY);
  await p.locator('#connect-submit').click();
  await p.locator('#connect-dialog').waitFor({ state: 'hidden' });
  await p.locator('#prompt').fill(PROMPT);
  await p.evaluate(() => document.activeElement?.blur());
  const frames = [];
  const t0 = Date.now();
  await p.locator('#send').click();
  // Frames until the reply's figures line ends in "elapsed", which it does once the reply is complete.
  for (let i = 0; i < 400; i++) {
    const name = `stream-${String(i).padStart(3, '0')}.jpg`;
    await p.screenshot({ path: `${OUT}/${name}`, type: 'jpeg', quality: 88 });
    frames.push({ t: (Date.now() - t0) / 1000, name });
    if (await p.locator('.message.assistant').getByText(/s elapsed/).count()) break;
    await p.waitForTimeout(STEP_MS);
  }
  await p.waitForTimeout(400);
  await p.screenshot({ path: `${OUT}/done.png` });
  const reply = await p.locator('.message.assistant .message-body').last().innerText();
  const meta = await p.locator('.message.assistant').getByText(/s elapsed/).last().innerText();
  const address = `${new URL(BASE).host}/models/${MODEL}/`;
  fs.writeFileSync(`${OUT}/frames.json`, JSON.stringify({ address, prompt: PROMPT, reply, meta, frames }, null, 1) + '\n');
  console.log(`${frames.length} frames over ${frames.at(-1).t.toFixed(2)} s\nreply: ${reply}\nmeta: ${meta}`);
  await b.close();
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
