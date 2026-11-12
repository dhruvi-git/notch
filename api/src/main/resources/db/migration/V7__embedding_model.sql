-- Which model made each stored vector. Vectors from different models live in different spaces and
-- must never be compared, so search filters on this, and a background job re-embeds old vectors
-- after the model changes.
ALTER TABLE chunks ADD COLUMN embedding_model TEXT;
UPDATE chunks SET embedding_model = 'offline-hashing' WHERE embedding IS NOT NULL;
CREATE INDEX chunks_embedding_model_idx ON chunks (project_id, embedding_model);
