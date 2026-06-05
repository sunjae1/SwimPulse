-- MySQL dump 10.13  Distrib 8.0.42, for Win64 (x86_64)
--
-- Host: localhost    Database: swimpulse
-- ------------------------------------------------------
-- Server version	8.0.42

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `app_users`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `display_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `notification_enabled` bit(1) NOT NULL,
  `profile_image_url` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK4vj92ux8a2eehds1mdvmks473` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notifications`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attempts` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `failure_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fcm_message_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('FAILED','QUEUED','SENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` enum('REGISTRATION_OPEN','REGISTRATION_REMINDER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id` bigint NOT NULL,
  `pool_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK87pbhgmx64hi8o3jxi70365hk` (`event_id`),
  KEY `FKq4gqiogs6c1ithsr5cowj45fp` (`pool_id`),
  KEY `FKkxpkwudgh8fqu6yqw1evf53u1` (`user_id`),
  CONSTRAINT `FK87pbhgmx64hi8o3jxi70365hk` FOREIGN KEY (`event_id`) REFERENCES `registration_events` (`id`),
  CONSTRAINT `FKkxpkwudgh8fqu6yqw1evf53u1` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`),
  CONSTRAINT `FKq4gqiogs6c1ithsr5cowj45fp` FOREIGN KEY (`pool_id`) REFERENCES `pools` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pool_notice_sources`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pool_notice_sources` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `last_scanned_at` datetime(6) DEFAULT NULL,
  `source_type` enum('HOMEPAGE','NOTICE_PAGE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','FAILED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `pool_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK26o4g7073tt0ikvbhhb6nfwtg` (`pool_id`),
  CONSTRAINT `FK26o4g7073tt0ikvbhhb6nfwtg` FOREIGN KEY (`pool_id`) REFERENCES `pools` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=126 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pool_notices`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pool_notices` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `confidence` double DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `extraction_status` enum('EXTRACTED','FAILED','LINK_ONLY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `raw_text` longtext COLLATE utf8mb4_unicode_ci,
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `registration_ends_at` datetime(6) DEFAULT NULL,
  `registration_starts_at` datetime(6) DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pool_id` bigint NOT NULL,
  `registration_periods_json` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `FKcrc4pabpuojxatop11x8dktch` (`pool_id`),
  CONSTRAINT `FKcrc4pabpuojxatop11x8dktch` FOREIGN KEY (`pool_id`) REFERENCES `pools` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=51 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pools`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pools` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `district` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `completion_year` int DEFAULT NULL,
  `contact_number` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `indoor_outdoor_type_name` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lot_number_address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `management_agency_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operating_organization_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_agency_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `postal_code` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `road_name_address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `standard_pool_lane_count` int DEFAULT NULL,
  `standard_pool_length_meters` decimal(6,2) DEFAULT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `geocode_status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING',
  `image_url` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_candidate_address` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_candidate_link` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_candidate_title` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_source` enum('MANUAL','NAVER_LOCAL_SEARCH','PUBLIC_DATA','UNKNOWN','USER_LOCATION_CANDIDATE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_status` enum('AUTO_UPDATED','FAILED','NEEDS_REVIEW','UNVERIFIED','VERIFIED') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `homepage_verified_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=133 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `registration_events`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `registration_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `registration_ends_at` datetime(6) NOT NULL,
  `registration_starts_at` datetime(6) NOT NULL,
  `reminder_queued` bit(1) NOT NULL,
  `start_queued` bit(1) NOT NULL,
  `status` enum('CLOSED','OPEN','UPCOMING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `pool_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKijg9ypm1p79l223tpkcv5dkhv` (`pool_id`),
  CONSTRAINT `FKijg9ypm1p79l223tpkcv5dkhv` FOREIGN KEY (`pool_id`) REFERENCES `pools` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `social_accounts`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `social_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `display_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `profile_image_url` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider` enum('GOOGLE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider_user_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_account_provider_user` (`provider`,`provider_user_id`),
  KEY `FKedqx3uqcqvx6lt2uk2wcm2lpd` (`user_id`),
  CONSTRAINT `FKedqx3uqcqvx6lt2uk2wcm2lpd` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `subscriptions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subscriptions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `pool_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `event_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subscription_user_event` (`user_id`,`event_id`),
  KEY `idx_subscriptions_user_id` (`user_id`),
  KEY `idx_subscriptions_pool_id` (`pool_id`),
  KEY `idx_subscriptions_event_id` (`event_id`),
  CONSTRAINT `FK1n4vskik87od4wdk9xgvewuxe` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`),
  CONSTRAINT `FK4wriambw9ouhjyi06d1jx4de3` FOREIGN KEY (`pool_id`) REFERENCES `pools` (`id`),
  CONSTRAINT `fk_subscriptions_event` FOREIGN KEY (`event_id`) REFERENCES `registration_events` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_devices`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_devices` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `device_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `enabled` bit(1) NOT NULL,
  `fcm_token` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_seen_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device` (`user_id`,`device_id`),
  CONSTRAINT `FKp11go9tv8ihco74vw872hxop8` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-05 13:12:07
