import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.locks.*;

public class UserManager {
    private final Map<String, User> users = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String userFile;
    
    /**
     * creates a new user manager
     */
    public UserManager(String userFile) {
        this.userFile = userFile;
        loadUsers();
    }
    
    /**
     * loads users from file
     */
    private void loadUsers() {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            File file = new File(userFile);
            if (!file.exists()) {
                registerUser("diogo", "1234");
                registerUser("alvaro", "1234");
                registerUser("tomas", "1234");
                registerUser("alice", "password1");
                registerUser("bob", "password2");
                registerUser("eve", "password3");
                saveUsers();
                return;
            }
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3) {
                        String username = parts[0];
                        String passwordHash = parts[1];
                        String salt = parts[2];
                        users.put(username, new User(username, passwordHash, salt));
                    }
                }
            } catch (IOException e) {
                System.err.println("Error loading users: " + e.getMessage());
            }
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * saves users to file
     */
    private void saveUsers() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            try (PrintWriter writer = new PrintWriter(new FileWriter(userFile))) {
                for (User user : users.values()) {
                    writer.println(user.getUsername() + ":" + user.getPasswordHash() + ":" + user.getSalt());
                }
            } catch (IOException e) {
                System.err.println("Error saving users: " + e.getMessage());
            }
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * registers a new user
     */
    public boolean registerUser(String username, String password) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            if (users.containsKey(username)) {
                return false;
            }
            
            String salt = generateSalt();
            String passwordHash = hashPassword(password, salt);
            
            users.put(username, new User(username, passwordHash, salt));
            saveUsers();
            return true;
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * authenticates a user
     */
    public User authenticateUser(String username, String password) {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            System.out.println("Authenticating user: " + username);
            User user = users.get(username);
            if (user == null) {
                System.out.println("User not found: " + username);
                return null;
            }
            
            String hash = hashPassword(password, user.getSalt());
            if (hash.equals(user.getPasswordHash())) {
                System.out.println("Correct password for user: " + username);
                return user;
            }
            
            System.out.println("Incorrect password for user: " + username);
            return null;
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * generates a random salt
     */
    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    
    /**
     * hashes a password with salt
     */
    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashedPassword = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashedPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}