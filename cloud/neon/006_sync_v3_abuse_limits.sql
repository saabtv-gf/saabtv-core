-- Apply only after reviewing sync traffic on the deployed v3 RPCs.
-- The authenticated surface is wrapped with per-account fixed-window quotas;
-- the former SECURITY DEFINER implementations become owner-only internals.
BEGIN;

CREATE SCHEMA IF NOT EXISTS saabtv_sync_private;
REVOKE ALL ON SCHEMA saabtv_sync_private FROM PUBLIC, anonymous, authenticated;

CREATE TABLE saabtv_sync_private.quota_windows (
    user_id uuid PRIMARY KEY REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    push_calls integer NOT NULL DEFAULT 0 CHECK (push_calls >= 0),
    push_bytes bigint NOT NULL DEFAULT 0 CHECK (push_bytes >= 0),
    pull_calls integer NOT NULL DEFAULT 0 CHECK (pull_calls >= 0)
);
REVOKE ALL ON saabtv_sync_private.quota_windows FROM PUBLIC, anonymous, authenticated;

-- Keep authenticated clients read-only on the encrypted component tables. All
-- state mutations must pass through the quota-checked CAS RPCs below.
DROP POLICY IF EXISTS saabtv_own_objects ON public.saabtv_account_objects;
CREATE POLICY saabtv_own_objects ON public.saabtv_account_objects FOR ALL TO PUBLIC
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
DROP POLICY IF EXISTS saabtv_own_account ON public.saabtv_account_state;
CREATE POLICY saabtv_own_account ON public.saabtv_account_state FOR ALL TO PUBLIC
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
REVOKE INSERT, DELETE ON public.saabtv_account_objects FROM authenticated;
GRANT SELECT ON public.saabtv_account_objects TO authenticated;
REVOKE INSERT, UPDATE ON public.saabtv_account_state FROM authenticated;
GRANT SELECT ON public.saabtv_account_state TO authenticated;

CREATE FUNCTION saabtv_sync_private.consume_quota(account_id uuid, request_kind text, request_bytes bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, saabtv_sync_private AS $$
DECLARE
    now_at timestamptz := clock_timestamp();
    started_at timestamptz;
    pushes integer;
    bytes bigint;
    pulls integer;
BEGIN
    IF account_id IS NULL OR request_kind IS NULL OR request_kind NOT IN ('push', 'pull')
       OR request_bytes IS NULL OR request_bytes < 0 THEN
        RAISE EXCEPTION 'Invalid sync quota request';
    END IF;

    INSERT INTO saabtv_sync_private.quota_windows(user_id, window_started_at)
        VALUES (account_id, now_at) ON CONFLICT (user_id) DO NOTHING;
    SELECT window_started_at, push_calls, push_bytes, pull_calls
        INTO started_at, pushes, bytes, pulls
        FROM saabtv_sync_private.quota_windows WHERE user_id = account_id FOR UPDATE;

    IF started_at <= now_at - interval '1 minute' THEN
        started_at := now_at;
        pushes := 0;
        bytes := 0;
        pulls := 0;
    END IF;

    IF request_kind = 'push' THEN
        IF pushes >= 30 OR bytes + request_bytes > 26214400 THEN
            RAISE EXCEPTION 'Sync write rate limit exceeded; retry after the current window.'
                USING ERRCODE = 'PT429';
        END IF;
        UPDATE saabtv_sync_private.quota_windows SET
            window_started_at = started_at, push_calls = pushes + 1,
            push_bytes = bytes + request_bytes, pull_calls = pulls
        WHERE user_id = account_id;
    ELSE
        IF pulls >= 12 THEN
            RAISE EXCEPTION 'Sync read rate limit exceeded; retry after the current window.'
                USING ERRCODE = 'PT429';
        END IF;
        UPDATE saabtv_sync_private.quota_windows SET
            window_started_at = started_at, push_calls = pushes,
            push_bytes = bytes, pull_calls = pulls + 1
        WHERE user_id = account_id;
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION saabtv_sync_private.consume_quota(uuid, text, bigint)
    FROM PUBLIC, anonymous, authenticated;

-- Retain the existing data validations/CAS behavior, but run those bodies as
-- the function owner under the explicit JWT-backed RLS policies above.
ALTER FUNCTION public.saabtv_save_components(bigint, text, jsonb, uuid[])
    RENAME TO saabtv_save_components_unthrottled;
ALTER FUNCTION public.saabtv_save_components_unthrottled(bigint, text, jsonb, uuid[])
    SECURITY DEFINER;
REVOKE ALL ON FUNCTION public.saabtv_save_components_unthrottled(bigint, text, jsonb, uuid[])
    FROM PUBLIC, anonymous, authenticated;

CREATE FUNCTION public.saabtv_save_components(expected_revision bigint,
    encrypted_state text, changed_objects jsonb, retained_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, saabtv_sync_private AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
    request_size bigint := octet_length(coalesce(encrypted_state, ''))
        + octet_length(coalesce(changed_objects::text, ''))
        + octet_length(coalesce(retained_ids::text, ''));
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    PERFORM saabtv_sync_private.consume_quota(uid, 'push', request_size);
    RETURN public.saabtv_save_components_unthrottled(
        expected_revision, encrypted_state, changed_objects, retained_ids);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_save_components(bigint, text, jsonb, uuid[])
    FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_save_components(bigint, text, jsonb, uuid[])
    TO authenticated;

ALTER FUNCTION public.saabtv_save_account(bigint, text)
    RENAME TO saabtv_save_account_unthrottled;
ALTER FUNCTION public.saabtv_save_account_unthrottled(bigint, text)
    SECURITY DEFINER;
REVOKE ALL ON FUNCTION public.saabtv_save_account_unthrottled(bigint, text)
    FROM PUBLIC, anonymous, authenticated;

CREATE FUNCTION public.saabtv_save_account(expected_revision bigint, encrypted_state text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, saabtv_sync_private AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    PERFORM saabtv_sync_private.consume_quota(uid, 'push', octet_length(coalesce(encrypted_state, '')));
    RETURN public.saabtv_save_account_unthrottled(expected_revision, encrypted_state);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_save_account(bigint, text) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_save_account(bigint, text) TO authenticated;

ALTER FUNCTION public.saabtv_sync_v3_push(jsonb)
    RENAME TO saabtv_sync_v3_push_unthrottled;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_push_unthrottled(jsonb)
    FROM PUBLIC, anonymous, authenticated;

CREATE FUNCTION public.saabtv_sync_v3_push(operations jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, saabtv_sync_private AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
    request_size bigint := octet_length(coalesce(operations::text, ''));
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF request_size > 20971520 THEN
        RAISE EXCEPTION 'Sync request exceeds limit' USING ERRCODE = 'PT413';
    END IF;
    PERFORM saabtv_sync_private.consume_quota(uid, 'push', request_size);
    RETURN public.saabtv_sync_v3_push_unthrottled(operations);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_push(jsonb) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_v3_push(jsonb) TO authenticated;

ALTER FUNCTION public.saabtv_sync_v3_pull(bigint, integer)
    RENAME TO saabtv_sync_v3_pull_unthrottled;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_pull_unthrottled(bigint, integer)
    FROM PUBLIC, anonymous, authenticated;

CREATE FUNCTION public.saabtv_sync_v3_pull(after_sequence bigint, max_results integer DEFAULT 500)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, saabtv_sync_private AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    PERFORM saabtv_sync_private.consume_quota(uid, 'pull', 0);
    RETURN public.saabtv_sync_v3_pull_unthrottled(after_sequence, max_results);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_pull(bigint, integer) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_v3_pull(bigint, integer) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
