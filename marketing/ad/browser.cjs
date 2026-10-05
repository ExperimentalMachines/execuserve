// A laptop using the phone: Playwright opens the browser chat the phone serves, at the phone's
// Wi-Fi address, connects with the key (typed into the page's password field, so never drawn),
// asks one question and films it as sharp 2x screenshots, one every ~80 ms, timestamped. They are
// encoded into assets/clips/<name>.mp4 at the pace they were taken; clips.sh turns that back into
// frames for the ad.
//   EXECUSERVE_BASE=http://192.168.100.171:8080 EXECUSERVE_KEY=... node browser.cjs <name> "<prompt>"
// Writes assets/clips/<name>.mp4 and assets/clips/<name>.json (what was asked and answered, and
// when, in seconds from the start of the clip: connected, sent, first text, done).
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');

const BASE = process.env.EXECUSERVE_BASE;
const KEY = process.env.EXECUSERVE_KEY;
const MODEL = process.env.EXECUSERVE_MODEL || 'qwen3-1.7b-8da4w-gptq-4k';
const [name, prompt] = process.argv.slice(2);
const OUT = `${__dirname}/assets/clips`;
const W = 1100, H = 690; // a laptop-sized window, so the chat's text reads at the ad's scale
// With ANDROID_SERIAL set, the phone's screen and lock state are read over adb before and after
// the take and kept with it: the ad says when a take was recorded with the phone asleep.
function phoneState() {
  if (!process.env.ANDROID_SERIAL) return null;
  try {
    const out = execFileSync('adb', ['shell', 'dumpsys power | grep -m1 mWakefulness=; dumpsys window | grep -m1 isKeyguardShowing'], { timeout: 15000 }).toString();
    return { wakefulness: (out.match(/mWakefulness=(\w+)/) || [])[1], locked: /isKeyguardShowing=true/.test(out), at: new Date().toISOString() };
  } catch { return { error: 'adb did not answer' }; }
}

(async () => {
  if (!BASE || !KEY || !name || !prompt) throw new Error('usage: EXECUSERVE_BASE=... EXECUSERVE_KEY=... node browser.cjs <name> "<prompt>"');
  fs.mkdirSync(OUT, { recursive: true });
  const tmp = fs.mkdtempSync(`${OUT}/.rec-`);
  const before = phoneState();
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 2, colorScheme: 'light' })).newPage();
  const shots = [];
  let filming = true;
  const t0 = Date.now();
  const at = () => (Date.now() - t0) / 1000;
  // The camera: screenshots back to back, each stamped with when it was taken.
  const camera = (async () => {
    while (filming) {
      const t = at();
      const file = `${tmp}/${String(shots.length).padStart(5, '0')}.jpg`;
      await p.screenshot({ path: file, type: 'jpeg', quality: 92 });
      shots.push({ t, file });
    }
  })();
  const marks = {};
  await p.goto(`${BASE}/models/${MODEL}/`, { waitUntil: 'networkidle' });
  await p.waitForTimeout(700);
  await p.locator('#connection').click();
  await p.waitForTimeout(350);
  await p.locator('#api-key').pressSequentially(KEY, { delay: 10 });
  await p.waitForTimeout(250);
  await p.locator('#connect-submit').click();
  await p.locator('#connect-dialog').waitFor({ state: 'hidden' });
  marks.connected = at();
  await p.waitForTimeout(600);
  await p.locator('#prompt').pressSequentially(prompt, { delay: 30 });
  await p.waitForTimeout(300);
  await p.locator('#send').click();
  marks.sent = at();
  const body = p.locator('.message.assistant .message-body').last();
  await body.filter({ hasText: /\S/ }).waitFor({ timeout: 120000 });
  marks.firstText = at();
  await p.locator('.message.assistant').getByText(/s elapsed/).waitFor({ timeout: 120000 });
  marks.done = at();
  await p.waitForTimeout(3000);
  filming = false;
  await camera;
  const reply = await body.innerText();
  const meta = await p.locator('.message.assistant').getByText(/s elapsed/).last().innerText();
  await b.close();
  const after = phoneState();
  // Encode at the pace the screenshots were taken: each shown until the next.
  const list = shots.map((s, i) => `file '${s.file}'\nduration ${((shots[i + 1]?.t ?? s.t + 0.08) - s.t).toFixed(4)}`).join('\n') + `\nfile '${shots.at(-1).file}'\n`;
  fs.writeFileSync(`${tmp}/list.txt`, list);
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', `${tmp}/list.txt`,
    '-vf', 'fps=25,format=yuv420p', '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', `${OUT}/${name}.mp4`]);
  // The first screenshot is the clip's time zero.
  const z = shots[0].t;
  for (const k of Object.keys(marks)) marks[k] = +(marks[k] - z).toFixed(3);
  fs.rmSync(tmp, { recursive: true, force: true });
  const address = `${new URL(BASE).host}/models/${MODEL}/`;
  fs.writeFileSync(`${OUT}/${name}.json`, JSON.stringify({ address, prompt, reply, meta, marks, size: [W, H], phone: before && { before, after } }, null, 1) + '\n');
  console.log(`${name}: ${shots.length} shots over ${(shots.at(-1).t - z).toFixed(1)} s\n${reply.replace(/\n/g, ' / ')}\n${meta}\n${JSON.stringify(marks)}`);
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
