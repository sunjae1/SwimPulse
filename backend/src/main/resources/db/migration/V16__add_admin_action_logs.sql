CREATE TABLE admin_action_logs
(
    id            bigint auto_increment
        primary key,
    admin_user_id bigint       null,
    action_type   varchar(80)  not null,
    target_type   varchar(80)  not null,
    target_id     bigint       null,
    result_status enum ('SUCCESS', 'FAILED') not null,
    message       varchar(1000) null,
    created_at    datetime(6)  not null,
    constraint fk_admin_action_logs_admin_user
        foreign key (admin_user_id) references app_users (id)
);

CREATE INDEX idx_admin_action_logs_created_at
    ON admin_action_logs (created_at DESC, id DESC);

CREATE INDEX idx_admin_action_logs_result_created_at
    ON admin_action_logs (result_status, created_at DESC, id DESC);

CREATE INDEX idx_admin_action_logs_action_created_at
    ON admin_action_logs (action_type, created_at DESC, id DESC);
