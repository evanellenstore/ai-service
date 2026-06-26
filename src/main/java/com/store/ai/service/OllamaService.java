package com.store.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.store.ai.dto.EmbeddingRequest;
import com.store.ai.dto.EmbeddingResponse;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OllamaService {

    private final RestTemplate restTemplate;

    private final QdrantClient qdrantClient;

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.embeddings-url}")
    private String embeddingsUrl;

    @Value("${ollama.emd-model}")
    private String emdModel;

    public OllamaService(RestTemplate restTemplate, QdrantClient qdrantClient) {
        this.restTemplate = restTemplate;
        this.qdrantClient = qdrantClient;
    }

    /**
     * Processes the given command based on the specified session mode. Depending on the session mode, it generates a prompt, calls the Ollama API, and processes the response to return a structured JSON string.
     * @param command
     * @param sessionMode
     * @return
     * @throws RuntimeException
     */
    public String processUnified(String command, String sessionMode) throws RuntimeException {
    String prompt;
    String resultJsonString = null;
    ObjectMapper mapper = new ObjectMapper();

    if ("CONFIRM_PACKAGING".equalsIgnoreCase(sessionMode)) {
      prompt = this.getConfirmationPrompt(command);
      String llmJsonString = executeOllamaCall(prompt);

      ObjectNode llmRootNode = (ObjectNode) mapper.readTree(llmJsonString);
      Boolean llmIsLooose = llmRootNode.get("isLoose").asBoolean();
      String searchContext = llmIsLooose ? "loose" : "packet";
      // Call our updated type-safe helper
      String matchedIsLooseStr = searchTopProductField(searchContext, "productType");
      // Write it back to the JSON node cleanly as an actual Boolean primitive
      if("loose".equals(matchedIsLooseStr)) {
        llmRootNode.put("isLoose", true);
      } else if("packet".equals(matchedIsLooseStr)) {
        llmRootNode.put("isLoose", false);
      }
      resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);

    } else if ("BRAND_SELECTION".equalsIgnoreCase(sessionMode)) {
        prompt = this.getBrandSelectionPrompt(command);
        String llmJsonString = executeOllamaCall(prompt);
        ObjectNode llmRootNode = (ObjectNode) mapper.readTree(llmJsonString);
        String llmBrand = llmRootNode.get("brand").asString();
        // Fetch the nearest brand match from Qdrant
        String matchedBrand = searchTopProductField(llmBrand, "brandName");
        llmRootNode.put("brand", matchedBrand);
        resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);
      } else {
      prompt = this.getProductPrompt(command);
      // llm response
      String llmJsonString = executeOllamaCall(prompt);
      ObjectNode llmRootNode = (ObjectNode) mapper.readTree(llmJsonString);
      String llmIntent = llmRootNode.get("intent").asString();
      if ("ADD_ITEM".equalsIgnoreCase(llmIntent)) {
        String llMProductName = llmRootNode.get("productName").asString();
        // Fetch the nearest product name match from Qdrant
        String matchedProductName = searchTopProductField(llMProductName, "name");
        llmRootNode.put("productName", matchedProductName);
        resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);
      } else {
        // Fallback if intent is not ADD_ITEM but you still need to return the raw
        // response
        resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);
      }
    }

    return resultJsonString;
}

/**
 * Helper method to handle common embedding generation and Qdrant vector searching.
 */
private String searchTopProductField(String textToEmbed, String payloadKey) {
    EmbeddingResponse embedding = createEmbedding(
        EmbeddingRequest.builder().prompt(textToEmbed).build()
    );

    List<Float> queryVector = embedding.getEmbedding().stream()
        .map(Double::floatValue)
        .toList();

    SearchPoints searchRequest = SearchPoints.newBuilder()
        .setCollectionName("products")
        .addAllVector(queryVector)
        .setLimit(1)
        .setWithPayload(WithPayloadSelectorFactory.enable(true))
        .build();

    try {
        List<ScoredPoint> qdrantResults = qdrantClient.searchAsync(searchRequest).get();
        
        if (qdrantResults != null && !qdrantResults.isEmpty()) {
            ScoredPoint point = qdrantResults.get(0);
            System.out.println("Vector Match Score: " + point.getScore());
            return point.getPayload().get(payloadKey).getStringValue();
        } else {
            throw new RuntimeException("No vector search results found for: " + textToEmbed);
        }
    } catch (InterruptedException | ExecutionException e) {
        Thread.currentThread().interrupt(); // Restore interrupted status if InterruptedException
        throw new RuntimeException("Failed to execute Qdrant vector search", e);
    }
}


/**
 * Creates an embedding for the given prompt using the Ollama API.  
 * @param request
 * @return
 */

    public EmbeddingResponse createEmbedding(EmbeddingRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", emdModel);
        payload.put("prompt", request.getPrompt());

        String endpoint = embeddingsUrl;
        Map<?, ?> response = restTemplate.postForObject(endpoint, payload, Map.class);
        if (response != null && response.get("embedding") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Double> embedding = (List<Double>) response.get("embedding");
            return EmbeddingResponse.builder().embedding(embedding).build();
        }
        return EmbeddingResponse.builder().embedding(List.of()).build();
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
    private String getProductPrompt(String command) {
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
                Add 5 kg Aata
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Aata",
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