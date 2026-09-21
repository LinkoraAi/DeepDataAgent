package com.linkroa.deepdataagent.knowledgebase.infrastructure.assembly;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultKbAssetStoragePort} 单元测试。
 * <p>覆盖：写入委派与入参校验、open 的「列举精确匹配 + 流透传 + size 透传」、
 * 对象缺失（列举未命中 / 近似前缀误配拦截 / get 竞态 NOT_FOUND）翻译为 empty、
 * 非 NOT_FOUND 技术异常原样上抛、幂等删除与前缀清退委派、空白键与空白前缀拒绝。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultKbAssetStoragePortTest {

    private static final String TEST_KEY = "rag/7/42/images/a.png";

    private static final String TEST_PREFIX = "rag/7/";

    @Mock
    private ObjectStorage objectStorage;

    @InjectMocks
    private DefaultKbAssetStoragePort port;

    /**
     * 构造列举命中元数据。
     *
     * @param key  对象键
     * @param size 字节数
     * @return 对象元数据
     */
    private static ObjectMetadata metadataOf(String key, long size) {
        return new ObjectMetadata(key, size, null, Instant.EPOCH, null);
    }

    // ==================== putSource / putMedia ====================

    @Test
    void should_delegatePut_when_putSource_given_validKeyAndContent() {
        // given
        byte[] content = "hello".getBytes();

        // when
        port.putSource(TEST_KEY, content, "application/pdf");

        // then 流与字节数原样透传
        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(objectStorage).put(eq(TEST_KEY), streamCaptor.capture(), eq(5L), eq("application/pdf"));
        assertTrue(streamCaptor.getValue() instanceof ByteArrayInputStream);
    }

    @Test
    void should_delegatePut_when_putMedia_given_validKeyAndContent() {
        // given
        byte[] content = new byte[]{1, 2, 3};

        // when
        port.putMedia(TEST_KEY, content, null);

        // then contentType 可为 null，由技术端口兜底
        verify(objectStorage).put(eq(TEST_KEY), any(InputStream.class), eq(3L), eq(null));
    }

    @Test
    void should_rejectWithoutStorageCall_when_putSource_given_blankKeyOrEmptyContent() {
        // when / then 空白键拒绝
        assertThrows(IllegalArgumentException.class,
                () -> port.putSource(" ", "x".getBytes(), "text/plain"));
        // when / then 空字节拒绝
        assertThrows(IllegalArgumentException.class,
                () -> port.putSource(TEST_KEY, new byte[0], "text/plain"));
        verifyNoInteractions(objectStorage);
    }

    // ==================== open ====================

    @Test
    void should_returnStreamWithExactSize_when_open_given_objectExists() {
        // given 列举精确命中（含一个近似前缀兄弟对象，必须被严格相等过滤排除其尺寸干扰）
        InputStream source = new ByteArrayInputStream("abc".getBytes());
        when(objectStorage.list(TEST_KEY)).thenReturn(
                List.of(metadataOf(TEST_KEY + ".bak", 999L), metadataOf(TEST_KEY, 3L)));
        when(objectStorage.get(TEST_KEY)).thenReturn(source);

        // when
        Optional<KbAssetStoragePort.OpenedObject> opened = port.open(TEST_KEY);

        // then size 取精确命中项，流原样透传
        assertTrue(opened.isPresent());
        assertEquals(3L, opened.get().size());
        assertEquals(source, opened.get().content());
        verify(objectStorage).get(TEST_KEY);
    }

    @Test
    void should_returnEmptyWithoutGet_when_open_given_onlySiblingPrefixMatch() {
        // given 仅近似对象命中（x.png.bak），精确键不存在
        when(objectStorage.list(TEST_KEY)).thenReturn(List.of(metadataOf(TEST_KEY + ".bak", 9L)));

        // when
        Optional<KbAssetStoragePort.OpenedObject> opened = port.open(TEST_KEY);

        // then 翻译为对象缺失，不发起读取
        assertTrue(opened.isEmpty());
        verify(objectStorage, never()).get(anyString());
    }

    @Test
    void should_returnEmpty_when_open_given_listMiss() {
        // given 列举无命中
        when(objectStorage.list(TEST_KEY)).thenReturn(List.of());

        // when / then
        assertTrue(port.open(TEST_KEY).isEmpty());
        verify(objectStorage, never()).get(anyString());
    }

    @Test
    void should_translateToEmpty_when_open_given_getRaceNotFound() {
        // given 列举命中后 get 抛 NOT_FOUND（竞态删除）
        when(objectStorage.list(TEST_KEY)).thenReturn(List.of(metadataOf(TEST_KEY, 3L)));
        when(objectStorage.get(TEST_KEY))
                .thenThrow(new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND, "对象不存在"));

        // when / then 同样翻译为对象缺失
        assertTrue(port.open(TEST_KEY).isEmpty());
    }

    @Test
    void should_propagateException_when_open_given_getUnavailable() {
        // given get 抛非 NOT_FOUND 技术异常（fail-closed）
        when(objectStorage.list(TEST_KEY)).thenReturn(List.of(metadataOf(TEST_KEY, 3L)));
        when(objectStorage.get(TEST_KEY))
                .thenThrow(new ObjectStorageException(ObjectStorageErrorKind.UNAVAILABLE, "存储不可达"));

        // when / then 原样上抛交调用方处置
        assertThrows(ObjectStorageException.class, () -> port.open(TEST_KEY));
    }

    @Test
    void should_propagateException_when_open_given_listFailure() {
        // given 列举失败
        when(objectStorage.list(TEST_KEY))
                .thenThrow(new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR, "列举失败"));

        // when / then 上抛且不发读取
        assertThrows(ObjectStorageException.class, () -> port.open(TEST_KEY));
        verify(objectStorage, never()).get(anyString());
    }

    @Test
    void should_rejectWithoutStorageCall_when_open_given_blankKey() {
        // when / then
        assertThrows(IllegalArgumentException.class, () -> port.open(" "));
        verifyNoInteractions(objectStorage);
    }

    // ==================== delete / cleanupPrefix ====================

    @Test
    void should_delegateDelete_when_delete_given_validKey() {
        // when（技术端口幂等：对象不存在静默成功）
        port.delete(TEST_KEY);

        // then
        verify(objectStorage).delete(TEST_KEY);
    }

    @Test
    void should_propagateException_when_delete_given_storageFailure() {
        // given
        doThrow(new ObjectStorageException(ObjectStorageErrorKind.UNAVAILABLE, "存储不可达"))
                .when(objectStorage).delete(TEST_KEY);

        // when / then
        assertThrows(ObjectStorageException.class, () -> port.delete(TEST_KEY));
    }

    @Test
    void should_rejectWithoutStorageCall_when_delete_given_blankKey() {
        // when / then
        assertThrows(IllegalArgumentException.class, () -> port.delete(""));
        verifyNoInteractions(objectStorage);
    }

    @Test
    void should_delegateDeletePrefix_when_cleanupPrefix_given_validPrefix() {
        // when
        port.cleanupPrefix(TEST_PREFIX);

        // then
        verify(objectStorage).deletePrefix(TEST_PREFIX);
    }

    @Test
    void should_rejectWithoutStorageCall_when_cleanupPrefix_given_blankPrefix() {
        // when / then 空白前缀直接拒绝，杜绝全桶误删
        assertThrows(IllegalArgumentException.class, () -> port.cleanupPrefix(" "));
        verifyNoInteractions(objectStorage);
    }

    @Test
    void should_passThroughSize_when_open_given_largeObject() {
        // given 大对象字节数原样透传（供 Content-Length 组装）
        when(objectStorage.list(anyString())).thenReturn(List.of(metadataOf(TEST_KEY, 52_428_800L)));
        when(objectStorage.get(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        // when
        Optional<KbAssetStoragePort.OpenedObject> opened = port.open(TEST_KEY);

        // then
        assertTrue(opened.isPresent());
        assertEquals(52_428_800L, opened.get().size());
        verify(objectStorage, never()).put(anyString(), any(InputStream.class), anyLong(), anyString());
    }
}
