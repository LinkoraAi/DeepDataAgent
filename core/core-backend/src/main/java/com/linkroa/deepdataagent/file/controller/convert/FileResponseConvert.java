package com.linkroa.deepdataagent.file.controller.convert;

import com.linkroa.deepdataagent.file.controller.response.FileResponse;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 文件领域 → 响应 DTO 转换器（MapStruct 静态单例）。
 * <p>purpose / status 输出契约词汇（小写 code），scope 值对象映射为嵌套响应，
 * 转换逻辑由 default 方法显式承载。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FileResponseConvert {

    FileResponseConvert INSTANCE = Mappers.getMapper(FileResponseConvert.class);

    /**
     * 领域聚合 → 文件响应（文件对象形态）。
     */
    default FileResponse toResponse(File file) {
        if (file == null) {
            return null;
        }
        return new FileResponse(
                file.fileId(),
                File.FILE_TYPE,
                file.filename(),
                file.mimeType(),
                file.sizeBytes(),
                file.purpose().code(),
                file.status().code(),
                file.downloadable(),
                toScopeResponse(file.scope()),
                file.metadata(),
                file.createdAt(),
                file.updatedAt());
    }

    /** scope 值对象 → 嵌套响应（未关联透传 null）。 */
    private static FileResponse.FileScopeResponse toScopeResponse(FileScope scope) {
        if (scope == null) {
            return null;
        }
        return new FileResponse.FileScopeResponse(scope.id(), scope.type());
    }
}
