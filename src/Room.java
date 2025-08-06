import java.util.*;
import java.util.concurrent.locks.*;
import java.util.function.Consumer;

public class Room {
    private final String name;
    private final boolean isAIRoom;
    private final String aiPrompt;
    private final List<String> messageHistory = new ArrayList<>();
    private final Map<User, MessageQueue> users = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    private static class MessageQueue {
        private final Queue<String> queue = new LinkedList<>();
        private final Consumer<String> messageHandler;
        private boolean processing = false;
        private final ReentrantLock queueLock = new ReentrantLock();
        
        public MessageQueue(Consumer<String> messageHandler) {
            this.messageHandler = messageHandler;
        }
        
        public void enqueue(String message) {
            queueLock.lock();
            try {
                queue.add(message);
                if (!processing) {
                    processing = true;
                    Thread.startVirtualThread(this::processQueue);
                }
            } finally {
                queueLock.unlock();
            }
        }
        
        private void processQueue() {
            try {
                while (true) {
                    String message;
                    queueLock.lock();
                    try {
                        message = queue.poll();
                        if (message == null) {
                            processing = false;
                            return;
                        }
                    } finally {
                        queueLock.unlock();
                    }
                    
                    try {
                        messageHandler.accept(message);
                        Thread.sleep(10);
                    } catch (Exception e) {
                        System.err.println("Error sending message to client: " + e.getMessage());
                        
                        String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        boolean isSocketClosed = errorMsg.contains("socket closed") || 
                                            errorMsg.contains("broken pipe") ||
                                            errorMsg.contains("connection reset");
                        
                        if (!isSocketClosed) {
                            queueLock.lock();
                            try {
                                queue.add(message);
                            } finally {
                                queueLock.unlock();
                            }
                            
                            Thread.sleep(500);
                        } else {
                            System.out.println("Socket closed, abandoning message sending");
                            break;
                        }
                    }
                }
                
                queueLock.lock();
                try {
                    processing = false;
                    
                    if (!queue.isEmpty()) {
                        processing = true;
                        Thread.startVirtualThread(this::processQueue);
                    }
                } finally {
                    queueLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                
                queueLock.lock();
                try {
                    processing = false;
                } finally {
                    queueLock.unlock();
                }
            }
        }
    }
    
    /**
     * creates a new room
     */
    public Room(String name, boolean isAIRoom, String aiPrompt) {
        this.name = name;
        this.isAIRoom = isAIRoom;
        this.aiPrompt = aiPrompt;
    }
    
    /**
     * returns the room name
     */
    public String getName() {
        return name;
    }
    
    /**
     * checks if this is an ai room
     */
    public boolean isAIRoom() {
        return isAIRoom;
    }
    
    /**
     * returns the ai prompt for this room
     */
    public String getAIPrompt() {
        return aiPrompt;
    }
    
    /**
     * adds a user to the room
     */
    public void addUser(User user, Consumer<String> messageHandler) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            MessageQueue queue = new MessageQueue(messageHandler);
            users.put(user, queue);
            
            int startIndex = Math.max(0, messageHistory.size() - 50);
            for (int i = startIndex; i < messageHistory.size(); i++) {
                queue.enqueue(messageHistory.get(i));
            }
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * removes a user from the room
     */
    public void removeUser(User user) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            users.remove(user);
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * adds a user message to the room
     */
    public void addUserMessage(User user, String message) {
        String formattedMessage = user.getUsername() + ": " + message;
        broadcastMessage(formattedMessage);
    }
    
    /**
     * adds a bot message to the room
     */
    public void addBotMessage(String message) {
        String[] lines = message.split("\n");
        
        StringBuilder formattedMessage = new StringBuilder("Bot: ");
        if (lines.length > 0) {
            formattedMessage.append(lines[0]);
            
            for (int i = 1; i < lines.length; i++) {
                formattedMessage.append("\n").append(lines[i]);
            }
        } else {
            formattedMessage.append(message);
        }
        
        broadcastMessage(formattedMessage.toString());
        
        System.out.println("Bot message sent: " + 
                (formattedMessage.length() > 50 ? 
                formattedMessage.substring(0, 50) + "..." : 
                formattedMessage));
    }
    
    /**
     * adds a system message to the room
     */
    public void addSystemMessage(String message) {
        String formattedMessage = "[" + message + "]";
        broadcastMessage(formattedMessage);
    }
    
    /**
     * broadcasts a message to all users in the room
     */
    private void broadcastMessage(String message) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            messageHistory.add(message);
            
            while (messageHistory.size() > 1000) {
                messageHistory.remove(0);
            }
        } finally {
            writeLock.unlock();
        }
        
        Lock readLock = lock.readLock();
        readLock.lock();
        List<MessageQueue> queues;
        try {
            queues = new ArrayList<>(users.values());
        } finally {
            readLock.unlock();
        }
        
        for (MessageQueue queue : queues) {
            try {
                queue.enqueue(message);
            } catch (Exception e) {
                System.err.println("Error queueing message: " + e.getMessage());
            }
        }
    }
    
    /**
     * returns the message history for this room
     */
    public String getMessageHistory() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            int startIndex = Math.max(0, messageHistory.size() - 100);
            List<String> recentMessages = messageHistory.subList(startIndex, messageHistory.size());
            return String.join("\n", recentMessages);
        } finally {
            readLock.unlock();
        }
    }
}