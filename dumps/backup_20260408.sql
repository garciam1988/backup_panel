-- MySQL dump 10.13  Distrib 8.4.7, for Win64 (x86_64)
--
-- Host: centerbeam.proxy.rlwy.net    Database: railway
-- ------------------------------------------------------
-- Server version	9.4.0

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
-- Table structure for table `aereos`
--

DROP TABLE IF EXISTS `aereos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `aereos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_aereos_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `aereos`
--

LOCK TABLES `aereos` WRITE;
/*!40000 ALTER TABLE `aereos` DISABLE KEYS */;
INSERT INTO `aereos` VALUES (1,'Vuelo domestico',1),(2,'Vuelo internacional',1),(3,'Charter',1);
/*!40000 ALTER TABLE `aereos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `aerolineas`
--

DROP TABLE IF EXISTS `aerolineas`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `aerolineas` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `telefono` varchar(60) DEFAULT NULL,
  `web` varchar(255) DEFAULT NULL,
  `iata_code` varchar(10) DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_aerolineas_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `aerolineas`
--

LOCK TABLES `aerolineas` WRITE;
/*!40000 ALTER TABLE `aerolineas` DISABLE KEYS */;
INSERT INTO `aerolineas` VALUES (1,'Aerolineas Argentinas','0810-222-86527','https://www.aerolineas.com.ar','AR',1),(2,'Jetsmart','0810-345-3368','https://www.jetsmart.com','JA',1),(3,'Flybondi','0810-999-4669','https://www.flybondi.com','FO',1);
/*!40000 ALTER TABLE `aerolineas` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `airline_baggage_rule`
--

DROP TABLE IF EXISTS `airline_baggage_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `airline_baggage_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aerolinea_id` bigint NOT NULL,
  `baggage_type_id` bigint NOT NULL,
  `fare_class` varchar(64) DEFAULT NULL,
  `weight_kg` decimal(10,2) NOT NULL,
  `dimensions` varchar(128) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_abr_airline_type` (`aerolinea_id`,`baggage_type_id`),
  KEY `fk_abr_baggage_type` (`baggage_type_id`),
  CONSTRAINT `fk_abr_airline` FOREIGN KEY (`aerolinea_id`) REFERENCES `aerolineas` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_abr_baggage_type` FOREIGN KEY (`baggage_type_id`) REFERENCES `baggage_type` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `airline_baggage_rule`
--

LOCK TABLES `airline_baggage_rule` WRITE;
/*!40000 ALTER TABLE `airline_baggage_rule` DISABLE KEYS */;
INSERT INTO `airline_baggage_rule` VALUES (1,1,1,NULL,6.00,'30 x 40 x 20 cm','2026-01-19 22:08:59'),(2,2,1,'Economy',12.00,'55 x 35 x 25 cm','2026-01-19 22:08:59'),(3,2,1,'Premium',16.00,'55 x 35 x 25 cm','2026-01-19 22:08:59'),(4,3,1,NULL,10.00,'25 x 35 x 55 cm','2026-01-19 22:08:59'),(5,1,2,NULL,15.00,'158 cm (lineal)','2026-01-19 22:08:59'),(6,2,2,NULL,20.00,'158 cm (lineal)','2026-01-19 22:08:59'),(7,3,2,NULL,23.00,'158 cm (lineal)','2026-01-19 22:08:59');
/*!40000 ALTER TABLE `airline_baggage_rule` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `alojamiento_x_horario`
--

DROP TABLE IF EXISTS `alojamiento_x_horario`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alojamiento_x_horario` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `id_alojamiento` bigint NOT NULL,
  `check_in_time` time DEFAULT NULL,
  `check_out_time` time DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_alojamiento_x_horario_alojamiento` (`id_alojamiento`),
  KEY `idx_alojamiento_x_horario_alojamiento` (`id_alojamiento`)
) ENGINE=MyISAM AUTO_INCREMENT=82 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `alojamiento_x_horario`
--

LOCK TABLES `alojamiento_x_horario` WRITE;
/*!40000 ALTER TABLE `alojamiento_x_horario` DISABLE KEYS */;
INSERT INTO `alojamiento_x_horario` VALUES (15,15,'15:00:00','10:00:00'),(16,16,'15:00:00','10:00:00'),(17,17,'15:00:00','10:00:00'),(18,18,'15:00:00','10:00:00'),(19,19,'15:00:00','10:00:00'),(20,20,'15:00:00','10:00:00'),(21,21,'15:00:00','10:00:00'),(22,22,'15:00:00','10:00:00'),(23,23,'15:00:00','10:00:00'),(24,24,'15:00:00','10:00:00'),(25,25,'15:00:00','10:00:00'),(26,26,'15:00:00','10:00:00'),(27,27,'15:00:00','10:00:00'),(28,28,'15:00:00','10:00:00'),(29,29,'15:00:00','10:00:00'),(30,30,'15:00:00','10:00:00'),(31,31,'15:00:00','10:00:00'),(32,32,'15:00:00','10:00:00'),(33,33,'15:00:00','10:00:00'),(34,34,'15:00:00','10:00:00'),(35,35,'15:00:00','10:00:00'),(36,36,'15:00:00','10:00:00'),(37,37,'15:00:00','10:00:00'),(38,38,'15:00:00','10:00:00'),(39,39,'15:00:00','10:00:00'),(40,40,'15:00:00','10:00:00'),(41,41,'15:00:00','10:00:00'),(42,42,'15:00:00','10:00:00'),(43,43,'15:00:00','10:00:00'),(44,44,'15:00:00','10:00:00'),(45,45,'15:00:00','10:00:00'),(46,46,'15:00:00','10:00:00'),(47,47,'15:00:00','10:00:00'),(48,48,'15:00:00','10:00:00'),(49,49,'15:00:00','10:00:00'),(50,50,'15:00:00','10:00:00'),(51,51,'15:00:00','10:00:00'),(52,52,'15:00:00','10:00:00'),(53,53,'15:00:00','10:00:00'),(54,54,'15:00:00','10:00:00'),(55,55,'15:00:00','10:00:00'),(56,56,'15:00:00','10:00:00'),(57,57,'15:00:00','10:00:00'),(58,58,'15:00:00','10:00:00'),(59,59,'15:00:00','10:00:00'),(60,60,'15:00:00','10:00:00'),(61,61,'15:00:00','10:00:00'),(62,62,'15:00:00','10:00:00'),(63,63,'15:00:00','10:00:00'),(64,64,'15:00:00','10:00:00'),(65,65,'15:00:00','10:00:00'),(66,66,'15:00:00','10:00:00'),(67,67,'15:00:00','10:00:00'),(68,68,'15:00:00','10:00:00'),(69,69,'15:00:00','10:00:00'),(70,70,'15:00:00','10:00:00'),(71,71,'15:00:00','10:00:00'),(72,72,'15:00:00','10:00:00'),(73,73,'15:00:00','10:00:00'),(74,74,'15:00:00','10:00:00'),(75,75,'15:00:00','10:00:00'),(76,76,'15:00:00','10:00:00'),(77,77,'15:00:00','10:00:00'),(78,78,'15:00:00','10:00:00'),(79,79,'15:00:00','10:00:00'),(80,80,'15:00:00','10:00:00'),(81,81,'15:00:00','10:00:00');
/*!40000 ALTER TABLE `alojamiento_x_horario` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `alojamiento_x_regimen`
--

DROP TABLE IF EXISTS `alojamiento_x_regimen`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alojamiento_x_regimen` (
  `id_alojamiento` bigint NOT NULL,
  `id_regimen` bigint NOT NULL,
  PRIMARY KEY (`id_alojamiento`,`id_regimen`),
  KEY `idx_axr_regimen` (`id_regimen`),
  CONSTRAINT `fk_axr_alojamiento` FOREIGN KEY (`id_alojamiento`) REFERENCES `alojamientos` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_axr_regimen` FOREIGN KEY (`id_regimen`) REFERENCES `regimen` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `alojamiento_x_regimen`
--

LOCK TABLES `alojamiento_x_regimen` WRITE;
/*!40000 ALTER TABLE `alojamiento_x_regimen` DISABLE KEYS */;
INSERT INTO `alojamiento_x_regimen` VALUES (63,1),(15,2),(16,2),(17,2),(18,2),(19,2),(20,2),(21,2),(22,2),(23,2),(24,2),(25,2),(26,2),(27,2),(28,2),(29,2),(30,2),(31,2),(32,2),(33,2),(34,2),(35,2),(36,2),(37,2),(38,2),(39,2),(40,2),(41,2),(42,2),(43,2),(44,2),(45,2),(46,2),(47,2),(48,2),(49,2),(50,2),(51,2),(52,2),(53,2),(54,2),(55,2),(56,2),(57,2),(58,2),(59,2),(60,2),(61,2),(62,2),(64,2),(65,2),(66,2),(67,2),(68,2),(69,2),(70,2),(71,2),(72,2),(73,2),(74,2),(75,2),(76,2),(77,2),(78,2),(79,2),(80,2),(81,2);
/*!40000 ALTER TABLE `alojamiento_x_regimen` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `alojamientos`
--

DROP TABLE IF EXISTS `alojamientos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alojamientos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `direccion` varchar(300) DEFAULT NULL,
  `telefono` varchar(60) DEFAULT NULL,
  `web` varchar(255) DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `id_ciudad` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_alojamientos_descripcion` (`descripcion`),
  KEY `fk_alojamientos_ciudad` (`id_ciudad`),
  CONSTRAINT `fk_alojamientos_ciudad` FOREIGN KEY (`id_ciudad`) REFERENCES `ciudades` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=82 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `alojamientos`
--

LOCK TABLES `alojamientos` WRITE;
/*!40000 ALTER TABLE `alojamientos` DISABLE KEYS */;
INSERT INTO `alojamientos` VALUES (15,'HTL La Malinka','Av. Bustillo Km 4.788',NULL,NULL,1,8),(16,'M382 Hotel Bariloche','Mitre, 382',NULL,NULL,1,8),(17,'Huella Andina','Quaglia 647',NULL,NULL,1,8),(18,'Hosteria Puyehue','Elordi E. 243',NULL,NULL,1,8),(19,'M383 Hotel Bariloche','Mitre 383',NULL,NULL,1,8),(20,'Gran Hotel Panamericano','Avenida Bustillo Km 22.900',NULL,NULL,1,8),(21,'Patagonia Sur Hotel','Elflein 340',NULL,NULL,1,8),(22,'Hotel Eco Ski by bund','Quaglia 281',NULL,NULL,1,8),(23,'Hotel Patagonia Bariloche','Av. Exequiel Bustillo 1231',NULL,NULL,1,8),(24,'Hotel Premier Bariloche','Rolando 263',NULL,NULL,1,8),(25,'Tierra Gaucha Hostel Boutique','Vice Almirante O\'Connor 766',NULL,NULL,1,8),(26,'Monte Cervino Hotel','Ruiz Moreno 211',NULL,NULL,1,8),(27,'Hosteria Santa Rita','Av. Exequiel Bustillo 7277',NULL,NULL,1,8),(28,'Hotel Patagonia Signature','Salta, 461',NULL,NULL,1,8),(29,'Hotel Plaza Bariloche','Vice Alte O\'Connor 431',NULL,NULL,1,8),(30,'Hotel Tierra Gaucha','Villegas 148',NULL,NULL,1,8),(31,'Hotel Nordico','San Martin 430',NULL,NULL,1,8),(32,'Hotel Concept Bariloche','Francisco Pascasio Moreno 136',NULL,NULL,1,8),(33,'Hotel Windsor Mendoza','Necochea 675',NULL,NULL,1,9),(34,'Cordon Del Plata','9 De Julio 1539',NULL,NULL,1,9),(35,'Hotel M Mendoza','1550 9 de Julio',NULL,NULL,1,9),(36,'Hathor Hotels Mendoza','Uspallata 840',NULL,NULL,1,9),(37,'Internacional Mendoza','Sarmiento 720',NULL,NULL,1,9),(38,'Abril Hotel Boutique','Patricias Mendocinas 866',NULL,NULL,1,9),(39,'Provincial Mendoza','Belgrano 1259',NULL,NULL,1,9),(40,'Buenos Aires Salta Hotel','Buenos Aires 745',NULL,NULL,1,14),(41,'Samka Hotel Salta','Buenos Aires 444',NULL,NULL,1,14),(42,'Hotel Aybal','Av. John F. Kennedy 2000',NULL,NULL,1,14),(43,'Urquiza Suites Salta','Urquiza 1045',NULL,NULL,1,14),(44,'Hotel Plaza Salta','Facundo De Zuviria 135',NULL,NULL,1,14),(45,'Posada del Marques','Cordoba 195',NULL,NULL,1,14),(46,'Hotel Posada Del Sol','Alvarado 646',NULL,NULL,1,14),(47,'Marilian Salta','Buenos Aires 176',NULL,NULL,1,14),(48,'Benjamin I Jujuy','659 San Antonio',NULL,NULL,1,22),(49,'Posada El Arribo','Gral. Belgrano 1263',NULL,NULL,1,22),(50,'Altos De La Vina','Pasquini Lopez',NULL,NULL,1,22),(51,'Howard Johnson Plaza Jujuy','Gral. Güemes 864',NULL,NULL,1,22),(52,'Hotel Fenicia','Av. 19 de Abril 427',NULL,NULL,1,22),(53,'Rainforest Selva Hotel','Iryapú Reserve 600 Hectáreas',NULL,NULL,1,15),(54,'Hosteria Casa Blanca Iguazu','Av. Guaraní 121',NULL,NULL,1,15),(55,'Hotel Lilian Iguazu','Fray Luis Beltrán',NULL,NULL,1,15),(56,'Bagu Namandu Guazu','Puerto Iguazu 3370',NULL,NULL,1,15),(57,'INGA by DOT Suites','Almirante Brown 465',NULL,NULL,1,15),(58,'Kelta Hotel Puerto Iguazu','Curupi 61',NULL,NULL,1,15),(59,'Tupa Boutique Hotel','Cocú 695',NULL,NULL,1,15),(60,'Altos del Iguazu Hotel','Avenida de los Trabajadores 100',NULL,NULL,1,15),(61,'Bagu Siete Bocas','Avenida San Martín 317',NULL,NULL,1,15),(62,'El Libertador Iguazu','Bompland 110',NULL,NULL,1,15),(63,'Patagonia Austral Apartamentos','Magallanes 1120',NULL,NULL,1,10),(64,'Mysten Kepen B&B','Rivadavia 826',NULL,NULL,1,10),(65,'Antartida Argentina Hotel','Rivadavia 172',NULL,NULL,1,10),(66,'Hotel Monaco Ushuaia','San Martin 1355',NULL,NULL,1,10),(67,'Hosteria Ailen','Av. Leandro N. Alem 3981',NULL,NULL,1,10),(68,'Hosteria Les Eclaireurs','Staiyakin 2676',NULL,NULL,1,10),(69,'Hosteria Rosa de los Vientos','Roca 533',NULL,NULL,1,10),(70,'Aires del Beagle Apartment','17 de Mayo 616',NULL,NULL,1,10),(71,'Villa Brescia Hotel','Av. San Martín 1299',NULL,NULL,1,10),(72,'Hotel Austral Ushuaia','9 de Julio 250',NULL,NULL,1,10),(73,'Hotel los Naranjos','San Martín 1446',NULL,NULL,1,10),(74,'Mil 810 Ushuaia Hotel','25 de Mayo 245',NULL,NULL,1,10),(75,'Glaciares De La Patagonia','Los Antiguos 194',NULL,NULL,1,11),(76,'Tierra Tehuelche Hosteria','Los Antiguos 171',NULL,NULL,1,11),(77,'Hosteria Hainen','Puerto Deseado N 118',NULL,NULL,1,11),(78,'Las Dunas Hotel','Av. Costanera Nestor Kirchner 751',NULL,NULL,1,11),(79,'Hosteria Austral Calafate','San Juan Bosco 917',NULL,NULL,1,11),(80,'Don Pepe Hotel y Cabanas','Casimiro Bigua 59',NULL,NULL,1,11),(81,'Kalenshen Calafate','Calle 998 N°36',NULL,NULL,1,11);
/*!40000 ALTER TABLE `alojamientos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `backup_restore_history`
--

DROP TABLE IF EXISTS `backup_restore_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `backup_restore_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dump_file_name` varchar(255) DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `message` varchar(2000) DEFAULT NULL,
  `performed_by` varchar(255) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `status` varchar(30) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `backup_restore_history`
--

LOCK TABLES `backup_restore_history` WRITE;
/*!40000 ALTER TABLE `backup_restore_history` DISABLE KEYS */;
/*!40000 ALTER TABLE `backup_restore_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `backup_run`
--

DROP TABLE IF EXISTS `backup_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `backup_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `file_path` varchar(1024) NOT NULL,
  `message` varchar(2000) DEFAULT NULL,
  `size_bytes` bigint NOT NULL,
  `status` varchar(30) DEFAULT NULL,
  `trigger_type` varchar(30) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `backup_run`
--

LOCK TABLES `backup_run` WRITE;
/*!40000 ALTER TABLE `backup_run` DISABLE KEYS */;
/*!40000 ALTER TABLE `backup_run` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `backup_settings`
--

DROP TABLE IF EXISTS `backup_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `backup_settings` (
  `id` bigint NOT NULL,
  `daily_time` varchar(10) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `last_daily_run` date DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `backup_settings`
--

LOCK TABLES `backup_settings` WRITE;
/*!40000 ALTER TABLE `backup_settings` DISABLE KEYS */;
INSERT INTO `backup_settings` VALUES (1,'02:00',_binary '\0',NULL);
/*!40000 ALTER TABLE `backup_settings` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `baggage_type`
--

DROP TABLE IF EXISTS `baggage_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `baggage_type` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_baggage_type_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `baggage_type`
--

LOCK TABLES `baggage_type` WRITE;
/*!40000 ALTER TABLE `baggage_type` DISABLE KEYS */;
INSERT INTO `baggage_type` VALUES (1,'CARRY_ON','De mano'),(2,'CHECKED','Despachado');
/*!40000 ALTER TABLE `baggage_type` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `bank_cards`
--

DROP TABLE IF EXISTS `bank_cards`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bank_cards` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bank_id` bigint NOT NULL,
  `name` varchar(120) NOT NULL,
  `nro_tarjeta` varchar(25) DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_bank_cards_bank_id` (`bank_id`),
  CONSTRAINT `fk_bank_cards_bank` FOREIGN KEY (`bank_id`) REFERENCES `banks` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `bank_cards`
--

LOCK TABLES `bank_cards` WRITE;
/*!40000 ALTER TABLE `bank_cards` DISABLE KEYS */;
/*!40000 ALTER TABLE `bank_cards` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `banks`
--

DROP TABLE IF EXISTS `banks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `banks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(120) NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_banks_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `banks`
--

LOCK TABLES `banks` WRITE;
/*!40000 ALTER TABLE `banks` DISABLE KEYS */;
INSERT INTO `banks` VALUES (1,'BROU - Banco de la República Oriental del Uruguay',1,'2026-02-10 04:37:42','2026-02-10 04:37:42'),(6,'BANCO GALICIA',1,'2026-04-06 14:19:38','2026-04-06 14:19:38'),(7,'SANTANDER RIO',1,'2026-04-06 14:20:50','2026-04-06 14:20:50');
/*!40000 ALTER TABLE `banks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ciudades`
--

DROP TABLE IF EXISTS `ciudades`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ciudades` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ciudades_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ciudades`
--

LOCK TABLES `ciudades` WRITE;
/*!40000 ALTER TABLE `ciudades` DISABLE KEYS */;
INSERT INTO `ciudades` VALUES (1,'Bariloche',0),(2,'Mendoza',0),(3,'Ushuaia',0),(4,'El Calafate',0),(5,'Buenos Aires (BUE)',1),(6,'Buenos Aires - Ezeiza (EZE)',1),(7,'Buenos Aires - Aeroparque (AEP)',1),(8,'Bariloche (BRC)',1),(9,'Mendoza (MDZ)',1),(10,'Ushuaia (USH)',1),(11,'El Calafate (FTE)',1),(12,'Cordoba (COR)',1),(13,'Rosario (ROS)',1),(14,'Salta (SLA)',1),(15,'Iguazu (IGR)',1),(16,'Neuquen (NQN)',1),(17,'Comodoro Rivadavia (CRD)',1),(18,'Puerto Madryn (PMY)',1),(19,'San Martin de los Andes (CPC)',1),(20,'Tucuman (TUC)',1),(21,'Mar del Plata (MDQ)',0),(22,'Jujuy (JJY)',1);
/*!40000 ALTER TABLE `ciudades` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `client_log_event`
--

DROP TABLE IF EXISTS `client_log_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `client_log_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `server_ts` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `client_ts` timestamp(6) NULL DEFAULT NULL,
  `level` varchar(10) NOT NULL,
  `category` varchar(40) DEFAULT NULL,
  `app` varchar(80) DEFAULT NULL,
  `env` varchar(20) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `request_id` varchar(80) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `user_email` varchar(255) DEFAULT NULL,
  `user_role` varchar(60) DEFAULT NULL,
  `ip` varchar(80) DEFAULT NULL,
  `user_agent` varchar(1024) DEFAULT NULL,
  `url` varchar(1024) DEFAULT NULL,
  `pathname` varchar(512) DEFAULT NULL,
  `message` longtext,
  `data_json` longtext,
  `breadcrumbs_json` longtext,
  `platform` varchar(120) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cle_server_ts` (`server_ts`),
  KEY `idx_cle_level` (`level`),
  KEY `idx_cle_request_id` (`request_id`),
  KEY `idx_cle_user_email` (`user_email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `client_log_event`
--

LOCK TABLES `client_log_event` WRITE;
/*!40000 ALTER TABLE `client_log_event` DISABLE KEYS */;
/*!40000 ALTER TABLE `client_log_event` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `destinos`
--

DROP TABLE IF EXISTS `destinos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `destinos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_destinos_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `destinos`
--

LOCK TABLES `destinos` WRITE;
/*!40000 ALTER TABLE `destinos` DISABLE KEYS */;
INSERT INTO `destinos` VALUES (1,'Bariloche',1),(2,'Mendoza',1),(3,'Ushuaia',1),(4,'El Calafate',1),(5,'Ushuaia & Calafate',1);
/*!40000 ALTER TABLE `destinos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `destinos_aereos`
--

DROP TABLE IF EXISTS `destinos_aereos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `destinos_aereos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ciudad` varchar(120) DEFAULT NULL,
  `aeropuerto` varchar(180) DEFAULT NULL,
  `iata` varchar(3) NOT NULL,
  `pais` varchar(120) DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_destinos_aereos_iata` (`iata`)
) ENGINE=InnoDB AUTO_INCREMENT=98 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `destinos_aereos`
--

LOCK TABLES `destinos_aereos` WRITE;
/*!40000 ALTER TABLE `destinos_aereos` DISABLE KEYS */;
INSERT INTO `destinos_aereos` VALUES (1,'Buenos Aires','Aeroparque Jorge Newbery','AEP','Argentina',1),(2,'Buenos Aires','Ministro Pistarini (Ezeiza)','EZE','Argentina',1),(3,'Buenos Aires','Buenos Aires (Ciudad)','BUE','Argentina',1),(4,'San Carlos de Bariloche','Teniente Luis Candelaria','BRC','Argentina',1),(5,'Mendoza','Gobernador Francisco Gabrielli','MDZ','Argentina',1),(6,'Córdoba','Ingeniero Aeronáutico Ambrosio L.V. Taravella','COR','Argentina',1),(7,'Puerto Iguazú','Cataratas del Iguazú','IGR','Argentina',1),(8,'Ushuaia','Malvinas Argentinas','USH','Argentina',1),(9,'El Calafate','Comandante Armando Tola','FTE','Argentina',1),(10,'Mar del Plata','Ástor Piazzolla','MDQ','Argentina',1),(11,'Neuquén','Presidente Perón','NQN','Argentina',1),(12,'Comodoro Rivadavia','General Enrique Mosconi','CRD','Argentina',1),(13,'Salta','Martín Miguel de Güemes','SLA','Argentina',1),(14,'San Miguel de Tucumán','Teniente Benjamín Matienzo','TUC','Argentina',1),(15,'Rosario','Islas Malvinas','ROS','Argentina',1),(16,'San Juan','Domingo Faustino Sarmiento','UAQ','Argentina',1),(17,'Santa Fe','Sauce Viejo','SFN','Argentina',1),(18,'Resistencia','Resistencia International','RES','Argentina',1),(19,'Trelew','Almirante Marcos A. Zar','REL','Argentina',1),(20,'Puerto Madryn','El Tehuelche','PMY','Argentina',1),(21,'Río Gallegos','Piloto Civil Norberto Fernández','RGL','Argentina',1),(22,'San Martín de los Andes','Aviador Carlos Campos','CPC','Argentina',1),(23,'Bahía Blanca','Comandante Espora','BHI','Argentina',1),(24,'Posadas','Libertador General José de San Martín','PSS','Argentina',1),(25,'Jujuy','Gobernador Horacio Guzmán','JUJ','Argentina',1),(26,'San Rafael','San Rafael Airport','AFA','Argentina',1),(27,'Corrientes','Dr. Fernando Piragine Niveyro','CNQ','Argentina',1),(28,'La Rioja','Capitán Vicente Almandos Almonacid','IRJ','Argentina',1),(29,'Catamarca','Coronel Felipe Varela','CTC','Argentina',1),(30,'Santiago del Estero','Vicecomodoro Ángel de la Paz Aragonés','SDE','Argentina',1),(31,'Buenos Aires','Aeroparque (Todos)','AER','Argentina',1),(32,'Santiago','Arturo Merino Benítez','SCL','Chile',1),(33,'Lima','Jorge Chávez','LIM','Perú',1),(34,'San Pablo','Guarulhos','GRU','Brasil',1),(35,'Río de Janeiro','Galeão','GIG','Brasil',1),(36,'Montevideo','Carrasco','MVD','Uruguay',1),(37,'Asunción','Silvio Pettirossi','ASU','Paraguay',1),(38,'La Paz','El Alto','LPB','Bolivia',1),(39,'Santa Cruz de la Sierra','Viru Viru','VVI','Bolivia',1),(40,'Bogotá','El Dorado','BOG','Colombia',1),(41,'Medellín','José María Córdova','MDE','Colombia',1),(42,'Ciudad de México','Benito Juárez','MEX','México',1),(43,'Cancún','Cancún','CUN','México',1),(44,'Miami','Miami International','MIA','USA',1),(45,'Nueva York','John F. Kennedy','JFK','USA',1),(46,'Nueva York','LaGuardia','LGA','USA',1),(47,'Los Ángeles','Los Angeles International','LAX','USA',1),(48,'Houston','George Bush Intercontinental','IAH','USA',1),(49,'Atlanta','Hartsfield-Jackson','ATL','USA',1),(50,'Dallas','Dallas/Fort Worth','DFW','USA',1),(51,'Orlando','Orlando International','MCO','USA',1),(52,'Madrid','Adolfo Suárez Madrid-Barajas','MAD','España',1),(53,'Barcelona','Barcelona-El Prat','BCN','España',1),(54,'París','Charles de Gaulle','CDG','Francia',1),(55,'París','Orly','ORY','Francia',1),(56,'Londres','Heathrow','LHR','Reino Unido',1),(57,'Londres','Gatwick','LGW','Reino Unido',1),(58,'Roma','Fiumicino','FCO','Italia',1),(59,'Milán','Malpensa','MXP','Italia',1),(60,'Ámsterdam','Schiphol','AMS','Países Bajos',1),(61,'Frankfurt','Frankfurt','FRA','Alemania',1),(62,'Múnich','Munich','MUC','Alemania',1),(63,'Zúrich','Zurich','ZRH','Suiza',1),(64,'Lisboa','Humberto Delgado','LIS','Portugal',1),(65,'Oporto','Francisco Sá Carneiro','OPO','Portugal',1),(66,'Dublín','Dublin','DUB','Irlanda',1),(67,'Toronto','Pearson','YYZ','Canadá',1),(68,'Montreal','Trudeau','YUL','Canadá',1),(69,'Ciudad de Panamá','Tocumen','PTY','Panamá',1),(70,'San José','Juan Santamaría','SJO','Costa Rica',1),(71,'La Habana','José Martí','HAV','Cuba',1),(72,'Punta Cana','Punta Cana','PUJ','República Dominicana',1),(73,'Santo Domingo','Las Américas','SDQ','República Dominicana',1),(74,'Quito','Mariscal Sucre','UIO','Ecuador',1),(75,'Guayaquil','José Joaquín de Olmedo','GYE','Ecuador',1),(76,'Caracas','Simón Bolívar','CCS','Venezuela',1),(77,'San Salvador','El Salvador International','SAL','El Salvador',1),(78,'Guatemala','La Aurora','GUA','Guatemala',1),(79,'San Juan','Luis Muñoz Marín','SJU','Puerto Rico',1),(80,'Doha','Hamad','DOH','Qatar',1),(81,'Dubái','Dubai International','DXB','Emiratos Árabes Unidos',1),(82,'Estambul','Istanbul Airport','IST','Turquía',1),(83,'Tel Aviv','Ben Gurion','TLV','Israel',1),(84,'Johannesburgo','O.R. Tambo','JNB','Sudáfrica',1),(85,'Ciudad del Cabo','Cape Town','CPT','Sudáfrica',1),(86,'Sydney','Sydney','SYD','Australia',1),(87,'Melbourne','Melbourne','MEL','Australia',1),(88,'Auckland','Auckland','AKL','Nueva Zelanda',1),(89,'Tokio','Narita','NRT','Japón',1),(90,'Tokio','Haneda','HND','Japón',1),(91,'Seúl','Incheon','ICN','Corea del Sur',1),(92,'Singapur','Changi','SIN','Singapur',1),(93,'Bangkok','Suvarnabhumi','BKK','Tailandia',1),(94,'Hong Kong','Hong Kong','HKG','Hong Kong',1),(95,'Pekín','Capital','PEK','China',1),(96,'Shanghái','Pudong','PVG','China',1),(97,'São Paulo','Congonhas','CGH','Brasil',1);
/*!40000 ALTER TABLE `destinos_aereos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `estados_emision`
--

DROP TABLE IF EXISTS `estados_emision`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `estados_emision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_estados_emision_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `estados_emision`
--

LOCK TABLES `estados_emision` WRITE;
/*!40000 ALTER TABLE `estados_emision` DISABLE KEYS */;
INSERT INTO `estados_emision` VALUES (1,'Pendiente',1),(2,'Reservado',1),(3,'Señado',1),(4,'Emitido',1);
/*!40000 ALTER TABLE `estados_emision` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `excursiones`
--

DROP TABLE IF EXISTS `excursiones`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `excursiones` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `horario_salida` time DEFAULT NULL,
  `horario_regreso` time DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `costo_usd` decimal(12,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `destination` varchar(128) NOT NULL,
  `nombre` varchar(255) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_excursiones_descripcion` (`descripcion`),
  UNIQUE KEY `uq_excursiones_destination_nombre` (`destination`,`nombre`),
  KEY `idx_excursiones_destination` (`destination`),
  KEY `idx_excursiones_activo` (`activo`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `excursiones`
--

LOCK TABLES `excursiones` WRITE;
/*!40000 ALTER TABLE `excursiones` DISABLE KEYS */;
INSERT INTO `excursiones` VALUES (1,'Circuito Chico','09:00:00','13:00:00',1,100.00,'2026-01-15 00:50:38.781248','','Circuito Chico','2026-01-15 00:50:38.787708'),(2,'Cerro Catedral','09:00:00','13:00:00',1,120.00,'2026-01-15 00:50:38.781248','','Cerro Catedral','2026-01-15 00:50:38.787708'),(3,'Isla Victoria y Bosque de Arrayanes','09:00:00','13:00:00',1,150.00,'2026-01-15 00:50:38.781248','','Isla Victoria y Bosque de Arrayanes','2026-01-15 00:50:38.787708'),(4,'Ruta del Vino (Maipu / Lujan de Cuyo)','10:30:00','16:30:00',1,200.00,'2026-01-15 00:50:38.781248','','Ruta del Vino (Maipu / Lujan de Cuyo)','2026-01-15 00:50:38.787708'),(5,'Alta Montana y Aconcagua','08:30:00','18:30:00',1,350.00,'2026-01-15 00:50:38.781248','','Alta Montana y Aconcagua','2026-01-18 07:59:15.685817'),(6,'Termas de Cacheuta','09:30:00','15:30:00',1,300.00,'2026-01-15 00:50:38.781248','','Termas de Cacheuta','2026-01-15 00:50:38.787708'),(7,'Canal Beagle (navegacion)','10:00:00','12:30:00',1,0.00,'2026-01-15 00:50:38.781248','','Canal Beagle (navegacion)','2026-01-15 00:50:38.787708'),(8,'Parque Nacional Tierra del Fuego','09:00:00','13:30:00',1,0.00,'2026-01-15 00:50:38.781248','','Parque Nacional Tierra del Fuego','2026-01-15 00:50:38.787708'),(9,'Tren del Fin del Mundo','14:00:00','17:00:00',1,0.00,'2026-01-15 00:50:38.781248','','Tren del Fin del Mundo','2026-01-15 00:50:38.787708'),(10,'Glaciar Perito Moreno','09:30:00','16:30:00',1,0.00,'2026-01-15 00:50:38.781248','','Glaciar Perito Moreno','2026-01-15 00:50:38.787708'),(11,'Navegacion Glaciares (Upsala / Spegazzini)','07:30:00','18:00:00',1,0.00,'2026-01-15 00:50:38.781248','','Navegacion Glaciares (Upsala / Spegazzini)','2026-01-15 00:50:38.787708');
/*!40000 ALTER TABLE `excursiones` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `excursiones_x_destinos`
--

DROP TABLE IF EXISTS `excursiones_x_destinos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `excursiones_x_destinos` (
  `id_excursion` bigint NOT NULL,
  `id_destino` bigint NOT NULL,
  PRIMARY KEY (`id_excursion`,`id_destino`),
  KEY `idx_exd_destino` (`id_destino`),
  CONSTRAINT `fk_exd_destino` FOREIGN KEY (`id_destino`) REFERENCES `destinos` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_exd_excursion` FOREIGN KEY (`id_excursion`) REFERENCES `excursiones` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `excursiones_x_destinos`
--

LOCK TABLES `excursiones_x_destinos` WRITE;
/*!40000 ALTER TABLE `excursiones_x_destinos` DISABLE KEYS */;
INSERT INTO `excursiones_x_destinos` VALUES (1,1),(2,1),(3,1),(4,2),(5,2),(6,2),(7,3),(8,3),(9,3),(10,4),(11,4);
/*!40000 ALTER TABLE `excursiones_x_destinos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `expenses`
--

DROP TABLE IF EXISTS `expenses`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `expenses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expense_date` date NOT NULL,
  `type` varchar(30) NOT NULL,
  `category` varchar(120) DEFAULT NULL,
  `concept` varchar(255) NOT NULL,
  `provider_id` bigint DEFAULT NULL,
  `payment_method` varchar(60) DEFAULT NULL,
  `amount` decimal(12,2) NOT NULL,
  `currency` varchar(10) NOT NULL DEFAULT 'ARS',
  `status` varchar(30) DEFAULT NULL,
  `receipt_number` varchar(80) DEFAULT NULL,
  `notes` text,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `group_id` bigint DEFAULT NULL,
  `receipt_blob` longblob,
  `receipt_content_type` varchar(255) DEFAULT NULL,
  `receipt_file_name` varchar(255) DEFAULT NULL,
  `receipt_last4` varchar(4) DEFAULT NULL,
  `menu_item_id` bigint DEFAULT NULL,
  `service_payment_record_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_expenses_date` (`expense_date`),
  KEY `idx_expenses_type` (`type`),
  KEY `idx_expenses_status` (`status`),
  KEY `idx_expenses_provider` (`provider_id`),
  KEY `idx_expenses_amount` (`amount`),
  KEY `idx_expenses_menu_item_id` (`menu_item_id`),
  KEY `idx_expenses_service_payment_record_id` (`service_payment_record_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `expenses`
--

LOCK TABLES `expenses` WRITE;
/*!40000 ALTER TABLE `expenses` DISABLE KEYS */;
/*!40000 ALTER TABLE `expenses` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ferry_providers`
--

DROP TABLE IF EXISTS `ferry_providers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ferry_providers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nombre` varchar(255) NOT NULL,
  `direccion` varchar(255) DEFAULT NULL,
  `telefono` varchar(100) DEFAULT NULL,
  `web` varchar(100) DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ferry_providers_nombre` (`nombre`),
  KEY `idx_ferry_providers_activo` (`activo`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ferry_providers`
--

LOCK TABLES `ferry_providers` WRITE;
/*!40000 ALTER TABLE `ferry_providers` DISABLE KEYS */;
INSERT INTO `ferry_providers` VALUES (1,'Buquebus','Av. Antártida Argentina 821, Puerto Madero, CABA','+54 11 4316-6500','www.buquebus.com',1,'2026-01-19 19:25:08','2026-03-19 23:42:12'),(2,'Colonia Express','Av. Elvira Rawson de Dellepiane 155, Puerto Madero Sur, CABA','+54 11 5167-7700','www.coloniaexpress.com',1,'2026-01-19 19:25:08','2026-03-19 23:42:12');
/*!40000 ALTER TABLE `ferry_providers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ferry_schedules`
--

DROP TABLE IF EXISTS `ferry_schedules`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ferry_schedules` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `bus_arrival_time` time(6) DEFAULT NULL,
  `bus_departure_time` time(6) DEFAULT NULL,
  `bus_destination` varchar(255) DEFAULT NULL,
  `bus_origin` varchar(255) DEFAULT NULL,
  `ferry_arrival_time` time(6) NOT NULL,
  `ferry_departure_time` time(6) NOT NULL,
  `ferry_destination` varchar(255) NOT NULL,
  `ferry_origin` varchar(255) NOT NULL,
  `provider` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ferry_sch_provider` (`provider`),
  KEY `idx_ferry_sch_route` (`ferry_origin`,`ferry_destination`)
) ENGINE=InnoDB AUTO_INCREMENT=69 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ferry_schedules`
--

LOCK TABLES `ferry_schedules` WRITE;
/*!40000 ALTER TABLE `ferry_schedules` DISABLE KEYS */;
INSERT INTO `ferry_schedules` VALUES (53,_binary '','09:45:00.000000','08:30:00.000000','Colonia del Sacramento','Buenos Aires','12:15:00.000000','10:15:00.000000','Montevideo','Colonia del Sacramento','Colonia Express'),(54,_binary '','11:45:00.000000','10:30:00.000000','Colonia del Sacramento','Buenos Aires','14:15:00.000000','12:15:00.000000','Montevideo','Colonia del Sacramento','Colonia Express'),(55,_binary '','14:05:00.000000','12:50:00.000000','Colonia del Sacramento','Buenos Aires','16:35:00.000000','14:35:00.000000','Montevideo','Colonia del Sacramento','Colonia Express'),(56,_binary '','19:45:00.000000','18:30:00.000000','Colonia del Sacramento','Buenos Aires','22:15:00.000000','20:15:00.000000','Montevideo','Colonia del Sacramento','Colonia Express'),(57,_binary '','21:15:00.000000','20:00:00.000000','Colonia del Sacramento','Buenos Aires','23:45:00.000000','21:45:00.000000','Montevideo','Colonia del Sacramento','Colonia Express'),(58,_binary '','08:00:00.000000','05:00:00.000000','Colonia del Sacramento','Montevideo','09:45:00.000000','08:30:00.000000','Buenos Aires','Colonia del Sacramento','Colonia Express'),(59,_binary '','10:00:00.000000','07:00:00.000000','Colonia del Sacramento','Montevideo','11:45:00.000000','10:30:00.000000','Buenos Aires','Colonia del Sacramento','Colonia Express'),(60,_binary '','16:00:00.000000','13:00:00.000000','Colonia del Sacramento','Montevideo','17:45:00.000000','16:30:00.000000','Buenos Aires','Colonia del Sacramento','Colonia Express'),(61,_binary '','17:30:00.000000','14:30:00.000000','Colonia del Sacramento','Montevideo','19:15:00.000000','18:00:00.000000','Buenos Aires','Colonia del Sacramento','Colonia Express'),(62,_binary '','20:00:00.000000','17:00:00.000000','Colonia del Sacramento','Montevideo','21:45:00.000000','20:30:00.000000','Buenos Aires','Colonia del Sacramento','Colonia Express'),(63,_binary '','09:45:00.000000','08:30:00.000000','Colonia del Sacramento','Buenos Aires','12:30:00.000000','10:15:00.000000','Montevideo','Colonia del Sacramento','Buquebus'),(64,_binary '','13:30:00.000000','12:15:00.000000','Colonia del Sacramento','Buenos Aires','16:15:00.000000','14:00:00.000000','Montevideo','Colonia del Sacramento','Buquebus'),(65,_binary '','20:00:00.000000','18:45:00.000000','Colonia del Sacramento','Buenos Aires','22:45:00.000000','20:30:00.000000','Montevideo','Colonia del Sacramento','Buquebus'),(66,_binary '','09:46:00.000000','07:00:00.000000','Colonia del Sacramento','Montevideo','11:31:00.000000','10:16:00.000000','Buenos Aires','Colonia del Sacramento','Buquebus'),(67,_binary '','16:30:00.000000','13:45:00.000000','Colonia del Sacramento','Montevideo','18:16:00.000000','17:00:00.000000','Buenos Aires','Colonia del Sacramento','Buquebus'),(68,_binary '','20:01:00.000000','17:00:00.000000','Colonia del Sacramento','Montevideo','21:46:00.000000','20:31:00.000000','Buenos Aires','Colonia del Sacramento','Buquebus');
/*!40000 ALTER TABLE `ferry_schedules` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ferrys`
--

DROP TABLE IF EXISTS `ferrys`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ferrys` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ferrys_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ferrys`
--

LOCK TABLES `ferrys` WRITE;
/*!40000 ALTER TABLE `ferrys` DISABLE KEYS */;
INSERT INTO `ferrys` VALUES (1,'Buenos Aires',1),(2,'Colonia del Sacramento',1),(3,'Montevideo',1);
/*!40000 ALTER TABLE `ferrys` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `financial_movement_conciliation`
--

DROP TABLE IF EXISTS `financial_movement_conciliation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `financial_movement_conciliation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movement_type` varchar(64) NOT NULL,
  `movement_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `note` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `bank_receipt_number` varchar(80) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fmc_type_movement` (`movement_type`,`movement_id`),
  KEY `idx_fmc_type_movement` (`movement_type`,`movement_id`),
  KEY `idx_fmc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `financial_movement_conciliation`
--

LOCK TABLES `financial_movement_conciliation` WRITE;
/*!40000 ALTER TABLE `financial_movement_conciliation` DISABLE KEYS */;
/*!40000 ALTER TABLE `financial_movement_conciliation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_accommodation_room`
--

DROP TABLE IF EXISTS `group_accommodation_room`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_accommodation_room` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `adults` int NOT NULL,
  `minors` int NOT NULL,
  `room_type` varchar(60) DEFAULT NULL,
  `room_number` int NOT NULL,
  `accommodation_service_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_accommodation_room_service_number` (`accommodation_service_id`,`room_number`),
  KEY `idx_group_accommodation_room_service` (`accommodation_service_id`),
  CONSTRAINT `fk_group_accommodation_room_service` FOREIGN KEY (`accommodation_service_id`) REFERENCES `group_accommodation_service` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_accommodation_room`
--

LOCK TABLES `group_accommodation_room` WRITE;
/*!40000 ALTER TABLE `group_accommodation_room` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_accommodation_room` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_accommodation_service`
--

DROP TABLE IF EXISTS `group_accommodation_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_accommodation_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `country` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `city` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `check_in_date` date NOT NULL,
  `check_in_time` time DEFAULT NULL,
  `check_out_date` date NOT NULL,
  `check_out_time` time DEFAULT NULL,
  `regimen` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `contract_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DIRECTA',
  `third_party_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_due_date` date DEFAULT NULL,
  `reservation_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `accommodation_id` bigint DEFAULT NULL,
  `provider_id` bigint DEFAULT NULL,
  `reservation_amount` decimal(12,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_accommodation_service_menu_item` (`menu_item_id`),
  UNIQUE KEY `uq_group_accommodation_service_group` (`group_id`),
  CONSTRAINT `fk_group_accommodation_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_accommodation_service`
--

LOCK TABLES `group_accommodation_service` WRITE;
/*!40000 ALTER TABLE `group_accommodation_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_accommodation_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_air_service`
--

DROP TABLE IF EXISTS `group_air_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_air_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `airline` varchar(100) NOT NULL,
  `departure_date` date NOT NULL,
  `departure_time` time NOT NULL,
  `departure_arrival_time` time DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time DEFAULT NULL,
  `return_arrival_time` time DEFAULT NULL,
  `baggage_allowance` varchar(10) NOT NULL,
  `destination` varchar(255) DEFAULT NULL,
  `origin` varchar(255) DEFAULT NULL,
  `trip_type` varchar(20) NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `notes` text,
  `document_expiration_date` date DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_air_service_menu_item` (`menu_item_id`),
  UNIQUE KEY `uq_group_air_service_group` (`group_id`),
  CONSTRAINT `fk_group_air_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_air_service`
--

LOCK TABLES `group_air_service` WRITE;
/*!40000 ALTER TABLE `group_air_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_air_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_destination_transfer_service`
--

DROP TABLE IF EXISTS `group_destination_transfer_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_destination_transfer_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `country` varchar(100) DEFAULT NULL,
  `city` varchar(150) DEFAULT NULL,
  `pickup_place` varchar(255) DEFAULT NULL,
  `destination_place` varchar(255) DEFAULT NULL,
  `departure_date` date DEFAULT NULL,
  `departure_time` time DEFAULT NULL,
  `departure_arrival_time` time DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time DEFAULT NULL,
  `return_arrival_time` time DEFAULT NULL,
  `notes` varchar(500) DEFAULT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `trip_type` enum('ONE_WAY','ROUND_TRIP') DEFAULT NULL,
  `pickup_point_name` varchar(200) DEFAULT NULL,
  `destination_point_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_destination_transfer_service_menu_item` (`menu_item_id`),
  UNIQUE KEY `uq_group_destination_transfer_group` (`group_id`),
  KEY `idx_group_destination_transfer_group_id` (`group_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_destination_transfer_service`
--

LOCK TABLES `group_destination_transfer_service` WRITE;
/*!40000 ALTER TABLE `group_destination_transfer_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_destination_transfer_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_ferry_service`
--

DROP TABLE IF EXISTS `group_ferry_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_ferry_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `trip_type` varchar(20) NOT NULL,
  `origin_port` varchar(100) NOT NULL,
  `destination_port` varchar(100) NOT NULL,
  `departure_date` date NOT NULL,
  `return_date` date DEFAULT NULL,
  `ferry_company` varchar(100) DEFAULT NULL,
  `departure_time` time DEFAULT NULL,
  `departure_arrival_time` time DEFAULT NULL,
  `return_time` time DEFAULT NULL,
  `return_arrival_time` time DEFAULT NULL,
  `notes` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `bus_arrival_time` time(6) DEFAULT NULL,
  `bus_departure_time` time(6) DEFAULT NULL,
  `bus_destination` varchar(160) DEFAULT NULL,
  `bus_origin` varchar(160) DEFAULT NULL,
  `return_bus_arrival_time` time(6) DEFAULT NULL,
  `return_bus_departure_time` time(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_ferry_service_menu_item` (`menu_item_id`),
  UNIQUE KEY `uq_group_ferry_service_group` (`group_id`),
  CONSTRAINT `fk_group_ferry_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_ferry_service`
--

LOCK TABLES `group_ferry_service` WRITE;
/*!40000 ALTER TABLE `group_ferry_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_ferry_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_operations`
--

DROP TABLE IF EXISTS `group_operations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_operations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `emitted_complete` tinyint(1) NOT NULL DEFAULT '0',
  `emitted_complete_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `services_complete` bit(1) NOT NULL,
  `services_complete_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_operations_group` (`group_id`),
  CONSTRAINT `fk_group_operations_group` FOREIGN KEY (`group_id`) REFERENCES `travel_group` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_operations`
--

LOCK TABLES `group_operations` WRITE;
/*!40000 ALTER TABLE `group_operations` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_operations` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_service_menu_item`
--

DROP TABLE IF EXISTS `group_service_menu_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_service_menu_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `service_id` bigint NOT NULL,
  `display_name` varchar(160) NOT NULL,
  `position` int NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `operation_status` enum('EMITIDO','PAGADO','PENDIENTE','RESERVADO','SENADO','SOLICITADO') DEFAULT NULL,
  `operation_status_updated_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(14,4) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_gsmi_group_pos` (`group_id`,`position`),
  KEY `idx_gsmi_group_service` (`group_id`,`service_id`),
  KEY `fk_gsmi_service` (`service_id`),
  CONSTRAINT `fk_gsmi_group` FOREIGN KEY (`group_id`) REFERENCES `travel_group` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_gsmi_service` FOREIGN KEY (`service_id`) REFERENCES `servicios` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_service_menu_item`
--

LOCK TABLES `group_service_menu_item` WRITE;
/*!40000 ALTER TABLE `group_service_menu_item` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_service_menu_item` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_transfer_service`
--

DROP TABLE IF EXISTS `group_transfer_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_transfer_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `pickup_place` varchar(255) DEFAULT NULL,
  `destination_place` varchar(255) DEFAULT NULL,
  `departure_date` date DEFAULT NULL,
  `departure_time` time DEFAULT NULL,
  `departure_arrival_time` time DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time DEFAULT NULL,
  `return_arrival_time` time DEFAULT NULL,
  `notes` varchar(500) DEFAULT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `trip_type` enum('ONE_WAY','ROUND_TRIP') DEFAULT NULL,
  `pickup_point_name` varchar(200) DEFAULT NULL,
  `destination_point_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_group_transfer_service_menu_item` (`menu_item_id`),
  KEY `fk_group_transfer_service_group` (`group_id`),
  CONSTRAINT `fk_group_transfer_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_transfer_service`
--

LOCK TABLES `group_transfer_service` WRITE;
/*!40000 ALTER TABLE `group_transfer_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_transfer_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `locations_x_points`
--

DROP TABLE IF EXISTS `locations_x_points`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `locations_x_points` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transfer_location_id` bigint NOT NULL,
  `transfer_point_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_locations_x_points` (`transfer_location_id`,`transfer_point_id`),
  KEY `idx_lxp_location_id` (`transfer_location_id`),
  KEY `idx_lxp_point_id` (`transfer_point_id`),
  CONSTRAINT `fk_lxp_transfer_location` FOREIGN KEY (`transfer_location_id`) REFERENCES `transfer_locations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_lxp_transfer_point` FOREIGN KEY (`transfer_point_id`) REFERENCES `transfer_points` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `locations_x_points`
--

LOCK TABLES `locations_x_points` WRITE;
/*!40000 ALTER TABLE `locations_x_points` DISABLE KEYS */;
INSERT INTO `locations_x_points` VALUES (2,2,1),(1,2,2),(6,3,3),(4,3,4),(5,3,5),(7,4,6),(8,4,7),(10,5,8),(11,5,9),(13,6,10),(14,6,11),(16,7,12);
/*!40000 ALTER TABLE `locations_x_points` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `marketing_campaign`
--

DROP TABLE IF EXISTS `marketing_campaign`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `marketing_campaign` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel` enum('BOTH','EMAIL','WHATSAPP') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `email_subject` varchar(500) DEFAULT NULL,
  `message_text` longtext,
  `name` varchar(300) NOT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `status` enum('CANCELED','COMPLETED','DRAFT','FAILED','SCHEDULED','SENDING') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `media_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKf7k57oh07197qi7xsl4gwl06v` (`media_id`),
  CONSTRAINT `FKf7k57oh07197qi7xsl4gwl06v` FOREIGN KEY (`media_id`) REFERENCES `marketing_media` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `marketing_campaign`
--

LOCK TABLES `marketing_campaign` WRITE;
/*!40000 ALTER TABLE `marketing_campaign` DISABLE KEYS */;
/*!40000 ALTER TABLE `marketing_campaign` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `marketing_media`
--

DROP TABLE IF EXISTS `marketing_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `marketing_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_blob` longblob,
  `content_type` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `file_name` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `marketing_media`
--

LOCK TABLES `marketing_media` WRITE;
/*!40000 ALTER TABLE `marketing_media` DISABLE KEYS */;
/*!40000 ALTER TABLE `marketing_media` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `marketing_message`
--

DROP TABLE IF EXISTS `marketing_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `marketing_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attempts` int NOT NULL,
  `channel` enum('BOTH','EMAIL','WHATSAPP') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` longtext,
  `recipient_email` varchar(320) DEFAULT NULL,
  `recipient_name` varchar(300) DEFAULT NULL,
  `recipient_phone` varchar(50) DEFAULT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('DRAFT','FAILED','PENDING','SENT') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `campaign_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_marketing_message_status_sched` (`status`,`scheduled_at`),
  KEY `FKoe3vwypmlanas82y1ol71r2vw` (`campaign_id`),
  CONSTRAINT `FKoe3vwypmlanas82y1ol71r2vw` FOREIGN KEY (`campaign_id`) REFERENCES `marketing_campaign` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `marketing_message`
--

LOCK TABLES `marketing_message` WRITE;
/*!40000 ALTER TABLE `marketing_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `marketing_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_accommodation_service`
--

DROP TABLE IF EXISTS `member_accommodation_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_accommodation_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `check_in_date` date NOT NULL,
  `check_in_time` time(6) DEFAULT NULL,
  `check_out_date` date NOT NULL,
  `check_out_time` time(6) DEFAULT NULL,
  `city` varchar(150) DEFAULT NULL,
  `country` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `name` varchar(200) NOT NULL,
  `overridden` bit(1) NOT NULL,
  `regimen` enum('ALL_INCLUSIVE','BREAKFAST','FULL_BOARD','HALF_BOARD','ROOM_ONLY') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `contract_type` varchar(20) NOT NULL DEFAULT 'DIRECTA',
  `third_party_name` varchar(200) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `accommodation_id` bigint DEFAULT NULL,
  `provider_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_accommodation_service_menu_item_member` (`menu_item_id`,`member_id`),
  KEY `idx_member_accommodation_member` (`member_id`),
  KEY `idx_member_accommodation_menu_item` (`menu_item_id`),
  CONSTRAINT `fk_member_accommodation_service_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_accommodation_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_accommodation_service`
--

LOCK TABLES `member_accommodation_service` WRITE;
/*!40000 ALTER TABLE `member_accommodation_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_accommodation_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_air_service`
--

DROP TABLE IF EXISTS `member_air_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_air_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `airline` varchar(100) NOT NULL,
  `baggage_allowance` varchar(10) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `departure_arrival_time` time(6) DEFAULT NULL,
  `departure_date` date NOT NULL,
  `departure_time` time(6) NOT NULL,
  `destination` varchar(255) DEFAULT NULL,
  `origin` varchar(255) DEFAULT NULL,
  `overridden` bit(1) NOT NULL,
  `return_arrival_time` time(6) DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time(6) DEFAULT NULL,
  `trip_type` varchar(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_air_service_menu_item_member` (`menu_item_id`,`member_id`),
  KEY `idx_member_air_member` (`member_id`),
  KEY `idx_member_air_menu_item` (`menu_item_id`),
  CONSTRAINT `fk_member_air_service_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_air_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_air_service`
--

LOCK TABLES `member_air_service` WRITE;
/*!40000 ALTER TABLE `member_air_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_air_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_destination_transfer_service`
--

DROP TABLE IF EXISTS `member_destination_transfer_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_destination_transfer_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `city` varchar(150) DEFAULT NULL,
  `country` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `departure_arrival_time` time(6) DEFAULT NULL,
  `departure_date` date DEFAULT NULL,
  `departure_time` time(6) DEFAULT NULL,
  `destination_place` varchar(255) DEFAULT NULL,
  `notes` varchar(500) DEFAULT NULL,
  `overridden` bit(1) NOT NULL,
  `pickup_place` varchar(255) DEFAULT NULL,
  `return_arrival_time` time(6) DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `trip_type` enum('ONE_WAY','ROUND_TRIP') DEFAULT NULL,
  `pickup_point_name` varchar(200) DEFAULT NULL,
  `destination_point_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_destination_transfer_service_menu_item_member` (`menu_item_id`,`member_id`),
  KEY `idx_member_dest_transfer_member` (`member_id`),
  KEY `idx_member_dest_transfer_menu_item` (`menu_item_id`),
  CONSTRAINT `fk_member_destination_transfer_service_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_destination_transfer_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_destination_transfer_service`
--

LOCK TABLES `member_destination_transfer_service` WRITE;
/*!40000 ALTER TABLE `member_destination_transfer_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_destination_transfer_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_emision`
--

DROP TABLE IF EXISTS `member_emision`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_emision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` bigint DEFAULT NULL,
  `destination` varchar(255) DEFAULT NULL,
  `travel_month` varchar(100) DEFAULT NULL,
  `full_name` varchar(255) DEFAULT NULL,
  `age` int DEFAULT NULL,
  `companion_type` varchar(50) DEFAULT NULL,
  `gender` varchar(50) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `emitted` bit(1) DEFAULT NULL,
  `emitted_at` datetime(6) DEFAULT NULL,
  `status` varchar(50) DEFAULT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_emision_request_id` (`request_id`),
  KEY `idx_member_emision_created_at` (`created_at`),
  KEY `idx_member_emision_emitted` (`emitted`),
  KEY `idx_member_emision_status` (`status`),
  CONSTRAINT `fk_member_emision_request` FOREIGN KEY (`request_id`) REFERENCES `travel_request` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_emision`
--

LOCK TABLES `member_emision` WRITE;
/*!40000 ALTER TABLE `member_emision` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_emision` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_ferry_service`
--

DROP TABLE IF EXISTS `member_ferry_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_ferry_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `menu_item_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `overridden` tinyint(1) NOT NULL DEFAULT '0',
  `trip_type` varchar(20) NOT NULL,
  `departure_time` time DEFAULT NULL,
  `return_time` time DEFAULT NULL,
  `origin_port` varchar(100) NOT NULL,
  `destination_port` varchar(100) NOT NULL,
  `departure_date` date NOT NULL,
  `return_date` date DEFAULT NULL,
  `ferry_company` varchar(100) DEFAULT NULL,
  `notes` text,
  `departure_arrival_time` time DEFAULT NULL,
  `return_arrival_time` time DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `bus_arrival_time` time(6) DEFAULT NULL,
  `bus_departure_time` time(6) DEFAULT NULL,
  `bus_destination` varchar(160) DEFAULT NULL,
  `bus_origin` varchar(160) DEFAULT NULL,
  `return_bus_arrival_time` time(6) DEFAULT NULL,
  `return_bus_departure_time` time(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_ferry` (`menu_item_id`,`member_id`),
  KEY `fk_member_ferry_member` (`member_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_ferry_service`
--

LOCK TABLES `member_ferry_service` WRITE;
/*!40000 ALTER TABLE `member_ferry_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_ferry_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_excursion_service`
--

DROP TABLE IF EXISTS `member_optional_excursion_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_excursion_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `excursion_date` date DEFAULT NULL,
  `excursion_time` time(6) DEFAULT NULL,
  `excursion_return_time` time DEFAULT NULL,
  `name` varchar(160) NOT NULL,
  `notes` text,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `excursion_id` bigint DEFAULT NULL,
  `menu_item_id` bigint NOT NULL,
  `cost` decimal(12,2) DEFAULT NULL,
  `sale` decimal(12,2) DEFAULT NULL,
  `payment_method` varchar(30) DEFAULT NULL,
  `prestador_id` bigint DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_optional_excursion` (`menu_item_id`,`member_id`),
  KEY `idx_member_optional_excursion_member` (`member_id`),
  KEY `idx_member_optional_excursion_excursion` (`excursion_id`),
  KEY `idx_member_optional_excursion_prestador` (`prestador_id`),
  KEY `idx_moes_excursion_id` (`excursion_id`),
  KEY `idx_moes_prestador_id` (`prestador_id`),
  KEY `idx_moes_excursion` (`excursion_id`),
  KEY `idx_moes_prestador` (`prestador_id`),
  CONSTRAINT `fk_member_optional_excursion_catalog` FOREIGN KEY (`excursion_id`) REFERENCES `excursiones` (`id`),
  CONSTRAINT `fk_member_optional_excursion_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_optional_excursion_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `member_optional_service_menu_item` (`id`),
  CONSTRAINT `fk_member_optional_excursion_prestador` FOREIGN KEY (`prestador_id`) REFERENCES `prestadores` (`id`),
  CONSTRAINT `fk_moes_excursion` FOREIGN KEY (`excursion_id`) REFERENCES `excursiones` (`id`),
  CONSTRAINT `fk_moes_prestador` FOREIGN KEY (`prestador_id`) REFERENCES `prestadores` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_excursion_service`
--

LOCK TABLES `member_optional_excursion_service` WRITE;
/*!40000 ALTER TABLE `member_optional_excursion_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_excursion_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_luggage_service`
--

DROP TABLE IF EXISTS `member_optional_luggage_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_luggage_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `notes` text,
  `cost` decimal(12,2) DEFAULT NULL,
  `luggage_type` enum('CARRY_ON','CHECKED') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `weight_kg` decimal(12,2) DEFAULT NULL,
  `airline` varchar(120) DEFAULT NULL,
  `baggage_rule_id` bigint DEFAULT NULL,
  `dimensions` varchar(128) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `sale` decimal(12,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_optional_luggage` (`menu_item_id`,`member_id`),
  KEY `idx_member_optional_luggage_member` (`member_id`),
  KEY `idx_mols_baggage_rule` (`baggage_rule_id`),
  CONSTRAINT `fk_member_optional_luggage_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_optional_luggage_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `member_optional_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_luggage_service`
--

LOCK TABLES `member_optional_luggage_service` WRITE;
/*!40000 ALTER TABLE `member_optional_luggage_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_luggage_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_service_menu_item`
--

DROP TABLE IF EXISTS `member_optional_service_menu_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_service_menu_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `display_name` varchar(120) NOT NULL,
  `position` int NOT NULL,
  `service_code` enum('ASISTENCIA_VIAJERO','EQUIPAJE','EXCURSIONES') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_optional_menu_member` (`member_id`),
  CONSTRAINT `fk_member_optional_menu_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_service_menu_item`
--

LOCK TABLES `member_optional_service_menu_item` WRITE;
/*!40000 ALTER TABLE `member_optional_service_menu_item` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_service_menu_item` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_service_payment_plan`
--

DROP TABLE IF EXISTS `member_optional_service_payment_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_service_payment_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `menu_item_id` bigint NOT NULL,
  `payment_form` varchar(20) NOT NULL,
  `total_amount` decimal(12,2) NOT NULL,
  `currency` varchar(10) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_mosp_plan_menu_item` (`menu_item_id`),
  KEY `idx_mosp_plan_menu_item` (`menu_item_id`),
  CONSTRAINT `fk_mosp_plan_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `member_optional_service_menu_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_service_payment_plan`
--

LOCK TABLES `member_optional_service_payment_plan` WRITE;
/*!40000 ALTER TABLE `member_optional_service_payment_plan` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_service_payment_plan` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_service_payment_record`
--

DROP TABLE IF EXISTS `member_optional_service_payment_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_service_payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `currency` varchar(10) NOT NULL,
  `payment_date` date NOT NULL,
  `one_time_method` varchar(80) DEFAULT NULL,
  `receipt_last4` varchar(4) DEFAULT NULL,
  `receipt_number` varchar(80) DEFAULT NULL,
  `bank_id` bigint DEFAULT NULL,
  `card_id` bigint DEFAULT NULL,
  `card_number` varchar(32) DEFAULT NULL,
  `receipt_blob` longblob,
  `receipt_content_type` varchar(255) DEFAULT NULL,
  `receipt_file_name` varchar(255) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mosp_record_plan` (`plan_id`),
  CONSTRAINT `fk_mosp_record_plan` FOREIGN KEY (`plan_id`) REFERENCES `member_optional_service_payment_plan` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_service_payment_record`
--

LOCK TABLES `member_optional_service_payment_record` WRITE;
/*!40000 ALTER TABLE `member_optional_service_payment_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_service_payment_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_optional_travel_assistance_service`
--

DROP TABLE IF EXISTS `member_optional_travel_assistance_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_optional_travel_assistance_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `emergency_phone` varchar(80) DEFAULT NULL,
  `notes` text,
  `cost` decimal(12,2) DEFAULT NULL,
  `plan` varchar(160) DEFAULT NULL,
  `policy_number` varchar(160) DEFAULT NULL,
  `provider` varchar(160) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `sale` decimal(12,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_optional_travel_assistance` (`menu_item_id`,`member_id`),
  KEY `idx_member_optional_travel_assistance_member` (`member_id`),
  CONSTRAINT `fk_member_optional_travel_assistance_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_optional_travel_assistance_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `member_optional_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_optional_travel_assistance_service`
--

LOCK TABLES `member_optional_travel_assistance_service` WRITE;
/*!40000 ALTER TABLE `member_optional_travel_assistance_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_optional_travel_assistance_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_payment_installment`
--

DROP TABLE IF EXISTS `member_payment_installment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_payment_installment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL,
  `installment_number` int NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `due_date` date NOT NULL,
  `paid_date` date DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PLANNED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `collection_notified_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_mpi_plan_num` (`plan_id`,`installment_number`),
  KEY `idx_mpi_plan` (`plan_id`),
  CONSTRAINT `fk_mpi_plan` FOREIGN KEY (`plan_id`) REFERENCES `member_payment_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_mpi_num` CHECK ((`installment_number` between 1 and 6)),
  CONSTRAINT `chk_mpi_status` CHECK ((`status` in (_utf8mb4'PLANNED',_utf8mb4'PAID',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_payment_installment`
--

LOCK TABLES `member_payment_installment` WRITE;
/*!40000 ALTER TABLE `member_payment_installment` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_payment_installment` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_payment_plan`
--

DROP TABLE IF EXISTS `member_payment_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_payment_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `plan_type` varchar(32) NOT NULL,
  `one_time_method` varchar(32) DEFAULT NULL,
  `total_amount` decimal(12,2) NOT NULL,
  `currency` varchar(8) NOT NULL DEFAULT 'ARS',
  `receipt_last4` varchar(4) DEFAULT NULL,
  `notes` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_mpp_group_member` (`group_id`,`member_id`),
  KEY `idx_mpp_group` (`group_id`),
  KEY `idx_mpp_member` (`member_id`),
  CONSTRAINT `fk_mpp_group` FOREIGN KEY (`group_id`) REFERENCES `travel_group` (`id`),
  CONSTRAINT `fk_mpp_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `chk_mpp_one_time_method` CHECK (((`one_time_method` is null) or (`one_time_method` in (_utf8mb4'TRANSFERENCIA',_utf8mb4'DEPOSITO',_utf8mb4'TARJETA_DEBITO',_utf8mb4'TARJETA_CREDITO')))),
  CONSTRAINT `chk_mpp_plan_type` CHECK ((`plan_type` in (_utf8mb4'ONE_TIME',_utf8mb4'OWN_FINANCING')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_payment_plan`
--

LOCK TABLES `member_payment_plan` WRITE;
/*!40000 ALTER TABLE `member_payment_plan` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_payment_plan` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_payment_record`
--

DROP TABLE IF EXISTS `member_payment_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(12,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `currency` varchar(8) NOT NULL,
  `group_id` bigint DEFAULT NULL,
  `installment_number` int DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `payment_date` date NOT NULL,
  `receipt_last4` varchar(4) NOT NULL,
  `plan_id` bigint NOT NULL,
  `receipt_blob` longblob,
  `receipt_content_type` varchar(255) DEFAULT NULL,
  `receipt_file_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mpr_group_member` (`group_id`,`member_id`),
  KEY `idx_mpr_plan` (`plan_id`),
  KEY `idx_mpr_group` (`group_id`),
  CONSTRAINT `FK4t5nud5nl9ljnvgfem44cht5p` FOREIGN KEY (`plan_id`) REFERENCES `member_payment_plan` (`id`),
  CONSTRAINT `fk_mpr_group` FOREIGN KEY (`group_id`) REFERENCES `travel_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_payment_record`
--

LOCK TABLES `member_payment_record` WRITE;
/*!40000 ALTER TABLE `member_payment_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_payment_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `member_transfer_service`
--

DROP TABLE IF EXISTS `member_transfer_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_transfer_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `departure_arrival_time` time(6) DEFAULT NULL,
  `departure_date` date DEFAULT NULL,
  `departure_time` time(6) DEFAULT NULL,
  `destination_place` varchar(255) DEFAULT NULL,
  `notes` varchar(500) DEFAULT NULL,
  `overridden` bit(1) NOT NULL,
  `pickup_place` varchar(255) DEFAULT NULL,
  `return_arrival_time` time(6) DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `provider` varchar(160) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `trip_type` enum('ONE_WAY','ROUND_TRIP') DEFAULT NULL,
  `pickup_point_name` varchar(200) DEFAULT NULL,
  `destination_point_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_transfer_service_menu_item_member` (`menu_item_id`,`member_id`),
  KEY `idx_member_transfer_member` (`member_id`),
  KEY `idx_member_transfer_menu_item` (`menu_item_id`),
  CONSTRAINT `fk_member_transfer_service_member` FOREIGN KEY (`member_id`) REFERENCES `travel_request` (`id`),
  CONSTRAINT `fk_member_transfer_service_menu_item` FOREIGN KEY (`menu_item_id`) REFERENCES `group_service_menu_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `member_transfer_service`
--

LOCK TABLES `member_transfer_service` WRITE;
/*!40000 ALTER TABLE `member_transfer_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `member_transfer_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `paises`
--

DROP TABLE IF EXISTS `paises`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `paises` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_paises_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `paises`
--

LOCK TABLES `paises` WRITE;
/*!40000 ALTER TABLE `paises` DISABLE KEYS */;
INSERT INTO `paises` VALUES (1,'Argentina',1);
/*!40000 ALTER TABLE `paises` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `paises_x_ciudades`
--

DROP TABLE IF EXISTS `paises_x_ciudades`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `paises_x_ciudades` (
  `id_pais` bigint NOT NULL,
  `id_ciudad` bigint NOT NULL,
  PRIMARY KEY (`id_pais`,`id_ciudad`),
  KEY `idx_pxc_ciudad` (`id_ciudad`),
  CONSTRAINT `fk_pxc_ciudad` FOREIGN KEY (`id_ciudad`) REFERENCES `ciudades` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_pxc_pais` FOREIGN KEY (`id_pais`) REFERENCES `paises` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `paises_x_ciudades`
--

LOCK TABLES `paises_x_ciudades` WRITE;
/*!40000 ALTER TABLE `paises_x_ciudades` DISABLE KEYS */;
INSERT INTO `paises_x_ciudades` VALUES (1,1),(1,2),(1,3),(1,4),(1,5),(1,6),(1,7),(1,8),(1,9),(1,10),(1,11),(1,12),(1,13),(1,14),(1,15),(1,16),(1,17),(1,18),(1,19),(1,20),(1,21),(1,22);
/*!40000 ALTER TABLE `paises_x_ciudades` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `parametros`
--

DROP TABLE IF EXISTS `parametros`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `parametros` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(100) NOT NULL,
  `value` varchar(255) NOT NULL,
  `descripcion` varchar(255) DEFAULT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parametros_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `parametros`
--

LOCK TABLES `parametros` WRITE;
/*!40000 ALTER TABLE `parametros` DISABLE KEYS */;
INSERT INTO `parametros` VALUES (1,'VALIDATION_CODE_ADMIN','1234','Código validación administrador',1,'2026-01-20 05:10:35','2026-01-20 05:10:35'),(2,'VALIDATION_CODE_SUPERVISOR','0000','Código validación supervisor (placeholder)',0,'2026-01-20 05:10:35','2026-01-20 05:10:35'),(4,'COTIZACION_DOLAR','1450',NULL,1,'2026-03-06 14:40:54','2026-03-06 14:40:54');
/*!40000 ALTER TABLE `parametros` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `planes_asistencia`
--

DROP TABLE IF EXISTS `planes_asistencia`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `planes_asistencia` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nombre` varchar(255) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_planes_asistencia_nombre` (`nombre`),
  KEY `idx_planes_asistencia_activo` (`activo`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `planes_asistencia`
--

LOCK TABLES `planes_asistencia` WRITE;
/*!40000 ALTER TABLE `planes_asistencia` DISABLE KEYS */;
INSERT INTO `planes_asistencia` VALUES (1,'Básico',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(2,'Plus',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(3,'Premium',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(4,'60k',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(5,'120k',1,'2026-01-19 20:36:23','2026-01-19 20:36:23');
/*!40000 ALTER TABLE `planes_asistencia` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `prestadores`
--

DROP TABLE IF EXISTS `prestadores`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prestadores` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nombre` varchar(255) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_prestadores_nombre` (`nombre`),
  KEY `idx_prestadores_activo` (`activo`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `prestadores`
--

LOCK TABLES `prestadores` WRITE;
/*!40000 ALTER TABLE `prestadores` DISABLE KEYS */;
INSERT INTO `prestadores` VALUES (1,'Prestador 1',0,'2026-01-15 04:13:53','2026-02-17 19:50:53'),(3,'Turisur',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(4,'Cau Cau (Espacio S.A.)',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(5,'Bariloche Excursiones',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(6,'Bariloche Travel',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(7,'Kahuak Turismo',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(8,'Trout & Wine',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(9,'Mr. Hugo Bikes',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(10,'Mendoza Holidays',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(11,'Andesmar Turismo',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(12,'Turismo Aymara',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(13,'Tolkeyen Patagonia Turismo',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(14,'Canal Ushuaia (CanalFun)',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(15,'Piratour Travel',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(16,'Yaghan Turismo',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(17,'Hielo & Aventura',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(18,'Solo Patagonia',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(19,'Patagonia Chic',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(20,'Calafate Mountain Park',1,'2026-02-17 19:50:53','2026-02-17 19:50:53'),(21,'MilOutdoor',1,'2026-02-17 19:50:53','2026-02-17 19:50:53');
/*!40000 ALTER TABLE `prestadores` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `prestadores_proveedores`
--

DROP TABLE IF EXISTS `prestadores_proveedores`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prestadores_proveedores` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `id_proveedor` bigint NOT NULL,
  `id_prestador` bigint NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_prestadores_proveedores` (`id_proveedor`,`id_prestador`),
  KEY `idx_pp_proveedor` (`id_proveedor`),
  KEY `idx_pp_prestador` (`id_prestador`),
  CONSTRAINT `fk_prestadores_proveedores_prestador` FOREIGN KEY (`id_prestador`) REFERENCES `alojamientos` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_prestadores_proveedores_proveedor` FOREIGN KEY (`id_proveedor`) REFERENCES `proveedores_alojamientos` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=277 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `prestadores_proveedores`
--

LOCK TABLES `prestadores_proveedores` WRITE;
/*!40000 ALTER TABLE `prestadores_proveedores` DISABLE KEYS */;
INSERT INTO `prestadores_proveedores` VALUES (22,1,15,1,'2026-04-06 15:16:44'),(23,2,15,1,'2026-04-06 15:16:44'),(24,1,16,1,'2026-04-06 15:16:44'),(25,2,16,1,'2026-04-06 15:16:44'),(26,1,17,1,'2026-04-06 15:16:44'),(27,2,17,1,'2026-04-06 15:16:44'),(28,1,18,1,'2026-04-06 15:16:44'),(29,2,18,1,'2026-04-06 15:16:44'),(30,1,19,1,'2026-04-06 15:16:44'),(31,2,19,1,'2026-04-06 15:16:44'),(32,1,20,1,'2026-04-06 15:16:44'),(33,2,20,1,'2026-04-06 15:16:44'),(34,1,21,1,'2026-04-06 15:16:44'),(35,2,21,1,'2026-04-06 15:16:44'),(36,1,22,1,'2026-04-06 15:16:44'),(37,2,22,1,'2026-04-06 15:16:44'),(38,1,23,1,'2026-04-06 15:16:44'),(39,2,23,1,'2026-04-06 15:16:44'),(40,1,24,1,'2026-04-06 15:16:44'),(41,2,24,1,'2026-04-06 15:16:44'),(42,1,25,1,'2026-04-06 15:16:44'),(43,2,25,1,'2026-04-06 15:16:44'),(44,1,26,1,'2026-04-06 15:16:44'),(45,2,26,1,'2026-04-06 15:16:44'),(46,1,27,1,'2026-04-06 15:16:44'),(47,2,27,1,'2026-04-06 15:16:44'),(48,1,28,1,'2026-04-06 15:16:44'),(49,2,28,1,'2026-04-06 15:16:44'),(50,1,29,1,'2026-04-06 15:16:44'),(51,2,29,1,'2026-04-06 15:16:44'),(52,1,30,1,'2026-04-06 15:16:44'),(53,2,30,1,'2026-04-06 15:16:44'),(54,1,31,1,'2026-04-06 15:16:44'),(55,2,31,1,'2026-04-06 15:16:44'),(56,1,32,1,'2026-04-06 15:16:44'),(57,2,32,1,'2026-04-06 15:16:44'),(58,1,33,1,'2026-04-06 15:16:44'),(59,2,33,1,'2026-04-06 15:16:44'),(60,1,34,1,'2026-04-06 15:16:44'),(61,2,34,1,'2026-04-06 15:16:44'),(62,1,35,1,'2026-04-06 15:16:44'),(63,2,35,1,'2026-04-06 15:16:44'),(64,1,36,1,'2026-04-06 15:16:44'),(65,2,36,1,'2026-04-06 15:16:44'),(66,1,37,1,'2026-04-06 15:16:44'),(67,2,37,1,'2026-04-06 15:16:44'),(68,1,38,1,'2026-04-06 15:16:44'),(69,2,38,1,'2026-04-06 15:16:44'),(70,1,39,1,'2026-04-06 15:16:44'),(71,2,39,1,'2026-04-06 15:16:44'),(72,1,40,1,'2026-04-06 15:16:44'),(73,2,40,1,'2026-04-06 15:16:44'),(74,1,41,1,'2026-04-06 15:16:44'),(75,2,41,1,'2026-04-06 15:16:44'),(76,1,42,1,'2026-04-06 15:16:44'),(77,2,42,1,'2026-04-06 15:16:44'),(78,1,43,1,'2026-04-06 15:16:44'),(79,2,43,1,'2026-04-06 15:16:44'),(80,1,44,1,'2026-04-06 15:16:44'),(81,2,44,1,'2026-04-06 15:16:44'),(82,1,45,1,'2026-04-06 15:16:44'),(83,2,45,1,'2026-04-06 15:16:44'),(84,1,46,1,'2026-04-06 15:16:44'),(85,2,46,1,'2026-04-06 15:16:44'),(86,1,47,1,'2026-04-06 15:16:44'),(87,2,47,1,'2026-04-06 15:16:44'),(88,1,48,1,'2026-04-06 15:16:44'),(89,2,48,1,'2026-04-06 15:16:44'),(90,1,49,1,'2026-04-06 15:16:44'),(91,2,49,1,'2026-04-06 15:16:44'),(92,1,50,1,'2026-04-06 15:16:44'),(93,2,50,1,'2026-04-06 15:16:44'),(94,1,51,1,'2026-04-06 15:16:44'),(95,2,51,1,'2026-04-06 15:16:44'),(96,1,52,1,'2026-04-06 15:16:44'),(97,2,52,1,'2026-04-06 15:16:44'),(98,1,53,1,'2026-04-06 15:16:44'),(99,2,53,1,'2026-04-06 15:16:44'),(100,1,54,1,'2026-04-06 15:16:44'),(101,2,54,1,'2026-04-06 15:16:44'),(102,1,55,1,'2026-04-06 15:16:44'),(103,2,55,1,'2026-04-06 15:16:44'),(104,1,56,1,'2026-04-06 15:16:44'),(105,2,56,1,'2026-04-06 15:16:44'),(106,1,57,1,'2026-04-06 15:16:44'),(107,2,57,1,'2026-04-06 15:16:44'),(108,1,58,1,'2026-04-06 15:16:44'),(109,2,58,1,'2026-04-06 15:16:44'),(110,1,59,1,'2026-04-06 15:16:44'),(111,2,59,1,'2026-04-06 15:16:44'),(112,1,60,1,'2026-04-06 15:16:44'),(113,2,60,1,'2026-04-06 15:16:44'),(114,1,61,1,'2026-04-06 15:16:44'),(115,2,61,1,'2026-04-06 15:16:44'),(116,1,62,1,'2026-04-06 15:16:44'),(117,2,62,1,'2026-04-06 15:16:44'),(118,1,63,1,'2026-04-06 15:16:44'),(119,2,63,1,'2026-04-06 15:16:44'),(120,1,64,1,'2026-04-06 15:16:44'),(121,2,64,1,'2026-04-06 15:16:44'),(122,1,65,1,'2026-04-06 15:16:44'),(123,2,65,1,'2026-04-06 15:16:44'),(124,1,66,1,'2026-04-06 15:16:44'),(125,2,66,1,'2026-04-06 15:16:44'),(126,1,67,1,'2026-04-06 15:16:44'),(127,2,67,1,'2026-04-06 15:16:44'),(128,1,68,1,'2026-04-06 15:16:44'),(129,2,68,1,'2026-04-06 15:16:44'),(130,1,69,1,'2026-04-06 15:16:44'),(131,2,69,1,'2026-04-06 15:16:44'),(132,1,70,1,'2026-04-06 15:16:44'),(133,2,70,1,'2026-04-06 15:16:44'),(134,1,71,1,'2026-04-06 15:16:44'),(135,2,71,1,'2026-04-06 15:16:44'),(136,1,72,1,'2026-04-06 15:16:44'),(137,2,72,1,'2026-04-06 15:16:44'),(138,1,73,1,'2026-04-06 15:16:44'),(139,2,73,1,'2026-04-06 15:16:44'),(140,1,74,1,'2026-04-06 15:16:44'),(141,2,74,1,'2026-04-06 15:16:44'),(142,1,75,1,'2026-04-06 15:16:44'),(143,2,75,1,'2026-04-06 15:16:44'),(144,1,76,1,'2026-04-06 15:16:44'),(145,2,76,1,'2026-04-06 15:16:44'),(146,1,77,1,'2026-04-06 15:16:44'),(147,2,77,1,'2026-04-06 15:16:44'),(148,1,78,1,'2026-04-06 15:16:44'),(149,2,78,1,'2026-04-06 15:16:44'),(150,1,79,1,'2026-04-06 15:16:44'),(151,2,79,1,'2026-04-06 15:16:44'),(152,1,80,1,'2026-04-06 15:16:44'),(153,2,80,1,'2026-04-06 15:16:44'),(154,1,81,1,'2026-04-06 15:16:44'),(155,2,81,1,'2026-04-06 15:16:44');
/*!40000 ALTER TABLE `prestadores_proveedores` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `prestadores_x_excursiones`
--

DROP TABLE IF EXISTS `prestadores_x_excursiones`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prestadores_x_excursiones` (
  `excursion_id` bigint NOT NULL,
  `prestador_id` bigint NOT NULL,
  PRIMARY KEY (`excursion_id`,`prestador_id`),
  KEY `fk_pxe_prestador` (`prestador_id`),
  CONSTRAINT `fk_pxe_excursion` FOREIGN KEY (`excursion_id`) REFERENCES `excursiones` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_pxe_prestador` FOREIGN KEY (`prestador_id`) REFERENCES `prestadores` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `prestadores_x_excursiones`
--

LOCK TABLES `prestadores_x_excursiones` WRITE;
/*!40000 ALTER TABLE `prestadores_x_excursiones` DISABLE KEYS */;
INSERT INTO `prestadores_x_excursiones` VALUES (1,3),(2,3),(3,3),(3,4),(1,5),(2,5),(3,5),(1,6),(2,6),(3,6),(4,7),(5,7),(6,7),(4,8),(4,9),(4,10),(5,11),(6,11),(5,12),(7,13),(8,13),(7,14),(7,15),(8,15),(9,15),(7,16),(8,16),(9,16),(10,17),(11,18),(10,19),(11,19),(10,20),(10,21),(11,21);
/*!40000 ALTER TABLE `prestadores_x_excursiones` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `proveedores_alojamientos`
--

DROP TABLE IF EXISTS `proveedores_alojamientos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proveedores_alojamientos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nombre` varchar(150) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_proveedores_alojamientos_nombre` (`nombre`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `proveedores_alojamientos`
--

LOCK TABLES `proveedores_alojamientos` WRITE;
/*!40000 ALTER TABLE `proveedores_alojamientos` DISABLE KEYS */;
INSERT INTO `proveedores_alojamientos` VALUES (1,'Sin proveedor',1,'2026-02-04 21:16:11',NULL),(2,'Liberty',1,'2026-02-04 21:16:11',NULL),(3,'HotelDO',0,'2026-02-04 21:16:11','2026-04-06 12:38:26'),(4,'Patagonia Brokers',0,'2026-02-04 21:16:11','2026-04-06 12:38:32');
/*!40000 ALTER TABLE `proveedores_alojamientos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `proveedores_asistencia`
--

DROP TABLE IF EXISTS `proveedores_asistencia`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proveedores_asistencia` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nombre` varchar(255) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_proveedores_asistencia_nombre` (`nombre`),
  KEY `idx_proveedores_asistencia_activo` (`activo`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `proveedores_asistencia`
--

LOCK TABLES `proveedores_asistencia` WRITE;
/*!40000 ALTER TABLE `proveedores_asistencia` DISABLE KEYS */;
INSERT INTO `proveedores_asistencia` VALUES (1,'Assist Card',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(2,'Universal Assistance',1,'2026-01-19 20:36:23','2026-01-19 20:36:23'),(3,'Coris',1,'2026-01-19 20:36:23','2026-01-19 20:36:23');
/*!40000 ALTER TABLE `proveedores_asistencia` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `proveedores_planes_asistencia`
--

DROP TABLE IF EXISTS `proveedores_planes_asistencia`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proveedores_planes_asistencia` (
  `id_proveedor` bigint NOT NULL,
  `id_plan` bigint NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id_proveedor`,`id_plan`),
  KEY `idx_prov_plan_asist_plan` (`id_plan`),
  CONSTRAINT `fk_prov_plan_asist_plan` FOREIGN KEY (`id_plan`) REFERENCES `planes_asistencia` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_prov_plan_asist_prov` FOREIGN KEY (`id_proveedor`) REFERENCES `proveedores_asistencia` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `proveedores_planes_asistencia`
--

LOCK TABLES `proveedores_planes_asistencia` WRITE;
/*!40000 ALTER TABLE `proveedores_planes_asistencia` DISABLE KEYS */;
INSERT INTO `proveedores_planes_asistencia` VALUES (1,4,'2026-01-19 20:36:23'),(1,5,'2026-01-19 20:36:23'),(2,1,'2026-01-19 20:36:23'),(2,2,'2026-01-19 20:36:23'),(2,3,'2026-01-19 20:36:23'),(3,1,'2026-01-19 20:36:23'),(3,2,'2026-01-19 20:36:23');
/*!40000 ALTER TABLE `proveedores_planes_asistencia` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `regimen`
--

DROP TABLE IF EXISTS `regimen`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `regimen` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(191) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_regimen_descripcion` (`descripcion`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `regimen`
--

LOCK TABLES `regimen` WRITE;
/*!40000 ALTER TABLE `regimen` DISABLE KEYS */;
INSERT INTO `regimen` VALUES (1,'Solo alojamiento',1),(2,'Desayuno',1),(3,'Media pension',1),(4,'Pension completa',1),(5,'All inclusive',1);
/*!40000 ALTER TABLE `regimen` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `report_definition`
--

DROP TABLE IF EXISTS `report_definition`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_definition` (
  `id` varchar(64) NOT NULL,
  `panel` varchar(32) NOT NULL,
  `name` varchar(160) NOT NULL,
  `category` varchar(80) DEFAULT NULL,
  `data_source_id` varchar(64) NOT NULL,
  `config_json` longtext,
  `is_template` tinyint(1) NOT NULL DEFAULT '0',
  `is_shared` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_report_def_panel` (`panel`),
  KEY `idx_report_def_category` (`category`),
  KEY `idx_report_def_ds` (`data_source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `report_definition`
--

LOCK TABLES `report_definition` WRITE;
/*!40000 ALTER TABLE `report_definition` DISABLE KEYS */;
INSERT INTO `report_definition` VALUES ('tpl_admin_groups_by_destination','ADMIN','Grupos por destino','Grupos','groups','{\"mode\":\"aggregate\",\"dimensions\":[\"destination\"],\"metrics\":[{\"id\":\"count_groups\",\"agg\":\"COUNT\",\"field\":\"group_id\",\"label\":\"Cantidad\"}],\"chartType\":\"bar\"}',1,1,'2026-01-17 19:16:59.492282','2026-01-17 19:16:59.492282'),('tpl_admin_groups_by_status','ADMIN','Grupos por estado','Grupos','groups','{\"mode\":\"aggregate\",\"dimensions\":[\"group_status\"],\"metrics\":[{\"id\":\"count_groups\",\"agg\":\"COUNT\",\"field\":\"group_id\",\"label\":\"Cantidad\"}],\"chartType\":\"bar\"}',1,1,'2026-01-17 19:16:59.492282','2026-01-17 19:16:59.492282'),('tpl_admin_members_by_destination_gender','ADMIN','Miembros por destino y genero','Miembros','group_members','{\"mode\":\"aggregate\",\"dimensions\":[\"destination\",\"gender\"],\"metrics\":[{\"id\":\"count_members\",\"agg\":\"COUNT\",\"field\":\"member_id\",\"label\":\"Cantidad\"}],\"chartType\":\"bar\"}',1,1,'2026-01-17 19:16:59.492282','2026-01-17 19:16:59.492282'),('tpl_admin_payments_by_group','ADMIN','Pagos por grupo','Pagos','payment_records','{\"mode\":\"aggregate\",\"dimensions\":[\"group_id\",\"destination\"],\"metrics\":[{\"id\":\"sum_amount\",\"agg\":\"SUM\",\"field\":\"amount\",\"label\":\"Total\"},{\"id\":\"count_payments\",\"agg\":\"COUNT\",\"field\":\"payment_record_id\",\"label\":\"Pagos\"}],\"chartType\":\"table\"}',1,1,'2026-01-17 19:16:59.492282','2026-01-17 19:16:59.492282');
/*!40000 ALTER TABLE `report_definition` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_operation_status_def`
--

DROP TABLE IF EXISTS `service_operation_status_def`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_operation_status_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `service_code` varchar(50) NOT NULL,
  `status_code` varchar(50) NOT NULL,
  `label` varchar(120) NOT NULL,
  `color` varchar(20) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sosd_service_status` (`service_code`,`status_code`),
  KEY `idx_sosd_service` (`service_code`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_operation_status_def`
--

LOCK TABLES `service_operation_status_def` WRITE;
/*!40000 ALTER TABLE `service_operation_status_def` DISABLE KEYS */;
INSERT INTO `service_operation_status_def` VALUES (1,'ALOJAMIENTOS','PENDIENTE','Pendiente','RED',0,1,'2026-01-13 15:53:46.132698'),(2,'ALOJAMIENTOS','RESERVADO','Reservado','SKY',1,1,'2026-01-13 15:53:46.132698'),(3,'ALOJAMIENTOS','SENADO','Señado','YELLOW',2,1,'2026-01-13 15:53:46.132698'),(4,'ALOJAMIENTOS','PAGADO','Pagado','GREEN',3,1,'2026-01-13 15:53:46.132698'),(5,'AEREOS','PENDIENTE','Pendiente','RED',0,1,'2026-01-13 15:53:46.132698'),(6,'AEREOS','EMITIDO','Emitido','GREEN',1,1,'2026-01-13 15:53:46.132698'),(7,'TRASLADOS','PENDIENTE','Pendiente','RED',0,1,'2026-01-13 15:53:46.132698'),(8,'TRASLADOS','SENADO','Señado','YELLOW',1,1,'2026-01-13 15:53:46.132698'),(9,'TRASLADOS','PAGADO','Pagado','GREEN',2,1,'2026-01-13 15:53:46.132698'),(10,'TRASLADOS_DESTINO','PENDIENTE','Pendiente','RED',0,1,'2026-01-13 15:53:46.132698'),(11,'TRASLADOS_DESTINO','SENADO','Señado','YELLOW',1,1,'2026-01-13 15:53:46.132698'),(12,'TRASLADOS_DESTINO','PAGADO','Pagado','GREEN',2,1,'2026-01-13 15:53:46.132698'),(13,'FERRY','PENDIENTE','Pendiente','RED',0,1,'2026-01-13 15:53:46.132698'),(14,'FERRY','EMITIDO','Emitido','GREEN',1,1,'2026-01-13 15:53:46.132698'),(15,'ALOJAMIENTOS','SOLICITADO','Solicitado','VIOLET',1,1,'2026-03-15 17:46:41.629601'),(16,'TRASLADOS','SOLICITADO','Solicitado','VIOLET',1,1,'2026-03-19 15:10:03.242673'),(17,'TRASLADOS_DESTINO','SOLICITADO','Solicitado','VIOLET',1,1,'2026-03-19 15:10:03.329392');
/*!40000 ALTER TABLE `service_operation_status_def` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_payment_plan`
--

DROP TABLE IF EXISTS `service_payment_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_payment_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `menu_item_id` bigint NOT NULL,
  `payment_form` varchar(20) NOT NULL,
  `total_amount` decimal(12,2) NOT NULL,
  `currency` varchar(10) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_service_payment_plan_menu_item` (`menu_item_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_payment_plan`
--

LOCK TABLES `service_payment_plan` WRITE;
/*!40000 ALTER TABLE `service_payment_plan` DISABLE KEYS */;
/*!40000 ALTER TABLE `service_payment_plan` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_payment_record`
--

DROP TABLE IF EXISTS `service_payment_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `currency` varchar(10) NOT NULL,
  `payment_date` date NOT NULL,
  `one_time_method` varchar(80) DEFAULT NULL,
  `receipt_last4` varchar(4) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `receipt_number` varchar(80) DEFAULT NULL,
  `receipt_blob` longblob,
  `receipt_content_type` varchar(255) DEFAULT NULL,
  `receipt_file_name` varchar(255) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `flight_number` varchar(80) DEFAULT NULL,
  `bank_id` bigint DEFAULT NULL,
  `card_id` bigint DEFAULT NULL,
  `card_number` varchar(32) DEFAULT NULL,
  `expense_id` bigint DEFAULT NULL,
  `member_id` bigint DEFAULT NULL,
  `passenger_full_name` varchar(255) DEFAULT NULL,
  `total_payment_cancellation_date` date DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_service_payment_record_plan` (`plan_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_payment_record`
--

LOCK TABLES `service_payment_record` WRITE;
/*!40000 ALTER TABLE `service_payment_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `service_payment_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `servicios`
--

DROP TABLE IF EXISTS `servicios`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `servicios` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL,
  `name` varchar(120) NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_servicios_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `servicios`
--

LOCK TABLES `servicios` WRITE;
/*!40000 ALTER TABLE `servicios` DISABLE KEYS */;
INSERT INTO `servicios` VALUES (1,'FERRY','Ferry',1,'2025-12-28 15:07:14.162274'),(2,'TRASLADOS','Traslados BA',1,'2025-12-28 15:07:14.162274'),(3,'TRASLADOS_DESTINO','Traslados Destino',1,'2025-12-28 15:07:14.162274'),(4,'ALOJAMIENTOS','Alojamientos',1,'2025-12-28 15:07:14.162274'),(5,'AEREOS','Aéreos',1,'2025-12-28 15:07:14.162274');
/*!40000 ALTER TABLE `servicios` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transfer_ba_providers`
--

DROP TABLE IF EXISTS `transfer_ba_providers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_ba_providers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `nombre` varchar(255) NOT NULL,
  `telefono` varchar(60) DEFAULT NULL,
  `web` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id_ciudad` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_transfer_ba_providers_nombre` (`nombre`),
  KEY `idx_transfer_ba_providers_activo` (`activo`),
  KEY `fk_transfer_ba_providers_ciudad` (`id_ciudad`),
  CONSTRAINT `fk_transfer_ba_providers_ciudad` FOREIGN KEY (`id_ciudad`) REFERENCES `ciudades` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transfer_ba_providers`
--

LOCK TABLES `transfer_ba_providers` WRITE;
/*!40000 ALTER TABLE `transfer_ba_providers` DISABLE KEYS */;
INSERT INTO `transfer_ba_providers` VALUES (22,_binary '','2026-04-06 13:01:36.000000','Jorge Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',5);
/*!40000 ALTER TABLE `transfer_ba_providers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transfer_destino_providers`
--

DROP TABLE IF EXISTS `transfer_destino_providers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_destino_providers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `nombre` varchar(255) NOT NULL,
  `telefono` varchar(60) DEFAULT NULL,
  `web` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id_ciudad` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_transfer_destino_providers_nombre` (`nombre`),
  KEY `idx_transfer_destino_providers_activo` (`activo`),
  KEY `fk_transfer_destino_providers_ciudad` (`id_ciudad`),
  CONSTRAINT `fk_transfer_destino_providers_ciudad` FOREIGN KEY (`id_ciudad`) REFERENCES `ciudades` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transfer_destino_providers`
--

LOCK TABLES `transfer_destino_providers` WRITE;
/*!40000 ALTER TABLE `transfer_destino_providers` DISABLE KEYS */;
INSERT INTO `transfer_destino_providers` VALUES (16,_binary '','2026-04-06 13:01:36.000000','Gaston Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',11),(17,_binary '','2026-04-06 13:01:36.000000','Pablo Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',10),(18,_binary '','2026-04-06 13:01:36.000000','Bustillo Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',8),(19,_binary '','2026-04-06 13:01:36.000000','Sars Turismo',NULL,NULL,'2026-04-06 13:01:36.000000',8),(20,_binary '','2026-04-06 13:01:36.000000','Sol Iguazu Turismo',NULL,NULL,'2026-04-06 13:01:36.000000',15),(21,_binary '','2026-04-06 13:01:36.000000','In Buzios',NULL,NULL,'2026-04-06 13:01:36.000000',NULL),(22,_binary '','2026-04-06 13:01:36.000000','Jorge Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',14),(23,_binary '','2026-04-06 13:01:36.000000','Gabriel Traslados',NULL,NULL,'2026-04-06 13:01:36.000000',22),(25,_binary '','2026-04-06 13:01:36.000000','Avc Turismo',NULL,NULL,'2026-04-06 13:01:36.000000',8),(26,_binary '','2026-04-06 13:01:36.000000','Yaghan Turismo',NULL,NULL,'2026-04-06 13:01:36.000000',10),(27,_binary '','2026-04-06 13:01:36.000000','Aventura Andina',NULL,NULL,'2026-04-06 13:01:36.000000',11);
/*!40000 ALTER TABLE `transfer_destino_providers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transfer_locations`
--

DROP TABLE IF EXISTS `transfer_locations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_locations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_locations_name` (`name`),
  KEY `idx_transfer_locations_active_name` (`active`,`name`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transfer_locations`
--

LOCK TABLES `transfer_locations` WRITE;
/*!40000 ALTER TABLE `transfer_locations` DISABLE KEYS */;
INSERT INTO `transfer_locations` VALUES (1,'Puerto Bles',0,'2026-03-06 05:42:13','2026-04-06 12:47:20'),(2,'Aeropuerto',1,'2026-03-13 02:17:59','2026-03-13 02:17:59'),(3,'Terminal',1,'2026-03-13 02:17:59','2026-03-13 02:17:59'),(4,'Hotel',1,'2026-03-13 02:17:59','2026-03-13 02:17:59'),(5,'Puerto',1,'2026-03-13 02:17:59','2026-03-13 02:17:59'),(6,'Centro',0,'2026-03-13 02:17:59','2026-04-06 13:02:07'),(7,'Domicilio',1,'2026-03-13 02:17:59','2026-03-13 02:17:59');
/*!40000 ALTER TABLE `transfer_locations` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transfer_points`
--

DROP TABLE IF EXISTS `transfer_points`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_points` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_transfer_points_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transfer_points`
--

LOCK TABLES `transfer_points` WRITE;
/*!40000 ALTER TABLE `transfer_points` DISABLE KEYS */;
INSERT INTO `transfer_points` VALUES (1,'Ezeiza - Aeropuerto Internacional',1),(2,'Aeroparque - Aero. Jorge Newbery',1),(3,'Terminal Retiro',1),(4,'Terminal Liniers',0),(5,'Terminal Mar del Plata',0),(6,'Hotel ',1),(7,'Hotel en Mar del Plata',0),(8,'Puerto Colonia Express',1),(9,'Puerto Buquebus',1),(10,'Departamento',1),(11,'San Telmo / CABA',0),(12,'Domicilio particular',1);
/*!40000 ALTER TABLE `transfer_points` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `travel_destination`
--

DROP TABLE IF EXISTS `travel_destination`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `travel_destination` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL,
  `name` varchar(255) NOT NULL,
  `active` bit(1) NOT NULL DEFAULT b'1',
  `sort_order` int DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=MyISAM AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `travel_destination`
--

LOCK TABLES `travel_destination` WRITE;
/*!40000 ALTER TABLE `travel_destination` DISABLE KEYS */;
INSERT INTO `travel_destination` VALUES (1,'bariloche','Bariloche',_binary '',1),(2,'ushuaia-calafate','Ushuaia + Calafate',_binary '',2),(3,'mendoza','Mendoza',_binary '',3),(4,'ushuaia','Ushuaia',_binary '',4),(5,'calafate','El Calafate',_binary '',5),(8,'salta-jujuy','Salta + Jujuy',_binary '',8),(7,'iguazu','Iguazu',_binary '',7),(6,'salta','Salta',_binary '',6);
/*!40000 ALTER TABLE `travel_destination` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `travel_group`
--

DROP TABLE IF EXISTS `travel_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `travel_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `destination` varchar(255) NOT NULL,
  `when_label` varchar(255) NOT NULL,
  `companion_preference` varchar(255) NOT NULL,
  `smoke_free` tinyint(1) NOT NULL,
  `size_target` int NOT NULL DEFAULT '4',
  `status` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `travel_date_label` varchar(255) DEFAULT NULL,
  `common_prefs_json` longtext,
  `age_bucket` varchar(255) DEFAULT NULL,
  `departure_month` varchar(20) DEFAULT NULL,
  `travel_start_date` date DEFAULT NULL,
  `travel_end_date` date DEFAULT NULL,
  `departure_year` int DEFAULT NULL,
  `auto_search_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `operation_confirmed` tinyint(1) NOT NULL DEFAULT '0',
  `auto_search_added` int NOT NULL DEFAULT '0',
  `seller_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_travel_group_seller_user_id` (`seller_user_id`),
  CONSTRAINT `fk_travel_group_seller_user` FOREIGN KEY (`seller_user_id`) REFERENCES `user_account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `travel_group`
--

LOCK TABLES `travel_group` WRITE;
/*!40000 ALTER TABLE `travel_group` DISABLE KEYS */;
INSERT INTO `travel_group` VALUES (1,'bariloche','Abril 2026','ANY',1,9,'EN_CONCILIACION','2026-04-08 01:13:35.845099','Abril 2026','{\"Destino\":\"bariloche\",\"Compañía\":\"ANY\",\"Rango edades\":\"0-40\",\"Smoke free\":\"Sí\",\"Pax\":\"Min: 4 · Max: 9\",\"paymentTitularMemberId\":\"1\"}','26-40',NULL,'2026-04-07',NULL,NULL,0,0,0,2);
/*!40000 ALTER TABLE `travel_group` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `travel_request`
--

DROP TABLE IF EXISTS `travel_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `travel_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `age_max` int NOT NULL,
  `age_min` int NOT NULL,
  `city` varchar(255) DEFAULT NULL,
  `province` varchar(255) DEFAULT NULL,
  `locality` varchar(255) DEFAULT NULL,
  `postal_code` varchar(255) DEFAULT NULL,
  `birth_date` date DEFAULT NULL,
  `companion_preference` varchar(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `date_preset_id` varchar(255) DEFAULT NULL,
  `when_label` varchar(255) DEFAULT NULL,
  `travel_start_date` date DEFAULT NULL,
  `destination` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `pax_min` int NOT NULL,
  `pax_max` int DEFAULT NULL,
  `smoke_free` tinyint(1) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `tz` varchar(255) DEFAULT NULL,
  `travelers_total` int NOT NULL DEFAULT '1',
  `travelers_adults` int NOT NULL DEFAULT '1',
  `travelers_minors` int NOT NULL DEFAULT '0',
  `status` enum('CANCELLED','GROUPED','MATCHED','NEW','NOTIFIED','PENDING','SEARCHING') NOT NULL,
  `group_id` bigint DEFAULT NULL,
  `shared_room` bit(1) DEFAULT NULL,
  `includes_tours` tinyint(1) DEFAULT NULL,
  `luggage_count` int DEFAULT NULL,
  `travel_assistance` tinyint(1) DEFAULT NULL,
  `luggage_json` longtext,
  `gender` varchar(255) DEFAULT NULL,
  `deposit_amount` decimal(10,2) DEFAULT NULL,
  `deposit_payment_method` varchar(32) DEFAULT NULL,
  `deposit_date` date DEFAULT NULL,
  `deposit_notes` text,
  `country` varchar(100) DEFAULT NULL,
  `country_id` bigint DEFAULT NULL,
  `dni` varchar(32) DEFAULT NULL,
  `deposit_receipt_blob` longblob,
  `deposit_receipt_content_type` varchar(255) DEFAULT NULL,
  `deposit_receipt_file_name` varchar(255) DEFAULT NULL,
  `travel_date_start` date DEFAULT NULL,
  `travel_end_date` date DEFAULT NULL,
  `document_expiry_date` date DEFAULT NULL,
  `document_no_expiry` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `IDXhei2n5risgbhapdwm5kfkp1t` (`destination`),
  KEY `IDXg9v0aghdu8qftgig3h4k6r0u8` (`date_preset_id`),
  KEY `IDX6p51mxc9rh0riwaa2csjiam28` (`companion_preference`),
  KEY `idx_tr_status` (`status`),
  KEY `idx_tr_bucket` (`destination`,`when_label`,`companion_preference`,`smoke_free`),
  KEY `idx_tr_group` (`group_id`),
  KEY `idx_travel_request_travelers` (`travelers_total`,`travelers_adults`,`travelers_minors`),
  CONSTRAINT `fk_travel_request_group` FOREIGN KEY (`group_id`) REFERENCES `travel_group` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `travel_request`
--

LOCK TABLES `travel_request` WRITE;
/*!40000 ALTER TABLE `travel_request` DISABLE KEYS */;
INSERT INTO `travel_request` VALUES (1,40,26,NULL,NULL,NULL,NULL,'1988-11-02','ANY','2026-04-08 01:13:34.649532','Abril 2026','Abril 2026','2026-04-07','bariloche','matiasg1988@gmail.com','Matias Garcia',4,9,1,'1150465015',NULL,1,1,0,'GROUPED',1,_binary '\0',0,0,0,'[]','HOMBRE',NULL,NULL,NULL,NULL,'Argentina',1,'34155033',NULL,NULL,NULL,NULL,'2026-04-15','2030-12-30',0),(2,40,26,NULL,NULL,NULL,NULL,'1996-01-19','ANY','2026-04-08 01:13:34.941014','Abril 2026','Abril 2026','2026-04-07','bariloche','rominadfer@gmail.com','Romina Fernandez',4,9,1,'1170391256',NULL,1,1,0,'GROUPED',1,_binary '\0',0,0,0,'[]','MUJER',NULL,NULL,NULL,NULL,'Argentina',1,'42595287',NULL,NULL,NULL,NULL,'2026-04-15','2031-12-15',0),(3,17,0,NULL,NULL,NULL,NULL,'2024-12-29','ANY','2026-04-08 01:13:35.187433','Abril 2026','Abril 2026','2026-04-07','bariloche','-','Sofia Garcia',4,9,1,'-',NULL,1,0,1,'GROUPED',1,_binary '\0',0,0,0,'[]','MUJER',NULL,NULL,NULL,NULL,'Argentina',1,'70000000',NULL,NULL,NULL,NULL,'2026-04-15','2035-12-05',0),(4,17,0,NULL,NULL,NULL,NULL,'2024-12-29','ANY','2026-04-08 01:13:35.435872','Abril 2026','Abril 2026','2026-04-07','bariloche','-','Julieta Garcia',4,9,1,'-',NULL,1,0,1,'GROUPED',1,_binary '\0',0,0,0,'[]','MUJER',NULL,NULL,NULL,NULL,'Argentina',1,'80000000',NULL,NULL,NULL,NULL,'2026-04-15','2035-12-02',0);
/*!40000 ALTER TABLE `travel_request` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `travel_request_air_service`
--

DROP TABLE IF EXISTS `travel_request_air_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `travel_request_air_service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `airline` varchar(100) NOT NULL,
  `baggage_allowance` varchar(10) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `departure_arrival_time` time(6) DEFAULT NULL,
  `departure_date` date NOT NULL,
  `departure_time` time(6) NOT NULL,
  `destination` varchar(255) DEFAULT NULL,
  `origin` varchar(255) DEFAULT NULL,
  `return_arrival_time` time(6) DEFAULT NULL,
  `return_date` date DEFAULT NULL,
  `return_time` time(6) DEFAULT NULL,
  `trip_type` varchar(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `request_id` bigint NOT NULL,
  `quoted_at` datetime(6) DEFAULT NULL,
  `quoted_value` decimal(12,4) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `total_cost_updated_at` datetime(6) DEFAULT NULL,
  `reservation_code` varchar(100) DEFAULT NULL,
  `document_expiration_date` date DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_travel_request_air_service_request` (`request_id`),
  KEY `idx_travel_request_air_service_request` (`request_id`),
  CONSTRAINT `fk_travel_request_air_service_request` FOREIGN KEY (`request_id`) REFERENCES `travel_request` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `travel_request_air_service`
--

LOCK TABLES `travel_request_air_service` WRITE;
/*!40000 ALTER TABLE `travel_request_air_service` DISABLE KEYS */;
/*!40000 ALTER TABLE `travel_request_air_service` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_account`
--

DROP TABLE IF EXISTS `user_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `first_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `last_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `profile_json` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=169 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_account`
--

LOCK TABLES `user_account` WRITE;
/*!40000 ALTER TABLE `user_account` DISABLE KEYS */;
INSERT INTO `user_account` VALUES (1,'mgarcia','$2a$10$mA31NmdLU3VwejVZKpBHxeYAAHk1YwIsphZDAC.IbDb.yuZE/p0Ou','SELLER','Mariana','2026-02-26 20:52:10.527364','Lopez',NULL),(2,'admin','$2a$10$bzApTdBIynAGx.NvtjBXseTmFKyE9wOuocB90yQ.jUDbSEFhHuiDq','ADMIN','Matias','2026-04-07 18:49:10.738465','Garcia',NULL),(3,'manuc','$2a$10$tzek8gtd.uqOO.tMjyPaAuZQvowJKvPi7YFkJ3jwp3GToLEahcfvu','SELLER','Manu','2026-02-03 20:39:43.976197','C',NULL),(33,'operador','$2a$10$bzApTdBIynAGx.NvtjBXseTmFKyE9wOuocB90yQ.jUDbSEFhHuiDq','OPERATIONS','Operations','2026-04-05 20:52:33.159463','User',NULL),(159,'coinbot','$2a$10$bzApTdBIynAGx.NvtjBXseTmFKyE9wOuocB90yQ.jUDbSEFhHuiDq','ADMIN','CoinBot','2026-03-23 20:15:17.560198',NULL,NULL),(166,'matiasg1988@gmail.com','$2a$10$UxdSXrj/sLel.6V/Sq17kOGvAPgQnR62cCOyIcBDwbK/ibnsyw3sy','USER',NULL,NULL,NULL,NULL),(167,'rominadfer@gmail.com','$2a$10$pGFLX1PCENhr238nzEhxYuXIWFpDymegs614zCZYs4LQ.h81hCyOG','USER',NULL,NULL,NULL,NULL),(168,'-','$2a$10$oLO476.SyYIxIYSr8vDWW.lLB5lVzvByp8NZMHMhUWYjNLjrUsLD.','USER',NULL,NULL,NULL,NULL);
/*!40000 ALTER TABLE `user_account` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_notification`
--

DROP TABLE IF EXISTS `user_notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel` enum('EMAIL') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `group_id` bigint DEFAULT NULL,
  `link_url` varchar(512) DEFAULT NULL,
  `menu_item_id` bigint DEFAULT NULL,
  `message` text,
  `read_at` datetime(6) DEFAULT NULL,
  `recipient_email` varchar(255) NOT NULL,
  `send_error` text,
  `sent_at` datetime(6) DEFAULT NULL,
  `service_code` varchar(40) DEFAULT NULL,
  `service_label` varchar(160) DEFAULT NULL,
  `subject` varchar(255) NOT NULL,
  `type` enum('SERVICE_EMITTED','SERVICE_PAID') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_notif_recipient_created` (`recipient_email`,`created_at`),
  KEY `idx_user_notif_group` (`group_id`),
  KEY `idx_user_notif_menu_item` (`menu_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_notification`
--

LOCK TABLES `user_notification` WRITE;
/*!40000 ALTER TABLE `user_notification` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_notification` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_profile_data`
--

DROP TABLE IF EXISTS `user_profile_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_profile_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `data_json` longtext,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_profile_data_email` (`email`(191))
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_profile_data`
--

LOCK TABLES `user_profile_data` WRITE;
/*!40000 ALTER TABLE `user_profile_data` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_profile_data` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Temporary view structure for view `vw_report_group_members`
--

DROP TABLE IF EXISTS `vw_report_group_members`;
/*!50001 DROP VIEW IF EXISTS `vw_report_group_members`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `vw_report_group_members` AS SELECT 
 1 AS `group_id`,
 1 AS `group_status`,
 1 AS `destination`,
 1 AS `departure_month`,
 1 AS `departure_year`,
 1 AS `member_id`,
 1 AS `member_status`,
 1 AS `member_name`,
 1 AS `member_email`,
 1 AS `gender`,
 1 AS `created_at`,
 1 AS `travelers_total`,
 1 AS `deposit_amount`,
 1 AS `deposit_payment_method`,
 1 AS `deposit_date`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `vw_report_groups`
--

DROP TABLE IF EXISTS `vw_report_groups`;
/*!50001 DROP VIEW IF EXISTS `vw_report_groups`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `vw_report_groups` AS SELECT 
 1 AS `group_id`,
 1 AS `destination`,
 1 AS `departure_month`,
 1 AS `departure_year`,
 1 AS `group_status`,
 1 AS `group_created_at`,
 1 AS `travel_start_date`,
 1 AS `travel_end_date`,
 1 AS `auto_search_enabled`,
 1 AS `auto_search_added`,
 1 AS `operation_confirmed`,
 1 AS `member_count`,
 1 AS `travelers_total`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `vw_report_member_payment_records`
--

DROP TABLE IF EXISTS `vw_report_member_payment_records`;
/*!50001 DROP VIEW IF EXISTS `vw_report_member_payment_records`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `vw_report_member_payment_records` AS SELECT 
 1 AS `payment_record_id`,
 1 AS `group_id`,
 1 AS `destination`,
 1 AS `group_status`,
 1 AS `member_id`,
 1 AS `member_email`,
 1 AS `member_name`,
 1 AS `payment_date`,
 1 AS `created_at`,
 1 AS `amount`,
 1 AS `currency`,
 1 AS `receipt_last4`,
 1 AS `installment_number`,
 1 AS `plan_type`,
 1 AS `one_time_method`,
 1 AS `plan_total_amount`,
 1 AS `plan_currency`*/;
SET character_set_client = @saved_cs_client;

--
-- Final view structure for view `vw_report_group_members`
--

/*!50001 DROP VIEW IF EXISTS `vw_report_group_members`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `vw_report_group_members` AS select `g`.`id` AS `group_id`,`g`.`status` AS `group_status`,`g`.`destination` AS `destination`,`g`.`departure_month` AS `departure_month`,`g`.`departure_year` AS `departure_year`,`r`.`id` AS `member_id`,`r`.`status` AS `member_status`,`r`.`name` AS `member_name`,`r`.`email` AS `member_email`,`r`.`gender` AS `gender`,`r`.`created_at` AS `created_at`,`r`.`travelers_total` AS `travelers_total`,`r`.`deposit_amount` AS `deposit_amount`,`r`.`deposit_payment_method` AS `deposit_payment_method`,`r`.`deposit_date` AS `deposit_date` from (`travel_request` `r` join `travel_group` `g` on((`g`.`id` = `r`.`group_id`))) where (`r`.`group_id` is not null) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `vw_report_groups`
--

/*!50001 DROP VIEW IF EXISTS `vw_report_groups`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `vw_report_groups` AS select `g`.`id` AS `group_id`,`g`.`destination` AS `destination`,`g`.`departure_month` AS `departure_month`,`g`.`departure_year` AS `departure_year`,`g`.`status` AS `group_status`,`g`.`created_at` AS `group_created_at`,`g`.`travel_start_date` AS `travel_start_date`,`g`.`travel_end_date` AS `travel_end_date`,`g`.`auto_search_enabled` AS `auto_search_enabled`,`g`.`auto_search_added` AS `auto_search_added`,`g`.`operation_confirmed` AS `operation_confirmed`,count(`r`.`id`) AS `member_count`,coalesce(sum(`r`.`travelers_total`),0) AS `travelers_total` from (`travel_group` `g` left join `travel_request` `r` on((`r`.`group_id` = `g`.`id`))) group by `g`.`id`,`g`.`destination`,`g`.`departure_month`,`g`.`departure_year`,`g`.`status`,`g`.`created_at`,`g`.`travel_start_date`,`g`.`travel_end_date`,`g`.`auto_search_enabled`,`g`.`auto_search_added`,`g`.`operation_confirmed` */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `vw_report_member_payment_records`
--

/*!50001 DROP VIEW IF EXISTS `vw_report_member_payment_records`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `vw_report_member_payment_records` AS select `pr`.`id` AS `payment_record_id`,`pr`.`group_id` AS `group_id`,`g`.`destination` AS `destination`,`g`.`status` AS `group_status`,`pr`.`member_id` AS `member_id`,`tr`.`email` AS `member_email`,`tr`.`name` AS `member_name`,`pr`.`payment_date` AS `payment_date`,`pr`.`created_at` AS `created_at`,`pr`.`amount` AS `amount`,`pr`.`currency` AS `currency`,`pr`.`receipt_last4` AS `receipt_last4`,`pr`.`installment_number` AS `installment_number`,`pl`.`plan_type` AS `plan_type`,`pl`.`one_time_method` AS `one_time_method`,`pl`.`total_amount` AS `plan_total_amount`,`pl`.`currency` AS `plan_currency` from (((`member_payment_record` `pr` join `member_payment_plan` `pl` on((`pl`.`id` = `pr`.`plan_id`))) join `travel_group` `g` on((`g`.`id` = `pr`.`group_id`))) left join `travel_request` `tr` on((`tr`.`id` = `pr`.`member_id`))) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-04-08  3:04:20
