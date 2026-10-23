-- Each rum type has its own colour (#RRGGBB), chosen at head office and shown beside its name.
ALTER TABLE rum_types ADD COLUMN color VARCHAR(7) NOT NULL DEFAULT '#808080';
