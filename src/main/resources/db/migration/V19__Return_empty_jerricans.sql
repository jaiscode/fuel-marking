-- A transfer is either supplies going out from a warehouse (SUPPLY: sealed containers arrive SEALED) or finished jerricans going back
-- to a warehouse for a refill (RETURN: they arrive EMPTY).
ALTER TABLE transfers ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'SUPPLY' CHECK (kind IN ('SUPPLY', 'RETURN'));
