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

        String prompt = this.getPrompt(command);

        Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("prompt", prompt);
            request.put("stream", false);

        Map<String, Object> options = new HashMap<>();
             options.put("temperature", 0);
             request.put("options", options);

        Map response = restTemplate.postForObject(ollamaUrl, request, Map.class);

        return response.get("response").toString();
    }


    private String getPrompt(String command) {

    return """
        You are a grocery billing assistant.

        Return ONLY valid JSON.

        Do not explain.
        Do not use markdown.
        Do not use code fences.

        Allowed intents:

        ADD_ITEM
        REMOVE_ITEM
        SEARCH_PRODUCT
        START_BILL
        PRINT_BILL
        OPEN_BILLING_CONTROLS
        CLOSE_BILLING_CONTROLS
        UNKNOWN

        Schema:

        {
          "intent":"",
          "productSku":"",
          "productName":"",
          "qty":0,
          "unit":""
        }

        Examples:

        Input:
        Add 5 kg Atta

        Output:
        {
          "intent":"ADD_ITEM",
          "productName":"Atta",
          "qty":5,
          "unit":"kg"
        }

        Input:
        Add 2 packets Maggi

        Output:
        {
          "intent":"ADD_ITEM",
          "productName":"Maggi",
          "qty":2,
          "unit":"packet"
        }

        Input:
        Search mustard oil

        Output:
        {
          "intent":"SEARCH_PRODUCT",
          "productName":"Mustard Oil",
          "qty":0,
          "unit":""
        }

        Command:
        """ + command;
}

}
