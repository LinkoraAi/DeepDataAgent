// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { ApiSchemaResponse, ParsedFieldResponse } from './api/apiSchema';
import {
  apiSchemaToDraft,
  buildParsedFieldTree,
  checkedToFieldPayload,
  commentRemaining,
  createEmptyApiSchemaDraft,
  draftToApiSchemaRequest,
  draftToParseRequest,
  flattenFieldTree,
  normalizePgSchema,
  normalizeSubType,
  portForSubType,
  rootPathHint,
  subTypeOptions,
  validateApiSchemaDraft,
  validateComment,
  validateDescription,
  validateJdbcForm,
  validateName,
  validatePgSchema,
  validatePort,
} from './mappers';
import type { ApiSchemaDraft } from './mappers';

/** 构造一个最小可用的合法 API 草稿（各用例在此基础上按需覆盖）。 */
function baseDraft(overrides: Partial<ApiSchemaDraft> = {}): ApiSchemaDraft {
  return { ...createEmptyApiSchemaDraft(), name: 'order_list', url: 'https://api.demo.com/orders', ...overrides };
}

/** 构造一个合法 API 表响应，供编辑回显用例使用。 */
function schemaResponse(overrides: Partial<ApiSchemaResponse> = {}): ApiSchemaResponse {
  return {
    id: 7,
    connectionId: 3,
    name: 'order_list',
    url: 'https://api.demo.com/orders',
    method: 'GET',
    headers: {},
    params: {},
    body: null,
    bodyType: null,
    jsonPathConfig: '$.data.list.*',
    timeout: 180,
    retryCount: 0,
    authConfig: { authType: 'BASIC_AUTH', username: 'u1', credentialConfigured: true },
    paginationConfig: {
      paginationType: 'PAGE_BASED',
      pageParamName: 'page',
      sizeParamName: 'size',
      totalCountJsonPath: '$.total',
      pageSize: 20,
      maxPages: 100,
    },
    preOperationConfigs: [],
    createdAt: '2026-01-01T00:00:00+08:00',
    updatedAt: '2026-01-02T00:00:00+08:00',
    ...overrides,
  };
}

describe('validateName', () => {
  it('should 通过_when 中文或字母开头且含合法字符', () => {
    // given & when & then
    expect(validateName('订单_2024-A')).toBe('');
    expect(validateName('abc')).toBe('');
  });

  it('should 报空_when 名称为空白', () => {
    expect(validateName('   ')).toBe('名称不能为空');
    expect(validateName(null)).toBe('名称不能为空');
  });

  it('should 拒绝_when 以数字或非法符号开头', () => {
    expect(validateName('1abc')).toContain('开头');
    expect(validateName('_abc')).toContain('开头');
  });

  it('should 拒绝_when 超过 100 字符', () => {
    const tooLong = `a${'b'.repeat(100)}`;
    expect(validateName(tooLong)).toContain('开头');
  });
});

describe('validatePort', () => {
  it('should 通过_when 端口在合法区间', () => {
    expect(validatePort(3306)).toBe('');
    expect(validatePort(1)).toBe('');
    expect(validatePort(65535)).toBe('');
  });

  it('should 拒绝_when 端口越界或非整数', () => {
    expect(validatePort(0)).toContain('1~65535');
    expect(validatePort(65536)).toContain('1~65535');
    expect(validatePort(3306.5)).toBe('端口必须为整数');
    expect(validatePort(undefined)).toBe('端口必须为整数');
  });
});

describe('portForSubType', () => {
  it('should 返回 JDBC 默认端口_when 子类型为 mysql/clickhouse/postgresql', () => {
    expect(portForSubType('MYSQL')).toBe(3306);
    expect(portForSubType('clickhouse')).toBe(8123);
    expect(portForSubType('POSTGRESQL')).toBe(5432);
  });

  it('should 返回 null_when 子类型为 API 或空', () => {
    expect(portForSubType('API')).toBeNull();
    expect(portForSubType(null)).toBeNull();
  });
});

describe('normalizeSubType', () => {
  it('should 大写归一_when 传入小写子类型', () => {
    expect(normalizeSubType('postgresql')).toBe('POSTGRESQL');
  });

  it('should 返回 null_when 传入非法或空白', () => {
    expect(normalizeSubType('')).toBeNull();
    expect(normalizeSubType('ORACLE')).toBeNull();
  });
});

describe('normalizePgSchema', () => {
  it('should 回落 public_when 空白', () => {
    expect(normalizePgSchema('  ')).toBe('public');
  });

  it('should trim_when 传入带空格 schema', () => {
    expect(normalizePgSchema(' my_schema ')).toBe('my_schema');
  });
});

describe('validatePgSchema', () => {
  it('should 跳过_when 非 PostgreSQL 子类型', () => {
    expect(validatePgSchema('pg_catalog', 'MYSQL')).toBe('');
  });

  it('should 跳过_when PostgreSQL 但 schema 留空', () => {
    expect(validatePgSchema('', 'POSTGRESQL')).toBe('');
  });

  it('should 拒绝_when 命中系统 schema 精确名', () => {
    expect(validatePgSchema('information_schema', 'POSTGRESQL')).toContain('系统 schema');
    expect(validatePgSchema('pg_catalog', 'POSTGRESQL')).toContain('系统 schema');
  });

  it('should 拒绝_when 命中 pg_temp / pg_toast 前缀变体', () => {
    expect(validatePgSchema('pg_temp_3', 'POSTGRESQL')).toContain('系统 schema');
    expect(validatePgSchema('pg_toast_wal', 'POSTGRESQL')).toContain('系统 schema');
  });

  it('should 通过_when 业务 schema', () => {
    expect(validatePgSchema('public', 'POSTGRESQL')).toBe('');
    expect(validatePgSchema('app', 'POSTGRESQL')).toBe('');
  });
});

describe('validateComment 与计数', () => {
  it('should 通过_when 注释为空或恰好 150', () => {
    expect(validateComment('')).toBe('');
    expect(validateComment('x'.repeat(150))).toBe('');
  });

  it('should 拒绝_when 超过 150 且计数为负', () => {
    expect(validateComment('x'.repeat(151))).toContain('150');
    expect(commentRemaining('x'.repeat(151))).toBe(-1);
    expect(commentRemaining('abc')).toBe(147);
  });
});

describe('validateDescription', () => {
  it('should 拒绝_when 描述超过 500', () => {
    expect(validateDescription('d'.repeat(501))).toContain('500');
    expect(validateDescription('d'.repeat(500))).toBe('');
  });
});

describe('rootPathHint', () => {
  it('should 不提示_when 根路径为 $ 或以 .* 结尾', () => {
    expect(rootPathHint('$')).toBe('');
    expect(rootPathHint('$.data.list.*')).toBe('');
  });

  it('should 不提示_when 根路径为空', () => {
    expect(rootPathHint('')).toBe('');
  });

  it('should 提示_when 指向数组但未以 .* 结尾', () => {
    expect(rootPathHint('$.data.list')).toContain('.*');
  });
});

describe('validateJdbcForm', () => {
  it('should 通过_when JDBC 必填项齐全', () => {
    expect(validateJdbcForm({ host: 'h', port: 3306, database: 'd', username: 'u' })).toEqual({});
  });

  it('should 逐项报错_when 必填缺失或端口越界', () => {
    const errs = validateJdbcForm({ host: '', port: 70000, database: '', username: '' });
    expect(errs.host).toBe('主机名不能为空');
    expect(errs.port).toContain('1~65535');
    expect(errs.database).toBe('数据库名不能为空');
    expect(errs.username).toBe('用户名不能为空');
  });

  it('should 报 schema_when PostgreSQL 选了系统 schema', () => {
    const errs = validateJdbcForm({ host: 'h', port: 5432, database: 'd', username: 'u', subType: 'POSTGRESQL', schema: 'pg_catalog' });
    expect(errs.schema).toContain('系统 schema');
  });
});

describe('validateApiSchemaDraft', () => {
  const createCtx = { mode: 'create' } as const;

  it('should 无错误_when 合法新建草稿', () => {
    expect(validateApiSchemaDraft(baseDraft(), createCtx)).toEqual({});
  });

  it('should 要求 name/url_when 两者为空', () => {
    const errs = validateApiSchemaDraft(baseDraft({ name: '', url: '' }), createCtx);
    expect(errs.name).toBeDefined();
    expect(errs.url).toBeDefined();
  });

  it('should 要求账密_when BASIC 且新建密码为空', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({ auth: { authType: 'BASIC_AUTH', username: '', password: '', credentialConfigured: false } }),
      createCtx,
    );
    expect(errs.authUsername).toBeDefined();
    expect(errs.authPassword).toBeDefined();
  });

  it('should 允许留空密码_when 编辑态凭证已配置', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({ auth: { authType: 'BASIC_AUTH', username: 'u', password: '', credentialConfigured: true } }),
      { mode: 'edit' },
    );
    expect(errs.authPassword).toBeUndefined();
  });

  it('should 要求分页必填项_when PAGE_BASED 缺参数名与范围', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({
        pagination: { paginationType: 'PAGE_BASED', pageParamName: '', sizeParamName: '', pageSize: 0, maxPages: 20000, totalCountJsonPath: '' },
      }),
      createCtx,
    );
    expect(errs.pageParamName).toBeDefined();
    expect(errs.sizeParamName).toBeDefined();
    expect(errs.pageSize).toBeDefined();
    expect(errs.maxPages).toBeDefined();
  });

  it('should 不校验分页参数_when NONE 分页', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({ pagination: { paginationType: 'NONE', pageParamName: '', sizeParamName: '', pageSize: 0, maxPages: 0, totalCountJsonPath: '' } }),
      createCtx,
    );
    expect(errs.pageSize).toBeUndefined();
    expect(errs.pageParamName).toBeUndefined();
  });

  it('should 拒绝_when 重试越界或超时非正', () => {
    const errs = validateApiSchemaDraft(baseDraft({ timeout: 0, retryCount: 6 }), createCtx);
    expect(errs.timeout).toBeDefined();
    expect(errs.retryCount).toBeDefined();
  });

  it('should 校验启用前置操作_when 缺 url 或映射不完整', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({
        preOperations: [
          { enabled: true, url: '', method: 'POST', headers: {}, params: {}, body: '', bodyType: 'JSON', paramMappings: [{ paramName: 't', paramLocation: 'header', jsonPath: '' }] },
        ],
      }),
      createCtx,
    );
    expect(errs['preOperations.0.url']).toBeDefined();
    expect(errs['preOperations.0.paramMappings']).toContain('JsonPath');
  });

  it('should 跳过未启用前置操作_when enabled=false', () => {
    const errs = validateApiSchemaDraft(
      baseDraft({
        preOperations: [{ enabled: false, url: '', method: 'GET', headers: {}, params: {}, body: '', bodyType: 'JSON', paramMappings: [] }],
      }),
      createCtx,
    );
    expect(errs['preOperations.0.url']).toBeUndefined();
  });
});

describe('buildParsedFieldTree / flattenFieldTree / checkedToFieldPayload', () => {
  // given：两层字段树，data 下含 id(number) 与 nested.name(string)
  const parsed: ParsedFieldResponse[] = [
    {
      originalName: 'data',
      jsonPath: '$.data',
      fieldType: 'object',
      children: [
        { originalName: 'id', jsonPath: '$.data.id', fieldType: 'number', children: [] },
        {
          originalName: 'nested',
          jsonPath: '$.data.nested',
          fieldType: 'object',
          children: [{ originalName: 'name', jsonPath: '$.data.nested.name', fieldType: 'string', children: [] }],
        },
      ],
    },
  ];

  it('should 递归构建 t-tree 节点_when 转换字段树', () => {
    // when
    const tree = buildParsedFieldTree(parsed);
    // then
    expect(tree[0].value).toBe('$.data');
    expect(tree[0].label).toContain('object');
    expect(tree[0].children[1].children[0].jsonPath).toBe('$.data.nested.name');
  });

  it('should 叶子 children 为空_when 无子字段', () => {
    expect(buildParsedFieldTree(parsed)[0].children[0].children).toEqual([]);
  });

  it('should 返回空数组_when 入参为空', () => {
    expect(buildParsedFieldTree(null)).toEqual([]);
  });

  it('should 扁平索引覆盖全部节点_when 展开字段树', () => {
    const index = flattenFieldTree(buildParsedFieldTree(parsed));
    expect(index.size).toBe(4);
    expect(index.get('$.data.nested.name')?.fieldType).toBe('string');
  });

  it('should 仅勾选节点进入载荷且 displayName 取原始名_when 勾选转载荷', () => {
    const index = flattenFieldTree(buildParsedFieldTree(parsed));
    const payload = checkedToFieldPayload(['$.data.id', '$.data.nested.name', '$.not.exists'], index);
    expect(payload).toHaveLength(2);
    expect(payload[0].displayName).toBe('id');
    expect(payload[0].description).toBe('');
    expect(payload[1].jsonPath).toBe('$.data.nested.name');
  });

  it('should 返回空载荷_when 未勾选', () => {
    const index = flattenFieldTree(buildParsedFieldTree(parsed));
    expect(checkedToFieldPayload([], index)).toEqual([]);
  });
});

describe('apiSchemaToDraft', () => {
  it('should 回显非敏感配置且密码留空_when 编辑态映射', () => {
    // when
    const draft = apiSchemaToDraft(schemaResponse(), []);
    // then
    expect(draft.id).toBe(7);
    expect(draft.rootPath).toBe('$.data.list.*');
    expect(draft.auth.authType).toBe('BASIC_AUTH');
    expect(draft.auth.username).toBe('u1');
    expect(draft.auth.password).toBe('');
    expect(draft.auth.credentialConfigured).toBe(true);
    expect(draft.pagination.paginationType).toBe('PAGE_BASED');
    expect(draft.pagination.pageParamName).toBe('page');
  });

  it('should 回落默认值_when 响应缺失可选配置', () => {
    const draft = apiSchemaToDraft(
      schemaResponse({ authConfig: null, paginationConfig: null, timeout: null, retryCount: null, jsonPathConfig: null }),
      [],
    );
    expect(draft.timeout).toBe(180);
    expect(draft.retryCount).toBe(0);
    expect(draft.rootPath).toBe('$');
    expect(draft.auth.authType).toBe('NO_AUTH');
    expect(draft.pagination.paginationType).toBe('NONE');
  });

  it('should 保留字段映射为载荷_when 携带已有字段', () => {
    const draft = apiSchemaToDraft(schemaResponse(), [
      { id: 1, apiSchemaId: 7, originalName: 'id', displayName: '编号', jsonPath: '$.id', fieldType: 'number', description: '主键' },
    ]);
    expect(draft.fields).toHaveLength(1);
    expect(draft.fields[0].displayName).toBe('编号');
  });
});

describe('draftToApiSchemaRequest', () => {
  it('should 转换字段命名_when rootPath 与分页参数名映射到请求侧', () => {
    const draft = baseDraft({
      rootPath: '$.data.*',
      pagination: { paginationType: 'PAGE_BASED', pageParamName: 'page', sizeParamName: 'size', pageSize: 50, maxPages: 200, totalCountJsonPath: '$.total' },
    });
    const req = draftToApiSchemaRequest(draft, { mode: 'create' });
    expect(req.jsonPathConfig).toBe('$.data.*');
    expect(req.pageNumberParamName).toBe('page');
    expect(req.pageSizeParamName).toBe('size');
    expect(req.pageSize).toBe(50);
    expect(req.maxPages).toBe(200);
  });

  it('should 省略鉴权与分页字段_when NO_AUTH 且 NONE', () => {
    const req = draftToApiSchemaRequest(baseDraft(), { mode: 'create' });
    expect(req.authType).toBe('NO_AUTH');
    expect(req.authUsername).toBeUndefined();
    expect(req.pageNumberParamName).toBeUndefined();
    expect(req.fields).toBeUndefined();
  });

  it('should 省略密码_when 编辑态凭证已配置且留空', () => {
    const req = draftToApiSchemaRequest(
      baseDraft({ auth: { authType: 'BASIC_AUTH', username: 'u', password: '', credentialConfigured: true } }),
      { mode: 'edit' },
    );
    expect(req.authUsername).toBe('u');
    expect(req.authPassword).toBeUndefined();
  });

  it('should 携带新密码_when 编辑态填写了密码', () => {
    const req = draftToApiSchemaRequest(
      baseDraft({ auth: { authType: 'BASIC_AUTH', username: 'u', password: 'newpass', credentialConfigured: true } }),
      { mode: 'edit' },
    );
    expect(req.authPassword).toBe('newpass');
  });
});

describe('draftToParseRequest', () => {
  it('should 保持 rootPath 并使用响应侧分页命名_when 构造试解析请求', () => {
    const draft = baseDraft({
      rootPath: '$.rows.*',
      auth: { authType: 'BASIC_AUTH', username: 'u', password: 'p', credentialConfigured: false },
      pagination: { paginationType: 'PAGE_BASED', pageParamName: 'pn', sizeParamName: 'ps', pageSize: 30, maxPages: 50, totalCountJsonPath: '$.c' },
    });
    const req = draftToParseRequest(draft, 99);
    expect(req.connectionId).toBe(99);
    expect(req.rootPath).toBe('$.rows.*');
    expect(req.pageParamName).toBe('pn');
    expect(req.sizeParamName).toBe('ps');
    expect(req.authPassword).toBe('p');
  });
});

describe('subTypeOptions', () => {
  it('should 回落静态四类_when 后端返回空', () => {
    expect(subTypeOptions([])).toHaveLength(4);
    expect(subTypeOptions(null)).toHaveLength(4);
  });

  it('should 原样返回_when 后端提供类型', () => {
    const types = subTypeOptions([{ type: 'API', subType: 'API', name: 'API', category: 'API', defaultPort: null }]);
    expect(types).toHaveLength(1);
  });
});
