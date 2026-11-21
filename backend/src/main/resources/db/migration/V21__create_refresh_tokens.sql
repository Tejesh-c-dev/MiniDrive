DO $migration$
DECLARE
    table_oid oid;
    expected record;
    actual_type text;
    actual_not_null boolean;
    actual_default text;
    token_hash_att smallint;
    user_id_att smallint;
    constraint_row record;
    index_row record;
    matching_index boolean;
    expected_index record;
BEGIN
    table_oid := to_regclass('public.refresh_tokens');
    IF table_oid IS NULL THEN
        CREATE TABLE public.refresh_tokens (
            token_hash CHAR(64) CONSTRAINT refresh_tokens_pkey PRIMARY KEY,
            family_id UUID NOT NULL,
            user_id UUID NOT NULL CONSTRAINT refresh_tokens_user_id_fkey
                REFERENCES public.users(id) ON DELETE CASCADE,
            expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
            revoked_at TIMESTAMP WITH TIME ZONE,
            replaced_by_hash CHAR(64),
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        table_oid := 'public.refresh_tokens'::regclass;
    ELSE
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c
            WHERE c.oid = table_oid AND c.relkind = 'r'
        ) THEN
            RAISE EXCEPTION 'V21 incompatible object public.refresh_tokens: expected an ordinary table';
        END IF;

        FOR expected IN
            SELECT * FROM (VALUES
                ('token_hash',       'character(64)', true,  NULL::text, 'token_hash CHAR(64) NOT NULL'),
                ('family_id',        'uuid',          true,  NULL::text, 'family_id UUID NOT NULL'),
                ('user_id',          'uuid',          true,  NULL::text, 'user_id UUID NOT NULL'),
                ('expires_at',       'timestamp with time zone', true, NULL::text, 'expires_at TIMESTAMP WITH TIME ZONE NOT NULL'),
                ('revoked_at',       'timestamp with time zone', false, NULL::text, 'revoked_at TIMESTAMP WITH TIME ZONE'),
                ('replaced_by_hash', 'character(64)', false, NULL::text, 'replaced_by_hash CHAR(64)'),
                ('created_at',       'timestamp with time zone', true, 'CURRENT_TIMESTAMP', 'created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP')
            ) AS definitions(column_name, type_name, not_null, default_expr, add_clause)
        LOOP
            IF NOT EXISTS (
                SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = table_oid AND a.attname = expected.column_name
                  AND a.attnum > 0 AND NOT a.attisdropped
            ) THEN
                EXECUTE 'ALTER TABLE public.refresh_tokens ADD COLUMN ' || expected.add_clause;
            END IF;

            SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid)
            INTO actual_type, actual_not_null, actual_default
            FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = table_oid AND a.attname = expected.column_name
              AND a.attnum > 0 AND NOT a.attisdropped;

            IF actual_type IS DISTINCT FROM expected.type_name
               OR actual_not_null IS DISTINCT FROM expected.not_null
               OR actual_default IS DISTINCT FROM expected.default_expr THEN
                RAISE EXCEPTION 'V21 incompatible column public.refresh_tokens.%: expected type %, NOT NULL %, default %, found type %, NOT NULL %, default %',
                    expected.column_name, expected.type_name, expected.not_null, expected.default_expr,
                    actual_type, actual_not_null, actual_default;
            END IF;
        END LOOP;
    END IF;

    SELECT attnum INTO token_hash_att FROM pg_attribute
    WHERE attrelid = table_oid AND attname = 'token_hash' AND attnum > 0 AND NOT attisdropped;
    SELECT attnum INTO user_id_att FROM pg_attribute
    WHERE attrelid = table_oid AND attname = 'user_id' AND attnum > 0 AND NOT attisdropped;

    FOR constraint_row IN
        SELECT conname, contype, conkey, confkey, confrelid, confdeltype,
               condeferrable, condeferred, convalidated, pg_get_constraintdef(oid) AS definition
        FROM pg_constraint WHERE conrelid = table_oid
    LOOP
        IF constraint_row.conname = 'refresh_tokens_pkey'
           AND (constraint_row.contype <> 'p'
                OR constraint_row.conkey <> ARRAY[token_hash_att]::smallint[]
                OR constraint_row.condeferrable OR constraint_row.condeferred
                OR NOT constraint_row.convalidated) THEN
            RAISE EXCEPTION 'V21 incompatible constraint public.refresh_tokens.refresh_tokens_pkey: found %', constraint_row.definition;
        END IF;
        IF constraint_row.conname = 'refresh_tokens_user_id_fkey'
           AND (constraint_row.contype <> 'f'
                OR constraint_row.conkey <> ARRAY[user_id_att]::smallint[]
                OR constraint_row.confrelid <> to_regclass('public.users')
                OR constraint_row.confdeltype <> 'c'
                OR constraint_row.condeferrable OR constraint_row.condeferred
                OR NOT constraint_row.convalidated) THEN
            RAISE EXCEPTION 'V21 incompatible constraint public.refresh_tokens.refresh_tokens_user_id_fkey: found %', constraint_row.definition;
        END IF;
        IF constraint_row.contype = 'p'
           AND (constraint_row.conkey <> ARRAY[token_hash_att]::smallint[]
                OR constraint_row.condeferrable OR constraint_row.condeferred
                OR NOT constraint_row.convalidated) THEN
            RAISE EXCEPTION 'V21 incompatible primary key on public.refresh_tokens: found %', constraint_row.definition;
        END IF;
        IF constraint_row.contype = 'f'
           AND constraint_row.conkey = ARRAY[user_id_att]::smallint[]
           AND (constraint_row.confrelid <> to_regclass('public.users')
                OR constraint_row.confkey <> ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass('public.users') AND attname = 'id')]::smallint[]
                OR constraint_row.confdeltype <> 'c'
                OR constraint_row.condeferrable OR constraint_row.condeferred
                OR NOT constraint_row.convalidated) THEN
            RAISE EXCEPTION 'V21 incompatible foreign key on public.refresh_tokens.user_id: found %', constraint_row.definition;
        END IF;
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = table_oid AND c.contype = 'p'
          AND c.conkey = ARRAY[token_hash_att]::smallint[]
          AND NOT c.condeferrable AND NOT c.condeferred AND c.convalidated
    ) THEN
        ALTER TABLE public.refresh_tokens ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (token_hash);
    END IF;

    IF to_regclass('public.users') IS NULL THEN
        RAISE EXCEPTION 'V21 prerequisite missing: public.users';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = table_oid AND c.contype = 'f'
          AND c.conkey = ARRAY[user_id_att]::smallint[]
          AND c.confrelid = to_regclass('public.users')
          AND c.confkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass('public.users') AND attname = 'id')]::smallint[]
          AND c.confdeltype = 'c' AND NOT c.condeferrable AND NOT c.condeferred AND c.convalidated
    ) THEN
        ALTER TABLE public.refresh_tokens ADD CONSTRAINT refresh_tokens_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
    END IF;

    FOR expected_index IN
        SELECT * FROM (VALUES
            ('idx_refresh_tokens_family_id', 'family_id'),
            ('idx_refresh_tokens_user_id', 'user_id'),
            ('idx_refresh_tokens_expires_at', 'expires_at')
        ) AS definitions(index_name, column_name)
    LOOP
        matching_index := false;
        FOR index_row IN
            SELECT idx.oid AS index_oid, idx.relname AS index_name,
                   pg_get_indexdef(idx.oid) AS definition, i.indisunique, i.indisvalid,
                   i.indisready, i.indisprimary, i.indnkeyatts, i.indnatts,
                   i.indkey[0] AS key_attnum, i.indexprs, i.indpred, am.amname
            FROM pg_class idx
            JOIN pg_index i ON i.indexrelid = idx.oid
            JOIN pg_class tbl ON tbl.oid = i.indrelid
            JOIN pg_am am ON am.oid = idx.relam
            WHERE tbl.oid = table_oid
        LOOP
            IF index_row.index_name = expected_index.index_name
               AND (index_row.indisunique OR NOT index_row.indisvalid OR NOT index_row.indisready
                    OR index_row.indisprimary OR index_row.indnkeyatts <> 1 OR index_row.indnatts <> 1
                    OR index_row.key_attnum <> (SELECT attnum FROM pg_attribute WHERE attrelid = table_oid AND attname = expected_index.column_name)
                    OR index_row.indexprs IS NOT NULL OR index_row.indpred IS NOT NULL OR index_row.amname <> 'btree') THEN
                RAISE EXCEPTION 'V21 incompatible index public.%: found %', index_row.index_name, index_row.definition;
            END IF;
            IF NOT index_row.indisunique AND index_row.indisvalid AND index_row.indisready
               AND NOT index_row.indisprimary AND index_row.indnkeyatts = 1 AND index_row.indnatts = 1
               AND index_row.key_attnum = (SELECT attnum FROM pg_attribute WHERE attrelid = table_oid AND attname = expected_index.column_name)
               AND index_row.indexprs IS NULL AND index_row.indpred IS NULL AND index_row.amname = 'btree' THEN
                matching_index := true;
            END IF;
        END LOOP;
        IF NOT matching_index THEN
            EXECUTE format('CREATE INDEX %I ON public.refresh_tokens (%I)', expected_index.index_name, expected_index.column_name);
        END IF;
    END LOOP;
END
$migration$;
