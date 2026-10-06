package com.lezi.babylog.core.database

/**
 * Single source of truth for media_assets ownership triggers.
 * Used by [DatabaseModule] fresh onCreate and Room 26→27 migrate-time recreate.
 */
fun mediaAssetOwnerTriggerSql(name: String, operation: String): String =
    """
    CREATE TRIGGER $name
    BEFORE $operation ON media_assets
    WHEN NOT (
        (
            NEW.kind = 'log'
            AND NEW.babyId IS NULL
            AND NEW.wakeObservationId IS NULL
            AND (
                (NEW.recordId IS NOT NULL AND NEW.carePlanId IS NULL)
                OR (NEW.recordId IS NULL AND NEW.carePlanId IS NOT NULL)
            )
        )
        OR (
            NEW.kind = 'avatar'
            AND NEW.babyId IS NOT NULL
            AND NEW.recordId IS NULL
            AND NEW.carePlanId IS NULL
            AND NEW.wakeObservationId IS NULL
        )
        OR (
            NEW.kind = 'wake'
            AND NEW.wakeObservationId IS NOT NULL
            AND NEW.recordId IS NULL
            AND NEW.carePlanId IS NULL
            AND NEW.babyId IS NULL
        )
    )
    BEGIN
        SELECT RAISE(ABORT, 'invalid media asset ownership');
    END
    """.trimIndent()
