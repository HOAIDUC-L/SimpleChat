package com.chat.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AccessLog - Lưu vết lịch sử ra vào của người dùng trên Server.
 */
public class AccessLog implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    public enum ActionType {
        LOGIN("Đăng nhập"),
        LOGOUT("Đăng xuất"),
        KICKED("Bị Server kích"),
        CONNECTION_LOST("Mất kết nối");

        private final String displayName;

        ActionType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final long timestamp;
    private final String username;
    private final ActionType action;
    private final String ipAddress;
    private final int port;
    private final String details;

    public AccessLog(String username, ActionType action, String ipAddress, int port, String details) {
        this.timestamp = System.currentTimeMillis();
        this.username = username;
        this.action = action;
        this.ipAddress = ipAddress;
        this.port = port;
        this.details = details != null ? details : "";
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).format(FORMATTER);
    }

    public String getUsername() {
        return username;
    }

    public ActionType getAction() {
        return action;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return "[" + getFormattedTime() + "] " + username + " - " + action.getDisplayName() 
               + " (" + ipAddress + ":" + port + ") " + (details.isEmpty() ? "" : ": " + details);
    }
}
