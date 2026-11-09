# Billing service notes

Running decisions and open work for the billing service.

Let's go with Postgres instead of MongoDB because we need joins and real transactions for payments.

We'll use Stripe webhooks for payment status, since polling the Stripe API would hit their rate limits.

## Open work

- [ ] add idempotency keys to the invoice creation endpoint
- [ ] write the monthly invoice job

Next step is writing the initial migration for customers, invoices and line_items.
