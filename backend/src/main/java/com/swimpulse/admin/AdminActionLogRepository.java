package com.swimpulse.admin;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {
	@Query("""
			select log
			from AdminActionLog log
			left join fetch log.adminUser
			where (:actionType is null or log.actionType = :actionType)
			  and (:resultStatus is null or log.resultStatus = :resultStatus)
			order by log.createdAt desc, log.id desc
			""")
	List<AdminActionLog> searchRecent(
			@Param("actionType") String actionType,
			@Param("resultStatus") AdminActionResultStatus resultStatus,
			Pageable pageable
	);
}
