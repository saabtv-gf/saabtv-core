-- Sync v3 stores one encrypted current value per profile/entity, not an ever-growing event log.
-- Review and apply manually; this file is not executed by the Android app.
BEGIN;

CREATE TABLE IF NOT EXISTS public.saabtv_sync_v3_heads (
    user_id uuid PRIMARY KEY REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    server_sequence bigint NOT NULL DEFAULT 0 CHECK (server_sequence >= 0)
);

CREATE TABLE IF NOT EXISTS public.saabtv_sync_v3_devices (
    user_id uuid NOT NULL REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS public.saabtv_sync_v3_state (
    user_id uuid NOT NULL REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
    operation_id uuid NOT NULL,
    entity_key text NOT NULL CHECK (entity_key ~ '^[0-9a-f]{64}$'),
    profile_id text NOT NULL CHECK (length(profile_id) BETWEEN 1 AND 128),
    entity_type text NOT NULL CHECK (entity_type IN ('profile_state', 'profile_runtime', 'library', 'watched_state', 'series_next_up', 'playback_progress', 'source_selection')),
    operation text NOT NULL CHECK (
        (entity_type = 'profile_state' AND operation IN ('UPSERT', 'DELETE')) OR
        (entity_type = 'profile_runtime' AND operation = 'UPSERT') OR
        (entity_type = 'library' AND operation IN ('UPSERT', 'DELETE')) OR
        (entity_type = 'watched_state' AND operation = 'UPSERT') OR
        (entity_type = 'series_next_up' AND operation IN ('UPSERT', 'DELETE')) OR
        (entity_type = 'playback_progress' AND operation IN ('UPSERT', 'DELETE')) OR
        (entity_type = 'source_selection' AND operation IN ('UPSERT', 'DELETE'))
    ),
    entity_revision bigint NOT NULL CHECK (entity_revision > 0),
    device_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence > 0),
    server_sequence bigint NOT NULL CHECK (server_sequence > 0),
    ciphertext text NOT NULL CHECK (octet_length(ciphertext) BETWEEN 1 AND 400000),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, entity_key),
    UNIQUE (user_id, server_sequence)
);
CREATE INDEX IF NOT EXISTS saabtv_sync_v3_pull_idx
    ON public.saabtv_sync_v3_state (user_id, server_sequence);

ALTER TABLE public.saabtv_sync_v3_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_sync_v3_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_sync_v3_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_sync_v3_devices FORCE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_sync_v3_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saabtv_sync_v3_state FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS saabtv_sync_v3_heads_own ON public.saabtv_sync_v3_heads;
CREATE POLICY saabtv_sync_v3_heads_own ON public.saabtv_sync_v3_heads FOR ALL TO PUBLIC
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
DROP POLICY IF EXISTS saabtv_sync_v3_devices_own ON public.saabtv_sync_v3_devices;
CREATE POLICY saabtv_sync_v3_devices_own ON public.saabtv_sync_v3_devices FOR ALL TO PUBLIC
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());
DROP POLICY IF EXISTS saabtv_sync_v3_state_own ON public.saabtv_sync_v3_state;
CREATE POLICY saabtv_sync_v3_state_own ON public.saabtv_sync_v3_state FOR ALL TO PUBLIC
    USING (user_id = public.saabtv_user_id()) WITH CHECK (user_id = public.saabtv_user_id());

REVOKE ALL ON public.saabtv_sync_v3_heads, public.saabtv_sync_v3_devices, public.saabtv_sync_v3_state
    FROM PUBLIC, anonymous, authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_sync_version()
RETURNS integer LANGUAGE sql STABLE SECURITY INVOKER AS $$ SELECT 3; $$;
REVOKE ALL ON FUNCTION public.saabtv_sync_version() FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_version() TO authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_sync_v3_push(operations jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
    item jsonb;
    operation_uuid uuid;
    device_uuid uuid;
    profile_uuid text;
    key_hash text;
    entity_kind text;
    operation_name text;
    revision bigint;
    device_sequence bigint;
    encrypted_payload text;
    highest_sequence bigint;
    cursor_value bigint;
    assigned_sequence bigint;
    accepted jsonb := '[]'::jsonb;
    state_size bigint;
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF operations IS NULL OR jsonb_typeof(operations) IS DISTINCT FROM 'array'
        OR jsonb_array_length(operations) > 500 OR octet_length(operations::text) > 50000000
        THEN RAISE EXCEPTION 'Invalid sync operation batch'; END IF;

    INSERT INTO public.saabtv_sync_v3_heads(user_id) VALUES (uid) ON CONFLICT DO NOTHING;
    SELECT server_sequence INTO cursor_value FROM public.saabtv_sync_v3_heads
        WHERE user_id = uid FOR UPDATE;

    FOR item IN SELECT value FROM jsonb_array_elements(operations) LOOP
        operation_uuid := (item->>'operation_id')::uuid;
        device_uuid := (item->>'device_id')::uuid;
        profile_uuid := item->>'profile_id';
        key_hash := item->>'entity_key';
        entity_kind := item->>'entity_type';
        operation_name := item->>'operation';
        revision := (item->>'entity_revision')::bigint;
        device_sequence := (item->>'sequence')::bigint;
        encrypted_payload := item->>'ciphertext';

        IF operation_uuid IS NULL OR device_uuid IS NULL
            OR profile_uuid IS NULL OR length(profile_uuid) NOT BETWEEN 1 AND 128
            OR key_hash IS NULL OR key_hash !~ '^[0-9a-f]{64}$'
            OR entity_kind IS NULL OR entity_kind NOT IN ('profile_state', 'profile_runtime', 'library', 'watched_state', 'series_next_up', 'playback_progress', 'source_selection')
            OR operation_name IS NULL OR NOT (
                (entity_kind = 'profile_state' AND operation_name IN ('UPSERT', 'DELETE')) OR
                (entity_kind = 'profile_runtime' AND operation_name = 'UPSERT') OR
                (entity_kind = 'library' AND operation_name IN ('UPSERT', 'DELETE')) OR
                (entity_kind = 'watched_state' AND operation_name = 'UPSERT') OR
                (entity_kind = 'series_next_up' AND operation_name IN ('UPSERT', 'DELETE')) OR
                (entity_kind = 'playback_progress' AND operation_name IN ('UPSERT', 'DELETE')) OR
                (entity_kind = 'source_selection' AND operation_name IN ('UPSERT', 'DELETE'))
            )
            OR revision IS NULL OR revision <= 0 OR device_sequence IS NULL OR device_sequence <= 0
            OR encrypted_payload IS NULL OR octet_length(encrypted_payload) NOT BETWEEN 1 AND 400000
            THEN RAISE EXCEPTION 'Invalid sync operation'; END IF;

        INSERT INTO public.saabtv_sync_v3_devices(user_id, device_id)
            VALUES (uid, device_uuid) ON CONFLICT DO NOTHING;
        SELECT last_sequence INTO highest_sequence FROM public.saabtv_sync_v3_devices
            WHERE user_id = uid AND device_id = device_uuid FOR UPDATE;
        IF device_sequence > highest_sequence THEN
            assigned_sequence := NULL;
            INSERT INTO public.saabtv_sync_v3_state(user_id, operation_id, entity_key, profile_id, entity_type,
                operation, entity_revision, device_id, sequence, server_sequence, ciphertext)
            VALUES (uid, operation_uuid, key_hash, profile_uuid, entity_kind, operation_name, revision,
                device_uuid, device_sequence, cursor_value + 1, encrypted_payload)
            -- Each device has its own monotonically increasing entity revision. Compare the
            -- pair as a stable total order so out-of-order delivery and concurrent offline
            -- edits converge on every client regardless of which request arrived last.
            -- A rejected (losing) write is still acknowledged below and its client will read
            -- the winning row through the pull cursor.
            ON CONFLICT (user_id, entity_key) DO UPDATE SET
                operation_id = EXCLUDED.operation_id,
                profile_id = EXCLUDED.profile_id,
                entity_type = EXCLUDED.entity_type,
                operation = EXCLUDED.operation,
                entity_revision = EXCLUDED.entity_revision,
                device_id = EXCLUDED.device_id,
                sequence = EXCLUDED.sequence,
                server_sequence = EXCLUDED.server_sequence,
                ciphertext = EXCLUDED.ciphertext,
                updated_at = now()
            WHERE (EXCLUDED.entity_revision, EXCLUDED.device_id::text) >
                  (saabtv_sync_v3_state.entity_revision, saabtv_sync_v3_state.device_id::text)
            RETURNING server_sequence INTO assigned_sequence;
            IF assigned_sequence IS NOT NULL THEN
                cursor_value := assigned_sequence;
                -- A profile tombstone removes its prior v3 values only when it wins.
                IF entity_kind = 'profile_state' AND operation_name = 'DELETE' THEN
                    DELETE FROM public.saabtv_sync_v3_state
                        WHERE user_id = uid AND profile_id = profile_uuid AND entity_key <> key_hash;
                END IF;
            ELSE
                -- Re-publish the current winner after an acknowledged losing write. A device may
                -- already have advanced its global cursor past this entity before going offline;
                -- without a fresh sequence it would keep its losing local value indefinitely.
                UPDATE public.saabtv_sync_v3_state SET
                    server_sequence = cursor_value + 1,
                    updated_at = now()
                WHERE user_id = uid AND entity_key = key_hash
                RETURNING server_sequence INTO assigned_sequence;
                IF assigned_sequence IS NOT NULL THEN cursor_value := assigned_sequence; END IF;
            END IF;
            UPDATE public.saabtv_sync_v3_devices SET last_sequence = device_sequence, last_seen_at = now()
                WHERE user_id = uid AND device_id = device_uuid;
        END IF;
        accepted := accepted || jsonb_build_array(operation_uuid::text);
    END LOOP;

    UPDATE public.saabtv_sync_v3_heads SET server_sequence = cursor_value WHERE user_id = uid;
    SELECT coalesce(sum(octet_length(ciphertext)), 0) INTO state_size
        FROM public.saabtv_sync_v3_state WHERE user_id = uid;
    IF state_size > 14680064 OR (SELECT count(*) FROM public.saabtv_sync_v3_state WHERE user_id = uid) > 20000
        THEN RAISE EXCEPTION 'Sync state exceeds account limit'; END IF;
    RETURN jsonb_build_object('accepted_operation_ids', accepted, 'cursor', cursor_value);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_push(jsonb) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_v3_push(jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.saabtv_sync_v3_pull(after_sequence bigint, max_results integer DEFAULT 500)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    uid uuid := public.saabtv_user_id();
    head_value bigint;
    cursor_value bigint;
    rows jsonb := '[]'::jsonb;
    item record;
    more boolean;
    requested_limit integer := greatest(1, least(coalesce(max_results, 500), 500));
BEGIN
    IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
    IF after_sequence IS NULL OR after_sequence < 0 THEN RAISE EXCEPTION 'Invalid sync cursor'; END IF;
    SELECT server_sequence INTO head_value FROM public.saabtv_sync_v3_heads WHERE user_id = uid;
    head_value := coalesce(head_value, 0);
    cursor_value := after_sequence;
    FOR item IN SELECT * FROM public.saabtv_sync_v3_state
        WHERE user_id = uid AND server_sequence > after_sequence
        ORDER BY server_sequence LIMIT requested_limit LOOP
        rows := rows || jsonb_build_array(jsonb_build_object(
            'server_sequence', item.server_sequence,
            'operation_id', item.operation_id,
            'profile_id', item.profile_id,
            'entity_key', item.entity_key,
            'entity_type', item.entity_type,
            'operation', item.operation,
            'entity_revision', item.entity_revision,
            'device_id', item.device_id,
            'sequence', item.sequence,
            'ciphertext', item.ciphertext
        ));
        cursor_value := item.server_sequence;
    END LOOP;
    SELECT EXISTS(SELECT 1 FROM public.saabtv_sync_v3_state
        WHERE user_id = uid AND server_sequence > cursor_value) INTO more;
    IF NOT more AND jsonb_array_length(rows) = 0 THEN cursor_value := head_value; END IF;
    RETURN jsonb_build_object('operations', rows, 'cursor', cursor_value, 'has_more', more);
END;
$$;
REVOKE ALL ON FUNCTION public.saabtv_sync_v3_pull(bigint, integer) FROM PUBLIC, anonymous;
GRANT EXECUTE ON FUNCTION public.saabtv_sync_v3_pull(bigint, integer) TO authenticated;

COMMIT;
