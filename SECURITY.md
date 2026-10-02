# Security policy

ExecuServe runs an HTTP server on a phone, so its security is the point of the design rather
than an afterthought. The model is described in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#exposure-and-security).

## Reporting a vulnerability

Email [alpha@experimentalmachines.org](mailto:alpha@experimentalmachines.org) with a
description, the steps to reproduce, and the app version or commit you tested. Please do not
open a public issue for a vulnerability. You will get an acknowledgement within a week, and a
fix or a decision with its reasoning before anything is disclosed.

## In scope

- Reaching the API without a valid key, or with a revoked one.
- One key reading another key's runs, stored responses or cache contents.
- Bypassing the `Host` check (DNS rebinding) or the browser chat's Content-Security-Policy.
- Starting the server, or changing its exposure, without the person's confirmation.
- Reading keys or settings through backups, device transfer, logs or other apps.
- Crashing or hanging the server with a request (resource exhaustion beyond the documented
  queue and body limits).

## Known and documented

- **Network mode is plain HTTP.** On a shared network, requests and the key can be read in
  transit. Use a trusted LAN or an authenticated tunnel such as Tailscale; TLS is planned.
- **Loopback is reachable by every app on the phone,** which is why a key is required there
  too unless the person turns that off in Settings.
- **The models themselves** are third-party files run as given; their outputs are not
  filtered.
