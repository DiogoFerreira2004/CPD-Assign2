public class User {
    private final String username;
    private final String passwordHash;
    private final String salt;
    
    /**
     * creates a new user
     */
    public User(String username, String passwordHash, String salt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
    }
    
    /**
     * returns the username
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * returns the password hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }
    
    /**
     * returns the salt
     */
    public String getSalt() {
        return salt;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return username.equals(user.username);
    }
    
    @Override
    public int hashCode() {
        return username.hashCode();
    }
}