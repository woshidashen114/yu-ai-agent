package com.yupi.yuaiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 简单的图像生成请求构造器（调用外部图像生成 API）
 * 该类会构造如下 JSON 结构并发送到指定的 API 地址：
 * {
 * "input": [
 * {
 * "type": "message",
 * "role": "user",
 * "content": [
 * { "type": "input_text", "text": "生成一张美女图片" }
 * ]
 * }
 * ]
 * }
 */
public class genaratorImg {

    private final String apiKey;
    private final String appid;
    private final String apiUrl = "https://dashscope.aliyuncs.com/api/v2/apps/agent/";

    public genaratorImg(String apiKey,String appId) {
        this.apiKey = apiKey;
        this.appid = appId;
    }

    @Tool(description = "Generate an image based on a text description")
    public String generateImage(@ToolParam(description = "photo desc") String text) {
        String posturl = apiUrl + appid + "/compatible-mode/v1/responses";

        // 构造 JSON 结构
        JSONObject root = new JSONObject();
        JSONArray inputArray = new JSONArray();

        JSONObject message = new JSONObject();
        message.set("type", "message");
        message.set("role", "user");

        JSONArray contentArray = new JSONArray();
        JSONObject inputText = new JSONObject();
        inputText.set("type", "input_text");
        inputText.set("text", text);
        contentArray.add(inputText);

        message.set("content", contentArray);
        inputArray.add(message);

        root.set("input", inputArray);

        String body = root.toString();

        try {
            // 发送 JSON 请求，设置 Content-Type 为 application/json
            String resp = HttpRequest.post(posturl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .execute()
                    .body();
            return resp;
        } catch (Exception e) {
            return "Error sending image generation request: " + e.getMessage() + "; payload=" + body;
        }
    }
}
