package com.jd.genie.service.impl;

import com.jd.genie.model.req.GptQueryReq;
import com.jd.genie.service.IGptProcessService;
import com.jd.genie.service.IMultiAgentService;
import com.jd.genie.util.ChateiUtils;
import com.jd.genie.util.SseUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GptProcessServiceImpl implements IGptProcessService {
    @Autowired
    private IMultiAgentService multiAgentService;

    @Override
    public SseEmitter queryMultiAgentIncrStream(GptQueryReq req) {
        log.info("🔵 [DEBUG] 进入 queryMultiAgentIncrStream 方法");
        log.info("🔵 [DEBUG] 原始请求对象: {}", req);
        
        long timeoutMillis = TimeUnit.HOURS.toMillis(1);
        log.info("🔵 [DEBUG] 设置超时时间: {} 毫秒", timeoutMillis);
        
        req.setUser("genie");
        log.info("🔵 [DEBUG] 设置用户为: genie");
        
        req.setDeepThink(req.getDeepThink() == null ? 0: req.getDeepThink());
        log.info("🔵 [DEBUG] 深度思考标志处理后: {}", req.getDeepThink());
        
        String traceId = ChateiUtils.getRequestId(req);
        log.info("🔵 [DEBUG] 生成 traceId: {}", traceId);
        
        req.setTraceId(traceId);
        log.info("🔵 [DEBUG] 完整的请求对象: {}", req);
        
        try {
            final SseEmitter emitter = SseUtil.build(timeoutMillis, req.getTraceId());
            log.info("🟢 [DEBUG] SSE 发射器构建成功");
            
            log.info("🔵 [DEBUG] 开始调用 multiAgentService.searchForAgentRequest");
            multiAgentService.searchForAgentRequest(req, emitter);
            log.info("🟢 [DEBUG] multiAgentService.searchForAgentRequest 调用完成");
            
            return emitter;
        } catch (Exception e) {
            log.error("🔴 [ERROR] queryMultiAgentIncrStream 处理失败", e);
            throw e;
        }
    }
}
