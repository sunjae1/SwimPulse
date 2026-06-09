create table registration_event_dedup_map_tmp (
	duplicate_id bigint not null primary key,
	canonical_id bigint not null
);

insert into registration_event_dedup_map_tmp (duplicate_id, canonical_id)
select duplicate_event.id, canonical_event.id
from registration_events duplicate_event
join registration_events canonical_event
	on duplicate_event.pool_id = canonical_event.pool_id
	and duplicate_event.title = canonical_event.title
	and duplicate_event.registration_starts_at = canonical_event.registration_starts_at
	and duplicate_event.registration_ends_at = canonical_event.registration_ends_at
	and duplicate_event.id > canonical_event.id
where canonical_event.id = (
	select min(base.id)
	from registration_events base
	where base.pool_id = duplicate_event.pool_id
		and base.title = duplicate_event.title
		and base.registration_starts_at = duplicate_event.registration_starts_at
		and base.registration_ends_at = duplicate_event.registration_ends_at
);

delete from subscriptions
where id in (
	select duplicate_subscription_id
	from (
		select subscription.id as duplicate_subscription_id
		from subscriptions subscription
		join registration_event_dedup_map_tmp dedup
			on subscription.event_id = dedup.duplicate_id
		join subscriptions existing
			on existing.user_id = subscription.user_id
			and existing.event_id = dedup.canonical_id
	) conflicting_subscriptions
);

update subscriptions
set event_id = (
	select dedup.canonical_id
	from registration_event_dedup_map_tmp dedup
	where dedup.duplicate_id = subscriptions.event_id
)
where event_id in (
	select duplicate_id
	from registration_event_dedup_map_tmp
);

update notifications
set event_id = (
	select dedup.canonical_id
	from registration_event_dedup_map_tmp dedup
	where dedup.duplicate_id = notifications.event_id
)
where event_id in (
	select duplicate_id
	from registration_event_dedup_map_tmp
);

delete from registration_events
where id in (
	select duplicate_id
	from registration_event_dedup_map_tmp
);

drop table registration_event_dedup_map_tmp;

alter table registration_events
	add constraint uk_registration_event_pool_title_period
	unique (pool_id, title, registration_starts_at, registration_ends_at);
