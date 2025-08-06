import java.time.Instant;
import java.util.UUID;

public class UserSession {
    private final String token;
    private final User user;
    private final Instant expirationTime;
    private Room currentRoom;
    
    /**
     * creates a new user session
     */
    public UserSession(User user, int expirationMinutes) {
        this.user = user;
        this.token = UUID.randomUUID().toString();
        this.expirationTime = Instant.now().plusSeconds(expirationMinutes * 60);
    }
    
    /**
     * returns the session token
     */
    public String getToken() {
        return token;
    }
    
    /**
     * returns the user
     */
    public User getUser() {
        return user;
    }
    
    /**
     * checks if session is expired
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expirationTime);
    }
    
    /**
     * returns the current room
     */
    public Room getCurrentRoom() {
        return currentRoom;
    }
    
    /**
     * sets the current room
     */
    public void setCurrentRoom(Room room) {
        this.currentRoom = room;
    }
}