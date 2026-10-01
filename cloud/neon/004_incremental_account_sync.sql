-- Apply once after reviewing, then refresh the Data API schema cache.
-- Existing snapshots remain intact until each account's first successful v2 save.
BEGIN;
ALTER TABLE public.saabtv_account_state ADD COLUMN IF NOT EXISTS sync_format integer NOT NULL DEFAULT 1;
CREATE TABLE IF NOT EXISTS public.saabtv_account_objects (
    user_id uuid NOT NULL DEFAULT public.saabtv_user_id() REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    object_id uuid NOT NULL,
    ciphertext text NOT NULL CHECK (octet_length(ciphertext) BETWEEN 1 AND 20971520),
    PRIMARY KEY (user_id, object_id)
);
ALTER TABLE public.saabtv_account_objects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_account_objects FORCE ROW LEVEL SECURITY;
CREATE POLICY saabtv_own_objects ON public.saabtv_account_objects FOR ALL TO authenticated
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
REVOKE ALL ON public.saabtv_account_objects FROM PUBLIC, anonymous;
GRANT SELECT, INSERT, DELETE ON public.saabtv_account_objects TO authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_sync_version()
RETURNS integer LANGUAGE sql STABLE SECURITY INVOKER AS $$ SELECT 2; $$;
REVOKE ALL ON FUNCTION public.saabtv_sync_version() FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_version() TO authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_save_components(expected_revision bigint,
    encrypted_state text, changed_objects jsonb, retained_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$
DECLARE uid uuid := public.saabtv_user_id(); current_revision bigint; created_revision bigint;
    item jsonb; object_uuid uuid;
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF expected_revision IS NULL OR expected_revision < 0 OR encrypted_state IS NULL
        OR octet_length(encrypted_state) NOT BETWEEN 1 AND 20971520
        OR jsonb_typeof(changed_objects) IS DISTINCT FROM 'array'
        OR jsonb_array_length(changed_objects) > 20000
        OR octet_length(changed_objects::text) > 22020096
        OR retained_ids IS NULL OR cardinality(retained_ids) > 20000
        OR array_position(retained_ids, NULL) IS NOT NULL
        THEN RAISE EXCEPTION 'Invalid components'; END IF;

    IF expected_revision = 0 THEN
        INSERT INTO public.saabtv_account_state (user_id, revision, ciphertext, sync_format)
        VALUES (uid, 1, encrypted_state, 2) ON CONFLICT (user_id) DO NOTHING
        RETURNING revision INTO created_revision;
    END IF;
    SELECT revision INTO current_revision FROM public.saabtv_account_state WHERE user_id = uid FOR UPDATE;
    IF current_revision IS NULL OR (created_revision IS NULL AND current_revision <> expected_revision) THEN
        RETURN jsonb_build_object('conflict', true, 'revision', current_revision);
    END IF;

    FOR item IN SELECT value FROM jsonb_array_elements(changed_objects) LOOP
        object_uuid := (item->>'object_id')::uuid;
        IF object_uuid IS NULL OR NOT (object_uuid = ANY(retained_ids)) OR item->>'ciphertext' IS NULL
            THEN RAISE EXCEPTION 'Invalid object'; END IF;
        INSERT INTO public.saabtv_account_objects (user_id, object_id, ciphertext)
        VALUES (uid, object_uuid, item->>'ciphertext') ON CONFLICT DO NOTHING;
        IF NOT EXISTS (SELECT 1 FROM public.saabtv_account_objects
            WHERE user_id = uid AND object_id = object_uuid AND ciphertext = item->>'ciphertext')
            THEN RAISE EXCEPTION 'Immutable object mismatch'; END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM unnest(retained_ids) AS refs(id)
        WHERE NOT EXISTS (SELECT 1 FROM public.saabtv_account_objects WHERE user_id = uid AND object_id = refs.id))
        THEN RAISE EXCEPTION 'Missing account object'; END IF;
    -- Bound live object storage, not just each individual upload.
    IF (SELECT coalesce(sum(octet_length(ciphertext)), 0) FROM public.saabtv_account_objects
        WHERE user_id = uid AND object_id = ANY(retained_ids)) > 22020096
        THEN RAISE EXCEPTION 'Account objects exceed limit'; END IF;
    IF created_revision IS NULL THEN
        UPDATE public.saabtv_account_state SET revision = revision + 1,
            ciphertext = encrypted_state, updated_at = now(), sync_format = 2 WHERE user_id = uid
        RETURNING revision INTO current_revision;
    END IF;
    DELETE FROM public.saabtv_account_objects WHERE user_id = uid AND NOT (object_id = ANY(retained_ids));
    RETURN jsonb_build_object('conflict', false, 'revision', current_revision);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_save_components(bigint,text,jsonb,uuid[]) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_save_components(bigint,text,jsonb,uuid[]) TO authenticated;

-- Older app versions must not replace a v2 manifest with a stale whole snapshot.
CREATE OR REPLACE FUNCTION public.saabtv_save_account(expected_revision bigint, encrypted_state text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$
DECLARE new_revision bigint;
BEGIN
    IF public.saabtv_user_id() IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF expected_revision IS NULL OR expected_revision < 0 OR encrypted_state IS NULL
        OR octet_length(encrypted_state) NOT BETWEEN 1 AND 20971520
        THEN RAISE EXCEPTION 'Invalid snapshot'; END IF;
    INSERT INTO public.saabtv_account_state AS state (user_id, revision, ciphertext)
        SELECT public.saabtv_user_id(), 1, encrypted_state WHERE expected_revision = 0
        ON CONFLICT (user_id) DO NOTHING RETURNING revision INTO new_revision;
    IF new_revision IS NULL AND expected_revision > 0 THEN
        UPDATE public.saabtv_account_state SET revision = revision + 1, ciphertext = encrypted_state, updated_at = now()
        WHERE user_id = public.saabtv_user_id() AND revision = expected_revision AND sync_format = 1
        RETURNING revision INTO new_revision;
    END IF;
    RETURN jsonb_build_object('conflict', new_revision IS NULL, 'revision', new_revision);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_save_account(bigint,text) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_save_account(bigint,text) TO authenticated;
COMMIT;
