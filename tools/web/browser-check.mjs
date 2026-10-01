// Run against the scripted dev server on port 8082. No real model is required.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
const playwright = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browserName = process.env.CHAT_BROWSER || 'chromium';
const base = process.env.CHAT_URL || 'http://127.0.0.1:8082';
const output = process.env.CHAT_ARTIFACTS || '/tmp/execuserve-browser-checks';
await mkdir(output, { recursive: true });
const browser = await playwright[browserName].launch({ headless: true });
let checks = 0;
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  // Playwright's WebKit screenshot helper injects an inline stylesheet, rejected by
  // our CSP. Keep policy enforcement on; capture visual evidence in Chromium.
  const screenshot = async path => { if (browserName !== 'webkit') await page.screenshot({ path }); };
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', e => { if (e.type() === 'error' && /Content Security Policy/.test(e.text())) errors.push(e.text()); });
  await page.goto(base);
  for (const [width, height] of [[1440,900],[1920,1080],[768,1024],[390,844],[320,568],[844,390],[667,375],[320,320]]) {
    await page.setViewportSize({ width, height });
    await page.waitForTimeout(80);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `overflow ${width}x${height}`);
    const box = await page.locator('#composer').boundingBox();
    assert(box.x >= 0 && box.x + box.width <= width + 1 && box.y >= 0 && box.y + box.height <= height, `composer ${width}x${height}`);
    await screenshot(`${output}/welcome-${width}x${height}.png`); checks++;
  }
  await page.locator('#prompt').fill('A multiline draft\n'.repeat(20));
  const compact = await page.locator('#composer').boundingBox();
  assert(compact.y >= 0 && compact.y + compact.height <= 320); checks++;
  await page.locator('#prompt').fill('');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#open-sidebar').click();
  assert(await page.locator('.workspace').evaluate(e => e.inert));
  await page.keyboard.press('Escape');
  assert(!await page.locator('.workspace').evaluate(e => e.inert)); checks++;
  // A first send must survive creating the conversation and connecting.
  await page.locator('#prompt').fill('Hello browser test');
  await page.locator('#send').click();
  await page.locator('#api-key').fill('wrong-key');
  await page.locator('#connect-submit').click();
  await page.locator('#connection-error').waitFor({ state: 'visible' });
  assert.match(await page.locator('#connection-error').innerText(), /not accepted/); checks++;
  await page.locator('#api-key').fill('sk-dev');
  await page.locator('#connect-submit').click();
  await page.waitForFunction(() => document.querySelector('.message.assistant .message-body')?.textContent.includes('Hello browser test'));
  await page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  assert.equal(await page.locator('.message.user .message-body').innerText(), 'Hello browser test'); checks++;
  await page.locator('#prompt').fill('Second turn'); await page.locator('#send').click();
  await page.waitForFunction(() => document.querySelectorAll('.message.assistant').length === 2 && document.querySelector('#send').getAttribute('aria-label') === 'Send message'); checks++;
  await screenshot(`${output}/conversation-mobile.png`);
  await page.setViewportSize({ width:1440, height:900 });
  await page.locator('#theme').click();
  await screenshot(`${output}/conversation-desktop-dark.png`);
  // Abort a real in-flight HTTP stream; a subsequent request must still work.
  await page.locator('#prompt').fill('Please repeat ' + 'many words '.repeat(100));
  await page.locator('#send').click();
  await page.waitForTimeout(250); await page.locator('#send').click();
  await page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  assert.match(await page.locator('.message-meta').last().innerText(), /Stopped/); checks++;
  await page.locator('#new-chat').click();
  // Exercise safe text rendering, reasoning and fenced code through the wire format.
  const content = '<img src=x onerror=alert(1)>\n\n```js\n' + 'x'.repeat(500) + '\n```';
  await page.route('**/v1/chat/completions', route => route.fulfill({ contentType:'text/event-stream', body:
    'data: ' + JSON.stringify({choices:[{delta:{reasoning_content:'Thinking safely',content}}]}) + '\r\n\r\n' +
    'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n' }));
  await page.locator('#prompt').fill('Render this safely'); await page.locator('#send').click();
  await page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  assert.equal(await page.locator('.message-body img').count(), 0);
  assert.equal(await page.locator('.reasoning').innerText(), 'Thinking');
  assert.equal(await page.locator('.message-body pre code').innerText(), 'x'.repeat(500)); checks++;
  for (const [width,height] of [[320,568],[844,390],[1920,1080]]) {
    await page.setViewportSize({width,height});
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); checks++;
  }
  await page.unroute('**/v1/chat/completions');
  // A dropped stream is visibly failed and retry does not duplicate the user turn.
  await page.route('**/v1/chat/completions', route => route.fulfill({contentType:'text/event-stream',body:'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'}));
  await page.locator('#prompt').fill('Interrupted'); await page.locator('#send').click();
  await page.locator('#retry').waitFor({state:'visible'});
  assert.match(await page.locator('#notice').innerText(), /connection ended/);
  const before = await page.locator('.message.user').count();
  await page.unroute('**/v1/chat/completions'); await page.locator('#retry').click();
  await page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  assert.equal(await page.locator('.message.user').count(), before); checks++;
  const reply = page.locator('.message.assistant .message-body').last();
  const expectedCopy = await reply.innerText();
  const copyButton = page.getByRole('button', { name: 'Copy reply', exact: true }).last();
  if (browserName === 'chromium') {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await copyButton.click();
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), expectedCopy); checks++;
    await page.evaluate(() => { navigator.clipboard.writeText = async () => { throw new Error('Denied'); }; });
    await copyButton.click();
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), expectedCopy); checks++;
  }
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('Denied'); } } });
    document.execCommand = () => false;
  });
  await copyButton.click();
  const manual = page.getByRole('dialog');
  await manual.waitFor({ state: 'visible' });
  assert.equal(await manual.locator('textarea').inputValue(), expectedCopy);
  await manual.getByRole('button', { name: 'Close', exact: true }).click(); checks++;
  assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
  await page.reload();
  assert.equal(await page.locator('#connection-label').innerText(), 'Connect');
  assert.equal(await page.locator('.message').count(), 0); checks++;
  assert.deepEqual(errors, []);
  console.log(`PASS: ${checks} ${browserName} browser checks; artifacts: ${output}`);
} finally { await browser.close(); }
