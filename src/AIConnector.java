import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.*;

public class AIConnector {
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final String SYSTEM_PROMPT_TEMPLATE = 
        "You are participating in a casual chat room. " +
        "Respond naturally to the most recent message in the conversation. " +
        "Speak in the same language as the user (Portuguese if they use Portuguese). " +
        "Never start with phrases like 'Based on our conversation history'. " +
        "Never mention analyzing the conversation. " +
        "Be concise, natural, and conversational. ";
    
    private static class CacheEntry {
        final String response;
        final long timestamp;
        
        public CacheEntry(String response) {
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - timestamp > ttlMillis;
        }
    }
    
    private final Map<String, CacheEntry> responseCache = new HashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private static final long CACHE_TTL_MILLIS = 300000; 
    
    private int requestCounter = 0;
    private int cacheHitCounter = 0;
    private int cacheMissCounter = 0;
    private int failureCounter = 0;
    private final ReentrantReadWriteLock counterLock = new ReentrantReadWriteLock();
    
    private int incrementRequestCounter() {
        Lock writeLock = counterLock.writeLock();
        writeLock.lock();
        try {
            return ++requestCounter;
        } finally {
            writeLock.unlock();
        }
    }

    private void incrementCacheHitCounter() {
        Lock writeLock = counterLock.writeLock();
        writeLock.lock();
        try {
            cacheHitCounter++;
        } finally {
            writeLock.unlock();
        }
    }

    private void incrementCacheMissCounter() {
        Lock writeLock = counterLock.writeLock();
        writeLock.lock();
        try {
            cacheMissCounter++;
        } finally {
            writeLock.unlock();
        }
    }

    private void incrementFailureCounter() {
        Lock writeLock = counterLock.writeLock();
        writeLock.lock();
        try {
            failureCounter++;
        } finally {
            writeLock.unlock();
        }
    }

    private int getRequestCounter() {
        Lock readLock = counterLock.readLock();
        readLock.lock();
        try {
            return requestCounter;
        } finally {
            readLock.unlock();
        }
    }

    private int getCacheHitCounter() {
        Lock readLock = counterLock.readLock();
        readLock.lock();
        try {
            return cacheHitCounter;
        } finally {
            readLock.unlock();
        }
    }

    private int getCacheMissCounter() {
        Lock readLock = counterLock.readLock();
        readLock.lock();
        try {
            return cacheMissCounter;
        } finally {
            readLock.unlock();
        }
    }

    private int getFailureCounter() {
        Lock readLock = counterLock.readLock();
        readLock.lock();
        try {
            return failureCounter;
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * gets AI response based on prompt and context
     */
    public String getAIResponse(String prompt, String context) throws IOException {
        int requestId = incrementRequestCounter();
        System.out.println("[AIConnector#" + requestId + "] Starting request processing");
        
        String latestContext = extractLatestMessages(context, 8);
        
        String cacheKey = generateCacheKey(prompt, latestContext);
        
        Lock readLock = cacheLock.readLock();
        readLock.lock();
        CacheEntry cachedEntry;
        try {
            cachedEntry = responseCache.get(cacheKey);
        } finally {
            readLock.unlock();
        }
        
        if (cachedEntry != null && !cachedEntry.isExpired(CACHE_TTL_MILLIS)) {
            incrementCacheHitCounter();
            System.out.println("[AIConnector#" + requestId + "] Using cached response (active TTL)");
            return cachedEntry.response;
        }
        
        incrementCacheMissCounter();
        
        try {
            String response = callOllamaAPI(prompt, latestContext, requestId);
            
            if (response != null && !response.isEmpty()) {
                Lock writeLock = cacheLock.writeLock();
                writeLock.lock();
                try {
                    responseCache.put(cacheKey, new CacheEntry(response));
                } finally {
                    writeLock.unlock();
                }
                System.out.println("[AIConnector#" + requestId + "] Response stored in cache with TTL");
            }
            
            return response;
        } catch (Exception e) {
            incrementFailureCounter();
            System.err.println("[AIConnector#" + requestId + "] Error getting response: " + e.getMessage());
            
            try {
                System.out.println("[AIConnector#" + requestId + "] Trying again with simplified prompt");
                String simplifiedResponse = callOllamaAPIWithSimplifiedPrompt(context, requestId);
                return simplifiedResponse;
            } catch (Exception e2) {
                System.err.println("[AIConnector#" + requestId + "] Failed on second attempt: " + e2.getMessage());
                return "Sorry, I'm having technical difficulties processing your message right now. Please try again in a few moments.";
            }
        }
    }
    
    private String generateCacheKey(String prompt, String context) {
        return String.format("%d_%d", prompt.hashCode(), context.hashCode());
    }
    
    private String extractLatestMessages(String context, int messageCount) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        
        String[] lines = context.split("\n");
        
        StringBuilder latestMessages = new StringBuilder();
        int messagesFound = 0;
        
        for (int i = lines.length - 1; i >= 0 && messagesFound < messageCount; i--) {
            String line = lines[i];
            if (line.contains(": ") || (line.startsWith("[") && line.endsWith("]"))) {
                latestMessages.insert(0, line + "\n");
                messagesFound++;
            }
        }
        
        return latestMessages.toString();
    }
    
    private String callOllamaAPI(String systemPrompt, String conversationContext, int requestId) throws IOException {
        System.out.println("[AIConnector#" + requestId + "] Sending request to Ollama");
        
        String requestBody = buildContextualizedRequestBody(systemPrompt, conversationContext, requestId);
        
        System.out.println("[AIConnector#" + requestId + "] Request payload: " + 
                requestBody.substring(0, Math.min(100, requestBody.length())) + "...");
        
        return sendRequest(requestBody, requestId);
    }
    
    private String callOllamaAPIWithSimplifiedPrompt(String context, int requestId) throws IOException {
        boolean usePortuguese = isContextInPortuguese(context);
        
        String simplifiedBody = String.format(
                "{\"model\":\"llama3\",\"prompt\":\"%s\",\"stream\":false}",
                escapeJson("<assistant>" + (usePortuguese ? 
                          "Responda de forma natural e conversacional: " : 
                          "Respond naturally and conversationally: ") + 
                          extractLastUserQuery(context) + "</assistant>")
        );
        
        return sendRequest(simplifiedBody, requestId);
    }
    
    private boolean isContextInPortuguese(String context) {
        if (context == null || context.isEmpty()) return false;
        
        String[] portugueseIndicators = {"como", "está", "olá", "bom dia", "boa tarde", "obrigado", "não", "qual", "para"};
        String lowercaseContext = context.toLowerCase();
        
        for (String indicator : portugueseIndicators) {
            if (lowercaseContext.contains(" " + indicator + " ") || 
                lowercaseContext.startsWith(indicator + " ") || 
                lowercaseContext.contains(" " + indicator + "\n")) {
                return true;
            }
        }
        return false;
    }
    
    private String extractLastUserQuery(String context) {
        if (context == null || context.isEmpty()) {
            return "How can I help?";
        }
        
        String[] lines = context.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.contains(": ") && !line.startsWith("Bot:")) {
                int colonPos = line.indexOf(": ");
                if (colonPos > 0 && colonPos < line.length() - 2) {
                    return line.substring(colonPos + 2);
                }
            }
        }
        
        return "How can I help?";
    }
    
    private String buildContextualizedRequestBody(String systemPrompt, String conversationContext, int requestId) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{");
        jsonBuilder.append("\"model\":\"llama3\",");
        
        StringBuilder formattedContext = new StringBuilder();
        String[] messages = conversationContext.split("\n");
        for (String message : messages) {
            if (message.isEmpty()) continue;
            
            if (message.startsWith("Bot: ")) {
                formattedContext.append("<assistant>").append(message.substring(5)).append("</assistant>\n");
            } else if (message.contains(": ")) {
                int colonPos = message.indexOf(": ");
                String username = message.substring(0, colonPos);
                String content = message.substring(colonPos + 2);
                formattedContext.append("<user name=\"").append(username).append("\">")
                              .append(content).append("</user>\n");
            } else if (message.startsWith("[") && message.endsWith("]")) {
                formattedContext.append("<system_message>").append(message).append("</system_message>\n");
            }
        }
        
        String enhancedPrompt = formattedContext.toString() + "<assistant>";
        jsonBuilder.append("\"prompt\":\"").append(escapeJson(enhancedPrompt)).append("\",");
        
        String enhancedSystemPrompt = SYSTEM_PROMPT_TEMPLATE + systemPrompt;
        jsonBuilder.append("\"system\":\"").append(escapeJson(enhancedSystemPrompt)).append("\",");
        
        jsonBuilder.append("\"stream\":false,");
        jsonBuilder.append("\"options\":{\"temperature\":0.8,\"top_p\":0.9,\"top_k\":40}");
        jsonBuilder.append("}");
        
        return jsonBuilder.toString();
    }
    
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    private String sendRequest(String requestBody, int requestId) throws IOException {
        long startTime = System.currentTimeMillis();
        HttpURLConnection connection = null;
        
        try {
            URL url = new URL(OLLAMA_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(REQUEST_TIMEOUT_SECONDS * 1000);
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            
            int responseCode = connection.getResponseCode();
            System.out.println("[AIConnector#" + requestId + "] HTTP response code: " + responseCode);
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                StringBuilder errorDetails = new StringBuilder();
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorDetails.append(line);
                    }
                } catch (Exception e) {
                    errorDetails.append("Could not read error details: ").append(e.getMessage());
                }
                
                System.err.println("[AIConnector#" + requestId + "] Error details: " + errorDetails.toString());
                throw new IOException("HTTP Error " + responseCode + ": " + errorDetails.toString());
            }
            
            StringBuilder responseBody = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBody.append(line);
                }
            }
            
            String jsonResponse = responseBody.toString();
            System.out.println("[AIConnector#" + requestId + "] JSON Response: " + 
                    (jsonResponse.length() > 100 ? jsonResponse.substring(0, 100) + "..." : jsonResponse));
            
            String extractedResponse = extractResponseFromJson(jsonResponse, requestId);
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("[AIConnector#" + requestId + "] Request completed in " + duration + "ms");
            
            return extractedResponse;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    private String extractResponseFromJson(String jsonResponse, int requestId) {
        try {
            if (jsonResponse == null || jsonResponse.isEmpty()) {
                System.err.println("[AIConnector#" + requestId + "] Empty JSON response");
                return "Sorry, an error occurred while processing your request.";
            }
            
            if (jsonResponse.contains("\"response\":")) {
                int start = jsonResponse.indexOf("\"response\":\"") + 12;
                if (start < 12) {
                    System.err.println("[AIConnector#" + requestId + "] Unexpected JSON format: " + jsonResponse);
                    return "A format error occurred in the response.";
                }
                
                int end = -1;
                boolean escaped = false;
                for (int i = start; i < jsonResponse.length(); i++) {
                    char c = jsonResponse.charAt(i);
                    if (c == '\\') {
                        escaped = !escaped;
                    } else if (c == '"' && !escaped) {
                        end = i;
                        break;
                    } else {
                        escaped = false;
                    }
                }
                
                if (end == -1) {
                    System.err.println("[AIConnector#" + requestId + "] Could not find the end of the response in JSON");
                    return "An error occurred while processing the response.";
                }
                
                String response = jsonResponse.substring(start, end);
                
                response = response.replace("\\\"", "\"")
                                   .replace("\\n", "\n")
                                   .replace("\\r", "\r")
                                   .replace("\\t", "\t")
                                   .replace("\\\\", "\\");
                
                return response;
            } else {
                System.err.println("[AIConnector#" + requestId + "] 'response' field not found: " + jsonResponse);
                return "The received response does not contain the expected content.";
            }
        } catch (Exception e) {
            System.err.println("[AIConnector#" + requestId + "] Error extracting response: " + e.getMessage());
            e.printStackTrace();
            return "A technical error occurred while processing the response.";
        }
    }
    
    /**
     * returns usage statistics
     */
    public String getStatistics() {
        Lock readLock = cacheLock.readLock();
        readLock.lock();
        try {
            return String.format(
                "AIConnector Stats: Requests=%d, Cache Hits=%d, Cache Misses=%d, Failures=%d, Hit Rate=%.1f%%, Cache Size=%d",
                getRequestCounter(),
                getCacheHitCounter(),
                getCacheMissCounter(),
                getFailureCounter(),
                getRequestCounter() > 0 ? (getCacheHitCounter() * 100.0 / getRequestCounter()) : 0,
                responseCache.size()
            );
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * clears the response cache
     */
    public void clearCache() {
        Lock writeLock = cacheLock.writeLock();
        writeLock.lock();
        try {
            int size = responseCache.size();
            responseCache.clear();
            System.out.println("Cache cleared. " + size + " entries removed.");
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * removes expired entries from cache
     */
    public void cleanExpiredCache() {
        Lock writeLock = cacheLock.writeLock();
        writeLock.lock();
        try {
            int initialSize = responseCache.size();
            responseCache.entrySet().removeIf(entry -> entry.getValue().isExpired(CACHE_TTL_MILLIS));
            int removedCount = initialSize - responseCache.size();
            System.out.println("Cache cleanup: " + removedCount + " expired entries removed. Remaining: " + responseCache.size());
        } finally {
            writeLock.unlock();
        }
    }
}
