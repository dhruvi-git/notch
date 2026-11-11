# billing-service

A small sample codebase for trying Notch's Explain. Zip this folder and upload it under
Explain, or run `notch map` inside a copy of it.

It matches the sample chats: Postgres for billing data, a Redis token bucket for rate limits,
server-side sessions, Stripe webhooks for payment status, and one module that slipped in
MongoDB for analytics events, which goes against the Postgres decision.
