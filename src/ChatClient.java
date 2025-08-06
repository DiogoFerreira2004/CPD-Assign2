import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Scanner;
import java.util.concurrent.locks.*;
import javax.net.ssl.*;

public class ChatClient {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 8989;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    
    private static final boolean USE_SSL = true;
    private static String TRUSTSTORE_PATH = "chatclient.jks";
    private static String TRUSTSTORE_PASSWORD = "password";
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean running = true;
    private boolean heartbeatRunning = false;
    private final ReentrantReadWriteLock runningLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock heartbeatLock = new ReentrantReadWriteLock();
    private final ReentrantLock outLock = new ReentrantLock();
    private String username;
    private String token;
    private String currentRoom = null;
    
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

    private boolean isHeartbeatRunning() {
        Lock readLock = heartbeatLock.readLock();
        readLock.lock();
        try {
            return heartbeatRunning;
        } finally {
            readLock.unlock();
        }
    }

    private boolean getAndSetHeartbeatRunning(boolean value) {
        Lock writeLock = heartbeatLock.writeLock();
        writeLock.lock();
        try {
            boolean oldValue = heartbeatRunning;
            heartbeatRunning = value;
            return oldValue;
        } finally {
            writeLock.unlock();
        }
    }

    private void setHeartbeatRunning(boolean value) {
        Lock writeLock = heartbeatLock.writeLock();
        writeLock.lock();
        try {
            heartbeatRunning = value;
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * configures the SSL context for the client
     * uses the truststore to validate server certificates
     * returns an SSLSocketFactory to create secure client sockets
     */
    private SSLSocketFactory configureSSL() {
        try {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream truststoreFile = new FileInputStream(TRUSTSTORE_PATH)) {
                trustStore.load(truststoreFile, TRUSTSTORE_PASSWORD.toCharArray());
                System.out.println("Truststore loaded successfully from " + TRUSTSTORE_PATH);
            }
            
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            System.out.println("SSL configured successfully for client");
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            System.err.println("Error configuring SSL with truststore: " + e.getMessage());
            e.printStackTrace();
            
            try {
                System.out.println("Trying system default SSL configuration...");
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null); 
                return sslContext.getSocketFactory();
            } catch (Exception ex) {
                System.err.println("Failed to initialize default SSL context: " + ex.getMessage());
                ex.printStackTrace();
                return null;
            }
        }
    }
    
    /**
     * starts the chat client
     */
    public void start() {
        try {
            if (!connectToServer()) {
                return;
            }
            
            if (!handleAuthentication()) {
                cleanup();
                return;
            }
            
            Thread receiveThread = Thread.startVirtualThread(this::receiveMessages);
            
            Thread heartbeatThread = Thread.startVirtualThread(this::sendHeartbeats);
            
            handleUserCommands();
            
        } catch (IOException e) {
            System.err.println("Error connecting to server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }
    
    /**
     * connects to the chat server
     */
    private boolean connectToServer() {
        System.out.println("Connecting to server " + SERVER_ADDRESS + ":" + SERVER_PORT + 
                         (USE_SSL ? " (secure connection)" : " (unsecure connection)"));
        
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                if (in != null) {
                    try { in.close(); } catch (IOException e) { /*  */ }
                }
                if (out != null) {
                    out.close();
                }
                
                if (USE_SSL) {
                    try {
                        SSLSocketFactory sslFactory = configureSSL();
                        if (sslFactory != null) {
                            socket = sslFactory.createSocket(SERVER_ADDRESS, SERVER_PORT);
                            System.out.println("Secure connection established with server");
                        } else {
                            System.out.println("SSL configuration failed. Trying unsecure connection...");
                            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                            System.out.println("Unsecure connection established with server");
                        }
                    } catch (Exception e) {
                        System.err.println("SSL connection failure: " + e.getMessage());
                        System.out.println("Trying unsecure connection...");
                        socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                        System.out.println("Unsecure connection established with server");
                    }
                } else {
                    socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                    System.out.println("Connection established with server");
                }
                
                socket.setSoTimeout(30000);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                
                out = new PrintWriter(new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream())), true);
                in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()), 8192);
                
                System.out.println("Communication streams initialized");
                return true;
            } catch (IOException e) {
                System.err.println("Connection attempt " + attempt + " failed: " + e.getMessage());
                
                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    try {
                        long sleepTime = (long) Math.pow(2, attempt - 1) * 1000;
                        System.out.println("Trying again in " + (sleepTime / 1000) + " seconds...");
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    System.err.println("Failed to connect after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * reconnects to the server after connection loss
     */
    private boolean reconnectToServer() {
        System.out.println("Connection lost. Trying to reconnect...");
        
        String previousRoom = currentRoom;
        
        setRunning(false);
        setHeartbeatRunning(false);
        
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            try {
                if (in != null) {
                    try { in.close(); } catch (IOException e) { /*  */ }
                    in = null;
                }
                if (out != null) {
                    out.close();
                    out = null;
                }
                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (IOException e) { /* */ }
                    socket = null;
                }
                
                setRunning(true);
                
                if (USE_SSL) {
                    try {
                        SSLSocketFactory sslFactory = configureSSL();
                        if (sslFactory != null) {
                            socket = sslFactory.createSocket(SERVER_ADDRESS, SERVER_PORT);
                            System.out.println("Secure reconnection established");
                        } else {
                            System.out.println("SSL configuration failed. Trying unsecure reconnection...");
                            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                            System.out.println("Unsecure reconnection established");
                        }
                    } catch (Exception e) {
                        System.err.println("SSL reconnection failure: " + e.getMessage());
                        System.out.println("Trying unsecure connection...");
                        socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                        System.out.println("Unsecure reconnection established");
                    }
                } else {
                    socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                    System.out.println("Reconnection established");
                }
                
                socket.setSoTimeout(30000);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                
                out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);
                in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                
                String authRequest = in.readLine();
                if (!"AUTH_REQUIRED".equals(authRequest)) {
                    System.err.println("Unexpected server response on reconnect: " + authRequest);
                    continue;
                }
                
                if (token != null) {
                    System.out.println("Using existing token to reconnect...");
                    
                    outLock.lock();
                    try {
                        String reconnectCommand = "RECONNECT " + token + (previousRoom != null ? " " + previousRoom : "");
                        System.out.println("Sending reconnect command: " + reconnectCommand);
                        out.println(reconnectCommand);
                        out.flush(); 
                    } finally {
                        outLock.unlock();
                    }
                    
                    String response = in.readLine();
                    System.out.println("Server response to reconnect: " + response);
                    
                    if (response != null && response.startsWith("RECONNECT_SUCCESS")) {
                        System.out.println("Successfully reconnected!");
                        
                        String[] parts = response.split(" ", 3);
                        if (parts.length >= 3) {
                            currentRoom = parts[2];
                            System.out.println("Session restored in room: " + currentRoom);
                        } else {
                            System.out.println("No room information in reconnect response");
                            currentRoom = null;
                        }
                        
                        setHeartbeatRunning(false);
                        try {
                            Thread.sleep(100);  
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        Thread receiveThread = Thread.startVirtualThread(this::receiveMessages);
                        Thread heartbeatThread = Thread.startVirtualThread(this::sendHeartbeats);
                        
                        try {
                            while (in.ready()) {
                                String pending = in.readLine();
                                System.out.println("Discarding pending message after reconnection: " + pending);
                            }
                        } catch (IOException e) {
                            System.err.println("Error clearing buffer: " + e.getMessage());
                        }
                        
                        return true;
                    } else if (response != null && response.equals("SESSION_EXPIRED")) {
                        System.out.println("Session expired. Please authenticate again.");
                        token = null;
                        if (handleAuthentication()) {
                            if (previousRoom != null) {
                                outLock.lock();
                                try {
                                    out.println("JOIN_ROOM " + previousRoom);
                                    out.flush(); 
                                } finally {
                                    outLock.unlock();
                                }
                                currentRoom = previousRoom;
                            }
                            
                            Thread.startVirtualThread(this::receiveMessages);
                            Thread.startVirtualThread(this::sendHeartbeats);
                            return true;
                        }
                    } else {
                        System.out.println("Reconnection failed: " + response);
                        Thread.sleep(500);
                    }
                } else {
                    if (handleAuthentication()) {
                        if (previousRoom != null) {
                            outLock.lock();
                            try {
                                out.println("JOIN_ROOM " + previousRoom);
                                out.flush();
                            } finally {
                                outLock.unlock();
                            }
                            currentRoom = previousRoom;
                        }
                        
                        Thread.startVirtualThread(this::receiveMessages);
                        Thread.startVirtualThread(this::sendHeartbeats);
                        return true;
                    }
                }
            } catch (IOException e) {
                System.err.println("Reconnection attempt " + attempt + " failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            
            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                try {
                    long sleepTime = (long) Math.pow(2, attempt - 1) * 1000;
                    System.out.println("Trying again in " + (sleepTime / 1000) + " seconds... (attempt " + attempt + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                System.err.println("Failed to reconnect after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
                return false;
            }
        }
        return false;
    }
        
    /**
     * handles user authentication
     */
    private boolean handleAuthentication() throws IOException {
        Scanner scanner = new Scanner(System.in);
        boolean authenticated = false;
        
        try {
            System.out.println("Waiting for authentication request from server...");
            String initialResponse = in.readLine();
            
            if (!"AUTH_REQUIRED".equals(initialResponse)) {
                System.out.println("PROTOCOL ERROR: expected AUTH_REQUIRED, received '" + initialResponse + "'");
                return false;
            }
            
            System.out.println("===== AUTHENTICATION =====");
            while (!authenticated && isRunning()) {
                System.out.println("\n1. Login");
                System.out.println("2. Register");
                System.out.print("Choose an option: ");
                
                String option = scanner.nextLine();
                
                switch (option) {
                    case "1":
                        System.out.print("Username: ");
                        username = scanner.nextLine();
                        System.out.print("Password: ");
                        String password = scanner.nextLine();
                        
                        outLock.lock();
                        try {
                            out.println("LOGIN " + username + " " + password);
                        } finally {
                            outLock.unlock();
                        }
                        
                        String response = in.readLine();
                        
                        if (response != null && response.startsWith("AUTH_SUCCESS")) {
                            authenticated = true;
                            
                            String[] parts = response.split(" ", 3);
                            if (parts.length >= 3) {
                                token = parts[2];
                                System.out.println("Login successful! Token obtained.");
                            }
                        } else {
                            System.out.println("Authentication failed. Try again.");
                            if (response == null) {
                                System.out.println("Lost connection to server.");
                                return false;
                            }
                        }
                        break;
                        
                    case "2":
                        System.out.print("Choose a username: ");
                        username = scanner.nextLine();
                        System.out.print("Choose a password: ");
                        password = scanner.nextLine();
                        
                        outLock.lock();
                        try {
                            out.println("REGISTER " + username + " " + password);
                        } finally {
                            outLock.unlock();
                        }
                        
                        response = in.readLine();
                        
                        if (response != null && "REGISTER_SUCCESS".equals(response)) {
                            System.out.println("Registration successful!");
                            
                            System.out.println("Automatically logging in...");
                            outLock.lock();
                            try {
                                out.println("LOGIN " + username + " " + password);
                            } finally {
                                outLock.unlock();
                            }
                            
                            response = in.readLine();
                            
                            if (response != null && response.startsWith("AUTH_SUCCESS")) {
                                authenticated = true;
                                
                                String[] parts = response.split(" ", 3);
                                if (parts.length >= 3) {
                                    token = parts[2];
                                    System.out.println("Login successful! Token obtained.");
                                }
                            }
                        } else {
                            System.out.println("Registration failed: " + response);
                            if (response == null) {
                                System.out.println("Lost connection to server.");
                                return false;
                            }
                        }
                        break;
                        
                    default:
                        System.out.println("Invalid option.");
                }
            }
            
            if (authenticated) {
                System.out.println("\n===== AUTHENTICATION COMPLETE =====");
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            System.err.println("Error during authentication: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * sends heartbeats to keep the connection alive
     */
    private void sendHeartbeats() {
        if (getAndSetHeartbeatRunning(true)) {
            System.out.println("Heartbeat thread already running. Exiting.");
            return;
        }
        
        try {
            System.out.println("Heartbeat thread started.");
            while (isRunning() && socket != null && !socket.isClosed()) {
                try {
                    Thread.sleep(20000);
                    
                    if (!isRunning() || socket == null || socket.isClosed()) {
                        System.out.println("Exit condition detected in heartbeat.");
                        break;
                    }
                    
                    outLock.lock();
                    try {
                        out.println("HEARTBEAT_ACK");
                        out.flush();
                    } finally {
                        outLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Heartbeat thread interrupted.");
                    break;
                } catch (Exception e) {
                    if (isRunning()) {
                        System.err.println("Heartbeat error: " + e.getMessage());
                        break;
                    }
                }
            }
        } finally {
            System.out.println("Heartbeat thread terminated.");
            setHeartbeatRunning(false);
        }
    }
    
    /**
     * handles user commands
     */
    private void handleUserCommands() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Commands: /rooms, /join <room>, /create <room>, /create_ai <room>|<prompt>, /leave, /logout");
            
            while (isRunning()) {
                String input = scanner.nextLine();
                
                if (input.startsWith("/")) {
                    String[] parts = input.substring(1).split(" ", 2);
                    String command = parts[0];
                    
                    switch (command) {
                        case "rooms":
                            outLock.lock();
                            try {
                                out.println("LIST_ROOMS");
                            } finally {
                                outLock.unlock();
                            }
                            break;
                            
                        case "join":
                            if (parts.length < 2) {
                                System.out.println("Usage: /join <room>");
                                continue;
                            }
                            outLock.lock();
                            try {
                                out.println("JOIN_ROOM " + parts[1]);
                                currentRoom = parts[1];
                            } finally {
                                outLock.unlock();
                            }
                            break;
                            
                        case "create":
                            if (parts.length < 2) {
                                System.out.println("Usage: /create <room>");
                                continue;
                            }
                            outLock.lock();
                            try {
                                out.println("CREATE_ROOM " + parts[1]);
                                currentRoom = parts[1];
                            } finally {
                                outLock.unlock();
                            }
                            break;
                            
                        case "create_ai":
                            if (parts.length < 2) {
                                System.out.println("Usage: /create_ai <room>|<prompt>");
                                continue;
                            }
                            String[] roomInfo = parts[1].split("\\|", 2);
                            if (roomInfo.length != 2) {
                                System.out.println("Invalid format. Use: /create_ai <room>|<prompt>");
                                continue;
                            }
                            outLock.lock();
                            try {
                                out.println("CREATE_AI_ROOM " + parts[1]);
                                currentRoom = roomInfo[0];
                            } finally {
                                outLock.unlock();
                            }
                            break;
                            
                        case "leave":
                            outLock.lock();
                            try {
                                out.println("LEAVE_ROOM");
                                currentRoom = null;
                            } finally {
                                outLock.unlock();
                            }
                            break;
                            
                        case "logout":
                            outLock.lock();
                            try {
                                out.println("LOGOUT");
                            } finally {
                                outLock.unlock();
                            }
                            setRunning(false);
                            break;

                        case "test_disconnect":
                            System.out.println("Simulating disconnection for test...");
                            try {
                                socket.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                            
                        default:
                            System.out.println("Unknown command: " + command);
                    }
                } else {
                    if (currentRoom != null) {
                        outLock.lock();
                        try {
                            out.println("MESSAGE " + input);
                        } finally {
                            outLock.unlock();
                        }
                    } else {
                        System.out.println("You are not in any room. Join a room first.");
                    }
                }
                
                if (socket.isClosed() || !socket.isConnected()) {
                    if (!reconnectToServer()) {
                        setRunning(false);
                    }
                }
            }
        }
    }
    
    /**
     * receives messages from the server
     */
    private void receiveMessages() {
        try {
            System.out.println("Message receiving thread started");
            String message;
            
            while (isRunning() && socket != null && !socket.isClosed() && (message = in.readLine()) != null) {
                if (message.equals("HEARTBEAT")) {
                    outLock.lock();
                    try {
                        out.println("HEARTBEAT_ACK");
                        out.flush(); 
                    } finally {
                        outLock.unlock();
                    }
                    continue;
                }
                
                if (message.startsWith("ROOM_MESSAGE ")) {
                    String content = message.substring(13);
                    
                    if (content.startsWith("Bot: ")) {
                        System.out.println(content);
                    } else {
                        System.out.println(content);
                    }
                } else if (message.startsWith("ROOM_LIST ")) {
                    System.out.println("Available rooms: " + message.substring(10));
                } else if (message.startsWith("JOINED_ROOM ")) {
                    System.out.println("You joined room: " + message.substring(12));
                } else if (message.equals("LEFT_ROOM")) {
                    System.out.println("You left the room");
                    currentRoom = null;
                } else if (message.startsWith("ROOM_CREATED ")) {
                    System.out.println("Room created: " + message.substring(13));
                } else if (message.startsWith("AI_ROOM_CREATED ")) {
                    System.out.println("AI Room created: " + message.substring(16));
                } else if (message.equals("LOGGED_OUT")) {
                    System.out.println("You have been disconnected");
                    setRunning(false);
                } else if (message.startsWith("ERROR ")) {
                    System.out.println("Error: " + message.substring(6));
                } else if (!message.isEmpty() && System.getProperty("chat.debug") != null) {
                    System.out.println("DEBUG: " + message);
                }
            }
            
            System.out.println("Receive thread ended normally");
        } catch (SocketTimeoutException e) {
            if (isRunning()) {
                System.err.println("Server communication timeout: " + e.getMessage());
                if (!reconnectToServer()) {
                    setRunning(false);
                }
            }
        } catch (IOException e) {
            if (isRunning() && socket != null && !socket.isClosed()) {
                System.err.println("Communication error in receive thread: " + e.getMessage());
                if (!reconnectToServer()) {
                    setRunning(false);
                }
            } else {
                System.out.println("Socket already closed or inactive thread. Exiting normally.");
            }
        } catch (Exception e) {
            if (isRunning()) {
                System.err.println("Unexpected error in receive thread: " + e.getMessage());
                e.printStackTrace();
                if (!reconnectToServer()) {
                    setRunning(false);
                }
            }
        }
    }
    
    /**
     * cleans up resources
     */
    private void cleanup() {
        setRunning(false);
        setHeartbeatRunning(false);
        
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Error during resource cleanup: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        String trustStorePath = System.getProperty("javax.net.ssl.trustStore");
        String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        
        if (trustStorePath != null && !trustStorePath.isEmpty()) {
            TRUSTSTORE_PATH = trustStorePath;
            System.out.println("Using truststore from system property: " + TRUSTSTORE_PATH);
        }
        
        if (trustStorePassword != null && !trustStorePassword.isEmpty()) {
            TRUSTSTORE_PASSWORD = trustStorePassword;
            System.out.println("Using truststore password from system property");
        }
        
        ChatClient client = new ChatClient();
        client.start();
    }
}