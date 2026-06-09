package com.swimpulse.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PoolSearchNormalizerTests {
	@Test
	void normalizesPoolNamesAndAddressesUsingExistingComparisonRules() {
		assertEquals(
				"성동구립용답체육센터",
				PoolSearchNormalizer.normalize(" <b>성동구립 용답체육센터 수영장</b> ")
		);
		assertEquals(
				"서울특별시성동구천호대로78길15-48",
				PoolSearchNormalizer.normalize("서울특별시 성동구 천호대로78길 15-48")
		);
		assertEquals("", PoolSearchNormalizer.normalize(null));
	}
}
