package com.linkroa.deepdataagent.file.application.convert;

import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FileCommandConvert} 单测：multipart 入口四参数装配顺序
 * （filename / purpose / metadata / content 错位防线；mime 不入命令，由服务端探测）。
 */
class FileCommandConvertTest {

    @Test
    void should_assembleCommandInOrder_when_toCreateCommand_given_multipartParts() {
        // given
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        // when
        CreateFileCommand command = FileCommandConvert.INSTANCE.toCreateCommand(
                "note.txt", "user_upload", "{\"k\":\"v\"}", content);

        // then
        assertEquals("note.txt", command.filename());
        assertEquals("user_upload", command.purpose());
        assertEquals("{\"k\":\"v\"}", command.metadata());
        assertArrayEquals(content, command.content());
    }

    @Test
    void should_keepNullParts_when_toCreateCommand_given_optionalPartsAbsent() {
        // given & when（purpose/metadata 缺省透传，由应用服务解析为 400 / 归一 {}）
        CreateFileCommand command = FileCommandConvert.INSTANCE.toCreateCommand(
                "a.txt", null, null, null);

        // then
        assertEquals("a.txt", command.filename());
        assertNull(command.purpose());
        assertNull(command.metadata());
        assertNull(command.content());
    }
}
