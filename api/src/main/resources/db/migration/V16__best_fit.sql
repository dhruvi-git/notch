-- Best-fit seat assignment: placements now come from min-cost flow (most seats, then the highest
-- total match). Each run keeps the total match of all three methods so the page can compare them,
-- and each placement says whether best-score-first greedy would have put that person elsewhere.

ALTER TABLE assignment_runs
    ADD COLUMN greedy_score     DOUBLE PRECISION,
    ADD COLUMN max_flow_score   DOUBLE PRECISION,
    ADD COLUMN max_flow_filled  INT,
    ADD COLUMN best_fit_score   DOUBLE PRECISION,
    ADD COLUMN best_fit_millis  DOUBLE PRECISION;

ALTER TABLE assignments
    ADD COLUMN moved BOOLEAN NOT NULL DEFAULT false;
