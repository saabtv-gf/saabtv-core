-- Capability-scoped, encrypted five-minute phone relay. No auth/account objects change.
BEGIN;
CREATE SCHEMA saabtv_pairing_private;
REVOKE ALL ON SCHEMA saabtv_pairing_private FROM PUBLIC, anonymous, authenticated;
CREATE TABLE saabtv_pairing_private.sessions (
    id uuid PRIMARY KEY,
    read_hash text NOT NULL CHECK (read_hash ~ '^[0-9a-f]{64}$'),
    write_hash text NOT NULL CHECK (write_hash ~ '^[0-9a-f]{64}$'),
    mode text NOT NULL CHECK (mode IN ('signin','signup','paste','search','avatar','hub')),
    manifest text NOT NULL CHECK (octet_length(manifest) <= 65536),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '5 minutes',
    sequence integer NOT NULL DEFAULT 0,
    acknowledged integer NOT NULL DEFAULT 0,
    message_id uuid,
    last_cipher_hash text,
    ciphertext text CHECK (octet_length(ciphertext) <= 1048576),
    last_write timestamptz,
    closed boolean NOT NULL DEFAULT false
);
ALTER TABLE saabtv_pairing_private.sessions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON saabtv_pairing_private.sessions FROM PUBLIC, anonymous, authenticated;

-- Fixed search paths and fully qualified tables. Only the owner can access storage.
CREATE FUNCTION public.saabtv_pair_create(session_id uuid, reader_hash text,
    writer_hash text, tool text, encrypted_manifest text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(1935761762, 1);
    DELETE FROM saabtv_pairing_private.sessions WHERE expires_at <= now();
    IF reader_hash IS NULL OR writer_hash IS NULL OR reader_hash = writer_hash
       OR reader_hash !~ '^[0-9a-f]{64}$' OR writer_hash !~ '^[0-9a-f]{64}$'
       OR encrypted_manifest IS NULL OR octet_length(encrypted_manifest) > 65536
       OR tool IS NULL OR tool NOT IN ('signin','signup','paste','search','avatar','hub')
       OR (SELECT count(*) FROM saabtv_pairing_private.sessions) >= 64
       THEN RAISE EXCEPTION 'Pairing unavailable'; END IF;
    INSERT INTO saabtv_pairing_private.sessions(id,read_hash,write_hash,mode,manifest)
        VALUES(session_id,reader_hash,writer_hash,tool,encrypted_manifest);
    RETURN true;
END; $$;

CREATE FUNCTION public.saabtv_pair_phone(session_id uuid, capability text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE s saabtv_pairing_private.sessions;
BEGIN
    SELECT * INTO s FROM saabtv_pairing_private.sessions WHERE id = session_id
      AND write_hash = encode(sha256(convert_to(capability, 'UTF8')), 'hex')
      AND expires_at > now();
    IF NOT FOUND THEN RAISE EXCEPTION 'Pairing expired or unavailable'; END IF;
    RETURN jsonb_build_object('manifest',s.manifest,'sequence',s.sequence,
      'acknowledged',s.acknowledged,'expires',s.expires_at,'closed',s.closed);
END; $$;

CREATE FUNCTION public.saabtv_pair_send(session_id uuid, capability text,
    message_id uuid, encrypted_message text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE s saabtv_pairing_private.sessions;
BEGIN
    SELECT * INTO s FROM saabtv_pairing_private.sessions WHERE id = session_id
      AND write_hash = encode(sha256(convert_to(capability, 'UTF8')), 'hex')
      AND expires_at > now() AND NOT closed FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Pairing expired or unavailable'; END IF;
    IF s.message_id = message_id AND s.last_cipher_hash = encode(sha256(convert_to(encrypted_message,'UTF8')),'hex') THEN RETURN s.sequence; END IF;
    IF message_id IS NULL OR encrypted_message IS NULL OR octet_length(encrypted_message) > 1048576
       OR octet_length(encrypted_message) < 32 OR s.sequence <> s.acknowledged
       OR s.sequence >= (CASE WHEN s.mode = 'hub' THEN 40 ELSE 1 END)
       OR s.last_write > now() - interval '1 second'
       THEN RAISE EXCEPTION 'Message rejected'; END IF;
    UPDATE saabtv_pairing_private.sessions SET sequence = sequence + 1,
      message_id = saabtv_pair_send.message_id, ciphertext = encrypted_message,
      last_cipher_hash = encode(sha256(convert_to(encrypted_message,'UTF8')),'hex'), last_write = now()
      WHERE id = session_id RETURNING sequence INTO s.sequence;
    RETURN s.sequence;
END; $$;

CREATE FUNCTION public.saabtv_pair_read(session_id uuid, capability text, ack integer)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE s saabtv_pairing_private.sessions;
BEGIN
    SELECT * INTO s FROM saabtv_pairing_private.sessions WHERE id = session_id
      AND read_hash = encode(sha256(convert_to(capability, 'UTF8')), 'hex')
      AND expires_at > now() AND NOT closed FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Pairing expired or unavailable'; END IF;
    IF ack IS NULL OR ack < s.acknowledged OR ack > s.sequence THEN RAISE EXCEPTION 'Invalid acknowledgement'; END IF;
    IF ack = s.sequence THEN
      UPDATE saabtv_pairing_private.sessions SET acknowledged = ack, ciphertext = NULL WHERE id = session_id;
      RETURN jsonb_build_object('sequence',s.sequence);
    END IF;
    RETURN jsonb_build_object('sequence',s.sequence,'message_id',s.message_id,'ciphertext',s.ciphertext);
END; $$;

CREATE FUNCTION public.saabtv_pair_close(session_id uuid, capability text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    UPDATE saabtv_pairing_private.sessions SET closed = true, ciphertext = NULL, manifest = ''
      WHERE id = session_id AND read_hash = encode(sha256(convert_to(capability, 'UTF8')), 'hex');
    RETURN FOUND;
END; $$;

REVOKE ALL ON FUNCTION public.saabtv_pair_create(uuid,text,text,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.saabtv_pair_phone(uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.saabtv_pair_send(uuid,text,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.saabtv_pair_read(uuid,text,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.saabtv_pair_close(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.saabtv_pair_create(uuid,text,text,text,text),
 public.saabtv_pair_phone(uuid,text),public.saabtv_pair_send(uuid,text,uuid,text),
 public.saabtv_pair_read(uuid,text,integer),public.saabtv_pair_close(uuid,text)
 TO anonymous, authenticated;
NOTIFY pgrst, 'reload schema';
COMMIT;
