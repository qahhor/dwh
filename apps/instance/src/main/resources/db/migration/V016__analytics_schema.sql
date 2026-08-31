-- ============================================================================
-- V016: Dynamic PostgreSQL Analytics Engine Indices and Permissions
-- ============================================================================

-- Fast aggregation indices for tasks analytics
CREATE INDEX IF NOT EXISTS idx_ms_tasks_created_at ON ms_tasks(created_at);
CREATE INDEX IF NOT EXISTS idx_ms_tasks_status_id ON ms_tasks(status_id);
CREATE INDEX IF NOT EXISTS idx_ms_tasks_project_id ON ms_tasks(project_id);
CREATE INDEX IF NOT EXISTS idx_ms_tasks_end_time ON ms_tasks(end_time);

-- Register form and actions
INSERT INTO md_forms (code, module, name)
VALUES ('analytics.dashboard', 'analytics', 'Аналитика и дашборды')
ON CONFLICT (code) DO NOTHING;

INSERT INTO md_form_actions (form_code, action, name)
VALUES
    ('analytics.dashboard', 'view', 'Просмотр аналитики'),
    ('analytics.dashboard', 'manage', 'Управление дашбордами')
ON CONFLICT (form_code, action) DO NOTHING;

-- Grant permissions to admin (role_id=1) and manager (role_id=2)
INSERT INTO md_role_permissions (role_id, form_code, action)
VALUES
    (1, 'analytics.dashboard', 'view'),
    (1, 'analytics.dashboard', 'manage'),
    (2, 'analytics.dashboard', 'view')
ON CONFLICT (role_id, form_code, action) DO NOTHING;
