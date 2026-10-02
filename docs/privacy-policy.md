# ExecuServe privacy policy

*Effective 2 October 2026. Published by [Experimental Machines](https://experimentalmachines.org) at
<https://experimentalmachines.org/execuserve/privacy/>; that page and this file change together.*

ExecuServe runs language models on your phone and serves them to apps you allow. It has no
account, no analytics, no advertising, no crash reporter and no server of ours. Nothing you
send to a model, and nothing a model replies, is sent to us.

## What stays on your phone

- **Models** you download or copy onto the phone, in the app's own storage.
- **Settings and API keys.** Keys let apps use the server; they are stored in the app's
  private storage and are excluded from Android backups and from device-to-device transfer.
- **Run history:** for each request, which model answered, which key asked (by the name you
  gave it), timings, token counts, and the phone's battery and temperature state at the time.
  Never the prompt, the reply, or the key itself. Kept for the last 10,000 requests or 30
  days, whichever comes first, and deleted with the app.
- **Requests and replies** pass through the phone's memory while they are answered. For the
  Responses API's `previous_response_id`, up to 64 recent responses are held in memory for at
  most an hour, per key; they are not written to storage and are gone when the server stops.
- **The browser chat** keeps your key and conversations only in that browser tab's memory;
  they are gone when you reload or close it.

## What leaves your phone, and when

- **Hugging Face.** When you browse the catalog, download a model, or the app shows a model
  publisher's picture, the app contacts `huggingface.co` and its download servers. Like any
  website they receive your IP address and the app's name as the user agent. No account or
  token is sent. Hugging Face's own privacy policy applies to those requests.
- **The apps and devices you let in.** The server answers whoever presents a valid key: on
  this phone only, by default, or on your local network if you choose **Your network**. In
  network mode the connection is plain HTTP, so others on the same network could read
  requests, replies and keys in transit; use a network you trust, or a private tunnel.
- **Network discovery.** In network mode only, the phone announces the server on the local
  network (as `_execuserve._tcp`, with your phone's model name) so clients can find it.
- **Things you choose to share.** Exporting your run history, or reporting a model reply,
  opens Android's share sheet. Nothing is sent until you pick where it goes, and it goes only
  there.

We do not sell, rent or share data, because we do not collect it.

## Model replies

Replies come from third-party models you choose, not from us. They can be wrong or
offensive. Any reply in the app can be reported from its **Report this reply** action, which
shows you the full report before you share it.

## Permissions

| Permission | Why |
|---|---|
| Internet, network and Wi-Fi state | Serving requests, downloading models, finding the phone's addresses |
| Wi-Fi multicast | Announcing the server on your network, in network mode only |
| Foreground service, wake lock | Keeping the server answering while the screen is off |
| Notifications | The ongoing notification that shows the server is running |
| Start at boot | Restarting the server after a reboot, if you turned that on |
| Battery optimisation exemption | Asked from Settings, so Android does not stop the server in the background |
| Hide overlays | Stops other apps covering the confirmation when another app asks to start the server |

## Children

ExecuServe is a developer tool and is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, with the effective date above. Questions:
[alpha@experimentalmachines.org](mailto:alpha@experimentalmachines.org).
