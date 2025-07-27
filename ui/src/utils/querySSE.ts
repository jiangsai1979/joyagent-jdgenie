import { fetchEventSource, EventSourceMessage } from '@microsoft/fetch-event-source';

const customHost = window.SERVICE_BASE_URL || 'http://127.0.0.1:8080';
const DEFAULT_SSE_URL = `${customHost}/web/api/v1/gpt/queryAgentStreamIncr`;

const SSE_HEADERS = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-cache',
  'Connection': 'keep-alive',
  'Accept': 'text/event-stream',
};

interface SSEConfig {
  body: any;
  handleMessage: (data: any) => void;
  handleError: (error: Error) => void;
  handleClose: () => void;
}

/**
 * 创建服务器发送事件（SSE）连接
 * @param config SSE 配置
 * @param url 可选的自定义 URL
 */
export default (config: SSEConfig, url: string = DEFAULT_SSE_URL): void => {
  const { body = null, handleMessage, handleError, handleClose } = config;

  // 添加详细调试日志
  console.log('🔵 [DEBUG] SSE 连接开始');
  console.log('🔵 [DEBUG] SSE URL:', url);
  console.log('🔵 [DEBUG] SSE 请求体:', JSON.stringify(body, null, 2));
  console.log('🔵 [DEBUG] SSE 请求头:', SSE_HEADERS);

  fetchEventSource(url, {
    method: 'POST',
    credentials: 'include',
    headers: SSE_HEADERS,
    body: JSON.stringify(body),
    openWhenHidden: true,
    onopen(response) {
      console.log('🟢 [DEBUG] SSE 连接已打开');
      console.log('🟢 [DEBUG] 响应状态:', response.status);
      console.log('🟢 [DEBUG] 响应头:', response.headers);
    },
    onmessage(event: EventSourceMessage) {
      console.log('📨 [DEBUG] 收到 SSE 消息:', event);
      if (event.data) {
        try {
          const parsedData = JSON.parse(event.data);
          console.log('📨 [DEBUG] 解析后的数据:', JSON.stringify(parsedData, null, 2));
          handleMessage(parsedData);
        } catch (error) {
          console.error('🔴 [ERROR] SSE 消息解析失败:', error);
          console.error('🔴 [ERROR] 原始数据:', event.data);
          handleError(new Error('Failed to parse SSE message'));
        }
      }
    },
    onerror(error: Error) {
      console.error('🔴 [ERROR] SSE 连接错误:', error);
      console.error('🔴 [ERROR] 错误详情:', {
        message: error.message,
        stack: error.stack,
        name: error.name
      });
      handleError(error);
    },
    onclose() {
      console.log('🟡 [DEBUG] SSE 连接已关闭');
      handleClose();
    }
  });
};
