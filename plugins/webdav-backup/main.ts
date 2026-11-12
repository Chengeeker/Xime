// =====================================================================
// WebDAV 云备份插件（QuickJS 脚本）
//
// 备份包的生成与恢复由宿主（BackupManager）完成，本插件只承载 WebDAV
// 传输协议：PUT / GET / PROPFIND / DELETE / MKCOL，认证用 Basic Auth
// （host.crypto.base64），配置经 host.config 存取（密码为宿主加密存储）。
//
// 大数据走宿主流式句柄（v3.1 契约）：
//   - 上传：backup.push 收到 {name, size, archiveId}，archiveId 是宿主已落盘的
//     备份包句柄，直接交给 host.http.upload 流式 PUT（整包不进 JS 堆）
//   - 下载：host.http.download 让宿主把响应体流式落盘，返回 blobId 给宿主恢复流程
//
// 备份条目 id = 服务器上的绝对路径（已解码），pull/delete 时原样回传。
//
// JS 约定（对齐 QuickJS 沙箱）：
//   - 二进制一律 Uint8Array；host.crypto.base64 / host.http.request 的 body
//     只接受字节，字符串先经 TextEncoder 转 UTF-8（JS 字符串直接传桥会变空）。
//   - JSON 用原生 JSON（宿主不再提供 host.json）。
//   - URL 编解码与 PROPFIND XML 解析拆在 libs/（构建时内联为单文件）。
// =====================================================================

import { encodePath } from './libs/url-codec';
import { parseBackupList } from './libs/webdav-xml';

// ------------------------------------------------------------------
// 配置
// ------------------------------------------------------------------

interface BackupConfig {
  url: string;
  username: string;
  password: string;
  remotePath: string;
}

// resolveBase 结果；error 非空时其余字段不可用
interface ResolvedBase {
  origin: string;
  base: string;
  basePath: string;
  davRoot: string;
  headers: Record<string, string>;
  cfg: BackupConfig;
  error: string | null;
}

// backup.push 结果（id 为上传成功后的远端条目 id，宿主 BackupResult 可读）
interface PushBackupResult {
  ok: boolean;
  id?: string;
  message?: string;
}

function getConfig(): BackupConfig {
  return {
    url: host.config.get('url') || '',
    username: host.config.get('username') || '',
    password: host.config.get('password') || '',
    remotePath: host.config.get('remote_path') || '/xime_backup',
  };
}

// 归一化：url 去尾斜杠，remotePath 去首尾斜杠。
// 返回：
//   base     完整 URL（origin + url 路径前缀 + remotePath），PROPFIND/PUT 用
//   basePath 服务器绝对路径（如坚果云为 /dav/xime_backup），条目 id / 列表过滤用
//   davRoot  origin + url 路径前缀（如 https://dav.jianguoyun.com/dav），MKCOL 起点用
//   origin   scheme + host（pull/delete 用：条目 id 已是含路径前缀的绝对路径）
//   headers  认证头
function resolveBase(): ResolvedBase {
  const cfg = getConfig();
  const result: ResolvedBase = {
    origin: '', base: '', basePath: '', davRoot: '', headers: {}, cfg, error: null,
  };
  if (cfg.url === '') { result.error = '请先填写服务器地址'; return result; }
  if (cfg.username === '') { result.error = '请先填写账号'; return result; }
  let url = cfg.url;
  if (!/^https?:\/\//.test(url)) url = 'https://' + url;
  url = url.replace(/\/+$/, '');
  if (!/^https:\/\/[^/@?#]+(?:\/[^?#]*)?$/.test(url)) { result.error = '请使用不含账号或查询参数的 HTTPS 地址'; return result; }
  const m = url.match(/^https?:\/\/[^/]+/);
  const origin = m ? m[0] : url;
  const urlPath = url.substring(origin.length); // DAV 根的路径前缀（坚果云为 /dav）
  const remote = cfg.remotePath.replace(/^\/+/, '').replace(/\/+$/, '');
  result.origin = origin;
  result.base = origin + urlPath + '/' + remote;
  result.basePath = urlPath + '/' + remote;
  result.davRoot = origin + urlPath;
  if (cfg.password !== '') {
    const b64 = host.crypto.base64(new TextEncoder().encode(cfg.username + ':' + cfg.password));
    if (!b64) { result.error = 'Basic 认证编码失败'; return result; }
    result.headers['Authorization'] = 'Basic ' + b64;
  }
  return result;
}

// ------------------------------------------------------------------
// WebDAV 操作
// ------------------------------------------------------------------

// 逐级 MKCOL 创建远端目录（起点为 DAV 根，即 url 的路径前缀；已存在 405 视为成功）
async function ensureCollection(cfg: BackupConfig, headers: Record<string, string>): Promise<string | null> {
  const { davRoot } = resolveBase();
  if (!davRoot) return '创建备份目录失败：服务器地址无效';
  const remote = cfg.remotePath.replace(/^\/+/, '').replace(/\/+$/, '');
  let cur = davRoot;
  for (const seg of remote.split('/').filter((s) => s !== '')) {
    cur = cur + '/' + encodePath(seg);
    try {
      const res = await host.http.request('MKCOL', cur, headers, null);
      if (res.status !== 201 && res.status !== 405 && res.status !== 200) {
        return '创建备份目录失败（HTTP ' + res.status + '）' + statusDetail(res);
      }
    } catch (e) {
      return '创建备份目录请求失败，请检查网络与服务器配置';
    }
  }
  return null;
}

function statusDetail(res: XimeHttpResponse): string {
  const errors: Record<number,string> = {401:'认证失败',403:'权限不足',404:'该路径不存在',429:'请求过于频繁'};
  return ': ' + (errors[res.status] || (res.status >= 500 ? '服务器错误' : res.status >= 300 && res.status < 400 ? '此操作不允许自动跳转' : '不支持的响应'));
}

// 仅供宿主 JVM 回归测试使用（JsEncodePathTest）；非插件契约字段，经 spread 并入，
// 不参与 definePlugin 的规格约束收窄
const testHooks = { _encodePath: encodePath };

const plugin = definePlugin({
  // ------------------------------------------------------------------
  // backup 扩展点（宿主调用路径 backup.*）
  // ------------------------------------------------------------------

  backup: {
    // 连接测试；返回错误消息，null 表示成功
    async test(): Promise<string | null> {
      const { base, headers, cfg, error } = resolveBase();
      if (error) return error;
      headers['Depth'] = '0';
      try {
        const res = await host.http.request('PROPFIND', encodePath(base), headers, null);
        if (res.status === 207 || res.status === 200) return null;
        if (res.status === 401) return '认证失败（401），请检查账号与密码';
        if (res.status === 404) {
          // 目录不存在不算失败（首次备份会自动创建）
          return await ensureCollection(cfg, headers);
        }
        return '服务器返回 HTTP ' + res.status + statusDetail(res);
      } catch (e) {
        return '连接测试请求失败，请检查网络与服务器配置';
      }
    },

    // 上传备份包（archiveId 为宿主 blob 句柄，整包经 host.http.upload 流式发送）
    async push(args: XimeBackupPushArgs): Promise<PushBackupResult> {
      const name = args.name || '';
      const archiveId = args.archiveId || '';
      if (name === '') return { ok: false, message: '备份包名为空' };
      if (archiveId === '') return { ok: false, message: '备份包为空' };

      const { base, basePath, headers, cfg, error } = resolveBase();
      if (error) return { ok: false, message: error };

      headers['Content-Type'] = 'application/octet-stream';
      headers['Overwrite'] = 'T';
      const url = encodePath(base) + '/' + encodePath(name);

      try {
        let res = await host.http.upload('PUT', url, headers, archiveId);
        if (res.status === 409 || res.status === 404) {
          // 父目录不存在：逐级创建后重试一次
          // （标准 DAV 报 409 Conflict；Alist/Nextcloud 等报 404）
          const creationError = await ensureCollection(cfg, headers);
          if (creationError) return { ok: false, message: creationError };
          res = await host.http.upload('PUT', url, headers, archiveId);
        }
        if (res.status === 200 || res.status === 201 || res.status === 204) {
          return { ok: true, id: basePath + '/' + name };
        }
        if (res.status === 401) return { ok: false, message: '认证失败（401），请检查账号与密码' };
        return { ok: false, message: '上传失败（HTTP ' + res.status + '）' + statusDetail(res) };
      } catch (e) {
        return { ok: false, message: '上传请求失败，请检查网络与服务器配置' };
      }
    },

    // 下载备份包：宿主流式落盘，返回 blob 句柄（宿主恢复流程消费，失败返回 null）
    async pull(id: string): Promise<string | null> {
      if (!id || !id.startsWith('/') || id.startsWith('//')) throw new Error('备份条目路径无效');
      const { origin, headers, error } = resolveBase();
      if (error) throw new Error(error);
      const res = await host.http.download('GET', origin + encodePath(id), headers);
      const errors: Record<number,string> = {401:'认证失败',403:'读取权限不足',404:'该路径没有备份',429:'请求过于频繁'};
      if (res.status !== 200) throw new Error('备份下载失败：' + (errors[res.status] || (res.status >= 500 ? '服务器错误' : '不支持的响应')) + '（HTTP ' + res.status + '）');
      if (!res.blobId) throw new Error('备份下载内容为空');
      return res.blobId;
    },

    // 列出远端备份
    async list(): Promise<XimeBackupItem[] | null> {
      const { base, basePath, headers, error } = resolveBase();
      if (error) return null;
      headers['Depth'] = '1';
      try {
        const res = await host.http.request('PROPFIND', encodePath(base), headers, null);
        if (res.status !== 207 && res.status !== 200) return null;
        return parseBackupList(res.text || '', basePath);
      } catch (e) {
        host.logError('备份列表请求失败');
        return null;
      }
    },

    // 删除远端备份
    async remove(id: string): Promise<boolean> {
      if (!id || id === '') return false;
      const { origin, headers, error } = resolveBase();
      if (error) return false;
      try {
        const res = await host.http.request('DELETE', origin + encodePath(id), headers, null);
        // 404：远端已不存在，视为删除成功
        return res.status === 204 || res.status === 200 || res.status === 404;
      } catch (e) {
        host.logError('删除备份请求失败');
        return false;
      }
    },
  },

  // ------------------------------------------------------------------
  // 配置表单（UiNode 契约）
  // ------------------------------------------------------------------

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          type: 'text', key: 'url', label: '服务器地址', required: true,
          placeholder: 'https://dav.jianguoyun.com/dav/',
          helpText: 'WebDAV 根地址；坚果云为 https://dav.jianguoyun.com/dav/',
        },
        { type: 'text', key: 'username', label: '账号', required: true },
        {
          type: 'secret', key: 'password', label: '密码 / 应用密码', required: true,
          helpText: '坚果云请到网页端「安全选项」生成应用密码',
        },
        {
          type: 'text', key: 'remote_path', label: '备份目录',
          defaultValue: '/xime_backup',
          helpText: '远端目录，不存在时自动创建',
        },
        { type: 'button', key: 'testConnection', label: '测试连接' },
      ];
    },
  },

  ...testHooks,
});

export default plugin;
