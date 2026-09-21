package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 支持的文件格式枚举。
 */
public enum FileType {

    PDF,
    DOC,
    DOCX,
    XLS,
    XLSX,
    PPT,
    PPTX,
    TXT,
    MD,
    CSV,
    HTML,
    PNG,
    JPG,
    JPEG;

    /**
     * 根据文件名扩展名解析文件类型。
     *
     * @param fileName 文件名
     * @return 文件类型枚举
     * @throws IllegalArgumentException 扩展名不支持时抛出
     */
    public static FileType fromFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new IllegalArgumentException("无法识别文件类型：" + fileName);
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase();
        try {
            return FileType.valueOf(ext);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的文件格式：" + ext);
        }
    }
}
