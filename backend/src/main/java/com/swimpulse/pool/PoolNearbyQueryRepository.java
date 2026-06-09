package com.swimpulse.pool;

import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PoolNearbyQueryRepository {
	private static final Logger log = LoggerFactory.getLogger(PoolNearbyQueryRepository.class);

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
		log.info("Executing nearby pool SQL. latitude={} longitude={} limit={}\n{}",
				latitude, longitude, limit, sql.strip());
		return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new NearbyPoolRow(
				rs.getLong("pool_id"),
				rs.getDouble("distance_meters")
		));
	}

	public List<NearbyPoolMatchRow> findNearestMatches(List<NearbySearchOrigin> origins, double distanceMeters) {
		if (origins.isEmpty()) {
			return List.of();
		}
		String candidateRows = IntStream.range(0, origins.size())
				.mapToObj(index -> """
						SELECT :candidateIndex%d AS candidate_index,
						       :latitude%d AS latitude,
						       :longitude%d AS longitude
						""".formatted(index, index, index).strip())
				.collect(java.util.stream.Collectors.joining("\nUNION ALL\n"));
		String distanceExpression = """
				ST_Distance_Sphere(
					POINT(pool.longitude, pool.latitude),
					POINT(candidate.longitude, candidate.latitude)
				)
				""".strip();
		String sql = """
				WITH candidate_coordinates AS (
					%s
				),
				ranked_matches AS (
					SELECT
						candidate.candidate_index,
						pool.id AS pool_id,
						%s AS distance_meters,
						ROW_NUMBER() OVER (
							PARTITION BY candidate.candidate_index
							ORDER BY %s, pool.id
						) AS match_rank
					FROM candidate_coordinates candidate
					JOIN pools pool
					  ON pool.latitude IS NOT NULL
					 AND pool.longitude IS NOT NULL
					 AND pool.geocode_status = 'SUCCESS'
					WHERE %s <= :distanceMeters
				)
				SELECT candidate_index, pool_id, distance_meters
				FROM ranked_matches
				WHERE match_rank = 1
				ORDER BY candidate_index
				""".formatted(candidateRows, distanceExpression, distanceExpression, distanceExpression);
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("distanceMeters", distanceMeters);
		for (int index = 0; index < origins.size(); index++) {
			NearbySearchOrigin origin = origins.get(index);
			parameters.addValue("candidateIndex" + index, origin.candidateIndex())
					.addValue("latitude" + index, origin.latitude())
					.addValue("longitude" + index, origin.longitude());
		}
		log.debug("Executing batch nearby pool match SQL. candidateCount={} distanceMeters={}",
				origins.size(), distanceMeters);
		return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new NearbyPoolMatchRow(
				rs.getInt("candidate_index"),
				rs.getLong("pool_id"),
				rs.getDouble("distance_meters")
		));
	}
}
