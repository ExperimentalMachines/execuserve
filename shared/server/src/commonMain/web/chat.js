/* ExecuServe's same-origin browser client. No external dependencies or durable storage. */
"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const ui = Object.fromEntries(["model", "prompt", "messages", "welcome", "history", "send", "thinking", "notice", "retry"].map(id => [id, $(id)]));
  const chats = [];
  let active = null, key = "", connected = false, models = [], request = null, connecting = null;
  let pendingSend = false, sequence = 0, followBottom = true;
  const scroll = $("conversation-scroll");
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

  function markdown(parent, text) {
    parent.replaceChildren();
    // Process fences line by line so an unfinished fence still displays its contents.
    const lines = text.split("\n");
    let paragraph = [], code = null, language = "", list = null;
    const flush = () => {
      if (paragraph.length) {
        const p = node("p"); inline(p, paragraph.join("\n")); parent.append(p); paragraph = [];
      }
      list = null;
    };
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
      else if (!line.trim()) flush();
      else if (/^#{1,4} /.test(line)) { flush(); const h = node("h3"); inline(h, line.replace(/^#{1,4} /, "")); parent.append(h); }
      else if (/^(?:[-*] |\d+\. )/.test(line)) {
        if (paragraph.length) flush();
        const kind = /^\d/.test(line) ? "ol" : "ul";
        if (!list || list.tagName.toLowerCase() !== kind) { list = node(kind); parent.append(list); }
        const li = node("li"); inline(li, line.replace(/^(?:[-*] |\d+\. )/, "")); list.append(li);
      } else { list = null; paragraph.push(line); }
    }
    flush(); if (code !== null) flushCode();
  }

  function message(role, text, name) {
    const article = node("article", "message " + role);
    const heading = node("div", "message-heading"), avatar = node("span", "message-avatar");
    if (role === "user") avatar.textContent = "Y";
    else { const image = node("img"); image.src = "/chat/assets/mark.svg"; image.alt = ""; avatar.append(image); }
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
    turn.view = { ...assistant, details, summary, reasoning, meta };
    ui.messages.append(user.article, assistant.article);
    updateTurn(turn);
  }

  function updateTurn(turn) {
    if (!turn.view) return;
    const view = turn.view, busy = turn.status === "pending";
    view.body.classList.toggle("streaming", busy);
    if (busy) view.body.textContent = turn.content;
    else markdown(view.body, turn.content);
    view.button.hidden = !turn.content || busy;
    view.details.hidden = !turn.reasoning;
    view.reasoning.textContent = turn.reasoning;
    view.summary.textContent = busy && !turn.content ? "Thinking…" : "Thinking";
    view.meta.classList.toggle("error", turn.status === "error");
    let meta = turn.error || (turn.status === "stopped" ? "Stopped" : "");
    if (!meta && turn.finish === "length") meta = "Reply limit reached. You can ask the model to continue.";
    if (!meta && turn.usage) {
      meta = turn.usage.completion_tokens + " tokens";
      if (turn.timings?.predicted_per_second) meta += " · " + Number(turn.timings.predicted_per_second).toFixed(1) + " tokens/s";
    }
    view.meta.textContent = meta;
    if (followBottom) scroll.scrollTop = scroll.scrollHeight;
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
    const operation = { controller: new AbortController(), stopped: false, timedOut: false };
    request = operation;
    const timeout = setTimeout(() => { operation.timedOut = true; operation.controller.abort(); }, 300000);
    Object.assign(turn, { content: "", reasoning: "", status: "pending", error: "", usage: null, timings: null, finish: null });
    const messages = [];
    for (const previous of active.turns) {
      if (previous === turn) { messages.push({ role: "user", content: turn.prompt }); break; }
      if (previous.content) messages.push({ role: "user", content: previous.prompt }, { role: "assistant", content: previous.content });
    }
    notice(); renderConversation(); controls();
    $("generation-status").textContent = "Reading your message…";
    let paint = null;
    try {
      const response = await fetch("/v1/chat/completions", {
        method: "POST", headers: { ...headers(key), "Content-Type": "application/json" }, signal: operation.controller.signal,
        body: JSON.stringify({ model: turn.model, messages, stream: true, stream_options: { include_usage: true }, return_progress: true,
          max_tokens: Number($("reply-length").value), chat_template_kwargs: { enable_thinking: turn.thinking } }),
      });
      if (!response.ok) {
        if (response.status === 401) { connected = false; key = ""; }
        throw await responseError(response);
      }
      await readStream(response, chunk => {
        for (const choice of chunk.choices || []) {
          if (choice.delta?.tool_calls?.length) throw new Error("The model requested a tool. This chat can display replies but cannot run tools.");
          turn.content += choice.delta?.content || "";
          turn.reasoning += choice.delta?.reasoning_content || "";
          if (choice.finish_reason) turn.finish = choice.finish_reason;
        }
        if (turn.content.length + turn.reasoning.length > 1024 * 1024) throw new Error("The reply is too large to display. Start a new conversation.");
        if (chunk.usage) turn.usage = chunk.usage;
        if (chunk.timings) turn.timings = chunk.timings;
        $("generation-status").textContent = turn.content ? "Writing…" : turn.reasoning ? "Thinking…" : "Reading your message…";
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
    if (!model) { notice("No chat model is available. Install one in the phone’s Models tab, then reconnect.", true); return; }
    if (!active) newChat();
    const turn = { prompt, content: "", reasoning: "", model: model.id,
      thinking: model.capabilities.includes("reasoning") && ui.thinking.checked, status: "pending" };
    active.model = model.id; active.turns.push(turn); active.draft = "";
    ui.prompt.value = ""; resizePrompt(); followBottom = true;
    void generate(turn);
  }

  function openConnection() {
    $("connection-error").hidden = true;
    $("api-key").value = "";
    $("api-key").placeholder = connected ? "Leave blank to keep the current key" : "Paste your key";
    dialog.showModal();
  }

  $("connect-form").addEventListener("submit", async event => {
    event.preventDefault(); if (connecting) return;
    const operation = new AbortController(); connecting = operation;
    const timeout = setTimeout(() => operation.abort(), 20000);
    const candidate = $("api-key").value.trim() || (connected ? key : "");
    $("connect-submit").disabled = true; $("connect-submit").textContent = "Connecting…";
    $("connection-error").hidden = true;
    try {
      const response = await fetch("/v1/models", { headers: headers(candidate), signal: operation.signal, cache: "no-store" });
      if (!response.ok) throw await responseError(response);
      const body = await response.json();
      operation.signal.throwIfAborted();
      if (!Array.isArray(body.data)) throw new Error("The server returned an invalid model list.");
      const previous = ui.model.value;
      models = body.data.filter(model => typeof model.id === "string" && model.capabilities?.includes("chat"));
      models.sort((a, b) => Number(b.loaded) - Number(a.loaded) || a.id.localeCompare(b.id));
      ui.model.replaceChildren();
      if (!models.length) { const option = node("option", "", "No chat models installed"); option.value = ""; ui.model.append(option); }
      for (const model of models) { const option = node("option", "", model.aliases?.[0] || model.id); option.value = model.id; ui.model.append(option); }
      if (models.some(model => model.id === previous)) ui.model.value = previous;
      key = candidate; connected = true; $("api-key").value = ""; connecting = null;
      const sendAfterConnect = pendingSend; pendingSend = false;
      dialog.close(); controls();
      notice(models.length ? "" : "No chat models are installed. Add a model in the phone’s Models tab, then reconnect.");
      if (sendAfterConnect) submit();
    } catch (error) {
      if (dialog.open) {
        $("connection-error").textContent = operation.signal.aborted ? "Connection timed out. Check that the phone’s server is running." : error instanceof TypeError ? "Cannot reach the phone. Check your network and the server." : error.message;
        $("connection-error").hidden = false;
      }
    } finally {
      clearTimeout(timeout); if (connecting === operation) connecting = null;
      $("connect-submit").disabled = false; $("connect-submit").textContent = "Connect";
    }
  });

  $("disconnect").addEventListener("click", () => {
    if (request) { request.stopped = true; request.controller.abort(); }
    connected = false; key = ""; models = []; $("api-key").value = "";
    ui.model.replaceChildren(node("option", "", "Connect to choose a model"));
    dialog.close(); notice("Disconnected. Your conversations remain in this tab."); controls();
  });
  dialog.addEventListener("close", () => { connecting?.abort(); $("api-key").value = ""; pendingSend = false; });
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
})();
