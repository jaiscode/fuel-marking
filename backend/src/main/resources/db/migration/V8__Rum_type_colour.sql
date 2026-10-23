-- Each rum type has its own colour (#RRGGBB), shown next to its name so a jerrican's rum can be told at a glance.
ALTER TABLE rum_types ADD COLUMN color VARCHAR(7) NOT NULL DEFAULT '#808080' CHECK (color ~ '^#[0-9A-F]{6}$');
