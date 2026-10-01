# ExecuServe architecture

ExecuServe turns an Android phone into an inference endpoint for compiled ExecuTorch
models. A model is loaded once, a foreground service
keeps it resident, and any app on the device or the network talks to it over an
OpenAI-compatible HTTP API. A bundled browser chat at `/` uses that same authenticated API.

```
execuserve --model qwen3-1.7b --port 8080
curl http://phone:8080/v1/chat/completions -d '{"model":"qwen3-1.7b","messages":[...]}'
```

llama.cpp already has `llama-server`. Nothing equivalent exists for `.pte` files, which are
the fast path on phones: on a Snapdragon 8 Elite the XNNPACK export decodes 1.2 to 1.6
times faster than llama.cpp (OpenWeights `docs/research/executorch-state-and-recipes.md`).
ExecuServe is that missing server, built from what OpenWeights already measured.

## Goals and non-goals

Goals, in priority order:

1. **Correct under concurrency.** Many clients, one model, one KV cache. No request may
   see another's tokens, and no crash, timeout or disconnect may leave the cache in a state
   the next request silently extends.
2. **Keeps serving in the background.** The screen off, the app swiped away, the ROM's
   freezer: the server either stays up or says clearly why it could not.
3. **Drop-in for OpenAI clients.** The official SDKs, LangChain, Open WebUI and curl work
   unmodified against `/v1`.
4. **Boundaries drawn for Kotlin Multiplatform.** Everything that is not a platform fact
   compiles for iOS today, so the iOS app is a runtime binding and a shell, not a port.

Non-goals for the first release: a native chat UI, server-side tools, vision input, embeddings,
batching several sequences into one forward pass (the exports are batch-size one).

## Module map

```
:shared:api        KMP   OpenAI wire schema + ExecuServe's own status schema
:shared:prompt     KMP   chat templates, tool-call parser, stop markers, reasoning split
:shared:engine     KMP   runtime SPI, sequence cache, compute lane, scheduler, residency
:shared:catalog    KMP   model manifests, id resolution, Hugging Face config.json parsing
:shared:server     KMP   Ktor CIO routes, auth, limits, SSE, error mapping
:shared:host       KMP   settings and their defaults, ServeHost (start/stop engine and
                         listener as one), what each state means, the console's test request
:android:executorch      ExecuTorch runtime binding (org.pytorch:executorch-android)
:android:app             foreground service, locks, NSD, downloads, DataStore, Compose UI
:jvm:devserver           the same server on a desktop JVM over a scripted runtime
tools/execuserve         host CLI that drives the phone over adb
```

Dependency direction is strict and one-way:

```
app ──▶ host ──▶ server ──▶ engine ──▶ prompt
 │                 │          │
 │                 └──▶ api   └──▶ (runtime SPI lives in engine)
 ├──▶ catalog
 └──▶ executorch ──▶ engine (implements the SPI, nothing else)
```

### Why these are shared and those are native

A piece of code is shared when it describes *what* the server does and native when it
touches *how the platform lets it*. Concretely:

| Shared (commonMain, compiles for iOS today) | Native (per platform) |
|---|---|
| HTTP routes, SSE framing, auth, limits (Ktor CIO has `iosArm64`) | the ExecuTorch binding (AAR on Android, Apple frameworks on iOS) |
| the scheduler, queue, deadlines, cancellation | keeping the process alive (foreground service vs. iOS's rules) |
| the sequence cache and prompt rendering | wake and Wi-Fi locks, thermal and battery readings |
| OpenAI schema, error codes, usage accounting | mDNS registration (NsdManager vs. NWListener) |
| catalog parsing, model id resolution | downloads (DownloadManager vs. URLSession background) |
| the policies that read thermal/battery state | the UI (Compose now; Compose Multiplatform or SwiftUI later) |
| settings, their defaults and ranges, and their mapping onto the engine and server | where settings are stored (DataStore vs. `UserDefaults`) |
| starting and stopping the engine and listener together (`ServeHost`) | deciding when to serve (a foreground service, boot, adb) |
| what each state means (`ServerLook`, `Outcome`, `Heat`, each with a `Mood`) | the words and colours for them |
| the console's test request and its stream parsing (`ConsoleTest`, `ReplyReader`) | the socket it goes over |

The engine never learns what a phone is. It reads an `Environment` flow (thermal level,
battery, charging) that each platform fills in, and applies one policy to it.

### The host layer

`ServeHost` owns the engine and the listener for the life of a process and takes three
interfaces from the platform: `HostPlatform` (version, the environment flow, a runtime, the
lane thread, this device's endpoints and host names), `HostStore` (settings and keys as
flows) and `ModelLibrary` (the installed models, rescanned before each start). On Android
those are `AndroidPlatform`, `SettingsStore` and `ModelStore`, and the foreground service
calls `start`, `stop` and a single serialized restart. An iOS app writes the same three and
its own reason to serve; everything behind them is compiled for iOS on every build.

`HostSettings` is the one list of what a person can set. Its defaults are read from
`EngineConfig()` and `ServerSettings()`, so a limit is written down once; `Choices` holds
the ranges and menus a console offers (the adb port override is checked against the same
range); `needsRestartFrom` says which changes need the listener restarted (engine limits and
the wake policy apply live). DataStore keeps only values that differ from their defaults, so
a default changed in the engine reaches everyone who never touched it.

State meanings are enums with a `Mood` (good, working, attention, failed, idle). A platform
maps each enum to its own words (Android: `strings.xml`, with plurals) and each mood to a
colour, so the two consoles cannot disagree about what counts as healthy. `JobRecord` now
carries a typed `FinishReason` or `FailureKind`; the status JSON still spells it as before.

## The runtime SPI

```kotlin
interface LlmRuntime {
    val id: String                                   // "executorch-xnnpack"
    fun probe(files: ModelFiles): ModelFacts         // window, prefill chunk, state reset
    fun open(files: ModelFiles, facts: ModelFacts): LlmSession
}

interface LlmSession : AutoCloseable {               // one KV cache, one sequence
    val tokenizerAddsBos: Boolean
    fun prefill(text: String)                        // appends at the current position
    fun generate(text: String, temperature: Float,
                 onToken: (String) -> Unit): RuntimeOutcome
    fun reset()                                      // back to position 0, state included
    fun stop()                                       // the only call safe from another thread
}
```

It is the ExecuTorch Java API reduced to what a server needs, and deliberately
backend-general: Vulkan, QNN and MediaTek are other implementations of the same two
interfaces, and so is the iOS binding. Everything above it (templating, streaming, stop
discipline, cache bookkeeping) is ordinary Kotlin tested on a laptop against a scripted
fake.

Three runtime facts shape everything above it, all measured in OpenWeights:

- **The cache only grows.** `generate` and `prefill` append at the runner's position. There
  is no partial rollback; the only way back is `reset()` to zero.
- **The token budget is advisory.** The 1.4.0 AAR ignores `maxNewTokens`, and the sampler
  has no repetition penalty. The engine counts callbacks and stops the runner itself.
- **One call must stay under the prefill chunk.** A single prefill of `get_max_seq_len`
  tokens fails, so long prompts are fed ahead in pieces cut at whitespace.

## One compute lane

ExecuTorch's Android XNNPACK binding uses one process-wide thread pool. The pinned
pthreadpool API serializes concurrent calls to the same pool; adding generation threads
would not create independent parallel CPU compute. These exports also use batch size one.
Latency, throughput and thermal effects of another scheduler would need device measurements.

ExecuServe therefore keeps one **compute lane**: a single coroutine on a single-parallelism
dispatcher that owns every call into every runtime. Loading, unloading, prefilling,
generating and resetting are all jobs on that lane. Nothing else touches a session, which
removes every data race on the runtime and on the cache record by construction rather
than by locking.

A model is **resident** when it is loaded, mapped and holding its own KV cache. The default
is one resident; Settings can request up to two or three. With automatic CPU threads, the
Android adapter preserves a fixed process-wide pool so several models can remain resident.
A custom thread count limits residency to one. Each model retains its own client-owned
sequence cache and reply ledger. All model URLs share the same listener, queue and limits.

The configured count is a ceiling, not a prediction that those models fit in RAM. Weights,
KV caches and native workspaces all consume memory. Android's low-memory signal reduces the
safe ceiling to one; idle extras close only on the lane, including after an active request.
Critical memory callbacks release residents through that same lane. Merely hiding the
console to open another client does not evict models. Residency avoids model reloads;
generation still runs one request at a time.

## Request lifecycle

```
HTTP ─▶ parse + validate ─▶ authorize ─▶ admit ─▶ queue ─▶ lane ─▶ stream ─▶ done
          400/413              401         429/503   │        │
                                                     │        └─ cancel on disconnect,
                                                     └─ expire   deadline, stop, unload
```

1. **Validate before admitting.** Unknown model, unsupported parameters (`n>1`,
   `tool_choice:"required"`), a body over the limit, a prompt whose estimate already
   exceeds the window: all refused with a proper status before anything is queued.
2. **Admit or refuse.** The queue is bounded (default 16 waiting) and so is each client
   (default 4 in flight, keyed by API key or address). Refusals are `429` with
   `Retry-After`. Refusals for conditions (thermal, battery, stopping) are `503`.
3. **Queue.** FIFO with one bounded exception, model affinity (below). A queued request
   has a deadline (default 120 s); past it the request is failed with `timeout` and never
   touches the lane. Every request also has an absolute deadline (default 10 min).
4. **Run.** The lane makes the model resident if needed, brings the sequence cache to the
   request's prompt (below), and generates. Tokens go into the request's own channel.
5. **Stream.** The HTTP side drains that channel. The lane never waits on a slow client:
   the channel is bounded (4096 events, a fragment each) and a client that falls that far
   behind is cancelled rather than buffered. `max_tokens` is capped at the model's window.

The SSE response is opened only when the job produces its **first token** (or after 15 s
of prefill, so that heartbeats can start). Until then nothing is written, so a queue
timeout, a load failure, or a prompt that overflows the window during prefill still travels
as a real HTTP status, which is what the OpenAI SDKs retry on (408, 429, 5xx) and what an
agent reads to trim its context (`context_length_exceeded`). After the stream opens, a failure can only be sent in-stream, as
`data: {"error": {...}}`, which the Python SDK raises as `APIError`. One coroutine writes
each response, so heartbeats and tokens never interleave on the channel.

### Model affinity

With several models requested, strict FIFO swaps models on every request and spends the
phone reloading. The picker prefers the oldest request for a model that is already
resident **unless** the oldest request overall has waited longer than `maxAffinityWaitMs`
(default 10 s), in which case it takes that one. This is a preference applied when the lane
picks, not a latency bound: a long generation in progress delays everyone. The absolute
deadline is the bound.

### Cancellation

A request ends early for five reasons: the client disconnected, its deadline passed, the
server is stopping, the event channel overflowed, or someone cancelled it from the UI. All
five set one flag on the job. On the lane the flag is read:

- before a queued job starts, so a job cancelled while waiting costs nothing;
- between prefill pieces (a piece is about 200 tokens, so that is the interrupt latency
  during a long prompt; a prefill call cannot be stopped mid-call);
- on every token callback, which then calls `session.stop()`.

`session.stop()` is only ever called **from inside the token callback, on the lane**. It
therefore can only reach the generation that is running, never a later job's; no
cross-thread stop exists to race. (The 1.4.0 runner also clears its stop flag when its
token loop starts, which is why a stop that lands before that point has to be re-issued
from the callback; OpenWeights measured this.)

What a flag cannot interrupt is a native call that never returns. An off-lane watchdog
checks that a cancelled job's lane returns within a grace period (default 30 s). If it does
not, the engine reports `WEDGED`, stops admitting, and the platform restarts the process;
it is never reported as a clean cancel.

For streaming requests a disconnect is noticed on the next write: once generating, SSE
comments (`: prefilling`, which every SSE parser skips) go out every few seconds while no
token has arrived, so a client that left is noticed during a long prefill too. A request
that disconnects while still queued is noticed when it starts. For non-streaming requests
the deadline is the backstop: Ktor does not surface a closed socket until something is
written.

## The sequence cache

Each resident model has one `SequenceCache`: the exact text its runtime has consumed since
the last reset, and an estimate of the tokens that is.

A new prompt **extends** the cache when it begins with that text, is longer, and the new
suffix begins with one of the template's end-of-turn markers. Then only the suffix is fed.
Anything else resets the runtime and feeds the whole prompt. The comparison is on
rendered text and is exact, so the reason a prompt changed never matters: a different
client, an edited history, a template that re-renders an older turn. All of them simply
miss.

The marker condition is what makes text equality stand in for token equality. The runtime
caches tokens, and the same text split at an arbitrary point can tokenize differently from
the whole. A special token is split out before BPE runs, so a boundary immediately before
`<|im_end|>` tokenizes the same either way. Prefill pieces are cut before a space or after
a newline, the rule OpenWeights fuzzed against six tokenizers with no difference on the
families shipped here. Raw `/v1/completions` prompts have no such boundary and never reuse
the cache.

The record is kept only when it is certainly true. After a generation that ended on the
template's own end marker, the cache is `prompt + reply` (the runtime holds the reply's
tokens but not the unfed end token, so the next rendering re-feeds the marker, matching
OpenWeights' measured behaviour). After anything else (a client stop sequence, the token
budget, a cancellation, an error, a rut) the record is cleared, because the runtime has
consumed tokens whose text boundary is unknown. The next request then resets. Correctness
never depends on the record being kept; only speed does.

Why this matters for a server: an agent client that sends a growing conversation hits the
cache on every turn and pays prefill only for its new tail (OpenWeights measured a second
turn on Qwen3-1.7B at 215 ms against 5.8 s cold). Two clients interleaving on one model
simply miss and pay full prefill. Correct either way.

**Who owns the cache.** The server, as in vLLM, llama.cpp and SGLang: a client never
manages KV, it resends its conversation and reads `cached_tokens` (Anthropic:
`cache_read_input_tokens`) to see what was saved. The cache itself lives in the runtime:
each `LlmModule` holds a static KV cache and a position that only advances. What the server
decides is whether a request may continue from that position.

**Per key.** The runtime's turn belongs to the key that made it (`Resident.owner`), and so
does the reply ledger. Another key's request resets the runtime and reads `cached_tokens: 0`,
because a shared prefix would otherwise tell it, through `cached_tokens` and the time to the
first token, what another client's conversation began with (vLLM's `cache_salt` addresses
the same leak). One key's follow-up to its own turn, the case reuse is for, is unaffected.

**Why only an exact extension.** llama.cpp truncates the KV cache to the longest common
prefix and recomputes the rest; ExecuTorch 1.x cannot. Its Java `LlmModule` exposes no start
position (`prefillPrompt` returns nothing since 1.3, `generate_from_pos` left the C++ runner
after 0.7), so the only way back is a reset to zero. The C++ `TextPrefiller` and
`TextDecoderRunner` do take a start position, so a custom JNI runner could roll back, but
only for pure-attention models: LFM2's convolution state and Qwen3.5's recurrent state
cannot be rewound by a position. Not worth a forked AAR today.

**Stored responses are not cached KV.** `previous_response_id` keeps a response's
conversation as text (see HTTP API); continuing it renders the same prompt the client would
have sent, so it hits the runtime's cache exactly as often as a resent conversation does.

LFM2 exports made before 2026-09-17 keep convolution state across `reset()`. The binding
reads `get_state_reset_at_zero` and reopens such a file on reset instead.

### The reply ledger

Exact matching alone never hits for an OpenAI client talking to Qwen3, measured on the
emulator (0 of 48 and 0 of 38 prompt tokens reused). With reasoning off, the prompt ends in
an empty `<think>\n\n</think>\n\n` that the template does not write back into history;
with it on, the client sends back only `content`, never the thought. Either way the history
a client sends is not the bytes the runtime holds.

So each resident model keeps a small ledger: for each recent reply that ended cleanly, the
exact bytes its runtime holds for that turn (the generation opener, found by diffing the
prompt against a rendering with one more turn, then everything the model wrote), keyed by
the text an OpenAI client will send back for it (content, plus tool calls in the family's
syntax, computed by the same `HistoryText` the translator uses). When a request's assistant
turn matches a key, the engine prepares a second rendering with those bytes written back,
and the lane uses it **only if it extends what the runtime holds**. Otherwise the normal
rendering is used. So the ledger can turn a miss into a hit and can never change what is
fed on a miss.

Reasoning is written back only where Qwen's own template keeps it: turns after the latest
user message, which is a tool loop. Earlier reasoning is not, unless the user opts in
(`keepReasoningInHistory`), because that would change what the model reads. Measured on the
emulator with Qwen3-0.6B: a follow-up with reasoning off reused 35 of 52 prompt tokens and
halved prefill time; a tool loop's second request reused 287 of 314, feeding only the tool
result.

## The token pipeline

Every fragment the runtime emits passes through the same filters, in order:

1. **Stop markers.** The template's end-of-turn markers (`<|im_end|>`, `<|eot_id|>`, ...)
   plus the request's `stop` strings. A tail that could still grow into a marker is held
   back, so `<|i` never reaches a client.
2. **Stop discipline.** The `max_tokens` budget, counted per callback, and the rut guard
   (32 identical fragments in a row).
3. **Reasoning split.** `<think>...</think>` goes to `reasoning_content`, the rest to
   `content`, with partial tags held back.
4. **Tool-call guard.** When the request carries tools, text from the first tool-call
   opener on is held back and parsed at the end into `tool_calls`; finish reason
   `tool_calls`. Raw tool syntax never streams as content.

## Accounting

The Java API exposes no tokenizer. The runtime reports prompt and generated token counts
for the `generate` call; text fed ahead in pieces is estimated from that call's own
characters-per-token rate. `usage.prompt_tokens_details.cached_tokens` reports what the
sequence cache saved. Window pre-checks use four characters per token and are only a
first gate: a runtime overflow is still translated to `context_length_exceeded`.

## Backgrounding on Android

- **Foreground service, `specialUse` type.** No other type fits a server on Android 14+.
  `dataSync` has a six-hour daily cap on Android 15, and `specialUse` has no timeout. It
  is also startable from `BOOT_COMPLETED`, which Android 15 forbids for `dataSync`.
- **Doze.** In AOSP a process in foreground-service state keeps network access and its
  partial wake locks under Doze and battery saver: `NetworkPolicyManager
  .isProcStateAllowedWhileIdleOrPowerSaveMode` allows `procState <=
  FOREGROUND_THRESHOLD_STATE` (`BOUND_FOREGROUND_SERVICE`), and `PowerManagerService
  .setWakeLockDisabledStateLocked` disables idle wake locks only above that state. (Read in
  the source during design review, because the Doze guide describes apps in general and
  was cited against this.) A partial wake lock is held while serving (policy `ALWAYS`, the
  default) or only while a request is queued or running (`WHILE_BUSY`). The Wi-Fi lock is
  `WIFI_MODE_FULL_LOW_LATENCY`, which Android only honours while the screen is on and the
  app is in front, so screen-off Wi-Fi latency is the radio's to decide. All of this is AOSP
  behaviour; an OEM ROM can do less, which is the next point.
- **OEM freezers.** HyperOS freezes background apps on battery regardless of the rules
  above (measured on the POCO in OpenWeights). The app checks what it can
  (battery-optimisation exemption, notification permission), deep-links the rest (MIUI
  autostart), and shows the checklist on the status screen instead of failing silently.
- **Memory.** `onTrimMemory` enqueues an eviction command on the lane; it never closes a
  session from the callback thread. Eviction, unload and rescans are lane commands, and a
  queued job re-establishes its model's residency when it runs, so no queued job can race
  an unload.
- **Thermal.** At `SEVERE` the server stops admitting (503 with `Retry-After`) and
  finishes what it has. At `CRITICAL` it cancels the running request too.
- **Starting it.** From the app, from `adb` (`am start-foreground-service` with the same
  flags as the CLI), at boot if enabled, or from another app through the
  `execuserve://start` link. That link opens a visible confirmation, never an automatic
  start: an exported activity that starts an expensive service on its own is a nuisance
  button any app can press.
- **Restarts are not availability.** `START_STICKY` restarts the service with a null
  intent after a low-memory kill, so the configuration is persisted before the server
  starts and read back on every start. A force-stop or the Task Manager's Stop kills the app
  without callbacks; the next launch shows the last exit reason (`ApplicationExitInfo`)
  instead of pretending nothing happened.

## Exposure and security

- **Keys, one per client.** Every listener requires a bearer key by default, loopback
  included: any app with `INTERNET` can reach `127.0.0.1`. Keys are named ("Open WebUI on
  the laptop"), issued and revoked from the app, compared in constant time, and are the
  client identity for per-client limits and the request log. Unauthenticated loopback is an
  explicit opt-in.
- **Host check.** Requests whose `Host` is not a loopback name, one of the phone's own
  addresses or its mDNS name are refused. That defeats DNS rebinding, where a web page in
  the phone's browser rebinds its own hostname to `127.0.0.1` and is then same-origin with
  the server; CORS is never consulted in that attack.
- **Binding.** Loopback mode binds `127.0.0.1` and `::1` (clients that resolve `localhost`
  to `::1` first would otherwise fail). Network mode binds `::`, dual-stack.
- **Plaintext.** Network mode is HTTP. On a shared Wi-Fi the key can be sniffed, and the
  app says so where the mode is switched on. The recommended path off the phone is an
  encrypted overlay such as Tailscale; TLS with certificate pinning is on the roadmap.
- CORS is off unless enabled, then with an explicit origin list.
- Request bodies are capped (default 4 MiB, checked while reading, not only from
  `Content-Length`), idle connections time out, and message counts, stop lists and
  `max_tokens` are bounded.
- mDNS advertises `_execuserve._tcp` in network mode only.

## Storage and the catalog

A finding from the emulator that shapes the layout: under Android 11+ FUSE storage, a
folder that `adb push dir/` or `adb shell mkdir` creates inside the app's own external
directory belongs to the shell, and the app cannot list it (the server listed no models).
Files pushed into a folder the app created are readable. So the app creates `models/`
itself, sets aside one it cannot read, and the host CLI wakes the app first and then pushes
loose `Name.pte` + `Name.tokenizer.json` files into it.

Models live in the app's external files directory,
`/sdcard/Android/data/org.experimentalmachines.execuserve/files/models/`, so `adb push`
works without root and uninstalling removes them. A model is a folder:

```
models/qwen3-1.7b-8da4w-gptq-2k/
  model.pte
  tokenizer.json
  execuserve.json      id, family, source repo and revision, window, sha256
```

A bare `Name.pte` with `Name.tokenizer.json` or `tokenizer.json` beside it is also picked
up, with its family read from the name. The catalog reads the
`experimentalmachines/*-ExecuTorch` repos' `xnnpack/config.json` (variants, window, size,
sha256) at one pinned repository commit, so the manifest, the hash and the file are one
snapshot. The service downloads with HTTP range resume into a staging name, hashes while
writing, and renames into place only on a match. This checks transport, not provenance: a
compromised repository could publish a matching hash, and a signed manifest is on the
roadmap. The system `DownloadManager` was the first choice and was dropped because Android
16 subjects it to job quotas even while a foreground service runs.

Ids: the folder name is the id (`qwen3-1.7b-8da4w-gptq-2k`). A shorter alias
(`qwen3-1.7b`) resolves when exactly one installed model matches it, so
`execuserve --model qwen3-1.7b` does what it says.

## HTTP API

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | `{"status":"ok"}` or 503 while stopping |
| GET | `/v1/models`, `/v1/models/{id}` | installed models, with `context_length`, `loaded` |
| POST | `/v1/chat/completions` | streaming and not, tools, `reasoning_content` |
| POST | `/v1/completions` | raw prompt, no template |
| POST | `/v1/responses` | the Responses API: items, function calls, typed stream events, `previous_response_id` and `store` |
| POST | `/v1/messages` | Anthropic's Messages API on the same engine: blocks, tools, thinking, its stream events |
| POST | `/apply-template` | the prompt a chat request renders to, canonical (never the ledger's), without running |
| GET | `/metrics` | Prometheus text, `execuserve_` names, no client labels |
| GET | `/v1/execuserve/runs` | the caller's own run history (JSON with summaries, or CSV) |
| GET | `/v1/execuserve/status` | lane, queue, residency, threads, the caller's recent requests |
| POST | `/v1/execuserve/models/{id}/load` / `unload` | explicit residency control |

Tools that are not function tools (hosted `web_search`, `namespace` groups of MCP tools,
custom tools) are dropped and named in `x-execuserve-ignored` rather than refused: the codex
CLI offers all three in every request, and refusing them made the server unusable to it.

Errors use OpenAI's shape. Status codes: 400 `invalid_request_error`
(`context_length_exceeded`, `unsupported_parameter`, `previous_response_not_found`), 401
`invalid_api_key`, 404 `model_not_found`, 413, 429 `rate_limit_exceeded` (a client over its
share), 503 `server_overloaded` (the server full, too hot, stopping), 500. The Messages API
answers the same statuses in Anthropic's error shape. Accepted
and ignored: `top_p`, penalties, `seed`, `user`, `logit_bias`, because the runtime's sampler
has only temperature. The response header `x-execuserve-ignored` names them.

## Edge cases and what happens

| Case | Behaviour |
|---|---|
| Client disconnects mid-stream | Ktor's `HttpRequestLifecycle` cancels the handler, which cancels the job; the runner stops within a token |
| Client disconnects while its prompt is read | the same cancel, noticed between prompt chunks: the lane was busy 37 s for nobody on the POCO before the lifecycle plugin, 0.9 s after (regression test in `ServeHostTest`) |
| Client disconnects while queued | the handler is cancelled, the job leaves the queue |
| Two clients, same model | FIFO on one lane; each prompt either extends the cache or resets it |
| Queue full | 503 with `Retry-After` derived from recent job times; a client over its own share gets 429 |
| Prompt longer than the window | 400 `context_length_exceeded` before queueing (estimate) or from the runtime |
| Model file deleted while resident | the mapping keeps working; unload on next rescan |
| Request for a model not resident | loaded on the lane; the residency ceiling evicts the least-recently-used idle model when needed |
| Load fails (bad file, wrong runtime version) | that request fails 500 with the runtime's message; the model is marked broken until rescanned |
| Generation throws | job fails, cache cleared, runtime reset; the next job is unaffected |
| Runtime hangs | the watchdog marks the engine `WEDGED` after the grace period; admission closes and the service restarts the process |
| Client reads slower than the model writes | the bounded event channel overflows, the job is cancelled |
| DNS rebinding from a browser on the phone | `Host` check refuses it |
| Stop pressed with requests in flight | admission closes, queued jobs get 503, the running job gets a grace period then is cancelled |
| Port in use | server state `failed` with the reason; nothing half-started |
| Thermal SEVERE | 503 for new work; running job finishes |
| Process killed | `START_STICKY` restarts the service with the saved configuration, and the console says it restarted. On HyperOS only if Autostart is allowed: without it a crash ended serving for good on the POCO (MIUI app op 10008 logged the rejection); with it the server was back in about 2 s |
| Two models alternating | when both remain resident, each retains its cache and avoids a reload; at capacity one, affinity groups requests to reduce reloads |
| A second key sends a conversation that shares a prefix with the first key's | it starts over and reads `cached_tokens: 0` |

## What has been measured

On an Android 16 arm64 emulator with the real ExecuTorch 1.4.0 runtime (SmolLM2-135M and
Qwen3-0.6B, 2k exports), before any physical phone:

- The OpenAI Python SDK suite passes 15 of 15 on the debug and the R8-minified release
  build, across Chat Completions and Responses:
  streaming, usage chunks, tool calls streamed and not, reasoning, error classes, early
  client disconnect (the lane is idle 1.5 s later), eight concurrent requests with 429s
  retried by the SDK.
- A tool loop's follow-up reuses 287 of 314 prompt tokens (Chat Completions) and 263 of 290
  (Responses).
- Under forced deep Doze, screen off, unplugged, a request over the emulator's network is
  served in 119 ms; `dumpsys netpolicy` shows `blocked=DOZE|APP_BACKGROUND
  allowed=FOREGROUND effective=NONE` for the app's uid.
- A catalog download (107 MB) resumes, verifies SHA-256 and installs in about 15 s.
- The codex CLI completes a streamed turn over `/v1/responses` against the dev server.
- `execuserve://start` shows its confirmation naming the caller, and starting from it brings
  the server up from the foreground trampoline.

## CPU threads

ExecuTorch keeps one thread pool per process. Constructing an `LlmModule` resizes it to the
performance cores minus one (7 on the POCO, whose cpuinfo finds no efficiency cores), and
`Module.load(path, mode, numThreads)` resizes it as asked; the 1.4.0 AAR has no other knob.
So a chosen count is applied by a throwaway memory-mapped `Module.load` **between** the model's
constructor and its `load()`. The order matters: XNNPACK binds the pool that exists when the
model loads, and resizing after that frees the pool under it. The first version did it after
`load()` and crashed with SIGSEGV in `generate` on the POCO. The effective count is read back
from ExecuTorch's own log buffer (`Module.readLogBufferStatic`), shown in Settings, the status
JSON and `/metrics`, and recorded on every run.

The important distinction is changing the count. In ExecuTorch 1.4.0, a reset to the
existing count is explicitly a no-op. Automatic-thread sessions therefore share a stable
pool. Metadata probes must pass its known count explicitly: ordinary `Module.load` defaults
to half the logical cores, which can differ from the LLM constructor's default. The adapter
tracks live sessions and the pinned count across the process, synchronizes lifecycle and
probe operations, and freezes each session's thread choice until it closes. A live settings
change unloads existing sessions before the next model adopts its new thread choice.

A manual override still permits only one resident, because constructing another LLM would
restore the constructor's default before reapplying the override. Unidentified pool counts
also fall back to one resident. These safeguards need no custom native build.

Primary implementation references: [LLM constructor](https://github.com/pytorch/executorch/blob/v1.4.0/extension/android/jni/jni_layer_llama.cpp#L156-L165),
[generic Module defaults](https://github.com/pytorch/executorch/blob/v1.4.0/extension/android/jni/jni_layer.cpp#L300-L319),
[same-count reset](https://github.com/pytorch/executorch/blob/v1.4.0/extension/threadpool/threadpool.cpp#L65-L79),
and [shared-pool call serialization](https://github.com/Maratyszcza/pthreadpool/blob/a56dcd79c699366e7ac6466792c3025883ff7704/include/pthreadpool.h#L293-L294).

Earlier single-model measurements on the POCO, Qwen3-1.7B, a 704-token prompt, three runs each:

| Threads | Prefill tok/s | Decode tok/s |
|---|---|---|
| 2 | 86 | 15.5 |
| 4 | 137 | 22.0 |
| 7 (ExecuTorch's automatic) | 184 | 18.1 |
| 8 | 199 | 19.8 |

Decoding is bound by memory and falls off past four threads; prefill is bound by compute and
keeps scaling. One count applies to both phases, since resizing between them is exactly what
crashes, so the setting exists and the benchmark tells a person which count suits them.

## Run history and metrics

Every finished request becomes a `JobRecord` of figures only: model, API, stream or not,
client (the key's name and its opaque id), queue, load, prefill, first-token and total times,
decode time as the runtime reported it and on this side's clock, prompt, cached, estimated and
completion tokens, threads, thermal status, battery and charging. Never the prompt, the reply
or a key. `RunHistory` keeps them as JSON lines (the last 10,000 or 30 days, compacted when
the file runs a fifth over), and the API shows each key only its own.

Rates are derived from counts and times, never stored, so they cannot disagree with them.
`Metrics.summarize` compares like with like: prefill speed counts cold prompts only (a cache
hit reads a fraction of its tokens), speeds are kept per context size (decode fell from 25
to 10 tokens a second between a short prompt and 2,300 tokens on Qwen3-1.7B), failures count
but give no rates, benchmark runs are kept apart, and every figure carries its number of runs.

`Metrics.discrepancies` checks each run against itself without trusting either clock: the
runtime's and this side's decode times more than 10% apart, queue plus load plus prefill
longer than the time to the first token, a first token after the end, more tokens reused than
the prompt held. A prompt's tokens are partly estimated when it is read in several runtime
calls (only the last call reports a count), and the record says how many. On the POCO, 47
runs of real traffic showed no discrepancy.

The benchmark reads a numbered list, short enough for one runtime call so its token count is
exact, as a raw prompt that never reuses the cache, then generates up to 128 tokens, three
times; it reports the counts the runtime gave.

`/metrics` carries llama.cpp's counter names under `execuserve_` (prompt and generated tokens
and seconds, cached tokens, model loads), gauges for the lane, and time-to-first-token and
request-time quantiles over the last 200 runs. No label names a client.

## Hosting, compared

Surveyed on 2026-09-30 (vLLM, llama.cpp's server, SGLang, Ollama, LM Studio, Jan, KoboldCpp,
Unsloth Studio; on phones OlliteRT, MNN Chat, InferrLM, PocketPal, Edge Gallery). What was
taken, what was not, decided with codex:

| Idea | Here | Why |
|---|---|---|
| Anthropic `/v1/messages` | adopted | every major server has it; agents built on Anthropic's SDKs |
| stateful Responses (`previous_response_id`) | adopted, bounded and per key/model | LM Studio does it; agents use it; cheap, since continuing hits the cache |
| Prometheus `/metrics` | adopted | llama.cpp and OlliteRT; scrapers exist |
| `/apply-template` | adopted | how a client debugs a cache miss |
| prompt progress in the stream | adopted, opt-in, in characters | llama.cpp's `return_progress`; tokens would be estimates |
| capabilities in `/v1/models` | adopted | agents choose before they fail |
| run history, cross-checks, benchmark | adopted | none of the surveyed servers keeps a run history |
| a thread setting | adopted | PocketPal, MNN and llama.cpp have one; measured above |
| `/tokenize`, `count_tokens` | not | no tokenizer on this side of the runtime; a count would be a guess |
| grammar-constrained `json_schema`, forced tool choice | not | needs logits; a prefilled opener would promise what it cannot keep |
| several resident models | supported with automatic CPU threads | separate model caches share one unchanged thread pool and compute queue |
| continuous batching, independent parallel generation | not implemented | batch-size-one exports and a shared CPU pool; would need a separately validated scheduler/backend |
| KV save and restore, partial rollback | not | `LlmModule` exposes neither |
| Ollama's `/api/*` | not | its clients speak OpenAI too |
| energy per token | not | battery current measures the whole phone, not the model |

## Taken from OpenWeights, and left there

ExecuServe is built on OpenWeights' ExecuTorch engine and templates (vendored, byte-tested).
This round it also took the lab pictures in the catalog: the Hub organisation's avatar,
looked up once and cached. Left behind on purpose: the chat screen and its conversation
store, tools such as web fetch and memory, and the open-ended Hub search, since only the
exporter's own `.pte` files are known to run.

## The console's design

Grounded in the family it belongs to: PyTorch's paper (`#F3F4F7`) and ink (`#262626`) as a
neutral ground in both themes, PyTorch's ember (`#EE4C2C`) for the mark, and a deeper ember
for Start and selection, since white on the pure ember fails contrast. A state is a light and
a coloured word on a neutral panel, never a painted panel; failure is crimson, off ember's
hue. Type is IBM Plex (Plex Mono is what pytorch.org sets code in): Sans for everything read,
with tabular figures; Mono only for what is copied. The fonts ship subset to Latin, Greek and
Cyrillic, about 480 KB.

The mark is the Block: a chip package seen from above as one solid object, its three faces
parted by cuts, legs on the two lower edges and an ember die on the lid. It belongs to the
chip-with-pins family ExecuTorch's emblem sits in without borrowing PyTorch's symbol, which
the Foundation's guidelines rule out ("don't incorporate our logo into yours"). The cuts are
real gaps rather than lines painted in a background colour, so one drawing sits on any
ground. In one colour (themed icon, notification), where Android keeps only the shape and
ember cannot show, the die stays solid in a socket cut into the lid rather than becoming a
hole the background shows through. Below 40 px a small drawing takes over, with wider cuts,
two legs a side and a larger die.
`tools/design/mark.py` draws it once and writes every copy: the launcher's foreground and
monochrome layers (the farthest point 30 from centre, inside the 33 every mask keeps), the
notification icon, `MarkPaths.kt` for the console header, the web chat's favicon and inline
symbols (inline so the body follows the chat's own theme toggle), and `docs/brand/`. The die
keeps the true ember in both themes; interactive text uses the darker accessible ember.


## Testing

- `commonTest`/`jvmTest`: scheduler (ordering, affinity, deadlines, cancellation at every
  stage, admission limits), sequence cache, every pipeline filter, schema round trips,
  Ktor routes through `testApplication`; the host's settings mapping, state meanings, the
  console request and stream reader, and `ServeHost` over real HTTP with the fake runtime.
- `tools/design/contrast.py`: WCAG 2 and APCA for every text, label and mark colour pair the
  app uses, in both themes (targets: body and labels 4.5:1 and Lc 75, headings Lc 60, marks
  3:1 and Lc 30). 0 of 58 below target.
- `:jvm:devserver`: the real HTTP stack over a scripted runtime, exercised with the
  official OpenAI Python client.
- On device: the app on the emulator and then the POCO X8 Pro Max with a real `.pte`.

## After the first release

In order of what the architecture already allows: the iOS runtime binding and shell; the
other ExecuTorch backends (Vulkan, QNN, MediaTek) as further `LlmRuntime`s; vision input
for exports that carry an encoder; configurable server-side tools; embeddings.

## Design review log

Codex (`gpt-5.6-terra`, reasoning `xhigh`) reviewed this design adversarially on
2026-09-29. Sixteen findings; what was done with each:

| # | Finding | Disposition |
|---|---|---|
| 1 | FGS loses network and wake locks in Doze | **Rejected on evidence.** AOSP `NetworkPolicyManager` and `PowerManagerService` exempt FGS process states (quoted above). Accepted the Wi-Fi lock correction and the need to test forced idle on the real phone. |
| 2 | Unauthenticated loopback, DNS rebinding | Accepted: keys on every listener, `Host` allowlist. |
| 3 | Plaintext bearer on LAN | Accepted as a documented limit with a warning; TLS on the roadmap. |
| 4 | Cancellation ownership | Accepted: stop only from the callback on the lane; watchdog for native stalls. |
| 5 | Unbounded event channel | Accepted: bounded, overflow cancels; `max_tokens` capped at the window. |
| 6 | Heartbeats commit a 200 before errors are known | Accepted: SSE opens when the job starts; later errors in-stream. |
| 7 | OpenAI stream shape | Accepted as a test matrix run with the official SDK. Rejected "reject ignored sampling fields": clients send them by default; they are named in `x-execuserve-ignored`. A whole tool call in one `tool_calls` delta is valid under the chunk schema. |
| 8 | Text equality is not token equality | Accepted: reuse only across a special-token boundary; raw completions never reuse. |
| 9 | Eviction races queued jobs | Accepted: evict and unload are lane commands; residency is re-established per job. |
| 10 | Affinity is not a latency bound | Accepted: documented as a preference. |
| 11 | Sticky restarts, specialUse requirements | Accepted. |
| 12 | Exported trampoline | Accepted: visible confirmation. |
| 13 | Download integrity and Android 16 quotas | Accepted: pinned commit, own downloader; signing on the roadmap. |
| 14 | Per-address limits | Accepted: limits per key, plus a shared bucket for unauthenticated loopback. |
| 15 | `localhost` resolving to `::1` | Accepted. |
| 16 | KMP boundary | Agreed; iOS's constraint is lifecycle, not compilation. |

A second review of the code (not the design) found five issues, four real and fixed: text
around a parsed tool call was dropped, an explicit load reported success on failure, cache
occupancy counted the unfed final token, and `Host` matching missed `localhost.` and
non-canonical IPv6 spellings. The fifth, a native error said to leak into the next
generation, was checked and rejected: both fields are locals of `generate()`. Its pairing
advice was taken anyway: a session whose native generate failed is now reopened rather than
reset.

A third review, of the Android app, the reply ledger and the Responses API, found no way to
make the ledger feed text the runtime does not hold, confirmed the DUMP-guarded service as
sound for adb control, and reported five smaller issues, all fixed: reasoning items lacked
their `content_part` events (the SDK's part union includes `reasoning_text`), a network
callback could write a stale `Running` over `Stopped`, a cancelled download could be
reported as failed after its socket closed, an unmovable unreadable models folder was not
handled, and a stale adb forward from another device broke the CLI.

A fourth review, of wiring, dead code, hard-coded values and KMP readiness (2026-09-30),
found and saw fixed: Restart sent a stop and a start as two intents that could land in
either order (now one `ACTION_RESTART`); cancelling a queued download closed the socket of
the one running; the live speed divided by time since the job started, load and prompt
included (now since the first token); a download's percentage could pass 100 because the
tokenizer had no published size (its length is now added when the server states it);
Delete said it freed the model's size when it removes the whole folder; the catalog called
variants "windows"; Try it capped replies at 512 tokens without saying so. Dead or
duplicated: `Engine.position()`, a key timestamp written and never read,
`DeviceEnvironment.stop()`, unread catalog download counts, three context-window
formatters, and defaults (port, temperature, limits) written in four places. It also
pointed at the console's test client as shared logic trapped behind `HttpURLConnection`,
now `ConsoleTest` and `ReplyReader` in `:shared:host`. Two review items stay open by
choice: the CLI still spells the service's action names itself (the contract is noted in
both places), and the downloader stays native (file ownership and the foreground service
are Android's; the install plan it executes is already shared).

A fifth round (2026-09-30, codex `gpt-6.1-sol`, low effort) decided this round's scope from
three surveys (mobile apps, desktop servers, serving engines) and the phone's own evidence;
its verdicts are the "Hosting, compared" table. It picked the icon, the palette rule and IBM
Plex, then reviewed the resulting screens and found, all since fixed: run rows that hid the
model, label columns that collided, actions that wrapped addresses at large text sizes, a
green badge that broke the light-and-word rule, the thread anecdote and retention notes on
the page instead of behind a disclosure, and icon variants that lost the spark. Its
suggestion to fold the copy buttons into one menu was declined: a Copy beside the value it
copies is the faster and more findable thing, and it is what Connect is for. On the phone the
round found and fixed a client that hung up mid-prompt keeping the lane busy for 37 s, a
thread setting written nowhere (a unit test now fails if any setting lacks a stored field),
the thread-pool crash above, and HyperOS refusing to restart the server without Autostart.
Codex's check of those fixes found three more, also fixed: the dashboard's two columns read
across as one sentence (a rule now splits each pair), the Runs header labelled a status
column "Client" (the header now names line one, model and figures; line two holds client,
outcome and time), and panel headings sat on their first row (4 dp more below each). The
screenshots behind that check turned up two of our own: at 1.5x text a run's figures lost
their units to clipping (rows now stack, with units, and the header hides), and a model
started from `tools/execuserve` was stored by its alias, so every screen that compares ids
showed no startup model (the service now stores the installed id, and corrects an alias
stored earlier).

## Bundled browser chat

`shared/server/src/commonMain/web` contains the dependency-free HTML, CSS, JavaScript,
and brand mark. `generateWebAssets` embeds them into common Kotlin source for every
platform; `WebChat.kt` serves the exact asset routes and a restrictive same-origin CSP.
The shell is public, while model discovery and generation keep the API key and Host
checks. No key is embedded in assets. The client stores keys and conversations only
in memory, renders model text using DOM text nodes, and aborts the HTTP stream when
stopped. Reloading discards the session.

The settings popup captures temperature, output-token limit, and a system prompt for
each submitted turn; retries reuse that turn's settings. User messages remain in the
next request even when a reply stopped, failed, or exhausted its budget during reasoning.
Only final assistant content is replayed as an assistant answer. The client never
silently drops old turns to fit the model's context window.

Each completed or stopped generation has an expandable measurements panel: prefill
and decode rates, input/output/cached token counts, queue/load/phase timings, and
server `first_token_ms` when available. Browser elapsed time includes network overhead;
a browser-observed first-output time is labelled separately if server timing is absent.
Generation settings change sampling and instructions; they do not train the model.

The browser output selector includes “Available model context”, which omits `max_tokens`.
The engine and model still enforce context capacity; prompt/history and generated tokens
share that capacity. This setting does not expand a compiled model’s context window.
