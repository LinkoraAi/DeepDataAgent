package com.linkroa.deepdataagent.datasource.controller.request;


public record ColumnListRequest(
        Long tableId,
        Long schemaId,
        String type
) {
}
