import java.io.*;
import java.net.*;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.*;
import javax.net.ssl.*;

public class ChatServer {
    private static final int PORT = 8989;
    private final UserManager userManager;
    private final RoomManager roomManager;
    private final AIConnector aiConnector;
    private final Map<String, UserSession> activeSessions = new HashMap<>();
    private final ReadWriteLock sessionLock = new ReentrantReadWriteLock();
    private static final int SESSION_EXPIRATION_MINUTES = 60;
    
    private static String KEYSTORE_PATH = "chatserver.jks"; 
    private static String KEYSTORE_PASSWORD = "password";
    private static final boolean USE_SSL = true;
    
    /**
     * creates a new chat server instance
     * 
     */
    public ChatServer() {
        this.userManager = new UserManager("users.txt");
        this.roomManager = new RoomManager();
        this.aiConnector = new AIConnector();
        
        roomManager.createRoom("General");
        roomManager.createRoom("Library");
        roomManager.createAIRoom("AI Doodle", "You are a helpful assistant who helps schedule meetings. " +
                "Summarize all user availability suggestions and propose a common meeting time.");
        
        Thread.startVirtualThread(this::cleanupExpiredSessions);
    }
    
    /**
     * configures the SSL context for the server
     * uses the keystore specified in KEYSTORE_PATH with the specified password
     * returns an SSLServerSocketFactory to create secure server sockets
     */
    private SSLServerSocketFactory configureSSL() {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream keystoreFile = new FileInputStream(KEYSTORE_PATH)) {
                keyStore.load(keystoreFile, KEYSTORE_PASSWORD.toCharArray());
                System.out.println("Keystore loaded successfully from " + KEYSTORE_PATH);
            }
            
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
            
            SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
            
            System.out.println("SSL configured successfully for server");
            return serverSocketFactory;
        } catch (Exception e) {
            System.err.println("Error configuring SSL: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * cleans up expired sessions periodically
     */
    private void cleanupExpiredSessions() {
        while (true) {
            try {
                Thread.sleep(60000);
                
                Lock writeLock = sessionLock.writeLock();
                writeLock.lock();
                try {
                    activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
                } finally {
                    writeLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * starts the chat server
     */
    public void start() {
        ServerSocket serverSocket = null;
        
        try {
            if (USE_SSL) {
                SSLServerSocketFactory sslFactory = configureSSL();
                if (sslFactory != null) {
                    serverSocket = sslFactory.createServerSocket(PORT);
                    System.out.println("Secure chat server (SSL/TLS) started on port " + PORT);
                } else {
                    System.out.println("SSL configuration failed. Starting in unsecure mode.");
                    serverSocket = new ServerSocket(PORT);
                    System.out.println("Chat server started on port " + PORT + " (unsecure)");
                }
            } else {
                serverSocket = new ServerSocket(PORT);
                System.out.println("Chat server started on port " + PORT + " (unsecure)");
            }
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New connection from: " + clientSocket.getInetAddress());
                Thread.startVirtualThread(() -> handleClient(clientSocket));
            }
            
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * handles a new client connection
     */
    private void handleClient(Socket clientSocket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("AUTH_REQUIRED");
            System.out.println("Sent AUTH_REQUIRED to client");
            
            String line = in.readLine();
            if (line == null) {
                System.out.println("Client disconnected before authentication");
                return;
            }
            
            System.out.println("Received from client: " + line);
            UserSession session = null;
            
            if (line.startsWith("RECONNECT")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 2) {
                    out.println("INVALID_FORMAT");
                    return;
                }
                
                String token = parts[1];
                String roomToRejoin = parts.length >= 3 ? parts[2] : null;
                System.out.println("Reconnection attempt with token: " + token + 
                                (roomToRejoin != null ? " to room: " + roomToRejoin : ""));
                
                Lock readLock = sessionLock.readLock();
                readLock.lock();
                try {
                    session = activeSessions.get(token);
                    
                    if (session == null || session.isExpired()) {
                        System.out.println("Session expired or not found");
                        out.println("SESSION_EXPIRED");
                        return;
                    }
                    
                    if (roomToRejoin != null) {
                        Room room = roomManager.getRoom(roomToRejoin);
                        if (room != null) {
                            session.setCurrentRoom(room);
                            System.out.println("Room " + roomToRejoin + " set for reconnected user: " + 
                                            session.getUser().getUsername());
                        }
                    }
                    
                    String responseWithRoom;
                    if (session.getCurrentRoom() != null) {
                        responseWithRoom = "RECONNECT_SUCCESS " + session.getUser().getUsername() + 
                                        " " + session.getCurrentRoom().getName();
                    } else {
                        responseWithRoom = "RECONNECT_SUCCESS " + session.getUser().getUsername();
                    }
                    out.println(responseWithRoom);
                    
                    System.out.println("Reconnected user: " + session.getUser().getUsername() + 
                                    (session.getCurrentRoom() != null ? 
                                    " to room: " + session.getCurrentRoom().getName() : ""));
                } finally {
                    readLock.unlock();
                }
            } else if (line.equals("HEARTBEAT_ACK")) {
                line = in.readLine();
                if (line != null && line.startsWith("RECONNECT")) {
                    handleReconnectCommand(clientSocket, line, in, out);
                    return;
                } else {
                    User user = authenticateUser(in, out, line);
                    if (user == null) {
                        System.out.println("Authentication failed");
                        return;
                    }
                    
                    session = createSession(user);
                    out.println("AUTH_SUCCESS " + user.getUsername() + " " + session.getToken());
                    System.out.println("New session created for: " + user.getUsername() + " with token: " + session.getToken());
                }
            } else {
                User user = authenticateUser(in, out, line);
                if (user == null) {
                    System.out.println("Authentication failed");
                    return;
                }
                
                session = createSession(user);
                out.println("AUTH_SUCCESS " + user.getUsername() + " " + session.getToken());
                System.out.println("New session created for: " + user.getUsername() + " with token: " + session.getToken());
            }
            
            ClientHandler clientHandler = new ClientHandler(clientSocket, session, in, out, roomManager, aiConnector, this);
            clientHandler.processMessages();
            
        } catch (SSLException sslEx) {
            System.err.println("SSL error during client handling: " + sslEx.getMessage());
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error in client handler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleReconnectCommand(Socket clientSocket, String line, BufferedReader in, PrintWriter out) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2) {
            out.println("INVALID_FORMAT");
            return;
        }
        
        String token = parts[1];
        String roomToRejoin = parts.length >= 3 ? parts[2] : null;
        System.out.println("Reconnection attempt with token: " + token + 
                        (roomToRejoin != null ? " to room: " + roomToRejoin : ""));
        
        Lock readLock = sessionLock.readLock();
        readLock.lock();
        UserSession session = null;
        try {
            session = activeSessions.get(token);
            
            if (session == null || session.isExpired()) {
                System.out.println("Session expired or not found");
                out.println("SESSION_EXPIRED");
                return;
            }
            
            if (roomToRejoin != null) {
                Room room = roomManager.getRoom(roomToRejoin);
                if (room != null) {
                    session.setCurrentRoom(room);
                    System.out.println("Room " + roomToRejoin + " set for reconnected user: " + 
                                    session.getUser().getUsername());
                }
            }
            
            String responseWithRoom;
            if (session.getCurrentRoom() != null) {
                responseWithRoom = "RECONNECT_SUCCESS " + session.getUser().getUsername() + 
                                " " + session.getCurrentRoom().getName();
            } else {
                responseWithRoom = "RECONNECT_SUCCESS " + session.getUser().getUsername();
            }
            out.println(responseWithRoom);
            
            System.out.println("Reconnected user: " + session.getUser().getUsername() + 
                            (session.getCurrentRoom() != null ? 
                            " to room: " + session.getCurrentRoom().getName() : ""));
            
            ClientHandler clientHandler = new ClientHandler(clientSocket, session, in, out, roomManager, aiConnector, this);
            clientHandler.processMessages();
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * creates a new user session
     */
    private UserSession createSession(User user) {
        UserSession session = new UserSession(user, SESSION_EXPIRATION_MINUTES);
        
        Lock writeLock = sessionLock.writeLock();
        writeLock.lock();
        try {
            activeSessions.put(session.getToken(), session);
        } finally {
            writeLock.unlock();
        }
        
        return session;
    }
    
    /**
     * authenticates a user
     */
    private User authenticateUser(BufferedReader in, PrintWriter out, String initialLine) throws IOException {
        String line = initialLine;
        
        if (line == null || 
            (!"LOGIN".equals(line.split(" ", 2)[0]) && 
             !"REGISTER".equals(line.split(" ", 2)[0]) && 
             !"RECONNECT".equals(line.split(" ", 2)[0]) && 
             !"HEARTBEAT_ACK".equals(line))) {
            line = in.readLine();
        }
        
        while (line != null && "HEARTBEAT_ACK".equals(line)) {
            line = in.readLine();
        }
        
        while (line != null) {
            System.out.println("Auth command received: " + line);
            
            if (line.startsWith("RECONNECT")) {
                return null;
            }
            
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) {
                out.println("INVALID_FORMAT");
                line = in.readLine();
                continue;
            }
            
            String command = parts[0];
            
            switch (command) {
                case "LOGIN":
                    if (parts.length != 3) {
                        out.println("INVALID_FORMAT");
                        line = in.readLine();
                        continue;
                    }
                    String username = parts[1];
                    String password = parts[2];
                    
                    User user = userManager.authenticateUser(username, password);
                    if (user != null) {
                        System.out.println("User authenticated: " + username);
                        return user;
                    } else {
                        System.out.println("Authentication failed for: " + username);
                        out.println("AUTH_FAILED");
                    }
                    break;
                    
                case "REGISTER":
                    if (parts.length != 3) {
                        out.println("INVALID_FORMAT");
                        line = in.readLine();
                        continue;
                    }
                    username = parts[1];
                    password = parts[2];
                    
                    System.out.println("Attempting to register user: " + username);
                    if (userManager.registerUser(username, password)) {
                        System.out.println("User registered successfully: " + username);
                        out.println("REGISTER_SUCCESS");
                    } else {
                        System.out.println("Registration failed for: " + username + " (user already exists)");
                        out.println("REGISTER_FAILED User already exists");
                    }
                    break;
                    
                default:
                    System.out.println("Unknown command received: " + command);
                    out.println("UNKNOWN_COMMAND");
            }
            
            line = in.readLine();
        }
        
        return null;
    }
    
    /**
     * removes a session
     */
    public void removeSession(String token) {
        Lock writeLock = sessionLock.writeLock();
        writeLock.lock();
        try {
            activeSessions.remove(token);
        } finally {
            writeLock.unlock();
        }
    }
    
    public static void main(String[] args) {
        String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
        String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
        
        if (keyStorePath != null && !keyStorePath.isEmpty()) {
            KEYSTORE_PATH = keyStorePath;
            System.out.println("Using keystore from system property: " + KEYSTORE_PATH);
        }
        
        if (keyStorePassword != null && !keyStorePassword.isEmpty()) {
            KEYSTORE_PASSWORD = keyStorePassword;
            System.out.println("Using keystore password from system property");
        }
        
        ChatServer server = new ChatServer();
        server.start();
    }
}