import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { MessagePlugin } from 'tdesign-vue-next';

/**
 * 通用 API 响应结构
 */
export interface ApiResponse<T> {
  success: boolean;
  code?: string;
  message?: string;
  data?: T;
}

/**
 * 分页响应结构
 */
export interface PaginatedResponse<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

/**
 * Axios 实例配置
 */
const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * 请求拦截器
 */
http.interceptors.request.use(
  (config) => {
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

/**
 * 响应拦截器 - 统一错误处理
 */
http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<any>>) => {
    const { data } = response;
    
    // 如果业务逻辑返回失败，显示错误消息
    if (data && data.success === false) {
      MessagePlugin.error(data.message || '请求失败');
      return Promise.reject(new Error(data.message));
    }
    
    return response;
  },
  (error) => {
    // 网络错误或超时
    if (error.response) {
      const { status, data } = error.response;
      const message = data?.message || error.message;
      
      switch (status) {
        case 400:
          MessagePlugin.error(`请求错误: ${message}`);
          break;
        case 401:
          MessagePlugin.error('未授权，请重新登录');
          break;
        case 403:
          MessagePlugin.error('拒绝访问');
          break;
        case 404:
          MessagePlugin.error('请求的资源不存在');
          break;
        case 500:
          MessagePlugin.error(`服务器错误: ${message}`);
          break;
        default:
          MessagePlugin.error(`请求失败: ${message}`);
      }
    } else if (error.code === 'ECONNABORTED') {
      MessagePlugin.error('请求超时，请稍后重试');
    } else {
      MessagePlugin.error('网络错误，请检查网络连接');
    }
    
    return Promise.reject(error);
  }
);

/**
 * 封装 GET 请求
 */
export async function get<T>(url: string, params?: any): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params });
  return response.data.data as T;
}

/**
 * 封装 POST 请求
 */
export async function post<T>(url: string, data?: any): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, data);
  return response.data.data as T;
}

/**
 * 封装 PUT 请求
 */
export async function put<T>(url: string, data?: any): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, data);
  return response.data.data as T;
}

/**
 * 封装 DELETE 请求
 */
export async function del<T>(url: string): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url);
  return response.data.data as T;
}

export default http;
