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

    /**
     * Single entry point that dynamically chooses the correct prompter
     */
    public String processUnified(String command, String sessionMode) {
        String prompt;

        // If the frontend tells us it is waiting for a confirmation, run the
        // specialized prompt
        if ("CONFIRM_PACKAGING".equalsIgnoreCase(sessionMode)) {
            prompt = this.getConfirmationPrompt(command);
        } else if ("BRAND_SELECTION".equalsIgnoreCase(sessionMode)) {
            prompt = this.getBrandSelectionPrompt(command);
        } else {
            prompt = this.getPrompt(command);
        }

        return executeOllamaCall(prompt);
    }

    /**
     * Helper method to execute the Ollama API call with the given prompt and return
     * the response as a string.
     * 
     * @param prompt The prompt to send to the Ollama API.
     * @return The response from the Ollama API as a string.
     */
    private String executeOllamaCall(String prompt) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("stream", false);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0);
        request.put("options", options);

        Map<?, ?> response = restTemplate.postForObject(ollamaUrl, request, Map.class);
        if (response != null && response.get("response") != null) {
            return response.get("response").toString();
        }
        return "{}";
    }

    /**
     * Generates a prompt for the standard intent parsing pipeline.
     * 
     * @param command The user's input command.
     * @return The generated prompt.
     */
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
                Add 1 packets Masala Munch
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Masala Munch",
                  "qty":1,
                  "unit":"packet"
                }


                Input:
                Add 1 litre mustard oil
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Mustard Oil",
                  "qty":1,
                  "unit":"l"
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

    private String getConfirmationPrompt(String command) {
        return """
                You are a packaging clarification assistant for a grocery system.
                Analyze the user's input and determine if they selected LOOSE or PACKET.

                Return ONLY valid JSON.
                Do not explain.
                Do not use markdown.
                Do not use code fences.

                Schema:
                {
                  "isLoose": true/false/null
                }

                Rules:
                - Set "isLoose" to true if input means loose or un-packaged.
                - Set "isLoose" to false if input means packet, container, bag, or boxed packaging.
                - Set "isLoose" to null if the response is unclear or unrelated.

                Examples:

                Input:
                loose
                Output:
                {"isLoose": true}

                Input:
                give me packet
                Output:
                {"isLoose": false}

                Input:
                packet form
                Output:
                {"isLoose": false}

                Input:
                open product
                Output:
                {"isLoose": true}

                Command:
                """ + command;
    }

    private String getBrandSelectionPrompt(String command) {
        return """
                You are a brand selection assistant for a retail billing system.
                Analyze the user's voice input to determine which brand or list option number they selected.

                Return ONLY a raw, valid JSON object.
                Do not add any explanation or prose.
                Do not wrap the output in markdown or triple-backtick code fences (```).

                Schema:
                {
                  "brand": "string containing the extracted brand name or the list index number"
                }

                Rules:
                - If the user names a specific brand (e.g., "Aashirvaad", "Fortune", "Tata"), extract that exact name.
                - If the user specifies an option number (e.g., "first one", "number 2", "pehla waala"), extract the number (e.g., "1", "2").
                - If the input is completely ambiguous or unrelated, set "brand" to null.

                Examples:

                Input:
                Aashirwad
                Output:
                {"brand": "Aashirwad"}

                Input:
                Pehla waala dedo
                Output:
                {"brand": "1"}

                Command:
                """
                + command;
    }
}