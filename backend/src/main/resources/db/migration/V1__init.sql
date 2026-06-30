-- =============================================================================
-- V1__init.sql — FlowPilot Phase 0: Tenancy & Identity tables
-- Conventions (Backend-Schema doc §2):
--   PKs: UUID (UUIDv7 assigned by application, gen_random_uuid() as DB fallback only)
--   Timestamps: TIMESTAMPTZ NOT NULL DEFAULT now()
--   Enums: TEXT + CHECK constraint
--   Naming: snake_case, singular
-- =============================================================================

-- -------------------------------------------------------
-- DB role: application user (non-superuser so RLS applies)
-- -------------------------------------------------------
-- This role is used by the app connection pool.
-- The superuser role (used by Flyway migrations) bypasses RLS by default.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'flowpilot_app') THEN
        CREATE ROLE flowpilot_app LOGIN;
    END IF;
END
$$;

-- Grant schema usage and table privileges (granted per table below after creation)
GRANT USAGE ON SCHEMA public TO flowpilot_app;


-- =============================================================================
-- tenant
-- =============================================================================
CREATE TABLE tenant (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    name       TEXT        NOT NULL,
    slug       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_tenant PRIMARY KEY (id),
    CONSTRAINT uq_tenant_slug UNIQUE (slug)
);

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

-- Tenant can only read its own row.
-- Superuser (Flyway, system tasks) bypasses RLS; app role is bound by it.
CREATE POLICY tenant_isolation ON tenant
    AS PERMISSIVE
    FOR ALL
    TO flowpilot_app
    USING (id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE tenant TO flowpilot_app;


-- =============================================================================
-- app_user
-- =============================================================================
CREATE TABLE app_user (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenant(id),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    display_name  TEXT        NOT NULL DEFAULT '',
    role          TEXT        NOT NULL DEFAULT 'owner' CHECK (role IN ('owner', 'editor', 'agent')),
    status        TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_app_user PRIMARY KEY (id),
    -- Global-unique email (one user = one tenant in MVP; Phase 2 adds membership table)
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE INDEX ix_app_user_tenant ON app_user (tenant_id);

-- RLS: app_user resolves tenant from JWT before the transaction, so we allow full row
-- access scoped to the owning tenant. Login (pre-context) runs as superuser/service account.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON app_user
    AS PERMISSIVE
    FOR ALL
    TO flowpilot_app
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE app_user TO flowpilot_app;


-- =============================================================================
-- refresh_token
-- =============================================================================
CREATE TABLE refresh_token (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    token_hash  TEXT        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID                 REFERENCES refresh_token(id),  -- rotation chain
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_refresh_token  PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token  UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- Refresh tokens are not tenant-scoped via RLS (looked up by hash before tenant context
-- is established). The service layer validates user_id ownership instead.
GRANT SELECT, INSERT, UPDATE ON TABLE refresh_token TO flowpilot_app;


-- =============================================================================
-- Proof table: rls_proof
-- A minimal table used exclusively by the cross-tenant isolation integration test
-- (FR-TEN-3) to verify RLS is enforced end-to-end for tenant-owned entities.
-- =============================================================================
CREATE TABLE rls_proof (
    id        UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID        NOT NULL REFERENCES tenant(id),
    payload   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_rls_proof PRIMARY KEY (id)
);

ALTER TABLE rls_proof ENABLE ROW LEVEL SECURITY;
ALTER TABLE rls_proof FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON rls_proof
    AS PERMISSIVE
    FOR ALL
    TO flowpilot_app
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE rls_proof TO flowpilot_app;
