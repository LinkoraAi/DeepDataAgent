/**
 * 浏览器端 SHA-256（WebCrypto），用于知识库文档上传前判重预检。
 * 输出 64 位小写十六进制，与后端 DocumentValidator 的 contentHash 口径一致。
 */

/** 计算字节数组的 SHA-256 十六进制摘要（小写）。 */
export async function sha256Hex(data: ArrayBuffer | Uint8Array): Promise<string> {
  // slice() 归一为持有 ArrayBuffer 的独立副本（TS 5.9 BufferSource 泛型约束）
  const bytes = data instanceof Uint8Array ? data.slice() : new Uint8Array(data);
  const digest = await crypto.subtle.digest('SHA-256', bytes.buffer as ArrayBuffer);
  return hexLower(digest);
}

/** 计算 File 的 SHA-256 十六进制摘要（小写）。 */
export async function sha256HexOfFile(file: File): Promise<string> {
  return sha256Hex(await file.arrayBuffer());
}

/** ArrayBuffer → 小写十六进制串。 */
export function hexLower(buffer: ArrayBuffer): string {
  const view = new Uint8Array(buffer);
  let out = '';
  for (const byte of view) {
    out += byte.toString(16).padStart(2, '0');
  }
  return out;
}
