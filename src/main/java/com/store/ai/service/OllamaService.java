package com.store.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {

    private final RestTemplate restTemplate;

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    public OllamaService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String process(String command) {

        String prompt = """
                You are a grocery billing assistant.

                Return ONLY valid JSON.

                Allowed intents:
                ADD_ITEM
                REMOVE_ITEM
                SEARCH_PRODUCT
                START_BILL
                PRINT_BILL
                OPEN_BILLING_CONTROLS
                CLOSE_BILLING_CONTROLS

                Schema:

                                {
                                    "intent":"",
                                    "productSku":"",
                                    "product":"",
                                    "quantity":0,
                                    "unit":"",
                                    "price":0,
                                    "discountAmount":0,
                                    "total":0
                                }

                Command:
                """ + command;

        Map<String, Object> request = new HashMap<>();

        request.put("model", model);
        request.put("prompt", prompt);
        request.put("stream", false);

        Map<String, Object> options = new HashMap<>();

        options.put("temperature", 0);

        request.put("options", options);

        Map response = restTemplate.postForObject(
                ollamaUrl,
                request,
                Map.class);

        return response.get("response")
                .toString();
    }
}
