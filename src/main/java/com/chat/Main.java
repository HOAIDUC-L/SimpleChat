package com.chat;

import com.chat.gui.ChatWindow;
import com.chat.gui.ServerWindow;
import com.chat.server.ChatServer;

/**
 * Lớp khởi động chính (Main Launcher) của ứng dụng SimpleChat.
 * 
 * - Mặc định khi chạy: Mở giao diện Client (ChatWindow).
 * - Nếu tham số là 'server': Mở giao diện Quản trị Server (ServerWindow).
 * - Nếu tham số là 'server-cli': Chạy Server trên giao diện dòng lệnh console (ChatServer).
 */
public class Main {
    public static void main(String[] args) {
        if (args.length > 0) {
            String mode = args[0].toLowerCase();
            if (mode.equals("server") || mode.equals("server-gui")) {
                ServerWindow.main(args);
                return;
            } else if (mode.equals("server-cli")) {
                ChatServer.main(new String[0]);
                return;
            }
        }
        ChatWindow.main(args);
    }
}
