import java.util.*;
import java.util.concurrent.locks.*;

public class RoomManager {
    private final Map<String, Room> rooms = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * creates a new regular chat room
     */
    public Room createRoom(String name) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            Room room = new Room(name, false, null);
            rooms.put(name, room);
            return room;
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * creates a new AI-enabled chat room
     */
    public Room createAIRoom(String name, String prompt) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            Room room = new Room(name, true, prompt);
            rooms.put(name, room);
            return room;
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * gets a room by name
     */
    public Room getRoom(String name) {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            return rooms.get(name);
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * checks if a room exists
     */
    public boolean roomExists(String name) {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            return rooms.containsKey(name);
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * gets a list of all room names
     */
    public List<String> getRoomNames() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            return new ArrayList<>(rooms.keySet());
        } finally {
            readLock.unlock();
        }
    }
}