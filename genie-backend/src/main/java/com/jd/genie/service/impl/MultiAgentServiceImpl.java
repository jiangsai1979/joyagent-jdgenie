package com.jd.genie.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jd.genie.agent.enums.AgentType;
import com.jd.genie.agent.enums.AutoBotsResultStatus;
import com.jd.genie.agent.enums.ResponseTypeEnum;
import com.jd.genie.config.GenieConfig;
import com.jd.genie.handler.AgentResponseHandler;
import com.jd.genie.model.dto.AutoBotsResult;
import com.jd.genie.model.multi.EventResult;
import com.jd.genie.model.req.AgentRequest;
import com.jd.genie.model.req.GptQueryReq;
import com.jd.genie.model.response.AgentResponse;
import com.jd.genie.model.response.GptProcessResult;
import com.jd.genie.service.IMultiAgentService;
import com.jd.genie.util.ChateiUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MultiAgentServiceImpl implements IMultiAgentService {
    @Autowired
    private GenieConfig genieConfig;
    @Autowired
    private Map<AgentType, AgentResponseHandler> handlerMap;

    @Override
    public AutoBotsResult searchForAgentRequest(GptQueryReq gptQueryReq, SseEmitter sseEmitter) {
        log.info("🔵 [DEBUG] 进入 searchForAgentRequest 方法");
        log.info("🔵 [DEBUG] 输入参数 - gptQueryReq: {}", JSON.toJSONString(gptQueryReq));
        
        AgentRequest agentRequest = buildAgentRequest(gptQueryReq);
        log.info("🔵 [DEBUG] 构建的 AgentRequest: {}", JSON.toJSONString(agentRequest));
        log.info("🔵 [DEBUG] Agent 类型: {}", agentRequest.getAgentType());
        log.info("🔵 [DEBUG] 流式传输: {}", agentRequest.getIsStream());
        
        try {
            log.info("🔵 [DEBUG] 开始调用 handleMultiAgentRequest");
            handleMultiAgentRequest(agentRequest, sseEmitter);
            log.info("🟢 [DEBUG] handleMultiAgentRequest 调用完成");
        } catch (Exception e) {
            log.error("🔴 [ERROR] requestMultiAgent 执行失败 - requestId: {}, deepThink: {}, errorMsg: {}", 
                gptQueryReq.getRequestId(), gptQueryReq.getDeepThink(), e.getMessage(), e);
            throw e;
        } finally {
            log.info("🔵 [DEBUG] searchForAgentRequest 结束 - requestId: {}", gptQueryReq.getRequestId());
        }

        AutoBotsResult result = ChateiUtils.toAutoBotsResult(agentRequest, AutoBotsResultStatus.loading.name());
        log.info("🔵 [DEBUG] 返回结果: {}", JSON.toJSONString(result));
        return result;
    }

    public void handleMultiAgentRequest(AgentRequest autoReq,SseEmitter sseEmitter) {
        log.info("🔵 [DEBUG] 进入 handleMultiAgentRequest 方法");
        log.info("🔵 [DEBUG] AgentRequest: {}", JSON.toJSONString(autoReq));
        
        long startTime = System.currentTimeMillis();
        Request request = buildHttpRequest(autoReq);
        log.info("🔵 [DEBUG] 构建的 HTTP 请求: {}", request.toString());
        log.info("🔵 [DEBUG] 请求 URL: {}", request.url());
        log.info("🔵 [DEBUG] 请求 Body: {}", autoReq);
        
        // 输出超时配置
        log.info("🔵 [DEBUG] SSE 客户端配置:");
        log.info("🔵 [DEBUG]   - 连接超时: 60 秒");
        log.info("🔵 [DEBUG]   - 读取超时: {} 秒", genieConfig.getSseClientReadTimeout());
        log.info("🔵 [DEBUG]   - 写入超时: 1800 秒");
        log.info("🔵 [DEBUG]   - 调用超时: {} 秒", genieConfig.getSseClientConnectTimeout());
        
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // 设置连接超时时间为 60 秒
                .readTimeout(genieConfig.getSseClientReadTimeout(), TimeUnit.SECONDS)    // 设置读取超时时间为 60 秒
                .writeTimeout(1800, TimeUnit.SECONDS)   // 设置写入超时时间为 60 秒
                .callTimeout(genieConfig.getSseClientConnectTimeout(), TimeUnit.SECONDS)    // 设置调用超时时间为 60 秒
                .build();
        
        log.info("🔵 [DEBUG] OkHttpClient 构建完成，开始发起请求");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("🔴 [ERROR] HTTP 请求失败 - requestId: {}", autoReq.getRequestId());
                log.error("🔴 [ERROR] 失败原因: {}", e.getMessage(), e);
                log.error("🔴 [ERROR] 请求 URL: {}", call.request().url());
                
                try {
                    // 向前端发送错误信息
                    GptProcessResult errorResult = buildDefaultAutobotsResult(autoReq, "任务执行失败，请联系管理员！");
                    sseEmitter.send(errorResult);
                    sseEmitter.complete();
                } catch (Exception sendError) {
                    log.error("🔴 [ERROR] 发送错误结果失败", sendError);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                log.info("🟢 [DEBUG] 收到 HTTP 响应 - requestId: {}", autoReq.getRequestId());
                log.info("🟢 [DEBUG] 响应状态: {}", response.code());
                log.info("🟢 [DEBUG] 响应头: {}", response.headers());
                
                List<AgentResponse> agentRespList = new ArrayList<>();
                EventResult eventResult = new EventResult();
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    log.error("🔴 [ERROR] 响应体为空 - requestId: {}", autoReq.getRequestId());
                    try {
                        GptProcessResult errorResult = buildDefaultAutobotsResult(autoReq, "任务执行失败，请联系管理员！");
                        sseEmitter.send(errorResult);
                        sseEmitter.complete();
                    } catch (Exception e) {
                        log.error("🔴 [ERROR] 发送错误结果失败", e);
                    }
                    return;
                }

                try {
                    if (!response.isSuccessful()) {
                        String errorBody = responseBody.string();
                        log.error("🔴 [ERROR] HTTP 响应失败 - requestId: {}, code: {}, body: {}", 
                            autoReq.getRequestId(), response.code(), errorBody);
                        
                        try {
                            GptProcessResult errorResult = buildDefaultAutobotsResult(autoReq, "任务执行失败，请联系管理员！");
                            sseEmitter.send(errorResult);
                            sseEmitter.complete();
                        } catch (Exception e) {
                            log.error("🔴 [ERROR] 发送错误结果失败", e);
                        }
                        return;
                    }
                    
                    log.info("🟢 [DEBUG] HTTP 响应成功，开始处理流式数据");

                    String line;
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream())
                    );

                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }

                        String data = line.substring(5);
                        if (data.equals("[DONE]")) {
                            log.info("{} data equals with [DONE] {}:", autoReq.getRequestId(), data);
                            break;
                        }

                        if (data.startsWith("heartbeat")) {
                            GptProcessResult result = buildHeartbeatData(autoReq.getRequestId());
                            sseEmitter.send(result);
                            log.info("{} heartbeat-data: {}", autoReq.getRequestId(), data);
                            continue;
                        }

                        log.info("{} recv from autocontroller: {}", autoReq.getRequestId(), data);
                        AgentResponse agentResponse = JSON.parseObject(data, AgentResponse.class);
                        AgentType agentType = AgentType.fromCode(autoReq.getAgentType());
                        AgentResponseHandler handler = handlerMap.get(agentType);
                        GptProcessResult result = handler.handle(autoReq, agentResponse,agentRespList, eventResult);
                        sseEmitter.send(result);
                        if (result.isFinished()) {
                            // 记录任务执行时间
                            log.info("{} task total cost time:{}ms", autoReq.getRequestId(), System.currentTimeMillis() - startTime);
                            sseEmitter.complete();
                        }
                    }
                }catch (Exception e) {
                    log.error("", e);
                }
            }
        });
    }

    private Request buildHttpRequest(AgentRequest autoReq) {
        String reqId = autoReq.getRequestId();
        autoReq.setRequestId(autoReq.getRequestId());
        String url = "http://127.0.0.1:8080/AutoAgent";
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                JSONObject.toJSONString(autoReq)
        );
        autoReq.setRequestId(reqId);
        return new Request.Builder().url(url).post(body).build();
    }

    private GptProcessResult buildDefaultAutobotsResult(AgentRequest autoReq, String errMsg) {
        GptProcessResult result = new GptProcessResult();
        boolean isRouter = AgentType.ROUTER.getValue().equals(autoReq.getAgentType());
        if (isRouter) {
            result.setStatus("success");
            result.setFinished(true);
            result.setResponse(errMsg);
            result.setTraceId(autoReq.getRequestId());
        } else {
            result.setResultMap(new HashMap<>());
            result.setStatus("failed");
            result.setFinished(true);
            result.setErrorMsg(errMsg);
        }
        return result;
    }

    private AgentRequest buildAgentRequest(GptQueryReq req) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(req.getTraceId());
        request.setErp(req.getUser());
        request.setQuery(req.getQuery());
        request.setAgentType(req.getDeepThink() == 0 ? 5: 3);
        request.setSopPrompt(request.getAgentType() == 3 ? genieConfig.getGenieSopPrompt(): "");
        request.setBasePrompt(request.getAgentType() == 5 ? genieConfig.getGenieBasePrompt() : "");
        request.setIsStream(true);
        request.setOutputStyle(req.getOutputStyle());

        return request;
    }


    private GptProcessResult buildHeartbeatData(String requestId) {
        GptProcessResult result = new GptProcessResult();
        result.setFinished(false);
        result.setStatus("success");
        result.setResponseType(ResponseTypeEnum.text.name());
        result.setResponse("");
        result.setResponseAll("");
        result.setUseTimes(0);
        result.setUseTokens(0);
        result.setReqId(requestId);
        result.setPackageType("heartbeat");
        result.setEncrypted(false);
        return result;
    }
}
