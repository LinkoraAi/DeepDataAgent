package com.linkroa.deepdataagent.file.application.convert;

import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 文件请求转换器（multipart 入口参数 → Command，MapStruct 静态单例）。
 * <p>multipart 各部分参数由控制器拆解后装配，转换逻辑由 default 方法承载；
 * mime_type 不在装配范围——一律由服务端探测。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FileCommandConvert {

    FileCommandConvert INSTANCE = Mappers.getMapper(FileCommandConvert.class);

    /**
     * multipart 参数 → 上传命令。
     *
     * @param filename 上传文件原始名
     * @param purpose  用途契约值（必填，缺省由应用服务解析为 400）
     * @param metadata 元数据 JSON 文本（可空）
     * @param content  文件内容字节
     * @return 上传命令
     */
    default CreateFileCommand toCreateCommand(String filename, String purpose,
                                              String metadata, byte[] content) {
        return new CreateFileCommand(filename, purpose, metadata, content);
    }
}
