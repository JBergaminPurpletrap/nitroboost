-- ============================================================================
-- NITRO BOOST - Schema inicial do banco SQLite
-- ============================================================================
-- Este schema e executado automaticamente pelo DatabaseManager na inicializacao
-- da aplicacao (CREATE TABLE IF NOT EXISTS - seguro rodar multiplas vezes).
--
-- Tabelas:
--   items            -> catalogo de itens ja descobertos por algum scanner
--                        (processo, servico, entrada de startup, tarefa
--                        agendada, chave de telemetria, app bloatware, etc).
--   actions_history   -> historico de toda acao (disable/enable/kill/lock/
--                        unlock/restore) executada pela aplicacao. Regra de
--                        ouro do projeto: NENHUMA acao acontece sem gravar
--                        aqui.
--   backups          -> snapshot do estado anterior de um item, salvo ANTES
--                        de qualquer acao destrutiva, para permitir reverter.
--   locks            -> itens marcados como "protegidos" pelo usuario; o
--                        ActionExecutor deve recusar qualquer acao sobre um
--                        item bloqueado ate ele ser desbloqueado explicitamente.
-- ============================================================================

PRAGMA foreign_keys = ON;

-- ----------------------------------------------------------------------------
-- items: catalogo de itens do sistema encontrados pelos scanners
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS items (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    name                TEXT    NOT NULL,                 -- nome tecnico do item (ex: nome do servico/processo)
    type                TEXT    NOT NULL,                 -- 'process' | 'service' | 'startup' | 'scheduled_task' |
                                                            -- 'power_plan' | 'telemetry_key' | 'bloatware' | 'other'
    classification      TEXT,                              -- 'seguro' | 'essencial' | 'depende' (vem da knowledge base)
    description         TEXT,                              -- explicacao em portugues do que o item faz
    current_state       TEXT,                              -- estado observado na ultima varredura (ex: 'Running', 'Stopped')
    is_locked           INTEGER NOT NULL DEFAULT 0,        -- flag de conveniencia (fonte da verdade e a tabela locks)
    first_seen_at       TEXT    NOT NULL DEFAULT (datetime('now')),
    last_seen_at        TEXT    NOT NULL DEFAULT (datetime('now')),
    UNIQUE (name, type)
);

CREATE INDEX IF NOT EXISTS idx_items_type ON items (type);
CREATE INDEX IF NOT EXISTS idx_items_classification ON items (classification);

-- ----------------------------------------------------------------------------
-- actions_history: historico completo de acoes executadas pela aplicacao
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS actions_history (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id             INTEGER REFERENCES items (id) ON DELETE SET NULL,
    item_name           TEXT    NOT NULL,                 -- copia do nome no momento da acao (mesmo se item for removido depois)
    item_type           TEXT    NOT NULL,
    action_type         TEXT    NOT NULL,                 -- 'disable' | 'enable' | 'kill' | 'lock' | 'unlock' | 'restore'
    previous_state      TEXT,                              -- estado antes da acao
    new_state            TEXT,                              -- estado depois da acao
    success             INTEGER NOT NULL DEFAULT 1,        -- 1 = sucesso, 0 = falhou
    error_message        TEXT,                              -- mensagem de erro quando success = 0
    performed_at         TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_actions_history_item_id ON actions_history (item_id);
CREATE INDEX IF NOT EXISTS idx_actions_history_performed_at ON actions_history (performed_at);

-- ----------------------------------------------------------------------------
-- backups: snapshot do estado anterior de um item, para permitir reverter
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backups (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id             INTEGER REFERENCES items (id) ON DELETE SET NULL,
    action_history_id   INTEGER REFERENCES actions_history (id) ON DELETE SET NULL,
    item_name           TEXT    NOT NULL,
    item_type           TEXT    NOT NULL,
    state_snapshot       TEXT    NOT NULL,                 -- estado serializado (JSON) suficiente para reverter a acao
    created_at           TEXT    NOT NULL DEFAULT (datetime('now')),
    restored             INTEGER NOT NULL DEFAULT 0,        -- 1 quando ja foi usado para reverter
    restored_at           TEXT
);

CREATE INDEX IF NOT EXISTS idx_backups_item_id ON backups (item_id);

-- ----------------------------------------------------------------------------
-- update_check_cache: cache de 24h do resultado da verificacao online de BIOS
-- (Fase 11 - Nivel 2). Evita bater no site do fabricante a cada clique repetido
-- em pouco tempo - ainda mais importante pensando em varios usuarios do app.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS update_check_cache (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    vendor              TEXT    NOT NULL,                 -- ex: 'ASUS'
    model               TEXT    NOT NULL,                 -- modelo da placa-mae detectado
    checked_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    result_version      TEXT,                              -- versao de BIOS extraida, ou NULL se falhou
    success             INTEGER NOT NULL DEFAULT 0,        -- 1 = conseguiu extrair, 0 = falhou/nao suportado
    UNIQUE (vendor, model)
);

-- ----------------------------------------------------------------------------
-- locks: itens marcados como "protegidos" pelo usuario
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS locks (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id             INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    item_name           TEXT    NOT NULL,
    item_type           TEXT    NOT NULL,
    reason               TEXT,                              -- motivo opcional informado pelo usuario
    locked_at             TEXT    NOT NULL DEFAULT (datetime('now')),
    UNIQUE (item_id)
);
