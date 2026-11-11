-- Bearer tokens are one-time credentials: keep only SHA-256 digests at rest.
UPDATE students
SET reset_token = LOWER(SHA2(reset_token, 256))
WHERE reset_token IS NOT NULL AND reset_token <> '';

UPDATE students
SET verification_token = LOWER(SHA2(verification_token, 256))
WHERE verification_token IS NOT NULL AND verification_token <> '';
