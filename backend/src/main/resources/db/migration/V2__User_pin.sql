-- PBKDF2 hash of the user's PIN; set by an administrator and replicated to the sites so they can log in offline
ALTER TABLE app_users ADD COLUMN pin_hash VARCHAR(200);
