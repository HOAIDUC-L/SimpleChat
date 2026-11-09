package com.chat;

import com.chat.client.ChatClient;
import com.chat.client.ChatClientListener;
import com.chat.server.ChatServer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kiểm thử tự động tính năng Chat và Truyền File nhị phân qua Socket.
 */
public class FileTransferVerification {

    public static void main(String[] args) throws Exception {
        System.out.println("[+] Bắt đầu kiểm thử toàn diện Chat và Truyền File...");

        // 1. Khởi động Server trên cổng ngẫu nhiên 8999
        int testPort = 8999;
        ChatServer server = new ChatServer(testPort);
        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500);

        // 2. Tạo một file nhị phân mẫu để test (khoảng 100KB)
        File tempDir = Files.createTempDirectory("simplechat_test").toFile();
        File sampleFile = new File(tempDir, "test_document.dat");
        byte[] originalBytes = new byte[102400]; // 100 KB dữ liệu mẫu
        for (int i = 0; i < originalBytes.length; i++) {
            originalBytes[i] = (byte) (i % 256);
        }
        try (FileOutputStream fos = new FileOutputStream(sampleFile)) {
            fos.write(originalBytes);
        }

        CountDownLatch loginLatch = new CountDownLatch(2);
        CountDownLatch chatLatch = new CountDownLatch(1);
        CountDownLatch fileLatch = new CountDownLatch(1);
        AtomicBoolean fileTransferredCorrectly = new AtomicBoolean(false);

        // 3. Client A (Sender - Alice)
        ChatClient clientAlice = new ChatClient(new SimpleListener("Alice") {
            @Override
            public void onLoginSuccess(String username, List<String> onlineUsers) {
                loginLatch.countDown();
            }

            @Override
            public void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String message) {
                if (isSending) {
                    System.out.println("[Alice] Đã gửi file thành công!");
                }
            }
        });

        // 4. Client B (Receiver - Bob)
        File receiveDir = new File(tempDir, "received");
        receiveDir.mkdirs();

        ChatClient[] bobHolder = new ChatClient[1];
        ChatClient clientBob = new ChatClient(new SimpleListener("Bob") {
            @Override
            public void onLoginSuccess(String username, List<String> onlineUsers) {
                loginLatch.countDown();
            }

            @Override
            public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {
                if (sender.equals("Alice") && message.equals("Xin chao Bob!")) {
                    System.out.println("[Bob] Đã nhận tin nhắn chat từ Alice: " + message);
                    chatLatch.countDown();
                }
            }

            @Override
            public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {
                System.out.println("[Bob] Nhận được yêu cầu gửi file từ " + sender + ": " + fileName);
                // Bob tự động đồng ý nhận file
                bobHolder[0].acceptFileRequest(fileId, sender, fileName, fileSize, receiveDir);
            }

            @Override
            public void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String message) {
                if (!isSending) {
                    System.out.println("[Bob] Nhận file hoàn tất: " + message);
                    fileTransferredCorrectly.set(true);
                    fileLatch.countDown();
                }
            }
        });
        bobHolder[0] = clientBob;

        // Kết nối cả 2 client
        clientAlice.connect("127.0.0.1", testPort, "Alice");
        clientBob.connect("127.0.0.1", testPort, "Bob");

        boolean loggedIn = loginLatch.await(5, TimeUnit.SECONDS);
        if (!loggedIn) {
            throw new RuntimeException("Lỗi: Không thể đăng nhập 2 client trong 5 giây.");
        }
        System.out.println("[+] Cả Alice và Bob đã đăng nhập thành công.");

        // Test gửi tin nhắn chat riêng Alice -> Bob
        clientAlice.sendMessage("Bob", "Xin chao Bob!");
        boolean chatReceived = chatLatch.await(5, TimeUnit.SECONDS);
        if (!chatReceived) {
            throw new RuntimeException("Lỗi: Bob không nhận được tin nhắn chat từ Alice.");
        }

        // Test truyền file Alice -> Bob
        System.out.println("[+] Alice bắt đầu gửi file 'test_document.dat' (100KB) cho Bob...");
        clientAlice.sendFileRequest("Bob", sampleFile);

        boolean fileDone = fileLatch.await(10, TimeUnit.SECONDS);
        if (!fileDone || !fileTransferredCorrectly.get()) {
            throw new RuntimeException("Lỗi: Quá trình truyền file không hoàn thành trong thời gian chờ.");
        }

        // Kiểm tra nội dung file Bob nhận được có khớp 100% với file gốc không
        File receivedFile = new File(receiveDir, "test_document.dat");
        if (!receivedFile.exists()) {
            throw new RuntimeException("Lỗi: File nhận không tồn tại trên đĩa cứng!");
        }

        byte[] receivedBytes = Files.readAllBytes(receivedFile.toPath());
        if (receivedBytes.length != originalBytes.length) {
            throw new RuntimeException("Lỗi kích thước file: Gốc " + originalBytes.length + " bytes, Nhận " + receivedBytes.length + " bytes");
        }

        for (int i = 0; i < originalBytes.length; i++) {
            if (originalBytes[i] != receivedBytes[i]) {
                throw new RuntimeException("Lỗi dữ liệu byte tại vị trí " + i + ": Không khớp dữ liệu gốc!");
            }
        }

        System.out.println("[+] Dữ liệu file nhận được khớp CHÍNH XÁC 100% từng byte với file gốc!");

        // Dọn dẹp
        clientAlice.disconnect("Test xong");
        clientBob.disconnect("Test xong");

        System.out.println("=========================================================");
        System.out.println("   TẤT CẢ KIỂM THỬ BƯỚC 3, 4, 5 ĐỀU THÀNH CÔNG RỰC RỠ!   ");
        System.out.println("=========================================================");
        System.exit(0);
    }

    private static class SimpleListener implements ChatClientListener {
        private final String name;
        public SimpleListener(String name) { this.name = name; }
        @Override public void onConnected() {}
        @Override public void onDisconnected(String reason) {}
        @Override public void onLoginSuccess(String username, List<String> onlineUsers) {}
        @Override public void onLoginFailed(String reason) {}
        @Override public void onUserListUpdated(List<String> onlineUsers) {}
        @Override public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {}
        @Override public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {}
        @Override public void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason) {}
        @Override public void onFileProgress(String fileId, String fileName, long bytesTransferred, long totalBytes, boolean isSending) {}
        @Override public void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String message) {}
        @Override public void onFileTransferFailed(String fileId, String fileName, boolean isSending, String errorMessage) {}
        @Override public void onKicked(String reason) {}
    }
}
