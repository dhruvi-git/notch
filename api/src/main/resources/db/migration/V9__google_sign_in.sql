-- Google sign-in: a Google account is identified by its stable subject id ("sub"), never by email.

ALTER TABLE users ADD COLUMN google_sub TEXT UNIQUE;
ALTER TABLE users ADD COLUMN email TEXT;
