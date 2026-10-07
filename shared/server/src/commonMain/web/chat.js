/* ExecuServe's same-origin browser client. No external dependencies or durable storage. */
"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const ui = Object.fromEntries(["model", "prompt", "messages", "welcome", "history", "send", "thinking", "notice", "retry"].map(id => [id, $(id)]));
  // A sign-in link (the phone's QR code) carries the key after "#": read once, then taken out
  // of the address bar and this history entry so it is not left on screen or bookmarked.
  const linkKey = new URLSearchParams(location.hash.slice(1)).get("key");
  if (linkKey !== null) history.replaceState(history.state, "", location.pathname + location.search);
  const scopePath = location.pathname.match(/^\/models\/([^/]+)\/?$/);
  const apiBase = scopePath ? "/models/" + scopePath[1] + "/v1" : "/v1";
  const chats = [];
  let active = null, key = "", connected = false, models = [], request = null, connecting = null;
  let pendingSend = false, sequence = 0, followBottom = true;
  const scroll = $("conversation-scroll");
  const touch = matchMedia("(pointer: coarse)");
  const dialog = $("connect-dialog");
  const mobile = matchMedia("(max-width: 760px)");
  const id = () => String(++sequence);
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const headers = candidate => candidate ? { Authorization: "Bearer " + candidate } : {};
  const selectedModel = () => models.find(model => model.id === ui.model.value);

  function notice(text = "", error = false, retry = false) {
    ui.notice.hidden = !text;
    ui.notice.classList.toggle("error", error);
    $("notice-text").textContent = text;
    ui.retry.hidden = !retry;
  }

  function controls() {
    const busy = Boolean(request);
    ui.send.disabled = !busy && !ui.prompt.value.trim();
    ui.send.classList.toggle("stopping", busy);
    ui.send.setAttribute("aria-label", busy ? "Stop generating" : "Send message");
    $("send-symbol").textContent = busy ? "■" : "↑";
    ui.model.disabled = busy || !models.length;
    ui.thinking.disabled = busy;
    ["reply-length", "temperature", "system-prompt"].forEach(id => { $(id).disabled = busy; });
    $("thinking-control").hidden = !selectedModel()?.capabilities?.includes("reasoning");
    $("new-chat").disabled = busy;
    ui.history.querySelectorAll("button").forEach(button => { button.disabled = busy; });
    $("connection").classList.toggle("connected", connected);
    $("connection-label").textContent = connected ? "Connected" : "Connect";
    $("disconnect").hidden = !connected;
  }

  function drawer(open) {
    const wasOpen = $("sidebar").classList.contains("open");
    $("sidebar").classList.toggle("open", open);
    $("scrim").hidden = !open;
    $("open-sidebar").setAttribute("aria-expanded", String(open));
    if (mobile.matches) {
      $("sidebar").inert = !open;
      document.querySelector(".workspace").inert = open;
      if (open) $("close-sidebar").focus();
      else if (wasOpen) $("open-sidebar").focus();
    } else {
      $("sidebar").inert = false;
      document.querySelector(".workspace").inert = false;
    }
  }

  function renderHistory() {
    ui.history.replaceChildren();
    if (!chats.some(chat => chat.turns.length)) ui.history.append(node("p", "history-empty", "Your conversations will appear here."));
    chats.filter(chat => chat.turns.length).slice().reverse().forEach(chat => {
      const button = node("button", "history-item", chat.turns[0].prompt.slice(0, 70));
      button.type = "button";
      button.setAttribute("aria-current", String(chat === active));
      button.disabled = Boolean(request);
      button.addEventListener("click", () => {
        if (request) return;
        if (active) active.draft = ui.prompt.value;
        active = chat;
        ui.prompt.value = chat.draft;
        if (models.some(model => model.id === chat.model)) ui.model.value = chat.model;
        renderConversation(); resizePrompt(); controls(); drawer(false);
      });
      ui.history.append(button);
    });
  }

  function newChat() {
    if (request) return;
    if (active) active.draft = ui.prompt.value;
    active = { id: id(), model: ui.model.value, turns: [], draft: "" };
    chats.push(active);
    ui.prompt.value = "";
    renderConversation(); resizePrompt(); controls(); drawer(false);
    ui.prompt.focus();
  }

  function legacyCopy(text) {
    const field = node("textarea", "clipboard-transfer");
    field.value = text; field.readOnly = true;
    document.body.append(field);
    try {
      field.focus(); field.select(); field.setSelectionRange(0, text.length);
      return document.execCommand("copy");
    } finally { field.remove(); }
  }

  function manualCopy(text) {
    const sheet = node("dialog"), title = node("h2", "", "Copy text");
    const help = node("p", "", "Your browser blocked automatic copying. Select the text below and use your device’s Copy command.");
    const field = node("textarea", "manual-copy"); field.value = text; field.readOnly = true;
    field.setAttribute("aria-label", "Text to copy");
    const close = node("button", "primary-button", "Close"); close.type = "button";
    close.addEventListener("click", () => sheet.close());
    sheet.addEventListener("close", () => sheet.remove(), { once: true });
    sheet.append(title, help, field, close); document.body.append(sheet);
    sheet.showModal(); field.focus(); field.select(); field.setSelectionRange(0, text.length);
  }

  async function copy(text, button) {
    let copied = false;
    if (navigator.clipboard && window.isSecureContext) {
      try { await navigator.clipboard.writeText(text); copied = true; } catch { /* Try the HTTP-compatible path too. */ }
    }
    if (!copied) { try { copied = legacyCopy(text); } catch { /* Offer selectable text below. */ } }
    if (copied) { button.focus(); button.textContent = "Copied"; }
    else { button.textContent = "Copy manually"; manualCopy(text); }
    setTimeout(() => { if (button.isConnected) button.textContent = "Copy"; }, 2000);
  }

  // Model output never enters innerHTML. These small Markdown affordances only create
  // text nodes: HTML, images, links and scripts cannot fetch or execute anything.
  function inline(parent, text) {
    const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*)/g;
    let start = 0;
    for (const match of text.matchAll(pattern)) {
      parent.append(document.createTextNode(text.slice(start, match.index)));
      const code = match[0].startsWith("`");
      parent.append(node(code ? "code" : "strong", "", match[0].slice(code ? 1 : 2, code ? -1 : -2)));
      start = match.index + match[0].length;
    }
    parent.append(document.createTextNode(text.slice(start)));
  }

  // Closes a bold or code span the stream has opened but not yet closed, so the half-written
  // word shows bold or code from its first token instead of as a literal ** or `.
  function closeOpen(text) {
    const fences = (text.match(/^```/gm) || []).length;
    if (fences % 2) return text; // inside an unfinished code block, which already displays as code
    const outside = text.replace(/```[\s\S]*?```/g, "");
    const lastLine = outside.slice(outside.lastIndexOf("\n") + 1);
    let closed = text;
    if ((lastLine.match(/`/g) || []).length % 2) closed += "`";
    else if ((lastLine.match(/\*\*/g) || []).length % 2 && !/\*\*\s*$/.test(lastLine)) closed += "**";
    return closed;
  }

  function markdown(parent, text) {
    parent.replaceChildren();
    // Process fences line by line so an unfinished fence still displays its contents.
    const lines = text.split("\n");
    let paragraph = [], code = null, language = "", list = null;
    const flushParagraph = () => {
      if (paragraph.length) {
        const p = node("p"); inline(p, paragraph.join("\n")); parent.append(p); paragraph = [];
      }
    };
    const flush = () => { flushParagraph(); list = null; };
    const flushCode = () => {
      const block = node("div", "code-block"), heading = node("div", "code-heading");
      const button = node("button", "copy-button", "Copy"); button.type = "button";
      const value = code.join("\n"); button.addEventListener("click", () => copy(value, button));
      heading.append(node("span", "", language || "Code"), button);
      const pre = node("pre"); pre.append(node("code", "", value));
      block.append(heading, pre); parent.append(block); code = null;
    };
    for (const line of lines) {
      if (line.startsWith("```")) {
        if (code !== null) flushCode();
        else { flush(); code = []; language = line.slice(3).trim().slice(0, 40); }
      } else if (code !== null) code.push(line);
      // A blank line ends a paragraph but not a list: models separate items with blank lines.
      else if (!line.trim()) flushParagraph();
      else if (/^#{1,4} /.test(line)) { flush(); const h = node("h3"); inline(h, line.replace(/^#{1,4} /, "")); parent.append(h); }
      else if (/^(?:[-*] |\d+\. )/.test(line)) {
        if (paragraph.length) flush();
        const kind = /^\d/.test(line) ? "ol" : "ul";
        if (!list || list.tagName.toLowerCase() !== kind) {
          list = node(kind); parent.append(list);
          // A list that resumes after other text keeps the number the model wrote.
          if (kind === "ol") list.start = parseInt(line, 10);
        }
        const li = node("li"); inline(li, line.replace(/^(?:[-*] |\d+\. )/, "")); list.append(li);
      } else { list = null; paragraph.push(line); }
    }
    flush(); if (code !== null) flushCode();
  }

  // The mark is an inline symbol (index.html), so its body follows the chat's theme toggle.
  function markIcon(size) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg"), use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    svg.setAttribute("class", "mark"); svg.setAttribute("width", size); svg.setAttribute("height", size); svg.setAttribute("aria-hidden", "true");
    use.setAttribute("href", "#mark-small"); svg.append(use); return svg;
  }

  function message(role, text, name) {
    const article = node("article", "message " + role);
    const heading = node("div", "message-heading"), avatar = node("span", "message-avatar");
    if (role === "user") avatar.textContent = "Y";
    else avatar.append(markIcon(28));
    heading.append(avatar, node("span", "message-name", name));
    const button = node("button", "copy-button", "Copy"); button.type = "button";
    button.setAttribute("aria-label", "Copy " + (role === "user" ? "your message" : "reply"));
    heading.append(button);
    const body = node("div", "message-body", text);
    article.append(heading, body);
    return { article, body, button };
  }

  function renderTurn(turn) {
    const user = message("user", turn.prompt, "You");
    user.button.addEventListener("click", () => copy(turn.prompt, user.button));
    const assistant = message("assistant", turn.content, turn.model);
    assistant.button.addEventListener("click", () => copy(turn.content, assistant.button));
    const details = node("details", "reasoning"), summary = node("summary", "", "Thinking"), reasoning = node("pre");
    details.append(summary, reasoning);
    assistant.article.insertBefore(details, assistant.body);
    const meta = node("div", "message-meta"); assistant.article.append(meta);
    const metrics = node("details", "generation-metrics"), measurements = node("summary"), grid = node("dl", "metric-grid");
    metrics.append(measurements, grid); assistant.article.append(metrics);
    measurements.addEventListener("click", () => {
      if (followBottom) requestAnimationFrame(() => { scroll.scrollTop = scroll.scrollHeight; });
    });
    turn.view = { ...assistant, details, summary, reasoning, meta, metrics, measurements, grid };
    ui.messages.append(user.article, assistant.article);
    updateTurn(turn);
  }

  function prefillLabel(progress) {
    const elapsed = (progress.time_ms / 1000).toFixed(1) + " s";
    return "Prefill · " + progress.processed.toLocaleString() + " / " + progress.total.toLocaleString() + " characters · " + elapsed;
  }

  function updateTurn(turn) {
    if (!turn.view) return;
    const view = turn.view, busy = turn.status === "pending";
    view.body.classList.toggle("streaming", busy);
    // Formatted as it streams, so a reply never shows raw ** and then jumps when it ends.
    markdown(view.body, busy ? closeOpen(turn.content) : turn.content);
    view.button.hidden = !turn.content || busy;
    view.details.hidden = !turn.reasoning;
    view.reasoning.textContent = turn.reasoning;
    view.summary.textContent = busy && !turn.content ? "Thinking…" : "Thinking";
    view.meta.classList.toggle("error", turn.status === "error");
    let meta = turn.error || (turn.status === "stopped" ? "Stopped" : "");
    if (!meta && busy && !turn.content && !turn.reasoning && turn.progress) meta = prefillLabel(turn.progress);
    if (!meta && turn.finish === "length") meta = turn.content
      ? "Reply limit reached. You can ask the model to continue."
      : "Output limit reached during thinking. Increase the output limit or turn off thinking, then ask again.";
    view.meta.textContent = meta;
    view.meta.hidden = !meta;
    renderMetrics(turn);
    if (followBottom) scroll.scrollTop = scroll.scrollHeight;
  }

  const validNumber = value => typeof value === "number" && Number.isFinite(value) && value >= 0;
  const seconds = ms => (ms / 1000).toFixed(2) + " s";
  function renderMetrics(turn) {
    const view = turn.view, timing = turn.timings || {}, usage = turn.usage || {};
    view.metrics.hidden = turn.status === "pending" || !validNumber(turn.elapsedMs);
    if (view.metrics.hidden) return;
    const summary = [];
    if (timing.prompt_ms > 0 && validNumber(timing.prompt_per_second)) summary.push("Prefill " + timing.prompt_per_second.toFixed(1) + " tok/s");
    if (timing.predicted_ms > 0 && validNumber(timing.predicted_per_second)) summary.push("Decode " + timing.predicted_per_second.toFixed(1) + " tok/s");
    summary.push(seconds(turn.elapsedMs) + " elapsed");
    view.measurements.textContent = summary.join(" · ");
    view.grid.replaceChildren();
    const metric = (label, value, format = String) => {
      if (!validNumber(value)) return;
      const item = node("div");
      item.append(node("dt", "metric-label", label), node("dd", "metric-value", format(value)));
      view.grid.append(item);
    };
    metric("Input tokens", usage.prompt_tokens);
    metric("Output tokens (including thinking)", usage.completion_tokens);
    metric("Cached input tokens", timing.cache_n ?? usage.prompt_tokens_details?.cached_tokens);
    metric("Time to first token (server)", timing.first_token_ms > 0 ? timing.first_token_ms : undefined, seconds);
    if (!(timing.first_token_ms > 0)) metric("Time to first output (browser)", turn.firstOutputMs, seconds);
    metric("Queue", timing.queue_ms, seconds);
    metric("Model loading", timing.load_ms, seconds);
    metric("Prefill", timing.prompt_ms, seconds);
    metric("Decode", timing.predicted_ms, seconds);
    metric("Elapsed (including network)", turn.elapsedMs, seconds);
    metric("Temperature", turn.settings?.temperature);
    metric("Output limit", turn.settings?.maxTokens);
  }

  function generationSettings() {
    const temperature = $("temperature");
    if (!temperature.value.trim() || !temperature.checkValidity()) {
      $("settings-dialog").showModal(); temperature.reportValidity(); return null;
    }
    return { temperature: Number(temperature.value), maxTokens: $("reply-length").value === "auto" ? null : Number($("reply-length").value), systemPrompt: $("system-prompt").value.trim() };
  }

  function renderConversation() {
    ui.messages.replaceChildren();
    const hasTurns = Boolean(active?.turns.length);
    ui.welcome.hidden = hasTurns; ui.messages.hidden = !hasTurns;
    active?.turns.forEach(renderTurn);
    const last = active?.turns.at(-1);
    notice(last?.error || "", Boolean(last?.error), Boolean(last?.error && connected));
    followBottom = true; scroll.scrollTop = scroll.scrollHeight;
    renderHistory();
  }

  async function responseError(response) {
    let description = "Request failed (" + response.status + ").";
    try { const body = await response.json(); if (body.error?.message) description = String(body.error.message).slice(0, 500); } catch { /* A proxy may return non-JSON. */ }
    if (response.status === 401) description = "This key was not accepted. Connect with a valid API key from the phone’s Settings.";
    if (response.status === 429) description = "The phone is busy. Try again" + (response.headers.get("retry-after") ? " in " + response.headers.get("retry-after") + " seconds." : " shortly.");
    return new Error(description);
  }

  async function readStream(response, onChunk) {
    if (!response.body || !response.headers.get("content-type")?.includes("text/event-stream")) throw new Error("The server did not return a streaming reply.");
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let buffer = "", data = [], done = false;
    const dispatch = () => {
      if (!data.length) return;
      const value = data.join("\n"); data = [];
      if (value === "[DONE]") { done = true; return; }
      const chunk = JSON.parse(value);
      if (chunk.error) throw new Error(String(chunk.error.message || "Generation failed."));
      onChunk(chunk);
    };
    const consume = text => {
      buffer += text;
      if (buffer.length > 1024 * 1024) throw new Error("The server returned an oversized stream event.");
      let end;
      while (!done && (end = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, end).replace(/\r$/, ""); buffer = buffer.slice(end + 1);
        if (!line) dispatch();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
    };
    try {
      while (!done) {
        const part = await reader.read();
        consume(part.done ? decoder.decode() : decoder.decode(part.value, { stream: true }));
        if (part.done) {
          if (buffer) consume("\n"); dispatch();
          if (!done) throw new Error("The connection ended before the reply finished. Try again when the phone is reachable.");
        }
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  }

  async function generate(turn) {
    if (request || !connected) return;
    const operation = { controller: new AbortController(), stopped: false, timedOut: false, startedAt: performance.now() };
    request = operation;
    const timeout = setTimeout(() => { operation.timedOut = true; operation.controller.abort(); }, 300000);
    Object.assign(turn, { content: "", reasoning: "", status: "pending", error: "", usage: null, timings: null, progress: null, finish: null, elapsedMs: null, firstOutputMs: null });
    const messages = turn.settings.systemPrompt ? [{ role: "system", content: turn.settings.systemPrompt }] : [];
    for (const previous of active.turns) {
      if (previous === turn) { messages.push({ role: "user", content: turn.prompt }); break; }
      // A failed or reasoning-only reply must not erase the user's question.
      messages.push({ role: "user", content: previous.prompt });
      if (previous.content) messages.push({ role: "assistant", content: previous.content });
    }
    notice(); renderConversation(); controls();
    $("generation-status").textContent = "Reading your message…";
    let paint = null;
    try {
      const response = await fetch(apiBase + "/chat/completions", {
        method: "POST", headers: { ...headers(key), "Content-Type": "application/json" }, signal: operation.controller.signal,
        body: JSON.stringify({ model: turn.model, messages, stream: true, stream_options: { include_usage: true }, return_progress: true,
          ...(turn.settings.maxTokens === null ? {} : { max_tokens: turn.settings.maxTokens }), temperature: turn.settings.temperature, chat_template_kwargs: { enable_thinking: turn.thinking } }),
      });
      if (!response.ok) {
        if (response.status === 401) { connected = false; key = ""; }
        throw await responseError(response);
      }
      await readStream(response, chunk => {
        for (const choice of chunk.choices || []) {
          if (choice.delta?.tool_calls?.length) throw new Error("The model requested a tool. This chat can display replies but cannot run tools.");
          if (turn.firstOutputMs === null && (choice.delta?.content || choice.delta?.reasoning_content)) turn.firstOutputMs = performance.now() - operation.startedAt;
          turn.content += choice.delta?.content || "";
          turn.reasoning += choice.delta?.reasoning_content || "";
          if (choice.finish_reason) turn.finish = choice.finish_reason;
        }
        if (turn.content.length + turn.reasoning.length > 1024 * 1024) throw new Error("The reply is too large to display. Start a new conversation.");
        const progress = chunk.prompt_progress;
        if (progress?.unit === "characters" && [progress.processed, progress.total, progress.time_ms].every(Number.isFinite) &&
            progress.total >= 0 && progress.processed >= 0 && progress.processed <= progress.total && progress.time_ms >= 0) turn.progress = progress;
        if (chunk.usage) turn.usage = chunk.usage;
        if (chunk.timings) turn.timings = chunk.timings;
        $("generation-status").textContent = turn.content ? "Writing…" : turn.reasoning ? "Thinking…" : turn.progress ? prefillLabel(turn.progress) : "Reading your message…";
        if (paint === null) paint = requestAnimationFrame(() => { paint = null; updateTurn(turn); });
      });
      if (!turn.content && !turn.reasoning) throw new Error("The model returned an empty reply. Try again or choose another model.");
      turn.status = "complete";
    } catch (error) {
      if (operation.stopped) turn.status = "stopped";
      else {
        turn.status = "error";
        turn.error = operation.timedOut ? "The reply timed out after five minutes." : error instanceof TypeError ? "Cannot reach the phone. Check that the server is running and your network is connected." : error.message;
        notice(turn.error, true, connected);
      }
    } finally {
      clearTimeout(timeout);
      if (paint !== null) cancelAnimationFrame(paint);
      turn.elapsedMs = performance.now() - operation.startedAt;
      operation.controller.abort(); request = null;
      $("generation-status").textContent = "";
      updateTurn(turn); controls();
    }
  }

  function submit() {
    if (request) { request.stopped = true; request.controller.abort(); return; }
    const prompt = ui.prompt.value.trim();
    if (!prompt) return;
    if (!connected) { pendingSend = true; openConnection(); return; }
    const model = selectedModel();
    if (!model) { notice("No chat model is available. Install one from the phone’s Library, then reconnect.", true); return; }
    const settings = generationSettings();
    if (!settings) return;
    if (!active) newChat();
    const turn = { prompt, settings, content: "", reasoning: "", model: model.id,
      thinking: model.capabilities.includes("reasoning") && ui.thinking.checked, status: "pending" };
    active.model = model.id; active.turns.push(turn); active.draft = "";
    ui.prompt.value = ""; resizePrompt(); followBottom = true;
    void generate(turn);
  }

  function openConnection() {
    $("connection-error").hidden = true; scanNote(); layoutWays();
    $("api-key").value = "";
    $("api-key").placeholder = connected ? "Leave blank to keep the current key" : "Paste your key";
    dialog.showModal();
  }

  $("connect-form").addEventListener("submit", event => {
    event.preventDefault();
    // What is typed now wins over a picture still being read.
    cancelScans();
    const typed = $("api-key").value.trim();
    if (!typed) {
      if (connected) void connect(key); else showConnectError("Paste a key, or scan the sign-in code from the phone.");
      return;
    }
    const code = readCodeText(typed);
    if (code.kind === "key") void connect(code.key); else showConnectError(codeProblem(code));
  });

  function showConnectError(text) { $("connection-error").textContent = text; $("connection-error").hidden = false; }

  /** Tries [candidate]; a newer attempt replaces one still in flight, which then says nothing. */
  async function connect(candidate) {
    connecting?.abort();
    const operation = new AbortController(); connecting = operation;
    const timeout = setTimeout(() => operation.abort(), 20000);
    $("connect-submit").disabled = true; $("connect-submit").textContent = "Connecting…";
    $("connection-error").hidden = true;
    try {
      const response = await fetch(apiBase + "/models", { headers: headers(candidate), signal: operation.signal, cache: "no-store" });
      if (!response.ok) throw await responseError(response);
      const body = await response.json();
      operation.signal.throwIfAborted();
      if (!Array.isArray(body.data)) throw new Error("The server returned an invalid model list.");
      const previous = ui.model.value;
      models = body.data.filter(model => typeof model.id === "string" && model.capabilities?.includes("chat"));
      models.sort((a, b) => Number(b.loaded) - Number(a.loaded) || a.id.localeCompare(b.id));
      ui.model.replaceChildren();
      if (!models.length) { const option = node("option", "", "No chat models installed"); option.value = ""; ui.model.append(option); }
      // A short alias names the model only when no other model shares it (two windows of one
      // model do); otherwise the full id, which says the window and the processor.
      const aliasUses = new Map();
      for (const model of models) { const alias = model.aliases?.[0]; if (alias) aliasUses.set(alias, (aliasUses.get(alias) || 0) + 1); }
      for (const model of models) {
        const alias = model.aliases?.[0];
        const option = node("option", "", alias && aliasUses.get(alias) === 1 ? alias : model.id);
        option.value = model.id; ui.model.append(option);
      }
      if (models.some(model => model.id === previous)) ui.model.value = previous;
      key = candidate; connected = true; $("api-key").value = ""; connecting = null;
      const sendAfterConnect = pendingSend; pendingSend = false;
      dialog.close(); controls();
      notice(models.length ? "" : "No chat models are installed. Add a model from the phone’s Library, then reconnect.");
      if (sendAfterConnect) submit();
    } catch (error) {
      if (dialog.open && connecting === operation) {
        showConnectError(operation.signal.aborted ? "Connection timed out. Check that the phone’s server is running." : error instanceof TypeError ? "Cannot reach the phone. Check your network and the server." : error.message);
      }
    } finally {
      clearTimeout(timeout);
      if (connecting === operation) { connecting = null; $("connect-submit").disabled = false; $("connect-submit").textContent = "Connect"; }
    }
  }

  // ---- Sign-in code: the phone shows a QR code of this page's address with the key after "#".

  /**
   * What a scanned or pasted code holds: a key (a bare key, or a sign-in link for this page's
   * own server), the address of another server, an address with no key, or nothing usable.
   * A key in a link for another server is never sent here: that server's key is its own.
   */
  function readCodeText(text) {
    const value = String(text).trim();
    if (/^https?:\/\//i.test(value)) {
      let url;
      try { url = new URL(value); } catch { return { kind: "unreadable" }; }
      const found = new URLSearchParams(url.hash.slice(1)).get("key");
      if (!found) return { kind: "address" };
      if (url.origin !== location.origin) return { kind: "elsewhere", url };
      return { kind: "key", key: found };
    }
    return /^\S{1,4096}$/.test(value) ? { kind: "key", key: value } : { kind: "unreadable" };
  }

  function codeProblem(code) {
    if (code.kind === "address") return "That is the server’s address, not a sign-in code. On the phone, tap Show sign-in code.";
    if (code.kind === "elsewhere") return "That sign-in code is for " + code.url.host + ", not this page. Open that address to use it, or paste this server’s key.";
    return "That is not a key or a sign-in code.";
  }

  function scanNote(text = "", error = false, elsewhere = null) {
    const note = $("scan-note"); note.textContent = text; note.hidden = !text; note.classList.toggle("error", error);
    // A code for another address of this phone (or another phone) opens there, where it belongs.
    otherServer = elsewhere; $("scan-open").hidden = !elsewhere;
    if (elsewhere) $("scan-open").textContent = "Open " + elsewhere.host;
  }
  let otherServer = null;
  $("scan-open").addEventListener("click", () => { if (otherServer && /^https?:$/.test(otherServer.protocol)) location.assign(otherServer.href); });

  // Every scan (camera or picture) has a turn; closing the dialog, stopping, or starting another
  // scan ends it, and nothing an ended turn finds is used.
  let scanTurn = 0;
  function cancelScans() { scanTurn++; stopCamera(); }
  const current = turn => turn === scanTurn && dialog.open;

  let qrReader = null;
  /** jsQR, fetched from the phone the first time a code is read (130 KB the chat itself never needs). */
  function loadReader() {
    if (window.jsQR) return Promise.resolve(window.jsQR);
    qrReader ||= new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "/chat/assets/qr.js";
      script.onload = () => window.jsQR ? resolve(window.jsQR) : reject(new Error("The QR reader did not start."));
      script.onerror = () => { qrReader = null; script.remove(); reject(new Error("The QR reader did not load. Check that the phone is reachable.")); };
      document.head.append(script);
    });
    return qrReader;
  }

  const scanCanvas = document.createElement("canvas");
  /** The text of a QR code in [source], drawn at most [longest] pixels on its long side; null when none is found. */
  function readCode(reader, source, width, height, longest) {
    const scale = Math.min(1, longest / Math.max(width, height));
    const w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
    scanCanvas.width = w; scanCanvas.height = h;
    const context = scanCanvas.getContext("2d", { willReadFrequently: true });
    context.drawImage(source, 0, 0, w, h);
    return reader(context.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: "attemptBoth" })?.data ?? null;
  }

  /** Connects with what a code held, or says why it cannot. */
  function useCode(text) {
    const code = readCodeText(text);
    if (code.kind !== "key") { scanNote(codeProblem(code), true, code.kind === "elsewhere" ? code.url : null); return; }
    cancelScans(); scanNote("Code read. Connecting…"); $("api-key").value = "";
    void connect(code.key).then(() => { if (!connected) scanNote(); });
  }

  /** Width and height from a PNG, GIF, WebP or JPEG header, read before anything is decoded; null for other formats. */
  async function pictureSize(file) {
    const head = new Uint8Array(await file.slice(0, 262144).arrayBuffer());
    const view = new DataView(head.buffer), text = (from, to) => String.fromCharCode(...head.subarray(from, to));
    if (head.length < 30) return null;
    if (text(1, 4) === "PNG") return [view.getUint32(16), view.getUint32(20)];
    if (text(0, 3) === "GIF") return [view.getUint16(6, true), view.getUint16(8, true)];
    if (text(0, 4) === "RIFF" && text(8, 12) === "WEBP") {
      const chunk = text(12, 16), u24 = at => head[at] | head[at + 1] << 8 | head[at + 2] << 16;
      if (chunk === "VP8X") return [1 + u24(24), 1 + u24(27)];
      if (chunk === "VP8 ") return [view.getUint16(26, true) & 0x3fff, view.getUint16(28, true) & 0x3fff];
      if (chunk === "VP8L") { const bits = view.getUint32(21, true); return [(bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1]; }
      return null;
    }
    if (head[0] === 0xff && head[1] === 0xd8) {
      // The first start-of-frame marker holds the size; every segment before it says its length.
      for (let at = 2; at + 9 < head.length;) {
        if (head[at] !== 0xff) return null;
        const marker = head[at + 1];
        if (marker === 0xff) { at++; continue; }
        if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) { at += 2; continue; }
        if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) return [view.getUint16(at + 7), view.getUint16(at + 5)];
        at += 2 + view.getUint16(at + 2);
      }
    }
    return null;
  }

  // 24 MP (a 6000 × 4000 photo) is about 96 MB decoded; one picture is decoded at a time.
  const MAX_PICTURE_BYTES = 25 * 1024 * 1024, MAX_PICTURE_PIXELS = 24e6;
  let decoding = Promise.resolve();
  async function readPicture(file) {
    cancelScans();
    const turn = scanTurn;
    if (!file || !file.type.startsWith("image/")) { scanNote("Choose a picture: a screenshot or photo of the sign-in code.", true); return; }
    if (file.size > MAX_PICTURE_BYTES) { scanNote("That picture is over 25 MB. Use a screenshot of the code instead.", true); return; }
    scanNote("Reading the picture…");
    let bitmap, release = null;
    try {
      // Sized from its header first: a small file can describe an image too large to decode.
      const size = await pictureSize(file);
      if (!current(turn)) return;
      if (!size) { scanNote("Use a PNG, JPEG, WebP or GIF picture of the code.", true); return; }
      if (!size[0] || !size[1] || size[0] * size[1] > MAX_PICTURE_PIXELS) { scanNote("That picture is too large to read. Use a screenshot of the code instead.", true); return; }
      const reader = await loadReader();
      if (!current(turn)) return;
      // Waits for the picture before it, which a newer turn makes give up at its next check.
      const before = decoding;
      decoding = new Promise(resolve => { release = resolve; });
      await before;
      if (!current(turn)) return;
      bitmap = await createImageBitmap(file);
      if (!current(turn)) return;
      // Small first (fast, and a screenshot reads at any size); larger for a photo of a distant screen.
      for (const longest of [800, 1600, 3200]) {
        const text = readCode(reader, bitmap, bitmap.width, bitmap.height, longest);
        if (text) { useCode(text); return; }
        if (longest >= Math.max(bitmap.width, bitmap.height)) break;
      }
      scanNote("No QR code found in that picture. Try a sharper, closer one, or paste the key.", true);
    } catch (error) {
      if (current(turn)) scanNote(error instanceof DOMException || error instanceof TypeError ? "This browser cannot open that picture. Use a PNG or JPEG." : error.message, true);
    } finally { bitmap?.close?.(); release?.(); }
  }

  let camera = null, cameraTimer = 0;
  async function startCamera() {
    cancelScans(); scanNote();
    const turn = scanTurn;
    // Browsers give the camera only to secure pages; this page comes over plain HTTP unless
    // opened at localhost. A phone's browser can still take a photo through the file picker.
    if (!liveCamera()) {
      if (touch.matches) { $("scan-file").setAttribute("capture", "environment"); $("scan-file").click(); return; }
      scanNote("Browsers allow the camera only on secure (https) pages, and this page comes straight from the phone over your network. Upload a screenshot or photo of the code instead, or paste the key.", true);
      return;
    }
    try {
      // The reader first, so a camera is never opened that a failed load would leave running.
      const reader = await loadReader();
      if (!current(turn)) return;
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false });
      // Stopped, closed or replaced while the browser asked for permission: let go of it.
      if (!current(turn)) { stream.getTracks().forEach(track => track.stop()); return; }
      camera = stream;
      const video = $("scan-video"); video.srcObject = stream; $("scanner").hidden = false;
      await video.play();
      const tick = () => {
        if (camera !== stream) return;
        if (video.readyState >= 2 && video.videoWidth) {
          const text = readCode(reader, video, video.videoWidth, video.videoHeight, 720);
          if (text) { useCode(text); if (camera !== stream) return; }
        }
        cameraTimer = setTimeout(tick, 200);
      };
      tick();
    } catch (error) {
      if (!current(turn)) return;
      stopCamera();
      scanNote(error?.name === "NotAllowedError" ? "Camera access was declined. Allow it in the browser’s site settings, or upload a picture." : error?.name === "NotFoundError" ? "No camera found. Upload a picture of the code, or paste the key." : error.message || "The camera did not start.", true);
    }
  }
  function stopCamera() {
    clearTimeout(cameraTimer);
    camera?.getTracks().forEach(track => track.stop()); camera = null;
    const video = $("scan-video"); video.pause(); video.srcObject = null; $("scanner").hidden = true;
  }

  // ---- Pairing: the page shows a code and the phone scans it with its own camera. The way in
  // for a laptop, whose browser refuses the camera to a plain-HTTP page.

  const liveCamera = () => window.isSecureContext && Boolean(navigator.mediaDevices?.getUserMedia);
  let pairing = null;

  /** Which ways in fit this device: a laptop starts with the pairing code; a phone with its camera. */
  function layoutWays() {
    const laptop = !touch.matches;
    // A laptop's camera works only on a secure page; a phone's browser can still take a photo.
    $("scan-camera").hidden = laptop && !liveCamera();
    $("show-pairing").hidden = laptop || !$("pairing").hidden;
    $("connect-help").replaceChildren(...(laptop
      ? ["Or, on the phone, tap ", strong("Show sign\u2011in code"), " under Chat in a browser and upload a picture of it, or paste the key."]
      : ["On the phone hosting the models, tap ", strong("Show sign\u2011in code"), " under Chat in a browser, then scan it here."]));
    if (laptop && !connected) showPairing();
  }
  function strong(text) { return node("strong", "", text); }

  function showPairing() {
    $("pairing").hidden = false; $("show-pairing").hidden = true;
    void startPairing();
  }

  function pairStatus(text, error = false, retry = false) {
    $("pair-status").textContent = text; $("pair-status").classList.toggle("error", error); $("pair-retry").hidden = !retry;
  }

  let qrWriter = null;
  function loadWriter() {
    if (window.qrcode) return Promise.resolve(window.qrcode);
    qrWriter ||= new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "/chat/assets/qrgen.js";
      script.onload = () => window.qrcode ? resolve(window.qrcode) : reject(new Error("The code could not be drawn."));
      script.onerror = () => { qrWriter = null; script.remove(); reject(new Error("The code could not be drawn. Check that the phone is reachable.")); };
      document.head.append(script);
    });
    return qrWriter;
  }

  function drawPairCode(writer, text) {
    const code = writer(0, "M"); code.addData(text); code.make();
    const canvas = $("pair-qr"), count = code.getModuleCount(), quiet = 4;
    // Whole device pixels per module at the size the canvas is shown, so no module blurs.
    const shown = (canvas.getBoundingClientRect().width || 150) * (window.devicePixelRatio || 1);
    const module = Math.max(2, Math.floor(shown / (count + quiet * 2)));
    canvas.width = canvas.height = module * (count + quiet * 2);
    const offset = module * quiet;
    const context = canvas.getContext("2d");
    context.fillStyle = "#fff"; context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#000";
    for (let row = 0; row < count; row++) for (let column = 0; column < count; column++) {
      if (code.isDark(row, column)) context.fillRect(offset + column * module, offset + row * module, module, module);
    }
  }

  /** Asks the phone for a pairing, shows it, and waits for the phone's answer. */
  async function startPairing() {
    if (pairing || connected) return;
    const mine = pairing = { controller: new AbortController(), id: null, poll: null };
    const signal = mine.controller.signal;
    pairStatus("Getting a code…"); $("pair-code").textContent = "······";
    try {
      // The drawer first: a pairing is asked for only when it can be shown.
      const writer = await loadWriter();
      if (pairing !== mine) return;
      // Not aborted when the dialog closes: the phone may already have made the pairing,
      // and only its answer says which one to cancel.
      const response = await fetch("/pair", { method: "POST", headers: { "x-execuserve-pair": "1" }, cache: "no-store" });
      if (response.status === 429) throw new Error("Too many browsers are waiting to sign in to this phone. Try again in a few minutes.");
      if (!response.ok) throw await responseError(response);
      const body = await response.json();
      // Kept at once, so whatever happens next can still cancel it on the phone.
      mine.id = body.pairing; mine.poll = body.poll;
      if (pairing !== mine) { cancelOnPhone(mine); return; }
      drawPairCode(writer, body.scan); $("pair-code").textContent = body.code;
      pairStatus("Waiting for the phone…");
      while (pairing === mine) {
        const reply = await fetch("/pair/" + encodeURIComponent(mine.id) + "/wait", { method: "POST", headers: { "x-execuserve-pair-poll": mine.poll }, signal, cache: "no-store" });
        const answer = await reply.json().catch(() => ({}));
        if (pairing !== mine) return;
        if (answer.status === "approved" && typeof answer.key === "string") {
          pairing = null; pairStatus("Approved on the phone. Connecting…");
          await connect(answer.key);
          if (!connected) pairStatus("The phone approved, but the key was not accepted. Get a new code and try again.", true, true);
          return;
        }
        if (answer.status === "declined") { pairing = null; pairStatus("Declined on the phone.", true, true); return; }
        // Expired (three minutes pass) or forgotten by a restart: a fresh code, without asking.
        if (reply.status === 410) { pairing = null; void startPairing(); return; }
        if (!reply.ok) throw await responseError(reply);
      }
    } catch (error) {
      if (pairing !== mine || signal.aborted) return;
      pairing = null; cancelOnPhone(mine);
      pairStatus(error instanceof TypeError ? "Cannot reach the phone. Check that hosting is on and you are on the same network." : error.message, true, true);
    }
  }

  /** Ends the page's pairing, here and on the phone. */
  function stopPairing() {
    const mine = pairing; pairing = null;
    if (!mine) return;
    mine.controller.abort();
    cancelOnPhone(mine);
  }
  function cancelOnPhone(mine) {
    if (!mine.id || mine.cancelled) return;
    mine.cancelled = true;
    void fetch("/pair/" + encodeURIComponent(mine.id) + "/cancel", { method: "POST", headers: { "x-execuserve-pair-poll": mine.poll }, keepalive: true }).catch(() => {});
  }

  $("show-pairing").addEventListener("click", showPairing);
  $("pair-retry").addEventListener("click", () => { stopPairing(); void startPairing(); });
  window.addEventListener("pagehide", stopPairing);
  // Back to this page from the browser's history: the old code was cancelled on leaving.
  window.addEventListener("pageshow", event => {
    if (event.persisted && dialog.open && !connected && !$("pairing").hidden) void startPairing();
  });

  $("scan-camera").addEventListener("click", () => void startCamera());
  $("scan-stop").addEventListener("click", cancelScans);
  $("scan-upload").addEventListener("click", () => { cancelScans(); $("scan-file").removeAttribute("capture"); $("scan-file").click(); });
  $("scan-file").addEventListener("change", () => { const file = $("scan-file").files?.[0]; $("scan-file").value = ""; if (file) void readPicture(file); });
  // A screenshot pasted anywhere in the dialog, or a picture dropped on it.
  dialog.addEventListener("paste", event => {
    const file = Array.from(event.clipboardData?.files || []).find(item => item.type.startsWith("image/"));
    if (file) { event.preventDefault(); void readPicture(file); }
  });
  dialog.addEventListener("dragover", event => { if (event.dataTransfer?.types.includes("Files")) { event.preventDefault(); dialog.classList.add("dropping"); } });
  dialog.addEventListener("dragleave", event => { if (event.target === dialog) dialog.classList.remove("dropping"); });
  dialog.addEventListener("drop", event => { event.preventDefault(); dialog.classList.remove("dropping"); void readPicture(event.dataTransfer?.files?.[0]); });

  $("disconnect").addEventListener("click", () => {
    if (request) { request.stopped = true; request.controller.abort(); }
    connected = false; key = ""; models = []; $("api-key").value = "";
    ui.model.replaceChildren(node("option", "", "Connect to choose a model"));
    dialog.close(); notice("Disconnected. Your conversations remain in this tab."); controls();
  });
  dialog.addEventListener("close", () => { connecting?.abort(); cancelScans(); stopPairing(); $("pairing").hidden = true; $("api-key").value = ""; pendingSend = false; });
  $("open-settings").addEventListener("click", () => $("settings-dialog").showModal());
  $("close-settings").addEventListener("click", () => $("settings-dialog").close());
  $("close-connect").addEventListener("click", () => dialog.close());
  $("connection").addEventListener("click", openConnection);
  $("composer").addEventListener("submit", event => { event.preventDefault(); submit(); });
  ui.prompt.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && !mobile.matches) { event.preventDefault(); if (!request) submit(); }
  });
  function resizePrompt() { ui.prompt.style.height = "auto"; ui.prompt.style.height = Math.min(ui.prompt.scrollHeight, mobile.matches ? 120 : 180, Math.max(40, (window.visualViewport?.height || innerHeight) * .25)) + "px"; }
  ui.prompt.addEventListener("input", () => { resizePrompt(); controls(); if (active) active.draft = ui.prompt.value; });
  ui.model.addEventListener("change", () => { if (active) active.model = ui.model.value; ui.thinking.checked = false; controls(); });
  ui.retry.addEventListener("click", () => { const turn = active?.turns.at(-1); if (turn && connected && !request) void generate(turn); });
  $("new-chat").addEventListener("click", newChat);
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => {
    ui.prompt.value = button.dataset.prompt; resizePrompt(); controls(); ui.prompt.focus();
  }));
  $("open-sidebar").addEventListener("click", () => drawer(true));
  $("close-sidebar").addEventListener("click", () => drawer(false));
  $("scrim").addEventListener("click", () => drawer(false));
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && $("sidebar").classList.contains("open")) drawer(false);
    if (event.key === "Tab" && mobile.matches && $("sidebar").classList.contains("open")) {
      const focusable = Array.from($("sidebar").querySelectorAll("button:not(:disabled)"));
      const first = focusable[0], last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });
  mobile.addEventListener("change", () => { drawer(false); resizePrompt(); });
  scroll.addEventListener("scroll", () => { followBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 100; });
  const dark = matchMedia("(prefers-color-scheme: dark)");
  function themeLabel() {
    const isDark = document.documentElement.dataset.theme ? document.documentElement.dataset.theme === "dark" : dark.matches;
    $("theme").setAttribute("aria-label", "Switch to " + (isDark ? "light" : "dark") + " theme");
    return isDark;
  }
  $("theme").addEventListener("click", () => { document.documentElement.dataset.theme = themeLabel() ? "light" : "dark"; themeLabel(); });
  dark.addEventListener("change", themeLabel);
  function viewport() {
    if (!window.visualViewport || window.visualViewport.scale === 1) {
      document.documentElement.style.setProperty("--viewport-height", (window.visualViewport?.height || innerHeight) + "px");
      resizePrompt();
    }
  }
  window.visualViewport?.addEventListener("resize", viewport); window.addEventListener("resize", viewport);
  viewport(); themeLabel(); drawer(false); controls();
  // Opened from the sign-in code: connect at once, in the dialog, so a refusal is explained there.
  if (linkKey !== null) {
    openConnection();
    if (/^\S{1,4096}$/.test(linkKey)) void connect(linkKey); else showConnectError("That link has no key in it. Scan the sign-in code again, or paste the key.");
  }
})();
