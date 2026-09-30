"""
Drives an ExecuServe endpoint with the official OpenAI Python SDK and checks every
behaviour a client depends on. Works against the dev server or a phone:

    uv run --with openai python tools/compat/openai_sdk_check.py http://127.0.0.1:8080/v1 sk-...
"""
import asyncio
import json
import sys
import time

import openai
from openai import AsyncOpenAI, OpenAI

BASE, KEY = sys.argv[1], sys.argv[2]
MODEL = sys.argv[3] if len(sys.argv) > 3 else None
client = OpenAI(base_url=BASE, api_key=KEY, max_retries=0, timeout=300)
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


model = MODEL or client.models.list().data[0].id
# Reasoning off unless a check wants it: a thinking model spends a small budget thinking.
QUIET = {"chat_template_kwargs": {"enable_thinking": False}}


def models():
    listed = client.models.list().data
    assert listed and listed[0].object == "model", listed
    return f"{len(listed)} model(s), first {listed[0].id}"


def chat_plain():
    r = client.chat.completions.create(model=model, messages=[{"role": "user", "content": "Say hi in three words."}], max_tokens=64, extra_body=QUIET)
    c = r.choices[0]
    assert c.message.role == "assistant" and c.message.content, r
    assert r.usage.completion_tokens > 0 and r.usage.total_tokens == r.usage.prompt_tokens + r.usage.completion_tokens
    return f"finish={c.finish_reason} content={c.message.content[:60]!r}"


def chat_stream():
    stream = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": "Count to five."}], stream=True,
        stream_options={"include_usage": True}, max_tokens=64, extra_body=QUIET,
    )
    text, finish, usage, ids = "", None, None, set()
    for chunk in stream:
        ids.add(chunk.id)
        if chunk.usage:
            usage = chunk.usage
        for choice in chunk.choices:
            text += choice.delta.content or ""
            finish = choice.finish_reason or finish
    assert len(ids) == 1, ids
    assert usage and usage.completion_tokens > 0, usage
    assert finish in ("stop", "length"), finish
    return f"finish={finish} tokens={usage.completion_tokens} text={text[:60]!r}"


def multi_turn_cache():
    history = [{"role": "user", "content": "Remember the word lantern."}]
    first = client.chat.completions.create(model=model, messages=history, max_tokens=64, extra_body=QUIET)
    history += [{"role": "assistant", "content": first.choices[0].message.content},
                {"role": "user", "content": "What was the word?"}]
    second = client.chat.completions.create(model=model, messages=history, max_tokens=64, extra_body=QUIET)
    cached = second.usage.prompt_tokens_details.cached_tokens
    return f"first finish={first.choices[0].finish_reason} second cached_tokens={cached} of {second.usage.prompt_tokens}"


def reasoning():
    r = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": "Is 17 prime? Answer yes or no."}], max_tokens=600,
        extra_body={"chat_template_kwargs": {"enable_thinking": True}})
    m = r.choices[0].message
    thought = getattr(m, "reasoning_content", None) or (m.model_extra or {}).get("reasoning_content")
    return f"finish={r.choices[0].finish_reason} reasoning={len(thought or '')} chars content={(m.content or '')[:60]!r}"


TOOLS = [{"type": "function", "function": {
    "name": "get_weather", "description": "Current weather for a city",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}]


def tools_round_trip():
    messages = [{"role": "user", "content": "What's the weather in Manila? Use the tool."}]
    r = client.chat.completions.create(model=model, messages=messages, tools=TOOLS, max_tokens=256)
    msg = r.choices[0].message
    if not msg.tool_calls:
        return f"model answered without a call (finish={r.choices[0].finish_reason}): {msg.content[:80]!r}"
    call = msg.tool_calls[0]
    json.loads(call.function.arguments)
    assert r.choices[0].finish_reason == "tool_calls"
    messages += [msg.model_dump(exclude_none=True),
                 {"role": "tool", "tool_call_id": call.id, "content": json.dumps({"temp_c": 31, "sky": "clear"})}]
    follow = client.chat.completions.create(model=model, messages=messages, tools=TOOLS, max_tokens=128)
    cached = follow.usage.prompt_tokens_details.cached_tokens
    return (f"called {call.function.name}({call.function.arguments}); follow-up reused {cached} of "
            f"{follow.usage.prompt_tokens} prompt tokens; then: {(follow.choices[0].message.content or '')[:60]!r}")


def tools_stream():
    stream = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": "What's the weather in Manila? Use the tool."}],
        tools=TOOLS, stream=True, max_tokens=256)
    calls, finish = {}, None
    for chunk in stream:
        for choice in chunk.choices:
            for tc in choice.delta.tool_calls or []:
                entry = calls.setdefault(tc.index, {"name": "", "args": ""})
                entry["name"] += tc.function.name or ""
                entry["args"] += tc.function.arguments or ""
            finish = choice.finish_reason or finish
    if calls:
        json.loads(calls[0]["args"])
        assert finish == "tool_calls", finish
    return f"finish={finish} calls={calls}"


def errors():
    for exc, kwargs in [
        (openai.NotFoundError, dict(model="no-such-model", messages=[{"role": "user", "content": "x"}])),
        (openai.BadRequestError, dict(model=model, n=2, messages=[{"role": "user", "content": "x"}])),
    ]:
        try:
            client.chat.completions.create(**kwargs)
            raise AssertionError(f"expected {exc.__name__}")
        except exc:
            pass
    try:
        OpenAI(base_url=BASE, api_key="wrong", max_retries=0).models.list()
        raise AssertionError("expected AuthenticationError")
    except openai.AuthenticationError:
        pass
    return "404, 400 and 401 map to the SDK's exception classes"


def early_close():
    stream = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": "Write a long story about a lighthouse."}],
        stream=True, max_tokens=400)
    got = 0
    for chunk in stream:
        got += 1
        if got >= 3:
            break
    stream.close()
    closed = time.time()
    import urllib.request
    req = urllib.request.Request(BASE.rsplit("/v1", 1)[0] + "/v1/execuserve/status", headers={"Authorization": f"Bearer {KEY}"})
    # A cancel is noticed within a token, or after the prefill piece in flight (about 200
    # tokens, over a second on a phone), so poll rather than race it.
    while True:
        status = json.load(urllib.request.urlopen(req))
        if status["lane"] == "idle" or time.time() - closed > 5:
            break
        time.sleep(0.25)
    outcome = status["recent"][0]["outcome"] if status["recent"] else None
    assert status["lane"] == "idle", status["lane"]
    return f"lane idle {time.time() - closed:.1f} s after early close; last outcome={outcome}"


async def concurrent():
    aclient = AsyncOpenAI(base_url=BASE, api_key=KEY, max_retries=6, timeout=600)

    async def one(i):
        r = await aclient.chat.completions.create(
            model=model, messages=[{"role": "user", "content": f"Request {i}: say ok."}], max_tokens=32)
        return r.choices[0].finish_reason

    started = time.time()
    outcomes = await asyncio.gather(*(one(i) for i in range(8)), return_exceptions=True)
    errors = [o for o in outcomes if isinstance(o, Exception)]
    assert not errors, errors
    return f"8 concurrent requests, all finished in {time.time() - started:.1f}s (over-limit ones retried after 429)"


RTOOLS = [{"type": "function", "name": "get_weather", "description": "Current weather for a city",
           "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}]
MINIMAL = {"effort": "minimal"}


def responses_plain():
    r = client.responses.create(model=model, input="Say hi in three words.", max_output_tokens=64, reasoning=MINIMAL)
    assert r.object == "response" and r.status in ("completed", "incomplete"), r
    assert r.output_text, r
    assert r.usage.output_tokens > 0
    return f"status={r.status} output_text={r.output_text[:60]!r}"


def responses_stream():
    types, text, final = [], "", None
    with client.responses.stream(model=model, input="Count to five.", max_output_tokens=64, reasoning=MINIMAL) as stream:
        for event in stream:
            types.append(event.type)
            if event.type == "response.output_text.delta":
                text += event.delta
        final = stream.get_final_response()
    assert types[0] == "response.created" and types[-1] in ("response.completed", "response.incomplete"), types
    assert final.output_text == text, (final.output_text, text)
    return f"{len(types)} events, final status={final.status}, text={text[:50]!r}"


def responses_reasoning_stream():
    # A reply cut by max_output_tokens ends with response.incomplete, as OpenAI's spec says;
    # the SDK's get_final_response() only accepts response.completed, so read events directly.
    kinds, final = [], None
    stream = client.responses.create(model=model, input="Is 17 prime? Answer yes or no.", max_output_tokens=1500,
                                     reasoning={"effort": "low"}, stream=True)
    for event in stream:
        kinds.append(event.type)
        if event.type in ("response.completed", "response.incomplete"):
            final = event.response
    assert final is not None, kinds[-3:]
    items = [o.type for o in final.output]
    return (f"{kinds[-1]}: items={items} content_parts={kinds.count('response.content_part.added')} "
            f"text={final.output_text[:40]!r}")


def responses_tools():
    items = [{"role": "user", "content": "What's the weather in Manila? Use the tool."}]
    r = client.responses.create(model=model, input=items, tools=RTOOLS, max_output_tokens=256)
    calls = [o for o in r.output if o.type == "function_call"]
    if not calls:
        return f"model answered without a call: {r.output_text[:80]!r}"
    call = calls[0]
    json.loads(call.arguments)
    items += [o.model_dump(exclude_none=True) for o in r.output if o.type in ("message", "function_call")]
    items.append({"type": "function_call_output", "call_id": call.call_id, "output": json.dumps({"temp_c": 31, "sky": "clear"})})
    follow = client.responses.create(model=model, input=items, tools=RTOOLS, max_output_tokens=128)
    cached = follow.usage.input_tokens_details.cached_tokens
    return (f"called {call.name}({call.arguments}); follow-up reused {cached} of {follow.usage.input_tokens} "
            f"input tokens; then: {follow.output_text[:60]!r}")


def responses_errors():
    try:
        client.responses.create(model=model, input="x", previous_response_id="resp_nope")
        raise AssertionError("expected BadRequestError")
    except openai.BadRequestError as error:
        assert error.code == "previous_response_not_found", error.code
    return "an unknown previous_response_id is a 400 previous_response_not_found"


def responses_chained():
    first = client.responses.create(model=model, input="Name a fruit. /no_think", max_output_tokens=32)
    second = client.responses.create(model=model, input="Another. /no_think", previous_response_id=first.id, max_output_tokens=32)
    third = client.responses.create(model=model, input="One more. /no_think", previous_response_id=second.id, max_output_tokens=32)
    cached = third.usage.input_tokens_details.cached_tokens
    assert third.previous_response_id == second.id
    assert cached > 0, f"third turn cached {cached}"
    return f"three turns by id; the third reused {cached} of {third.usage.input_tokens} input tokens"


check("models", models)
check("chat", chat_plain)
check("stream", chat_stream)
check("multi-turn cache", multi_turn_cache)
check("reasoning", reasoning)
check("tools", tools_round_trip)
check("tools stream", tools_stream)
check("errors", errors)
check("early close", early_close)
check("responses", responses_plain)
check("responses stream", responses_stream)
check("responses reasoning stream", responses_reasoning_stream)
check("responses tools", responses_tools)
check("responses errors", responses_errors)
check("responses chained", responses_chained)
check("concurrency", lambda: asyncio.run(concurrent()))

failed = [r for r in results if not r[1]]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
