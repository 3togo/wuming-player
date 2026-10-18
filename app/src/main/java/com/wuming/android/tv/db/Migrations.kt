package com.wuming.android.tv.db

import androidx.annotation.NonNull
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migrations {

    companion object {
        @JvmField
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE Track")
                db.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL, `adaptive` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)")
            }
        }

        @JvmField
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History")
                db.execSQL("DROP TABLE History")
                db.execSQL("ALTER TABLE History_Backup RENAME to History")
            }
        }

        @JvmField
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL")
            }
        }

        @JvmField
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE Track")
                db.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)")
            }
        }

        @JvmField
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE Track")
                db.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)")
            }
        }

        @JvmField
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_History_cid` ON `History` (`cid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_History_vodName` ON `History` (`vodName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_History_cid_vodName` ON `History` (`cid`, `vodName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Keep_type` ON `Keep` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Keep_cid` ON `Keep` (`cid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Keep_cid_type` ON `Keep` (`cid`, `type`)")
            }
        }

        @JvmField
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Config` ADD COLUMN `danmaku` TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `drive` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `type` INTEGER NOT NULL, `configJson` TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `SearchHistory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `keyword` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `frequency` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_SearchHistory_keyword` ON `SearchHistory` (`keyword`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_SearchHistory_timestamp` ON `SearchHistory` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_SearchHistory_frequency` ON `SearchHistory` (`frequency`)")
            }
        }

        @JvmField
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `SitePerformance` (`siteKey` TEXT NOT NULL, `totalRequests` INTEGER NOT NULL, `successCount` INTEGER NOT NULL, `deadLinkCount` INTEGER NOT NULL, `totalSpeed` INTEGER NOT NULL, `speedSampleCount` INTEGER NOT NULL, `lastSpeed` INTEGER NOT NULL, `avgSpeed` INTEGER NOT NULL, `lastSuccessTime` INTEGER NOT NULL, `lastFailureTime` INTEGER NOT NULL, `reliabilityScore` REAL NOT NULL, `performanceScore` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`siteKey`))")
            }
        }

        @JvmField
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                // Schema changes:
                // 1. totalSpeed  INTEGER -> REAL
                // 2. avgSpeed    INTEGER -> REAL
                // 3. failureCount added (INTEGER NOT NULL DEFAULT 0)
                db.execSQL("CREATE TABLE `SitePerformance_new` (`siteKey` TEXT NOT NULL, `totalRequests` INTEGER NOT NULL, `successCount` INTEGER NOT NULL, `deadLinkCount` INTEGER NOT NULL, `failureCount` INTEGER NOT NULL DEFAULT 0, `totalSpeed` REAL NOT NULL, `speedSampleCount` INTEGER NOT NULL, `lastSpeed` INTEGER NOT NULL, `avgSpeed` REAL NOT NULL, `lastSuccessTime` INTEGER NOT NULL, `lastFailureTime` INTEGER NOT NULL, `reliabilityScore` REAL NOT NULL, `performanceScore` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`siteKey`))")
                db.execSQL("INSERT INTO `SitePerformance_new` (siteKey, totalRequests, successCount, deadLinkCount, failureCount, totalSpeed, speedSampleCount, lastSpeed, avgSpeed, lastSuccessTime, lastFailureTime, reliabilityScore, performanceScore, updatedAt) SELECT siteKey, totalRequests, successCount, deadLinkCount, 0, CAST(totalSpeed AS REAL), speedSampleCount, lastSpeed, CAST(avgSpeed AS REAL), lastSuccessTime, lastFailureTime, reliabilityScore, performanceScore, updatedAt FROM `SitePerformance`")
                db.execSQL("DROP TABLE `SitePerformance`")
                db.execSQL("ALTER TABLE `SitePerformance_new` RENAME TO `SitePerformance`")
            }
        }

        @JvmField
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `CloudCacheRecord` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `accountHash` TEXT NOT NULL, `remoteFolderId` TEXT NOT NULL, `remoteFileId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sourceSiteKey` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `state` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_CloudCacheRecord_provider_accountHash_remoteFileId` ON `CloudCacheRecord` (`provider`, `accountHash`, `remoteFileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_CloudCacheRecord_provider_state` ON `CloudCacheRecord` (`provider`, `state`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `CloudAuditEvent` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `operation` TEXT NOT NULL, `success` INTEGER NOT NULL, `itemCount` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `message` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_CloudAuditEvent_provider_createdAt` ON `CloudAuditEvent` (`provider`, `createdAt`)")
            }
        }

        @JvmField
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ViewingRecord` (`id` TEXT NOT NULL, `key` TEXT, `siteKey` TEXT, `vodId` TEXT, `vodName` TEXT, `vodPic` TEXT, `episode` TEXT, `typeName` TEXT, `year` TEXT, `area` TEXT, `director` TEXT, `actor` TEXT, `content` TEXT, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `lastWatchedAt` INTEGER NOT NULL, `watchedDuration` INTEGER NOT NULL, `mediaDuration` INTEGER NOT NULL, `startPosition` INTEGER NOT NULL, `endPosition` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ViewingRecord_key` ON `ViewingRecord` (`key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ViewingRecord_siteKey` ON `ViewingRecord` (`siteKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ViewingRecord_lastWatchedAt` ON `ViewingRecord` (`lastWatchedAt`)")
            }
        }

        @JvmField
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `MediaResource` (`uri` TEXT NOT NULL, `authority` TEXT NOT NULL, `kind` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `title` TEXT NOT NULL, `manifestVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MediaResource_authority_kind_resourceId` ON `MediaResource` (`authority`, `kind`, `resourceId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `MediaSource` (`sourceId` TEXT NOT NULL, `resourceUri` TEXT NOT NULL, `adapterId` TEXT NOT NULL, `locatorVersion` INTEGER NOT NULL, `locatorJson` TEXT NOT NULL, `credentialRef` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`), FOREIGN KEY(`resourceUri`) REFERENCES `MediaResource`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_MediaSource_resourceUri` ON `MediaSource` (`resourceUri`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_MediaSource_fingerprint` ON `MediaSource` (`fingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_MediaSource_adapterId_enabled` ON `MediaSource` (`adapterId`, `enabled`)")
            }
        }

        @JvmField
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `History` ADD COLUMN `mediaUri` TEXT DEFAULT NULL")
            }
        }

        /** Adds conservative lifecycle metadata without changing any existing OpenDrive identity. */
        @JvmField
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `MediaResource` ADD COLUMN `lastAccessedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `MediaResource` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `MediaResource` SET `lastAccessedAt` = CASE "
                        + "WHEN `updatedAt` > 0 THEN `updatedAt` ELSE `createdAt` END")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_MediaResource_pinned_lastAccessedAt` "
                        + "ON `MediaResource` (`pinned`, `lastAccessedAt`)")
            }
        }

        @JvmField
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `EpisodeNameLog` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vodName` TEXT NOT NULL, `episodeName` TEXT NOT NULL, `flagName` TEXT NOT NULL, `totalEpisodes` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `displayLength` INTEGER NOT NULL, `isLong` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_EpisodeNameLog_isLong` ON `EpisodeNameLog` (`isLong`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_EpisodeNameLog_timestamp` ON `EpisodeNameLog` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_EpisodeNameLog_vodName_episodeName` ON `EpisodeNameLog` (`vodName`, `episodeName`)")
            }
        }

        @JvmField
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(@NonNull db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_SitePerformance_performanceScore` ON `SitePerformance` (`performanceScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_SitePerformance_reliabilityScore` ON `SitePerformance` (`reliabilityScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_SitePerformance_avgSpeed` ON `SitePerformance` (`avgSpeed`)")
            }
        }
        /** One production/test migration registry; historical fixture versions start at 35. */
        @JvmStatic
        fun all(): Array<Migration> = arrayOf(
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
        )
    }
}
