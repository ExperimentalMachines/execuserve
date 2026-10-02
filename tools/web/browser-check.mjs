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
  const newConversation = async () => {
    if (page.viewportSize().width <= 760) await page.locator('#open-sidebar').click();
    await page.locator('#new-chat').click();
  };
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', e => { if (e.type() === 'error' && /Content Security Policy/.test(e.text())) errors.push(e.text()); });
  await page.goto(base);
  for (const [width, height] of [[1440,900],[1920,1080],[768,1024],[390,844],[320,568],[844,390],[667,375],[320,320]]) {
    await page.setViewportSize({ width, height });
    // Let the 180ms drawer transition finish when crossing the compact breakpoint.
    await page.waitForTimeout(300);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `overflow ${width}x${height}`);
    const box = await page.locator('#composer').boundingBox();
    assert(box.x >= 0 && box.x + box.width <= width + 1 && box.y >= 0 && box.y + box.height <= height, `composer ${width}x${height}`);
    if (width <= 760) {
      const sidebar = await page.locator('.sidebar').boundingBox();
      assert(sidebar.x + sidebar.width <= 1, `closed drawer covers content ${width}x${height}`);
    }
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
  await newConversation();
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
  // A model-specific browser link must discover and infer through that model's API.
  const discovery = await page.request.get(base + '/v1/models', { headers: { Authorization: 'Bearer sk-dev' } });
  const modelId = (await discovery.json()).data[0].id;
  const scopedPath = '/models/' + encodeURIComponent(modelId) + '/';
  await page.goto(base + scopedPath);
  const scopedDiscovery = page.waitForResponse(response => new URL(response.url()).pathname === scopedPath + 'v1/models');
  await page.locator('#prompt').fill('Scoped endpoint works');
  await page.locator('#send').click();
  await page.locator('#api-key').fill('sk-dev');
  const scopedInference = page.waitForResponse(response => new URL(response.url()).pathname === scopedPath + 'v1/chat/completions');
  await page.locator('#connect-submit').click();
  assert.equal((await scopedDiscovery).status(), 200);
  assert.equal((await scopedInference).status(), 200);
  await page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  assert.equal(await page.locator('#model option').count(), 1);
  assert.equal(await page.locator('#model').inputValue(), modelId);
  assert.match(await page.locator('.message.assistant .message-body').innerText(), /Scoped endpoint works/); checks++;
  // Fixtures retain the real fetch/ReadableStream decoder, cancellation and rendering.
  // Delayed chunks exercise transitions that route.fulfill's buffered response cannot.
  const installStreams = async plans => page.evaluate(plans => {
    const original = window.fetch;
    window.__chatRequests = [];
    window.__restoreChatFetch = () => { window.fetch = original; };
    window.fetch = async (...args) => {
      if (!String(args[0]).endsWith('/chat/completions')) return original(...args);
      const plan = plans.shift();
      if (!plan) throw new Error('Unexpected fixture request');
      const options = args[1];
      window.__chatRequests.push(JSON.parse(options.body));
      const encoder = new TextEncoder();
      let timers = [], ended = false;
      const stopTimers = () => { timers.forEach(clearTimeout); timers = []; };
      const stream = new ReadableStream({
        start(controller) {
          const abort = () => {
            if (ended) return;
            ended = true; stopTimers(); controller.error(new DOMException('Aborted', 'AbortError'));
          };
          options.signal?.addEventListener('abort', abort, { once: true });
          for (const event of plan.events) timers.push(setTimeout(() => {
            if (ended) return;
            controller.enqueue(encoder.encode('data: ' + JSON.stringify(event.chunk) + '\n\n'));
          }, event.at));
          timers.push(setTimeout(() => {
            if (ended) return;
            if (plan.done !== false) controller.enqueue(encoder.encode('data: [DONE]\n\n'));
            ended = true; controller.close(); stopTimers();
            options.signal?.removeEventListener('abort', abort);
          }, plan.end ?? 100));
        },
        cancel() { ended = true; stopTimers(); },
      });
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } });
    };
  }, plans);
  const restoreStreams = async () => page.evaluate(() => window.__restoreChatFetch());
  const finished = async () => page.waitForFunction(() => document.querySelector('#send').getAttribute('aria-label') === 'Send message');
  const sendPrompt = async text => { await page.locator('#prompt').fill(text); await page.locator('#send').click(); };
  const shortAnswer = { events: [{ at: 0, chunk: { choices: [{ delta: { content: 'Ready.' }, finish_reason: 'stop' }] } }], end: 80 };

  await newConversation();
  await page.locator('#open-settings').click();
  await page.locator('#settings-dialog').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#reply-length').inputValue(), '1024');
  assert.deepEqual(await page.locator('#reply-length option').evaluateAll(options => options.map(option => option.value)), ['128','512','1024','2048','auto']);
  assert.equal(await page.locator('#temperature').inputValue(), '0.7');
  assert.equal(await page.locator('#temperature').getAttribute('min'), '0');
  assert.equal(await page.locator('#temperature').getAttribute('max'), '2');
  assert.equal(await page.locator('#system-prompt').inputValue(), '');
  assert.equal(await page.locator('#reply-length').evaluate(node => node.closest('dialog').id), 'settings-dialog'); checks++;
  for (const [width, height] of [[320,568],[844,390],[1440,900]]) {
    await page.setViewportSize({ width, height });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const dialogBox = await page.locator('#settings-dialog').boundingBox();
    assert(dialogBox.x >= 0 && dialogBox.y >= 0 && dialogBox.x + dialogBox.width <= width + 1 && dialogBox.y + dialogBox.height <= height + 1, `settings dialog outside ${width}x${height}`);
    await screenshot(`${output}/generation-settings-${width}x${height}.png`); checks++;
  }
  await page.locator('#reply-length').selectOption('2048');
  await page.locator('#temperature').fill('0.3');
  await page.locator('#system-prompt').fill('Reply briefly without markdown.');
  await page.locator('#close-settings').click();
  await installStreams([{ ...shortAnswer, end: 700 }, shortAnswer]);
  await sendPrompt('Use my generation settings');
  for (const selector of ['#reply-length', '#temperature', '#system-prompt', '#thinking']) assert(await page.locator(selector).isDisabled(), selector + ' must be locked during generation');
  await finished();
  let captured = await page.evaluate(() => window.__chatRequests);
  assert.equal(captured[0].max_tokens, 2048);
  assert.equal(captured[0].temperature, 0.3);
  assert.deepEqual(captured[0].messages[0], { role: 'system', content: 'Reply briefly without markdown.' }); checks++;
  await page.locator('#open-settings').click();
  await page.locator('#reply-length').selectOption('auto');
  await page.locator('#temperature').fill('0.9');
  await page.locator('#system-prompt').fill('Give concrete examples.');
  await page.locator('#close-settings').click();
  await sendPrompt('Use the updated settings'); await finished();
  captured = await page.evaluate(() => window.__chatRequests);
  assert.equal(captured[0].temperature, 0.3);
  assert.equal(captured[1].temperature, 0.9);
  assert(!Object.hasOwn(captured[1], 'max_tokens'), 'model context mode must not impose an output cap');
  assert.deepEqual(captured[1].messages[0], { role: 'system', content: 'Give concrete examples.' }); checks++;
  await restoreStreams();

  // Reasoning-only, interrupted and stopped replies must not erase the user's question
  // from the next turn, or send private reasoning as an assistant answer.
  for (const ending of ['length', 'interrupted', 'stopped']) {
    await newConversation();
    const secretReasoning = 'Internal reasoning that is not an assistant answer';
    await installStreams([
      { events: [{ at: 0, chunk: { choices: [{ delta: { reasoning_content: secretReasoning }, ...(ending === 'length' ? { finish_reason: 'length' } : {}) }] } }], end: ending === 'stopped' ? 10000 : 100, done: ending !== 'interrupted' },
      shortAnswer,
    ]);
    const question = 'Original question before ' + ending;
    await sendPrompt(question);
    if (ending === 'stopped') {
      await page.locator('.reasoning').last().waitFor({ state: 'visible' });
      await page.locator('#send').click();
    }
    await finished();
    if (ending === 'interrupted') await page.locator('#retry').waitFor({ state: 'visible' });
    await sendPrompt('Continue'); await finished();
    captured = await page.evaluate(() => window.__chatRequests);
    assert.deepEqual(captured[1].messages.filter(message => message.role === 'user').map(message => message.content), [question, 'Continue'], ending + ' lost the original question');
    assert(!JSON.stringify(captured[1].messages).includes(secretReasoning), ending + ' leaked reasoning into history');
    assert.equal(captured[1].messages.filter(message => message.role === 'assistant').length, 0); checks++;
    await restoreStreams();
  }

  const measuredUsage = { prompt_tokens: 140, completion_tokens: 10, total_tokens: 150, prompt_tokens_details: { cached_tokens: 20 } };
  const measuredTimings = { queue_ms: 125, load_ms: 250, cache_n: 20, prompt_n: 120, prompt_ms: 1500, prompt_per_second: 80, predicted_n: 9, predicted_ms: 450, predicted_per_second: 20 };
  for (const [width, height] of [[390,844],[844,390]]) {
    await page.setViewportSize({ width, height });
    await newConversation();
    await page.waitForTimeout(250);
    await installStreams([{ events: [
      { at: 0, chunk: { choices: [], prompt_progress: { unit: 'characters', processed: 200, total: 400, time_ms: 1250 } } },
      { at: 250, chunk: { choices: [], prompt_progress: { unit: 'characters', processed: 23456, total: 99999, time_ms: 98765 } } },
      { at: 500, chunk: { choices: [{ delta: { reasoning_content: 'Thinking through a longer response. '.repeat(12) } }] } },
      { at: 750, chunk: { choices: [{ delta: { content: ('A paragraph with enough content to scroll through.\n\n').repeat(25) } }] } },
      { at: 1250, chunk: { choices: [{ delta: { content: 'More streamed text should not pull the reader down.' } }] } },
      { at: 1550, chunk: { choices: [{ delta: {}, finish_reason: 'stop' }], usage: measuredUsage, timings: measuredTimings } },
    ], end: 1700 }]);
    await page.locator('#prompt').fill('Track prefill and keep the composer still');
    await page.locator('#send').click();
    const baseline = await page.locator('#composer').boundingBox();
    await page.waitForFunction(() => document.querySelector('#generation-status').textContent.includes('200 / 400 characters'));
    assert.match(await page.locator('.message-meta').last().innerText(), /Prefill.*200.*400.*1\.3 s/); checks++;
    const stableComposer = async phase => {
      const current = await page.locator('#composer').boundingBox();
      assert(Math.abs(current.y - baseline.y) <= 1 && Math.abs(current.height - baseline.height) <= 1,
        `composer moved during ${phase} at ${width}x${height}: ${JSON.stringify({ baseline, current })}`);
      assert(current.y >= 0 && current.y + current.height <= height + 1);
    };
    await page.waitForFunction(() => document.querySelector('#generation-status').textContent.includes('23,456') || document.querySelector('#generation-status').textContent.includes('23456'));
    await stableComposer('long prefill status');
    await page.waitForFunction(() => document.querySelector('.reasoning-body')?.textContent.length > 0 || document.querySelector('.reasoning')?.textContent.includes('Thinking through'));
    await stableComposer('reasoning');
    await page.waitForFunction(() => document.querySelector('.message.assistant .message-body')?.textContent.length > 1000);
    await stableComposer('reply');
    const scrollSelector = await page.evaluate(() => {
      const body = document.querySelector('.message.assistant .message-body');
      for (let node = body.parentElement; node; node = node.parentElement) {
        if (node.scrollHeight > node.clientHeight + 20 && /auto|scroll/.test(getComputedStyle(node).overflowY)) {
          node.dataset.testScroll = 'true'; node.scrollTop = 0; node.dispatchEvent(new Event('scroll')); return '[data-test-scroll]';
        }
      }
      throw new Error('No scrollable conversation found');
    });
    await page.waitForFunction(() => document.querySelector('.message.assistant .message-body')?.textContent.includes('More streamed text'));
    assert((await page.locator(scrollSelector).evaluate(node => node.scrollTop)) <= 1, 'stream pulled reader away from older messages'); checks++;
    await stableComposer('additional reply');
    await finished();
    await stableComposer('completed metrics');
    const metrics = page.locator('details.generation-metrics').last();
    await metrics.waitFor({ state: 'visible' });
    assert.match(await metrics.locator('summary').innerText(), /Prefill.*80\.0.*Decode.*20\.0/i);
    await metrics.locator('summary').click();
    const values = await metrics.locator('.metric-grid').innerText();
    for (const label of [/input/i, /output/i, /cached/i, /queue/i, /load/i, /prefill/i, /decode/i, /TTFT|first token|first output/i]) assert.match(values, label);
    for (const value of ['140', '10', '20']) assert(values.includes(value), 'missing measured token count ' + value);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); checks++;
    await page.locator(scrollSelector).evaluate(node => { node.scrollTop = node.scrollHeight; });
    await screenshot(`${output}/metrics-${width}x${height}.png`);
    await restoreStreams();
  }
  // Models separate numbered items with blank lines; that is still one list, numbered 1-3,
  // and a list that resumes after a paragraph keeps the model's own number.
  await page.setViewportSize({ width: 1440, height: 900 });
  await newConversation();
  const listed = '1. **One**: first\n\n2. **Two**: second\n\n3. **Three**: third\n\nBetween.\n\n4. Four';
  await installStreams([{ events: [{ at: 0, chunk: { choices: [{ delta: { content: listed }, finish_reason: 'stop' }] } }], end: 80 }]);
  await sendPrompt('A numbered list'); await finished();
  const lists = await page.locator('.message.assistant .message-body').last().evaluate(body =>
    [...body.querySelectorAll('ol')].map(list => ({ start: list.start, items: list.children.length })));
  assert.deepEqual(lists, [{ start: 1, items: 3 }, { start: 4, items: 1 }]); checks++;
  await restoreStreams();
  assert.deepEqual(errors, []);
  console.log(`PASS: ${checks} ${browserName} browser checks; artifacts: ${output}`);
} finally { await browser.close(); }
