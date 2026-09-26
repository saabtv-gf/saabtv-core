-- Correct the existing deployment without granting access to Neon auth tables.
BEGIN;
CREATE OR REPLACE FUNCTION public.saabtv_user_id()
RETURNS uuid LANGUAGE sql STABLE SECURITY INVOKER SET search_path = pg_catalog AS $$
    SELECT (nullif(current_setting('request.jwt.claims', true), '')::jsonb->>'sub')::uuid;
$$;
REVOKE ALL ON FUNCTION public.saabtv_user_id() FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_user_id() TO authenticated;
ALTER TABLE public.saabtv_account_state ALTER COLUMN user_id SET DEFAULT public.saabtv_user_id();
ALTER POLICY saabtv_own_account ON public.saabtv_account_state
USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
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
COMMIT;
