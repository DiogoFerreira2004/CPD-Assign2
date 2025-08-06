import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.*;

public class ClientHandler {
    private final Socket socket;
    private final UserSession session;
    private final BufferedReader in;
    private final PrintWriter out;
    private final RoomManager roomManager;
    private final AIConnector aiConnector;
    private final ChatServer server;
    private Room currentRoom;
    private boolean running = true;
    private boolean inRoom = false;
    private final ReentrantReadWriteLock runningLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock inRoomLock = new ReentrantReadWriteLock();
    
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    
    /**
     * creates a new client handler
     */
    public ClientHandler(Socket socket, UserSession session, BufferedReader in, PrintWriter out, 
                         RoomManager roomManager, AIConnector aiConnector, ChatServer server) {
        this.socket = socket;
        this.session = session;
        this.in = in;
        this.out = out;
        this.roomManager = roomManager;
        this.aiConnector = aiConnector;
        this.server = server;
        this.currentRoom = session.getCurrentRoom();
        
        if (this.currentRoom != null) {
            setInRoom(true);
        }
        
        try {
            this.socket.setSoTimeout(60000);
            this.socket.setKeepAlive(true);
        } catch (SocketException e) {
            System.err.println("Error configuring socket: " + e.getMessage());
        }
    }
    
    private boolean isRunning() {
        Lock readLock = runningLock.readLock();
        readLock.lock();
        try {
            return running;
        } finally {
            readLock.unlock();
        }
    }

    private void setRunning(boolean value) {
        Lock writeLock = runningLock.writeLock();
        writeLock.lock();
        try {
            running = value;
        } finally {
            writeLock.unlock();
        }
    }

    private boolean isInRoom() {
        Lock readLock = inRoomLock.readLock();
        readLock.lock();
        try {
            return inRoom;
        } finally {
            readLock.unlock();
        }
    }

    private void setInRoom(boolean value) {
        Lock writeLock = inRoomLock.writeLock();
        writeLock.lock();
        try {
            inRoom = value;
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * processes messages from the client
     */
    public void processMessages() {
        try {
            System.out.println("ClientHandler for " + session.getUser().getUsername() + 
                            " started. Current room: " + 
                            (session.getCurrentRoom() != null ? 
                            session.getCurrentRoom().getName() : "None"));
            
            if (session.getCurrentRoom() == null) {
                sendRoomList();
            }
            
            if (session.getCurrentRoom() != null) {
                currentRoom = session.getCurrentRoom();
                setInRoom(true);
                rejoinRoom(currentRoom.getName());
                System.out.println("User " + session.getUser().getUsername() + 
                                " restored to room " + currentRoom.getName());
            }
            
            Thread heartbeatThread = Thread.startVirtualThread(this::heartbeatTask);
            
            Thread messageThread = Thread.startVirtualThread(this::handleIncomingMessages);
            
            while (isRunning() && !socket.isClosed()) {
                Thread.sleep(500); 
            }
        } catch (Exception e) {
            System.err.println("Error in ClientHandler: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    /**
     * sends heartbeats to verify connection
     */
    private void heartbeatTask() {
        try {
            while (isRunning() && socket != null && !socket.isClosed()) {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                
                if (!isSocketConnected()) {
                    System.out.println("Connection with " + session.getUser().getUsername() + " lost. Terminating old session.");
                    setRunning(false);
                    break;
                }
                
                try {
                    out.println("HEARTBEAT");
                    out.flush();
                } catch (Exception e) {
                    System.out.println("Error sending heartbeat to " + session.getUser().getUsername() + ": " + e.getMessage());
                    setRunning(false);
                    break;
                }
            }
            System.out.println("Heartbeat thread for " + session.getUser().getUsername() + " terminated.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * checks if socket is connected
     */
    private boolean isSocketConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected() 
               && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }
    
    /**
     * handles incoming messages from client
     */
    private void handleIncomingMessages() {
        try {
            String inputLine;
            while (isRunning() && (inputLine = in.readLine()) != null) {
                if ("HEARTBEAT_ACK".equals(inputLine)) {
                    continue;
                }
                
                String[] parts = inputLine.split(" ", 2);
                if (parts.length == 0) continue;
                
                String command = parts[0];
                
                switch (command) {
                    case "LIST_ROOMS":
                        sendRoomList();
                        break;
                        
                    case "JOIN_ROOM":
                        if (parts.length < 2) {
                            out.println("INVALID_FORMAT");
                            continue;
                        }
                        joinRoom(parts[1]);
                        break;
                        
                    case "CREATE_ROOM":
                        if (parts.length < 2) {
                            out.println("INVALID_FORMAT");
                            continue;
                        }
                        createRoom(parts[1], false, null);
                        break;
                        
                    case "CREATE_AI_ROOM":
                        if (parts.length < 2) {
                            out.println("INVALID_FORMAT");
                            continue;
                        }
                        String[] roomInfo = parts[1].split("\\|", 2);
                        if (roomInfo.length != 2) {
                            out.println("INVALID_FORMAT_AI_ROOM");
                            continue;
                        }
                        createRoom(roomInfo[0], true, roomInfo[1]);
                        break;
                        
                    case "MESSAGE":
                        if (parts.length < 2) {
                            out.println("INVALID_FORMAT");
                            continue;
                        }
                        if (currentRoom == null) {
                            out.println("ERROR Not in a room");
                            continue;
                        }
                        String message = parts[1];
                        sendMessage(message);
                        break;
                        
                    case "LEAVE_ROOM":
                        leaveCurrentRoom();
                        break;
                        
                    case "LOGOUT":
                        setRunning(false);
                        server.removeSession(session.getToken());
                        out.println("LOGGED_OUT");
                        break;
                        
                    case "HEARTBEAT":
                        out.println("HEARTBEAT_ACK");
                        break;
                        
                    default:
                        out.println("UNKNOWN_COMMAND");
                }
            }
        } catch (IOException e) {
            if (isRunning()) {
                System.err.println("Error reading from client " + session.getUser().getUsername() + ": " + e.getMessage());
            }
        } finally {
            setRunning(false);
        }
    }
    
    /**
     * sends list of available rooms
     */
    private void sendRoomList() {
        List<String> rooms = roomManager.getRoomNames();
        out.println("ROOM_LIST " + String.join(",", rooms));
    }
    
    /**
     * rejoins a room after reconnection
     */
    private void rejoinRoom(String roomName) {
        System.out.println("Reconnecting " + session.getUser().getUsername() + " to room " + roomName);
        
        Room room = roomManager.getRoom(roomName);
        if (room == null) {
            System.out.println("Room not found during reconnection: " + roomName);
            currentRoom = null;
            session.setCurrentRoom(null);
            setInRoom(false);
            return;
        }
        
        if (currentRoom != null && currentRoom.isAIRoom() && !room.isAIRoom()) {
            System.out.println("Transitioning from AI room to regular room during reconnection");
        }
        
        currentRoom = room;
        session.setCurrentRoom(room);
        setInRoom(true);
        
        room.addUser(session.getUser(), message -> {
            try {
                out.println("ROOM_MESSAGE " + message);
                out.flush(); 
            } catch (Exception e) {
                System.err.println("Error sending message to " + session.getUser().getUsername() + ": " + e.getMessage());
            }
        });
        
        try {
            out.println("ROOM_MESSAGE [System: Reconnected to room " + roomName + "]");
            out.flush();
        } catch (Exception e) {
            System.err.println("Error sending reconnect notification: " + e.getMessage());
        }
    }
    
    /**
     * joins a chat room
     */
    private void joinRoom(String roomName) {
        if (isInRoom()) {
            leaveCurrentRoom();
        }
        
        Room room = roomManager.getRoom(roomName);
        if (room == null) {
            out.println("ERROR Room not found");
            return;
        }
        
        currentRoom = room;
        session.setCurrentRoom(room);
        setInRoom(true);
        
        room.addUser(session.getUser(), message -> {
            try {
                out.println("ROOM_MESSAGE " + message);
                out.flush();
            } catch (Exception e) {
                System.err.println("Error sending message to " + session.getUser().getUsername() + ": " + e.getMessage());
            }
        });
        
        out.println("JOINED_ROOM " + roomName);
        currentRoom.addSystemMessage(session.getUser().getUsername() + " enters the room");
    }
    
    /**
     * creates a new chat room
     */
    private void createRoom(String roomName, boolean isAIRoom, String aiPrompt) {
        if (roomManager.roomExists(roomName)) {
            out.println("ERROR Room already exists");
            return;
        }
        
        if (isAIRoom) {
            roomManager.createAIRoom(roomName, aiPrompt);
            out.println("AI_ROOM_CREATED " + roomName);
        } else {
            roomManager.createRoom(roomName);
            out.println("ROOM_CREATED " + roomName);
        }
        
        joinRoom(roomName);
    }
    
    /**
     * sends a message to the current room
     */
    private void sendMessage(String message) {
        if (currentRoom == null || !isInRoom()) {
            out.println("ERROR Not in a room");
            return;
        }
        
        currentRoom.addUserMessage(session.getUser(), message);
        
        if (currentRoom.isAIRoom()) {
            String prompt = currentRoom.getAIPrompt();
            String context = currentRoom.getMessageHistory();
            
            Thread.startVirtualThread(() -> {
                try {
                    System.out.println("Getting AI response for room: " + currentRoom.getName());
                    
                    String aiResponse = aiConnector.getAIResponse(prompt, context);
                    
                    if (aiResponse == null || aiResponse.trim().isEmpty()) {
                        System.err.println("Empty or null AI response");
                        currentRoom.addSystemMessage("Error: Bot did not generate a valid response");
                        return;
                    }
                    
                    System.out.println("AI response received (" + aiResponse.length() + " characters)");
                    
                    String cleanedResponse = cleanAIResponse(aiResponse);
                    currentRoom.addBotMessage(cleanedResponse);
                } catch (Exception e) {
                    System.err.println("Error getting AI response: " + e.getMessage());
                    e.printStackTrace();
                    currentRoom.addSystemMessage("Error: Bot not available - " + e.getMessage());
                }
            });
        }
    }

    private String cleanAIResponse(String response) {
        String cleaned = response.replaceAll("<assistant>|</assistant>", "")
                                .replaceAll("\\\\u003c", "<")
                                .replaceAll("\\\\u003e", ">");
        return cleaned;
    }
    
    /**
     * leaves the current room
     */
    private void leaveCurrentRoom() {
        if (currentRoom != null && isInRoom()) {
            if (isRunning()) {
                currentRoom.addSystemMessage(session.getUser().getUsername() + " leaves the room");
            }
            currentRoom.removeUser(session.getUser());
            currentRoom = null;
            session.setCurrentRoom(null);
            setInRoom(false);
            try {
                out.println("LEFT_ROOM");
            } catch (Exception e) {
            }
        }
    }
    
    /**
     * cleans up resources
     */
    private void cleanup() {
        boolean isExplicitLogout = isRunning();
        
        setRunning(false);
        
        if (currentRoom != null && isInRoom()) {
            if (isExplicitLogout) {
                currentRoom.addSystemMessage(session.getUser().getUsername() + " leaves the room");
            }
            currentRoom.removeUser(session.getUser());
            setInRoom(false);
        }
        
        if (!isExplicitLogout) {
            System.out.println("Keeping " + session.getUser().getUsername() + 
                            " associated with room " + 
                            (currentRoom != null ? currentRoom.getName() : "none") + 
                            " for future reconnection");
        } else {
            session.setCurrentRoom(null);
            currentRoom = null;
        }
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing socket: " + e.getMessage());
        }
    }
}