package com.linkroa.deepdataagent.shared.infrastructure.convert;

import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecord;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.IdempotencyRecordEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IdempotencyRecordPersistenceConvert} 持久化映射单测。
 */
class IdempotencyRecordPersistenceConvertTest {

    @Test
    void should_mapAllFields_when_toEntity_given_record() {
        // given
        IdempotencyRecord record = new IdempotencyRecord(
                "key-1", 7L, "post_agents", "hash-1", 200, "{\"data\":{}}");

        // when
        IdempotencyRecordEntity entity = IdempotencyRecordPersistenceConvert.INSTANCE.toEntity(record);

        // then
        assertEquals("key-1", entity.getIdempotencyKey());
        assertEquals(7L, entity.getOwnerId());
        assertEquals("post_agents", entity.getScope());
        assertEquals("hash-1", entity.getRequestHash());
        assertEquals(200, entity.getResponseStatus());
        assertEquals("{\"data\":{}}", entity.getResponseBody());
    }

    @Test
    void should_roundTrip_when_toDomain_given_convertedEntity() {
        // given
        IdempotencyRecord record = new IdempotencyRecord(
                "key-1", 7L, "post_agents", "hash-1", 201, "{\"data\":{}}");

        // when
        IdempotencyRecord domain = IdempotencyRecordPersistenceConvert.INSTANCE
                .toDomain(IdempotencyRecordPersistenceConvert.INSTANCE.toEntity(record));

        // then
        assertEquals(record, domain);
    }
}