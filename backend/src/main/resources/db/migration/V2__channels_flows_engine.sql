-- =============================================================================
-- V2__channels_flows_engine.sql — FlowPilot Phase 1: Channel, Flow, Engine tables
-- Conventions: same as V1 (UUID PKs app-assigned, TIMESTAMPTZ, TEXT+CHECK lowercase,
--              RLS enabled+forced on all tenant-owned tables, GRANT to flowpilot_app)
-- =============================================================================

-- =============================================================================
-- channel_connection  (§5)
-- Per-tenant record of a connected Telegram bot or embeddable widget.
-- Bot token and webhook secret are stored only as AES-GCM ciphertext.
-- =============================================================================
CREATE TABLE channel_connection (
    id                         UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                  UUID        NOT NULL REFERENCES tenant(id),
    type                       TEXT        NOT NULL CHECK (type IN ('telegram','web_widget')),
    name                       TEXT        NOT NULL,
    status                     TEXT        NOT NULL DEFAULT 'disconnected'
                               CHECK (status IN ('connected','disconnected','error')),
    -- Telegram-specific fields (nullable for web_widget)
    secret_ciphertext          BYTEA,      -- AES-GCM ciphertext of the bot token
    bot_username               TEXT,
    webhook_secret_ciphertext  BYTEA,      -- AES-GCM ciphertext of the webhook secret
    -- Widget-specific fields (nullable for telegram)
    public_key                 TEXT,
    allowed_origins            JSONB,
    -- Linked flow (which flow runs on this channel)
    flow_id                    UUID,       -- FK added after flow table is created (see ALTER below)
    -- Generic extras
    config                     JSONB,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_channel_connection PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_channel_widget_key
    ON channel_connection(public_key)
    WHERE public_key IS NOT NULL;

CREATE UNIQUE INDEX uq_channel_tg_bot
    ON channel_connection(tenant_id, bot_username)
    WHERE bot_username IS NOT NULL;

CREATE INDEX ix_channel_tenant ON channel_connection(tenant_id);

ALTER TABLE channel_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_connection FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON channel_connection
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE channel_connection TO flowpilot_app;


-- =============================================================================
-- flow  (§6)
-- A named workflow owned by a tenant. status active/inactive controls whether
-- it can be assigned to a channel.
-- =============================================================================
CREATE TABLE flow (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL REFERENCES tenant(id),
    name                 TEXT        NOT NULL,
    description          TEXT,
    status               TEXT        NOT NULL DEFAULT 'inactive'
                         CHECK (status IN ('inactive','active')),
    published_version_id UUID,       -- deferred FK added below after flow_version
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_flow PRIMARY KEY (id),
    CONSTRAINT uq_flow_name UNIQUE (tenant_id, name)
);

CREATE INDEX ix_flow_tenant ON flow(tenant_id);

ALTER TABLE flow ENABLE ROW LEVEL SECURITY;
ALTER TABLE flow FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON flow
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE flow TO flowpilot_app;


-- =============================================================================
-- flow_version  (§6)
-- Immutable snapshot of a flow's graph. In-flight conversations pin to the
-- version they started on (ADR-006). The graph column holds the full React Flow
-- document: {nodes:[{id,type,position,config}], edges:[{id,source,target,sourceHandle}]}.
-- =============================================================================
CREATE TABLE flow_version (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    flow_id      UUID        NOT NULL REFERENCES flow(id),
    version_no   INT         NOT NULL,
    state        TEXT        NOT NULL DEFAULT 'draft'
                 CHECK (state IN ('draft','published','archived')),
    graph        JSONB       NOT NULL,
    created_by   UUID        REFERENCES app_user(id),
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_flow_version PRIMARY KEY (id),
    CONSTRAINT uq_flow_version UNIQUE (flow_id, version_no)
);

CREATE INDEX ix_flow_version_flow ON flow_version(flow_id);

ALTER TABLE flow_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE flow_version FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON flow_version
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE flow_version TO flowpilot_app;


-- Deferred FKs now that both tables exist
ALTER TABLE flow
    ADD CONSTRAINT fk_flow_published_version
    FOREIGN KEY (published_version_id) REFERENCES flow_version(id);

ALTER TABLE channel_connection
    ADD CONSTRAINT fk_channel_flow
    FOREIGN KEY (flow_id) REFERENCES flow(id);


-- =============================================================================
-- contact  (§7)
-- A person who interacts with the bot, normalized across channels.
-- =============================================================================
CREATE TABLE contact (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    display_name TEXT,
    attributes   JSONB,
    tags         TEXT[]      NOT NULL DEFAULT '{}',
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_contact PRIMARY KEY (id)
);

CREATE INDEX ix_contact_tenant ON contact(tenant_id);
CREATE INDEX ix_contact_tags   ON contact USING GIN (tags);

ALTER TABLE contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON contact
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE contact TO flowpilot_app;


-- =============================================================================
-- contact_identity  (§7)
-- Maps a channel-specific external identifier (Telegram user id, widget visitor
-- token) to a canonical contact. UNIQUE (tenant, channel, external_id) ensures
-- the same Telegram user always resolves to the same contact.
-- =============================================================================
CREATE TABLE contact_identity (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenant(id),
    contact_id  UUID        NOT NULL REFERENCES contact(id),
    channel     TEXT        NOT NULL CHECK (channel IN ('telegram','web_widget')),
    external_id TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_contact_identity PRIMARY KEY (id),
    CONSTRAINT uq_contact_identity UNIQUE (tenant_id, channel, external_id)
);

CREATE INDEX ix_contact_identity_contact ON contact_identity(contact_id);

ALTER TABLE contact_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_identity FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON contact_identity
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE contact_identity TO flowpilot_app;


-- =============================================================================
-- conversation  (§7)
-- The execution state of a flow instance for one contact on one channel.
-- current_node_id is the engine's cursor in the pinned flow_version graph.
-- variables JSONB accumulates collected inputs and extracted entities.
-- version provides JPA @Version optimistic locking; the primary concurrency
-- guard is the per-conversation Redis lock.
-- =============================================================================
CREATE TABLE conversation (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id             UUID        NOT NULL REFERENCES tenant(id),
    contact_id            UUID        NOT NULL REFERENCES contact(id),
    channel_connection_id UUID        NOT NULL REFERENCES channel_connection(id),
    flow_id               UUID        NOT NULL REFERENCES flow(id),
    flow_version_id       UUID        NOT NULL REFERENCES flow_version(id),
    current_node_id       TEXT,
    status                TEXT        NOT NULL DEFAULT 'active'
                          CHECK (status IN ('active','awaiting_input','waiting','handoff','ended')),
    variables             JSONB       NOT NULL DEFAULT '{}',
    wait_until            TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_conversation PRIMARY KEY (id)
);

CREATE INDEX ix_conv_tenant_status ON conversation(tenant_id, status);
CREATE INDEX ix_conv_contact       ON conversation(contact_id);
-- Scheduler poll for wait nodes (partial index keeps it small)
CREATE INDEX ix_conv_wait_due ON conversation(wait_until)
    WHERE status = 'waiting';

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE conversation TO flowpilot_app;


-- =============================================================================
-- message  (§7)
-- Individual inbound/outbound messages within a conversation.
-- body JSONB holds the normalized payload (text, media URL, button choices).
-- channel_message_id is the provider-side id for deduplication / edits.
-- =============================================================================
CREATE TABLE message (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenant(id),
    conversation_id     UUID        NOT NULL REFERENCES conversation(id),
    direction           TEXT        NOT NULL CHECK (direction IN ('inbound','outbound')),
    content_type        TEXT        NOT NULL DEFAULT 'text'
                        CHECK (content_type IN ('text','media','buttons','system')),
    body                JSONB       NOT NULL,
    produced_by_node_id TEXT,
    channel_message_id  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_message PRIMARY KEY (id)
);

CREATE INDEX ix_message_conv ON message(conversation_id, created_at);

ALTER TABLE message ENABLE ROW LEVEL SECURITY;
ALTER TABLE message FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON message
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE message TO flowpilot_app;


-- =============================================================================
-- conversation_event  (§7)
-- Append-only execution log: node entries, branches, awaits, errors, handoffs.
-- Used for the live operator view, analytics, and debugging.
-- =============================================================================
CREATE TABLE conversation_event (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant(id),
    conversation_id UUID        NOT NULL REFERENCES conversation(id),
    type            TEXT        NOT NULL,
    node_id         TEXT,
    data            JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_conversation_event PRIMARY KEY (id)
);

CREATE INDEX ix_conv_event_conv ON conversation_event(conversation_id, created_at);

ALTER TABLE conversation_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_event FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_event
    AS PERMISSIVE FOR ALL TO flowpilot_app
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT ON TABLE conversation_event TO flowpilot_app;
