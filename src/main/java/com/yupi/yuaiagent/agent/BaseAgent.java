package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 3、清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 标记 emitter 是否已完成，避免重复 complete/completeWithError 导致 IllegalStateException
        AtomicBoolean sseCompleted = new AtomicBoolean(false);
        Consumer<Throwable> safeCompleteWithError = (ex) -> {
            try {
                if (sseCompleted.compareAndSet(false, true)) {
                    sseEmitter.completeWithError(ex);
                }
            } catch (Exception ignore) {
                // ignore
            }
        };
        Runnable safeComplete = () -> {
            try {
                if (sseCompleted.compareAndSet(false, true)) {
                    sseEmitter.complete();
                }
            } catch (Exception ignore) {
                // ignore
            }
        };
        // 使用线程异步处理，避免阻塞主线程
    CompletableFuture.runAsync(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    try {
                        sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    } catch (IOException | IllegalStateException ignore) {
                        // ignore send errors
                    }
                    safeComplete.run();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    try {
                        sseEmitter.send("错误：不能使用空提示词运行代理");
                    } catch (IOException | IllegalStateException ignore) {
                        // ignore send errors
                    }
                    safeComplete.run();
                    return;
                }
            } catch (Exception e) {
                safeCompleteWithError.accept(e);
                return;
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 输出当前每一步的结果到 SSE（保护性处理，忽略已关闭的连接）
                    try {
                        sseEmitter.send(result);
                    } catch (IOException ioe) {
                        // 发送失败，记录并终止流
                        log.warn("Failed to send SSE chunk: {}", ioe.getMessage());
                        safeCompleteWithError.accept(ioe);
                        break;
                    } catch (IllegalStateException ise) {
                        // emitter 已完成/取消，避免重复完成操作
                        log.warn("SSE emitter already completed: {}", ise.getMessage());
                        break;
                    }
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    try {
                        sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                    } catch (IOException | IllegalStateException ignore) {
                        // ignore
                    }
                }
                // 正常完成
                safeComplete.run();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    try {
                        sseEmitter.send("执行错误：" + e.getMessage());
                    } catch (IOException | IllegalStateException ignore) {
                        // ignore
                    }
                } finally {
                    safeComplete.run();
                }
            } finally {
                // 3、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            safeComplete.run();
            log.warn("SSE connection timeout");
        });
        // 设置完成回调
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            // 标记已完成（如果尚未标记）
            sseCompleted.compareAndSet(false, true);
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}
