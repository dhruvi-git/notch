# Code review: billing-service, week 3

**You:** Can you look at src/lib/db.ts? The pool keeps running out under load.

**Assistant:** The pool in src/lib/db.ts allows 10 connections, but createInvoice in src/billing/invoices.ts holds one for the whole transaction. Under load, raise max to 20 and make sure every path releases the client, which the finally block already does.

**You:** Also, someone added src/events/store.ts with Mongo for analytics events. Is that fine?

**Assistant:** It goes against the decision to keep billing data in Postgres. If the events can live in Postgres too, an append-only events table would avoid running a second database. Everything under src/billing/ should stay on Postgres either way.
