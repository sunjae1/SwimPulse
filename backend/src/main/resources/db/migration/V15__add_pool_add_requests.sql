CREATE TABLE pool_add_requests
(
    id                   bigint auto_increment primary key,
    requested_by_user_id bigint       not null,
    title                varchar(100) not null,
    category             varchar(255) null,
    address              varchar(255) null,
    road_address         varchar(255) null,
    homepage_url         varchar(500) null,
    latitude             double       null,
    longitude            double       null,
    status               enum ('PENDING', 'APPROVED', 'REJECTED', 'MERGED') not null,
    approved_pool_id     bigint       null,
    admin_note           varchar(1000) null,
    created_at           datetime(6)  not null,
    reviewed_at          datetime(6)  null,
    reviewed_by_admin_id bigint       null,
    constraint fk_pool_add_requests_requested_by
        foreign key (requested_by_user_id) references app_users (id),
    constraint fk_pool_add_requests_reviewed_by
        foreign key (reviewed_by_admin_id) references app_users (id),
    constraint fk_pool_add_requests_approved_pool
        foreign key (approved_pool_id) references pools (id)
);

CREATE INDEX idx_pool_add_requests_status_created_at
    ON pool_add_requests (status, created_at DESC, id DESC);
