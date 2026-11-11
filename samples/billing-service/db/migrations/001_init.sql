CREATE TABLE customers (id UUID PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE);
CREATE TABLE invoices (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), customer_id UUID REFERENCES customers (id),
                       total_cents BIGINT NOT NULL, status TEXT NOT NULL);
CREATE TABLE line_items (invoice_id UUID REFERENCES invoices (id), quantity INT NOT NULL, unit_cents BIGINT NOT NULL);
CREATE TABLE sessions (id TEXT PRIMARY KEY, customer_id UUID REFERENCES customers (id), expires_at TIMESTAMPTZ);
