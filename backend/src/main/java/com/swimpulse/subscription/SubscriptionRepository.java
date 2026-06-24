package com.swimpulse.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
	List<Subscription> findByUser_IdOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByUser_IdAndEventIsNotNullOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByEvent_Id(Long eventId);

	long countByEvent_Id(Long eventId);

	Optional<Subscription> findByUser_IdAndEvent_Id(Long userId, Long eventId);

	Optional<Subscription> findByIdAndUser_Id(Long id, Long userId);

	@Modifying
	@Query("delete from Subscription subscription where subscription.user.id = :userId and subscription.event.id = :eventId")
	int deleteByUserIdAndEventId(@Param("userId") Long userId, @Param("eventId") Long eventId);

	@Query("""
			select subscription.pool.id as poolId,
			       subscription.pool.name as poolName,
			       count(subscription.id) as subscriptionCount
			from Subscription subscription
			where subscription.event is not null
			group by subscription.pool.id, subscription.pool.name
			order by count(subscription.id) desc, subscription.pool.name asc
			""")
	List<PoolSubscriptionRankingProjection> findPoolSubscriptionRankings(org.springframework.data.domain.Pageable pageable);

	@Query("""
			select case
			           when subscription.pool.district is null or subscription.pool.district = '' then '지역 미지정'
			           else subscription.pool.district
			       end as district,
			       count(subscription.id) as subscriptionCount
			from Subscription subscription
			where subscription.event is not null
			group by case
			           when subscription.pool.district is null or subscription.pool.district = '' then '지역 미지정'
			           else subscription.pool.district
			       end
			order by count(subscription.id) desc
			""")
	List<DistrictSubscriptionRankingProjection> findDistrictSubscriptionRankings(org.springframework.data.domain.Pageable pageable);
}
