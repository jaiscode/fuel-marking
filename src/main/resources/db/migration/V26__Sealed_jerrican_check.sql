-- The reconciliation also checks the sealed jerricans: counted, and each seal looked at (nothing is weighed - that would break the seal).
-- A line of the signed count says what was found for a sealed jerrican: FOUND (seal intact), MISSING, or SEAL_BROKEN (found, but the seal is broken or is not the recorded one).
ALTER TABLE stock_count_lines ADD COLUMN check_result VARCHAR(12) CHECK (check_result IN ('FOUND', 'MISSING', 'SEAL_BROKEN'));
