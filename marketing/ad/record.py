# /// script
# requires-python = ">=3.11"
# dependencies = ["openai", "anthropic"]
# ///
# Real footage for the ad, recorded from a phone serving a model: what /v1/models lists, a
# streamed reply through the OpenAI SDK with the moment each token arrived, a reply through the
# Anthropic SDK, and a cold turn against a warm one on the same long conversation. The ad
# replays these; it invents none of them.
#   tools/execuserve --model qwen3-1.7b-8da4w-gptq-4k --port 8090   (prints the key)
#   EXECUSERVE_BASE=http://127.0.0.1:8090 EXECUSERVE_KEY=... uv run marketing/ad/record.py
#   ONLY=anthropic ... to record one part (models, stream, anthropic or cache).
# Writes assets/{models,stream,anthropic,cache}.json.
import json
import os
import time
from pathlib import Path

import anthropic
import openai

HERE = Path(__file__).parent
BASE = os.environ.get('EXECUSERVE_BASE', 'http://127.0.0.1:8080')
KEY = os.environ['EXECUSERVE_KEY']
MODEL = os.environ.get('EXECUSERVE_MODEL', 'qwen3-1.7b-8da4w-gptq-4k')
DEVICE = os.environ.get('EXECUSERVE_DEVICE', 'POCO X8 Pro Max')
ONLY = os.environ.get('ONLY')
NO_THINK = {'chat_template_kwargs': {'enable_thinking': False}}
PROMPT = 'In two short sentences, why would a developer run a language model on their own phone?'

oa = openai.OpenAI(base_url=f'{BASE}/v1', api_key=KEY)
an = anthropic.Anthropic(base_url=BASE, api_key=KEY)


def save(name, data):
    (HERE / 'assets' / name).write_text(json.dumps(data, indent=1, ensure_ascii=False) + '\n')
    print('wrote', name)


def models():
    save('models.json', {'device': DEVICE, 'models': [m.id for m in oa.models.list().data]})


def stream():
    """A streamed reply, token by token, through the OpenAI SDK."""
    t0 = time.monotonic()
    tokens, usage, timings, text = [], None, None, ''
    for chunk in oa.chat.completions.create(
        model=MODEL, messages=[{'role': 'user', 'content': PROMPT}], stream=True,
        stream_options={'include_usage': True}, max_tokens=120, extra_body=NO_THINK,
    ):
        timings = (chunk.model_extra or {}).get('timings') or timings
        if chunk.usage:
            usage = chunk.usage.model_dump()
        for choice in chunk.choices:
            piece = choice.delta.content or ''
            if piece:
                tokens.append({'t': round(time.monotonic() - t0, 4), 'text': piece})
                text += piece
    save('stream.json', {
        'device': DEVICE, 'model': MODEL, 'prompt': PROMPT, 'text': text, 'tokens': tokens,
        'usage': usage, 'timings': timings, 'sdk': f'openai {openai.__version__}',
    })


def anthropic_reply():
    """The same phone through the Anthropic SDK, thinking off the Messages API's own way."""
    msg = an.messages.create(
        model=MODEL, max_tokens=60, thinking={'type': 'disabled'},
        messages=[{'role': 'user', 'content': 'Say hello to developers in five words.'}],
    )
    save('anthropic.json', {
        'model': msg.model, 'text': ''.join(b.text for b in msg.content if b.type == 'text'),
        'usage': msg.usage.model_dump(), 'sdk': f'anthropic {anthropic.__version__}',
    })


def turn(messages):
    """One streamed turn: time to the first token, the reply, and the server's own figures
    (prompt_n tokens read, cache_n of them already held in the KV cache)."""
    start, reply, ttft, timings = time.monotonic(), '', None, None
    for chunk in oa.chat.completions.create(model=MODEL, messages=messages, stream=True, max_tokens=24,
                                           stream_options={'include_usage': True}, extra_body=NO_THINK):
        timings = (chunk.model_extra or {}).get('timings') or timings
        for choice in chunk.choices:
            if choice.delta.content and ttft is None:
                ttft = time.monotonic() - start
            reply += choice.delta.content or ''
    return {'ttft_s': round(ttft, 2), 'prompt_tokens': timings['prompt_n'] + timings['cache_n'],
            'cached_tokens': timings['cache_n'], 'load_ms': timings.get('load_ms', 0)}, reply


def cache():
    """A long conversation's first turn against its follow-up. A fresh nonce opens the
    conversation, so nothing of it can be cached before the first turn; the follow-up resends
    everything and adds a question, and the server's own figures say how much it reused."""
    notes = ' '.join(f'Note {i}: the build cache for module {i} was rebuilt after a dependency bump, and its tests passed.' for i in range(1, 90))
    nonce = os.urandom(6).hex()
    convo = [{'role': 'system', 'content': f'You are a terse assistant. Session {nonce}.'},
             {'role': 'user', 'content': f'Here are my build notes.\n{notes}\nHow many notes are there?'}]
    # Load the model first, with an unrelated prompt, so the cold turn times reading, not loading.
    turn([{'role': 'user', 'content': 'Say ready.'}])
    cold, answer = turn(convo)
    convo += [{'role': 'assistant', 'content': answer}, {'role': 'user', 'content': 'And which module was last?'}]
    warm, _ = turn(convo)
    assert cold['cached_tokens'] == 0, f'the first turn was not cold: {cold}'
    assert cold['load_ms'] == 0 and warm['load_ms'] == 0, f'a timed turn included loading the model: {cold} {warm}'
    save('cache.json', {'model': MODEL, 'device': DEVICE, 'cold': cold, 'warm': warm})


PARTS = {'models': models, 'stream': stream, 'anthropic': anthropic_reply, 'cache': cache}
for name, part in PARTS.items():
    if ONLY in (None, name):
        part()
