"""
Drives ExecuServe's /v1/messages with the official Anthropic Python SDK and checks what a
client depends on. The SDK adds /v1 itself, so the base URL has none:

    uv run --with anthropic python tools/compat/anthropic_sdk_check.py http://127.0.0.1:8080 sk-... [model]
"""
import json
import os
import sys
import time

import anthropic
from anthropic import Anthropic

# Only the key given here is sent: an ANTHROPIC_API_KEY in the environment would otherwise
# travel as x-api-key beside it, to a server that is not Anthropic's.
for name in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL"):
    os.environ.pop(name, None)

BASE, KEY = sys.argv[1], sys.argv[2]
model = sys.argv[3] if len(sys.argv) > 3 else "qwen3-1.7b"
client = Anthropic(base_url=BASE, api_key=KEY, max_retries=0, timeout=300)
results = []


def check(name, fn):
    started = time.time()
    try:
        detail = fn()
        results.append((name, True, detail))
        print(f"PASS {name} ({time.time() - started:.1f}s) {detail or ''}")
    except Exception as e:  # noqa: BLE001
        results.append((name, False, repr(e)))
        print(f"FAIL {name}: {e!r}")


# Reasoning off unless a check wants it: a thinking model spends a small budget thinking.
QUIET = {"type": "disabled"}


def text_of(message):
    return "".join(block.text for block in message.content if block.type == "text")


def create():
    r = client.messages.create(model=model, max_tokens=64, thinking=QUIET,
                               messages=[{"role": "user", "content": "Say hi in three words."}])
    assert r.type == "message" and r.role == "assistant" and r.id.startswith("msg_"), r
    assert r.content and r.content[0].type == "text" and r.content[0].text, r.content
    assert r.stop_reason in ("end_turn", "max_tokens"), r.stop_reason
    assert r.usage.output_tokens > 0 and r.usage.input_tokens > 0, r.usage
    return f"stop_reason={r.stop_reason} usage=({r.usage.input_tokens} in, {r.usage.output_tokens} out) text={text_of(r)[:60]!r}"


def system_blocks():
    r = client.messages.create(model=model, max_tokens=64, thinking=QUIET,
                               system=[{"type": "text", "text": "Answer briefly.", "cache_control": {"type": "ephemeral"}}],
                               messages=[{"role": "user", "content": [{"type": "text", "text": "Name a colour."}]}])
    assert text_of(r), r.content
    return f"text={text_of(r)[:60]!r}"


def stream():
    collected = ""
    with client.messages.stream(model=model, max_tokens=64, thinking=QUIET,
                                messages=[{"role": "user", "content": "Count to five."}]) as s:
        for text in s.text_stream:
            collected += text
        final = s.get_final_message()
    assert collected and text_of(final) == collected, (text_of(final), collected)
    assert final.stop_reason in ("end_turn", "max_tokens"), final.stop_reason
    # message_start cannot know the prompt's size; the SDK takes it from message_delta.
    assert final.usage.input_tokens > 0 and final.usage.output_tokens > 0, final.usage
    return f"stop_reason={final.stop_reason} usage=({final.usage.input_tokens} in, {final.usage.output_tokens} out) text={collected[:50]!r}"


def multi_turn_cache():
    history = [{"role": "user", "content": "Remember the word lantern."}]
    first = client.messages.create(model=model, max_tokens=64, thinking=QUIET, messages=history)
    history += [{"role": "assistant", "content": first.content}, {"role": "user", "content": "What was the word?"}]
    second = client.messages.create(model=model, max_tokens=64, thinking=QUIET, messages=history)
    u = second.usage
    return f"second turn: cache_read_input_tokens={u.cache_read_input_tokens}, input_tokens={u.input_tokens} (uncached)"


TOOLS = [{"name": "get_weather", "description": "Current weather for a city",
          "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}]
ASK = [{"role": "user", "content": "What's the weather in Manila? Use the tool."}]


def tools_round_trip():
    r = client.messages.create(model=model, max_tokens=256, tools=TOOLS, messages=ASK)
    calls = [b for b in r.content if b.type == "tool_use"]
    if not calls:
        return f"model answered without a call (stop_reason={r.stop_reason}): {text_of(r)[:80]!r}"
    call = calls[0]
    assert r.stop_reason == "tool_use" and call.id.startswith("toolu_") and isinstance(call.input, dict), r
    messages = ASK + [
        {"role": "assistant", "content": r.content},
        {"role": "user", "content": [{"type": "tool_result", "tool_use_id": call.id,
                                      "content": json.dumps({"temp_c": 31, "sky": "clear"})}]},
    ]
    follow = client.messages.create(model=model, max_tokens=128, tools=TOOLS, messages=messages)
    u = follow.usage
    return (f"called {call.name}({json.dumps(call.input)}); follow-up read {u.cache_read_input_tokens} cached tokens, "
            f"fed {u.input_tokens}; then: {text_of(follow)[:60]!r}")


def tools_stream():
    with client.messages.stream(model=model, max_tokens=256, tools=TOOLS, messages=ASK) as s:
        kinds = [event.type for event in s]
        final = s.get_final_message()
    calls = [b for b in final.content if b.type == "tool_use"]
    if not calls:
        return f"model answered without a call (stop_reason={final.stop_reason}): {text_of(final)[:80]!r}"
    assert final.stop_reason == "tool_use" and isinstance(calls[0].input, dict), final
    assert "input_json" in kinds, kinds
    return f"stop_reason={final.stop_reason} tool_use={calls[0].name}({json.dumps(calls[0].input)}) via {len(kinds)} events"


def bearer():
    token_client = Anthropic(base_url=BASE, api_key=None, auth_token=KEY, max_retries=0, timeout=300)
    r = token_client.messages.create(model=model, max_tokens=16, thinking=QUIET, messages=[{"role": "user", "content": "Hi"}])
    return f"Authorization: Bearer accepted, stop_reason={r.stop_reason}"


def errors():
    seen = []
    for exc, error_type, call in [
        (anthropic.NotFoundError, "not_found_error",
         lambda: client.messages.create(model="no-such-model", max_tokens=16, messages=[{"role": "user", "content": "x"}])),
        (anthropic.AuthenticationError, "authentication_error",
         lambda: Anthropic(base_url=BASE, api_key="wrong", max_retries=0).messages.create(
             model=model, max_tokens=16, messages=[{"role": "user", "content": "x"}])),
        (anthropic.BadRequestError, "invalid_request_error",
         lambda: client.messages.create(model=model, max_tokens=16, tools=TOOLS, tool_choice={"type": "any"},
                                        messages=[{"role": "user", "content": "x"}])),
        (anthropic.BadRequestError, "invalid_request_error",
         lambda: client.messages.create(model=model, max_tokens=16, messages=[{"role": "user", "content": [
             {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo="}}]}])),
    ]:
        try:
            call()
            raise AssertionError(f"expected {exc.__name__}")
        except exc as e:
            assert e.body["type"] == "error" and e.body["error"]["type"] == error_type, e.body
            seen.append(f"{e.status_code} {exc.__name__}")
    return ", ".join(seen)


check("create", create)
check("system blocks", system_blocks)
check("stream", stream)
check("multi-turn cache", multi_turn_cache)
check("tools", tools_round_trip)
check("tools stream", tools_stream)
check("bearer auth", bearer)
check("errors", errors)

failed = [r for r in results if not r[1]]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
