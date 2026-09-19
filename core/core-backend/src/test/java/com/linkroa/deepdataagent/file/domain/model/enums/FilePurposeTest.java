package com.linkroa.deepdataagent.file.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FilePurpose} 枚举单测（五态契约词汇与 downloadable 派生）。
 */
class FilePurposeTest {

    @Test
    void should_exposeContractCodes_when_code_given_fivePurposes() {
        // given // when & then
        assertEquals("user_upload", FilePurpose.USER_UPLOAD.code());
        assertEquals("tool_output", FilePurpose.TOOL_OUTPUT.code());
        assertEquals("skill_output", FilePurpose.SKILL_OUTPUT.code());
        assertEquals("session_resource", FilePurpose.SESSION_RESOURCE.code());
        assertEquals("agent_output", FilePurpose.AGENT_OUTPUT.code());
    }

    @Test
    void should_deriveDownloadable_when_downloadable_given_fivePurposes() {
        // given // when & then（仅工具 / 技能产出可直接下载）
        assertFalse(FilePurpose.USER_UPLOAD.downloadable());
        assertTrue(FilePurpose.TOOL_OUTPUT.downloadable());
        assertTrue(FilePurpose.SKILL_OUTPUT.downloadable());
        assertFalse(FilePurpose.SESSION_RESOURCE.downloadable());
        assertFalse(FilePurpose.AGENT_OUTPUT.downloadable());
    }

    @Test
    void should_parseEnum_when_fromCode_given_validCode() {
        // given // when & then
        assertEquals(FilePurpose.USER_UPLOAD, FilePurpose.fromCode("user_upload"));
        assertEquals(FilePurpose.AGENT_OUTPUT, FilePurpose.fromCode("agent_output"));
    }

    @Test
    void should_throwException_when_fromCode_given_blankOrUnknownCode() {
        // given // when // then（缺省 / 非法用途 → 400 语义）
        assertThrows(IllegalArgumentException.class, () -> FilePurpose.fromCode(null));
        assertThrows(IllegalArgumentException.class, () -> FilePurpose.fromCode(" "));
        assertThrows(IllegalArgumentException.class, () -> FilePurpose.fromCode("bogus"));
    }
}
