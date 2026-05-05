package com.swimpulse.config;

import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.event.RegistrationEventRepository;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapData {
	@Bean
	CommandLineRunner loadSampleData(
			PoolRepository poolRepository,
			RegistrationEventRepository eventRepository
	) {
		return args -> {
			if (poolRepository.count() == 0) {
				List<Pool> pools = poolRepository.saveAll(List.of(
						new Pool("강남스포츠문화센터 수영장", "서울 강남구 밤고개로1길 52", "강남구", "https://www.gangnam.go.kr", "새벽반과 저녁반 경쟁률이 높은 공공 수영장입니다."),
						new Pool("마포구민체육센터 수영장", "서울 마포구 월드컵로25길 190", "마포구", "https://www.mapo.go.kr", "월초 접수 알림 수요가 많은 구민 체육시설입니다."),
						new Pool("성동구립 용답체육센터", "서울 성동구 천호대로78길 15-48", "성동구", "https://www.sd.go.kr", "직장인반과 어린이반 모집 공지가 자주 갱신됩니다.")
				));

				Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
				eventRepository.saveAll(List.of(
						new RegistrationEvent(pools.get(0), "5월 신규회원 새벽반 접수", now.plus(8, ChronoUnit.MINUTES), now.plus(2, ChronoUnit.HOURS), EventStatus.UPCOMING),
						new RegistrationEvent(pools.get(1), "5월 구민 우선 접수", now.plus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS), EventStatus.UPCOMING),
						new RegistrationEvent(pools.get(2), "평일 저녁반 잔여석 접수", now.minus(30, ChronoUnit.MINUTES), now.plus(90, ChronoUnit.MINUTES), EventStatus.OPEN)
				));
			}
		};
	}
}
