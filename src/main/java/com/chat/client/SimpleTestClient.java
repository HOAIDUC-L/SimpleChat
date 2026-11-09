package com.chat.client;

import com.chat.protocol.Packet;
import com.chat.protocol.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * SimpleTestClient - Client kiểm thử giao thức Wire Protocol trên console.
 * - Gửi gói tin LOGIN_REQUEST xác thực tên người dùng.
 * - Nhận LOGIN_RESPONSE kiểm tra tính hợp lệ và chống trùng lặp.
 * - Luồng nền đọc và phân giải các gói tin PacketType (USER_LIST_UPDATE, CHAT_MESSAGE,...).
 * - Cho phép gửi tin nhắn toàn phòng (Broadcast) hoặc tin nhắn riêng (Private: /w <user> <nội dung>).
 */
public class SimpleTestClient {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8888;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile boolean isRunning = true;
    private static final List<String> currentOnlineUsers = new ArrayList<>();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("=================================================");
        System.out.println("  SIMPLE CHAT TEST CLIENT - BƯỚC 2: GIAO THỨC    ");
        System.out.println("=================================================");

        System.out.print("Nhập IP Server [Mặc định " + DEFAULT_HOST + "]: ");
        String hostInput = scanner.nextLine().trim();
        String host = hostInput.isEmpty() ? DEFAULT_HOST : hostInput;

        System.out.print("Nhập Port Server [Mặc định " + DEFAULT_PORT + "]: ");
        String portInput = scanner.nextLine().trim();
        int port = DEFAULT_PORT;
        if (!portInput.isEmpty()) {
            try {
                port = Integer.parseInt(portInput);
            } catch (NumberFormatException e) {
                System.out.println("Port không hợp lệ, chuyển về mặc định: " + DEFAULT_PORT);
            }
        }

        String username = "";
        while (username.isEmpty()) {
            System.out.print("Nhập Username của bạn (ví dụ: Alice, Bob): ");
            username = scanner.nextLine().trim();
        }

        System.out.println("\n[*] Đang kết nối tới " + host + ":" + port + "...");

        try (Socket socket = new Socket(host, port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            // 1. Gửi gói tin LOGIN_REQUEST đầu tiên
            Packet loginReq = Packet.createLoginRequest(username);
            loginReq.writeTo(out);

            // 2. Chờ phản hồi LOGIN_RESPONSE từ Server
            Packet loginResp = Packet.readFrom(in);
            if (!loginResp.isSuccess()) {
                System.err.println("[-] Đăng nhập thất bại: " + loginResp.getContent());
                return;
            }

            System.out.println("[+] Đăng nhập thành công với Username: [" + username + "]");
            synchronized (currentOnlineUsers) {
                currentOnlineUsers.clear();
                currentOnlineUsers.addAll(loginResp.getUserList());
                System.out.println("[+] Danh sách người dùng online: " + currentOnlineUsers);
            }

            printHelp();

            final String myUsername = username;

            // 3. Khởi chạy luồng nền liên tục lắng nghe các gói tin gửi từ Server
            Thread receiverThread = new Thread(() -> {
                try {
                    while (isRunning) {
                        Packet packet = Packet.readFrom(in);

                        switch (packet.getType()) {
                            case USER_LIST_UPDATE:
                                synchronized (currentOnlineUsers) {
                                    currentOnlineUsers.clear();
                                    currentOnlineUsers.addAll(packet.getUserList());
                                }
                                System.out.println("\n[Hệ thống] Cập nhật người dùng online: " + packet.getUserList());
                                System.out.print("> ");
                                break;

                            case CHAT_MESSAGE:
                                printMessage(myUsername, packet);
                                System.out.print("> ");
                                break;

                            default:
                                break;
                        }
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        System.out.println("\n[!] Mất kết nối tới Server: " + e.getMessage());
                    }
                } finally {
                    isRunning = false;
                }
            });
            receiverThread.setDaemon(true);
            receiverThread.start();

            // 4. Luồng chính: Đọc từ bàn phím và gửi các gói tin tương ứng
            System.out.print("> ");
            while (isRunning && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    System.out.print("> ");
                    continue;
                }

                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/exit")) {
                    Packet logout = Packet.createLogout(myUsername);
                    logout.writeTo(out);
                    System.out.println("[*] Đang đăng xuất và thoát...");
                    break;
                } else if (line.equalsIgnoreCase("/users")) {
                    synchronized (currentOnlineUsers) {
                        System.out.println("[*] Người dùng online hiện tại: " + currentOnlineUsers);
                    }
                } else if (line.startsWith("/w ") || line.startsWith("/msg ")) {
                    // Cú pháp gửi tin nhắn riêng: /w <username> <nội dung>
                    String[] parts = line.split("\\s+", 3);
                    if (parts.length < 3) {
                        System.out.println("[!] Cú pháp sai. Hãy dùng: /w <tên-người-nhận> <nội dung tin nhắn>");
                    } else {
                        String targetUser = parts[1];
                        String messageContent = parts[2];
                        Packet privateMsg = Packet.createChatMessage(myUsername, targetUser, messageContent);
                        privateMsg.writeTo(out);
                    }
                } else {
                    // Mặc định: Gửi tin nhắn Broadcast cho tất cả mọi người
                    Packet broadcastMsg = Packet.createChatMessage(myUsername, "ALL", line);
                    broadcastMsg.writeTo(out);
                }

                System.out.print("> ");
            }

        } catch (IOException e) {
            System.err.println("[-] Lỗi kết nối Socket: " + e.getMessage());
        } finally {
            isRunning = false;
        }

        System.out.println("[*] Chương trình Client đã kết thúc.");
    }

    private static void printHelp() {
        System.out.println("-------------------------------------------------");
        System.out.println(" HƯỚNG DẪN DÙNG LỆNH CHAT:");
        System.out.println(" - Gõ văn bản bất kỳ: Gửi cho TẤT CẢ mọi người.");
        System.out.println(" - /w <user> <nội dung>: Gửi tin nhắn RIÊNG 1-1.");
        System.out.println(" - /users: Xem lại danh sách người dùng online.");
        System.out.println(" - /quit: Đăng xuất và thoát chương trình.");
        System.out.println("-------------------------------------------------");
    }

    private static void printMessage(String myUsername, Packet packet) {
        String timeStr = LocalTime.ofInstant(
            Instant.ofEpochMilli(packet.getTimestamp()), ZoneId.systemDefault()
        ).format(TIME_FORMATTER);

        String sender = packet.getSender();
        String recipient = packet.getRecipient();
        String content = packet.getContent();

        if (sender.equalsIgnoreCase("SERVER")) {
            System.out.println("\n[" + timeStr + "][Hệ Thống] " + content);
        } else if (recipient.equalsIgnoreCase("ALL")) {
            System.out.println("\n[" + timeStr + "][Mọi người] " + sender + ": " + content);
        } else if (sender.equalsIgnoreCase(myUsername)) {
            // Tin nhắn riêng do chính mình gửi đi
            System.out.println("\n[" + timeStr + "][Riêng đến " + recipient + "]: " + content);
        } else {
            // Tin nhắn riêng người khác gửi cho mình
            System.out.println("\n[" + timeStr + "][Riêng từ " + sender + "]: " + content);
        }
    }
}
