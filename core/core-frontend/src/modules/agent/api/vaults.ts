/**
 * 保管库接口模块（对齐后端 VaultController：/api/v1/cloud/vaults，基线版本 "1+" 覆盖 v1 起全部版本）。
 * <p>保管库 + 凭证（static_bearer / mcp_oauth）双资源：凭证令牌只写不读
 * （响应恒不含明文 / 密文）；列表走统一游标信封（`name` 模糊 + `include_archived` 过滤）；
 * 结构性筛选（metadata 精确匹配 + 名称）归独立搜索端点（只接受 JSON Body）；
 * 凭证支持归档（幂等）、删除（逻辑删，删除响应以 `type` 标识确认）与 POST merge 补丁更新
 * （拉链式提交 `auth` 子树：类型限定字段可轮换、可变字段显式 `null` = 清除，
 * 身份字段 mcp_server_url / refresh 身份字段不可提交）；
 * validate 先按既有 refresh 配置刷新令牌（`refresh.status` 四态：
 * no_refresh_token / succeeded / failed / connect_error），随后发起 MCP `initialize` 有界探测；
 * 探测结果**仅在失败时**以 `mcp_probe` 出现（成功档整键省略），
 * 结论由 `status`（valid / invalid / unknown）承载，响应无 `message` 字段且绝不含明文。
 * 删除受历史 Session 引用守卫（409）。</p>
 */
import { fetchJson, type CursorPageResponse } from '@/shared/api/http';

/** 保管库 DTO（对齐后端 VaultResponse，不含任何凭证明文 / 密文）。 */
export interface VaultDto {
  /** 保管库业务 ID（前缀 vault_） */
  id: string;
  /** 资源类型标识（固定 vault） */
  type: string;
  /** 显示名称（≤255 字符） */
  display_name: string | null;
  /** 元数据键值对象（无 metadata 时为空对象） */
  metadata: Record<string, unknown>;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
  /** 空凭证数组（仅创建响应返回；列表与详情省略该键） */
  credentials?: unknown[];
}

/** 令牌端点鉴权（仅方式本身，密钥只写不读）。 */
export interface VaultTokenEndpointAuthDto {
  /** none / client_secret_basic / client_secret_post */
  type: string;
}

/** 脱敏刷新配置（无 refresh_token 与 client_secret）。 */
export interface VaultCredentialRefreshDto {
  /** 令牌端点分配的客户端 ID（身份字段） */
  client_id: string;
  /** 令牌端点 URL（身份字段） */
  token_endpoint: string;
  resource?: string | null;
  scope?: string | null;
  token_endpoint_auth?: VaultTokenEndpointAuthDto;
}

/** 脱敏鉴权信息（嵌套 auth 形状；无 token / access_token / refresh_token）。 */
export interface VaultCredentialAuthDto {
  /** static_bearer / mcp_oauth */
  type: string;
  mcp_server_url: string;
  expires_at?: string | null;
  refresh?: VaultCredentialRefreshDto;
}

/** 保管库凭证 DTO（对齐后端 VaultCredentialResponse，令牌只写不读不回显）。 */
export interface VaultCredentialDto {
  /** 凭证业务 ID（新写入为 vcred_ 前缀；存量 cr_ 原样返回） */
  id: string;
  /** 资源类型标识（固定 vault_credential） */
  type: string;
  vault_id: string;
  auth: VaultCredentialAuthDto;
  /** 显示名称（当前固定 null） */
  display_name: string | null;
  metadata: Record<string, unknown>;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 删除确认响应（type ∈ vault_deleted / vault_credential_deleted）。 */
export interface VaultDeletedDto {
  id: string;
  type: string;
}

/** 外部 HTTP 响应诊断（后端已脱敏 + 截断；未收到响应时整键省略）。 */
export interface VaultCredentialValidationHttpResponseDto {
  status_code: number;
  content_type: string | null;
  body: string | null;
  body_truncated: boolean;
}

/**
 * 凭证校验结果（对齐后端 VaultCredentialValidationResponse 契约形状，字段为下划线命名）。
 * <p>结论三态：`valid` / `invalid` / `unknown`；刷新四态见 `refresh.status`；
 * `mcp_probe` **仅探测失败时出现**（成功档整键省略，前端以「是否存在」判断）。</p>
 */
export interface VaultCredentialValidationDto {
  credential_id: string;
  vault_id: string;
  type: string;
  /** valid / invalid / unknown */
  status: string;
  validated_at: string;
  has_refresh_token: boolean;
  refresh: {
    /** no_refresh_token / succeeded / failed / connect_error */
    status: string;
    http_response?: VaultCredentialValidationHttpResponseDto;
  };
  mcp_probe?: {
    method: string;
    http_response?: VaultCredentialValidationHttpResponseDto;
  };
}

/** 创建保管库请求（metadata 为键值对象；携带 `credentials` 后端返回 400）。 */
export interface CreateVaultPayload {
  display_name: string;
  metadata?: Record<string, unknown> | null;
}

/**
 * 新增凭证请求（秘密材料嵌套在 `auth` 子树内，逐类型限定密钥字段名）：
 * `static_bearer` 用 `auth.token`、`mcp_oauth` 用 `auth.access_token`；
 * 身份字段 `auth.mcp_server_url` 创建时提供、此后不可修改。
 */
export interface AddVaultCredentialPayload {
  auth: {
    /** 鉴权类型（static_bearer / mcp_oauth） */
    type: string;
    /** 绑定的 MCP 服务器 URL（≤2048 字符，此后不可修改） */
    mcp_server_url: string;
    /** static_bearer 专用：凭证明文 */
    token?: string;
    /** mcp_oauth 专用：访问令牌明文 */
    access_token?: string;
  };
}

/**
 * 凭证更新补丁（merge 语义：未传字段保持原值；`auth.type` 必填且 MUST 与既有类型一致）。
 * <p>身份字段（`mcp_server_url`、`refresh.client_id`、`refresh.token_endpoint`）不可提交，
 * 出现即被后端 400 拒绝；按类型限定可更新字段——`static_bearer` 仅 `token`，
 * `mcp_oauth` 仅 `access_token` / `expires_at` / `refresh.{refresh_token,scope,token_endpoint_auth}`。
 * 显式 `null` 表示清除（`expires_at`、`refresh.scope` 置空；`metadata` 整体 `null` 重置为 `{}`，
 * 单 key 传 `null` 删除该键）。</p>
 */
export interface UpdateVaultCredentialPayload {
  auth?: {
    /** 鉴权类型（提供 auth 时必填；与既有类型不一致 → 400） */
    type: string;
    /** static_bearer 专用：新凭证明文 */
    token?: string;
    /** mcp_oauth 专用：新访问令牌 */
    access_token?: string;
    /** mcp_oauth 专用：到期时间（显式 null = 清除，ISO-8601） */
    expires_at?: string | null;
    /** mcp_oauth 专用：刷新配置补丁（仅可修补既有配置，不可新增） */
    refresh?: {
      refresh_token?: string;
      /** 授权范围（显式 null = 清除） */
      scope?: string | null;
      token_endpoint_auth?: { type: string; client_secret?: string };
    };
  };
  /** 元数据浅合并增量（显式 null = 整体重置为 {}；单 key 传 null = 删除该键） */
  metadata?: Record<string, unknown> | null;
}

/** 保管库列表游标与过滤参数（结构性筛选走 searchVaults）。 */
export interface ListVaultsOptions {
  /** 显示名称模糊过滤（不区分大小写） */
  name?: string;
  /** 归档态：缺省 / false = 仅未归档；true = 不限（含归档） */
  includeArchived?: boolean;
  limit?: number;
  /** 向后翻页游标（与 afterId / beforeId 互斥） */
  page?: string;
  afterId?: string;
  beforeId?: string;
}

/** 保管库搜索参数（只走 JSON Body；响应在游标形状上增加 total，仅首页返回）。 */
export interface SearchVaultsOptions {
  /** metadata 精确匹配条件（多条件 AND；key 1–64 / value ≤512） */
  metadata?: Record<string, unknown>;
  /** 显示名称模糊过滤（不区分大小写） */
  name?: string;
  includeArchived?: boolean;
  limit?: number;
  /** 向后翻页游标（搜索只认 page） */
  page?: string;
}

/** 搜索响应（游标形状 + 仅首页出现的 total）。 */
export interface VaultSearchResponse extends CursorPageResponse<VaultDto> {
  total?: number;
}

/** 凭证列表游标与过滤参数。 */
export interface ListVaultCredentialsOptions {
  /** 绑定的 MCP 服务器 URL 模糊过滤（契约以 name 承载该搜索） */
  name?: string;
  /** 归档态：缺省 / false = 仅未归档；true = 不限（含归档） */
  includeArchived?: boolean;
  limit?: number;
  /** 向后翻页游标（与 afterId / beforeId 互斥） */
  page?: string;
  afterId?: string;
  beforeId?: string;
}

/** 凭证鉴权类型值域。 */
export const VAULT_AUTH_TYPES = ['static_bearer', 'mcp_oauth'] as const;

const VAULTS_API_BASE = '/api/v1/cloud/vaults';

/** 游标 / 归档态查询参数装配（列表与凭证列表共用）。 */
function appendListParams(
  query: URLSearchParams,
  options: { name?: string; includeArchived?: boolean; limit?: number; page?: string; afterId?: string; beforeId?: string },
): void {
  if (options.name) {
    query.set('name', options.name);
  }
  if (options.includeArchived != null) {
    query.set('include_archived', String(options.includeArchived));
  }
  if (options.limit != null) {
    query.set('limit', String(options.limit));
  }
  // 后端 resolveCursor：page 与 after_id / before_id 互斥（同时提交 400），故 page 优先
  if (options.page) {
    query.set('page', options.page);
  } else if (options.afterId) {
    query.set('after_id', options.afterId);
  } else if (options.beforeId) {
    query.set('before_id', options.beforeId);
  }
}

/** 游标查询保管库列表（创建时间降序，无 total）。 */
export function listVaults(options: ListVaultsOptions = {}): Promise<CursorPageResponse<VaultDto>> {
  const query = new URLSearchParams();
  appendListParams(query, options);
  const qs = query.toString();
  return fetchJson<CursorPageResponse<VaultDto>>(qs ? `${VAULTS_API_BASE}?${qs}` : VAULTS_API_BASE);
}

/** 搜索保管库（筛选条件只从 JSON Body 取；同请求 URL Query 被忽略）。 */
export function searchVaults(options: SearchVaultsOptions = {}): Promise<VaultSearchResponse> {
  const body: Record<string, unknown> = {};
  if (options.metadata) {
    body.metadata = options.metadata;
  }
  if (options.name) {
    body.name = options.name;
  }
  if (options.includeArchived != null) {
    body.include_archived = options.includeArchived;
  }
  if (options.limit != null) {
    body.limit = options.limit;
  }
  if (options.page) {
    body.page = options.page;
  }
  return fetchJson<VaultSearchResponse>(`${VAULTS_API_BASE}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** 创建保管库（响应含固定为空的 credentials[]）。 */
export function createVault(payload: CreateVaultPayload): Promise<VaultDto> {
  return fetchJson<VaultDto>(VAULTS_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 保管库详情。 */
export function getVault(vaultId: string): Promise<VaultDto> {
  return fetchJson<VaultDto>(`${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}`);
}

/** 归档保管库（幂等：已归档返回现状；归档后凭证不再参与运行时注入）。 */
export async function archiveVault(vaultId: string): Promise<void> {
  await fetchJson<void>(`${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/archive`, { method: 'POST' });
}

/** 删除保管库（级联逻辑删其凭证；仍有历史 Session 引用时后端拒绝 409）。 */
export function deleteVault(vaultId: string): Promise<VaultDeletedDto> {
  return fetchJson<VaultDeletedDto>(`${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}`, { method: 'DELETE' });
}

/** 新增凭证（authType+MCP URL 精确匹配去重；令牌只写不读）。 */
export function addVaultCredential(
  vaultId: string,
  payload: AddVaultCredentialPayload,
): Promise<VaultCredentialDto> {
  return fetchJson<VaultCredentialDto>(`${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 游标查询凭证列表（URL 模糊 + 归档态过滤）。 */
export function listVaultCredentials(
  vaultId: string,
  options: ListVaultCredentialsOptions = {},
): Promise<CursorPageResponse<VaultCredentialDto>> {
  const query = new URLSearchParams();
  appendListParams(query, options);
  const qs = query.toString();
  const base = `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials`;
  return fetchJson<CursorPageResponse<VaultCredentialDto>>(qs ? `${base}?${qs}` : base);
}

/** 凭证详情。 */
export function getVaultCredential(vaultId: string, credentialId: string): Promise<VaultCredentialDto> {
  return fetchJson<VaultCredentialDto>(
    `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
  );
}

/** 归档凭证（幂等；归档后不再注入新 Session，不可再更新 / 校验）。 */
export async function archiveVaultCredential(vaultId: string, credentialId: string): Promise<void> {
  await fetchJson<void>(
    `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}/archive`,
    { method: 'POST' },
  );
}

/** 删除凭证（逻辑删；删除后列表 / 详情不可见，不再参与会话挂载鉴权）。 */
export function deleteVaultCredential(
  vaultId: string,
  credentialId: string,
): Promise<VaultDeletedDto> {
  return fetchJson<VaultDeletedDto>(
    `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
    { method: 'DELETE' },
  );
}

/** 更新凭证（POST merge 补丁：提交 auth 子树，未传字段保持原值；身份字段不可修改）。 */
export function updateVaultCredential(
  vaultId: string,
  credentialId: string,
  payload: UpdateVaultCredentialPayload,
): Promise<VaultCredentialDto> {
  return fetchJson<VaultCredentialDto>(
    `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  );
}

/** 校验 active `mcp_oauth` 凭证（先刷新后探测；static_bearer 或已归档凭证后端返回 409）。 */
export function validateVaultCredential(vaultId: string, credentialId: string): Promise<VaultCredentialValidationDto> {
  return fetchJson<VaultCredentialValidationDto>(
    `${VAULTS_API_BASE}/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}/mcp_oauth_validate`,
    { method: 'POST' },
  );
}