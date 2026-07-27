package com.swimpulse.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NaverLocalSearchClientTests {

	@Test
	void parsesScaledWgs84Coordinates() {
		assertEquals(126.9873882, NaverLocalSearchClient.parseMapCoordinate("1269873882", 180));
		assertEquals(37.5666103, NaverLocalSearchClient.parseMapCoordinate("375666103", 90));
	}

	@Test
	void keepsAlreadyDecimalCoordinates() {
		assertEquals(126.9873882, NaverLocalSearchClient.parseMapCoordinate("126.9873882", 180));
		assertEquals(37.5666103, NaverLocalSearchClient.parseMapCoordinate("37.5666103", 90));
	}

	@Test
	void rejectsMissingOrOutOfRangeCoordinates() {
		assertNull(NaverLocalSearchClient.parseMapCoordinate(null, 180));
		assertNull(NaverLocalSearchClient.parseMapCoordinate("not-a-coordinate", 180));
		assertNull(NaverLocalSearchClient.parseMapCoordinate("0", 180));
		assertNull(NaverLocalSearchClient.parseMapCoordinate("9999999999", 90));
	}
}
