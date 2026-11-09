package com.chat.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Lớp User đại diện cho một người dùng kết nối vào hệ thống SimpleChat.
 */
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String ipAddress;
    private final int port;
    private final long connectedTime;

    public User(String username, String ipAddress, int port) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.port = port;
        this.connectedTime = System.currentTimeMillis();
    }

    public String getUsername() {
        return username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public long getConnectedTime() {
        return connectedTime;
    }

    public String getFormattedConnectedTime() {
        return java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(connectedTime), 
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd/MM"));
    }

    public String getFormattedDuration() {
        long seconds = (System.currentTimeMillis() - connectedTime) / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(username, user.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    @Override
    public String toString() {
        return username + " (" + ipAddress + ":" + port + ")";
    }
}
