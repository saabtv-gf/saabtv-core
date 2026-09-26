-- Run as the database owner after enabling Managed Auth and the Data API.
-- No database credentials belong in this repository or the Android app.
BEGIN;

-- The Data API populates request.jwt.claims after validating the bearer token.
-- This narrow helper avoids the separate direct-Postgres pg_session_jwt context.
CREATE OR REPLACE FUNCTION public.saabtv_user_id()
RETURNS uuid LANGUAGE sql STABLE SECURITY INVOKER SET search_path = pg_catalog AS $$
    SELECT (nullif(current_setting('request.jwt.claims', true), '')::jsonb->>'sub')::uuid;
$$;
REVOKE ALL ON FUNCTION public.saabtv_user_id() FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_user_id() TO authenticated;

CREATE TABLE IF NOT EXISTS public.saabtv_account_state (
    user_id uuid PRIMARY KEY DEFAULT public.saabtv_user_id() REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    ciphertext text NOT NULL CHECK (octet_length(ciphertext) <= 20971520),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.saabtv_account_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_account_state FORCE ROW LEVEL SECURITY;
CREATE POLICY saabtv_own_account ON public.saabtv_account_state
    FOR ALL TO authenticated USING (user_id = public.saabtv_user_id())
    WITH CHECK (user_id = public.saabtv_user_id());
REVOKE ALL ON public.saabtv_account_state FROM PUBLIC, anonymous;
GRANT USAGE ON SCHEMA public TO authenticated, anonymous;
GRANT SELECT, INSERT, UPDATE ON public.saabtv_account_state TO authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_save_account(expected_revision bigint, encrypted_state text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$
DECLARE new_revision bigint;
BEGIN
    IF public.saabtv_user_id() IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF expected_revision < 0 OR encrypted_state IS NULL OR octet_length(encrypted_state) > 20971520
        THEN RAISE EXCEPTION 'Invalid snapshot'; END IF;
    INSERT INTO public.saabtv_account_state AS state (user_id, revision, ciphertext)
        SELECT public.saabtv_user_id(), 1, encrypted_state WHERE expected_revision = 0
        ON CONFLICT (user_id) DO NOTHING RETURNING revision INTO new_revision;
    IF new_revision IS NULL AND expected_revision > 0 THEN
        UPDATE public.saabtv_account_state SET revision = revision + 1,
            ciphertext = encrypted_state, updated_at = now()
            WHERE user_id = public.saabtv_user_id() AND revision = expected_revision
            RETURNING revision INTO new_revision;
    END IF;
    RETURN jsonb_build_object('conflict', new_revision IS NULL, 'revision', new_revision);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_save_account(bigint, text) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_save_account(bigint, text) TO authenticated;

-- Returns only a boolean; no email addresses, account IDs, hashes or sessions.
CREATE OR REPLACE FUNCTION public.saabtv_username_available(requested_username text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT requested_username ~ '^[a-z0-9_]{3,32}$' AND NOT EXISTS (
        SELECT 1 FROM neon_auth."user"
        WHERE lower(email) = requested_username || '@accounts.saabtv.invalid'
    );
$$;
REVOKE ALL ON FUNCTION public.saabtv_username_available(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.saabtv_username_available(text) TO anonymous, authenticated;
COMMIT;
