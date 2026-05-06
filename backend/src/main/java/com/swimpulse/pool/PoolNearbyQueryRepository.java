package com.swimpulse.pool;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PoolNearbyQueryRepository {
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public PoolNearbyQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<NearbyPoolRow> findNearby(double latitude, double longitude, int limit) {
		String sql = """
				SELECT
					p.id AS pool_id,
					ST_Distance_Sphere(
						POINT(p.longitude, p.latitude),
						POINT(:longitude, :latitude)
					) AS distance_meters
				FROM pools p
				WHERE p.latitude IS NOT NULL
					AND p.longitude IS NOT NULL
					AND p.geocode_status = 'SUCCESS'
				ORDER BY distance_meters ASC
				LIMIT :limit
				""";
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("latitude", latitude)
				.addValue("longitude", longitude)
				.addValue("limit", limit);
		return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new NearbyPoolRow(
				rs.getLong("pool_id"),
				rs.getDouble("distance_meters")
		));
	}
}
