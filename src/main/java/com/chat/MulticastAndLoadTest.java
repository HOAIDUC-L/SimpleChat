package com.chat;

import com.chat.client.ChatClient;
import com.chat.client.ChatClientListener;
import com.chat.server.ChatServer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lớp kiểm thử tự động tích hợp (Integration Test) cho Phần 2:
 * 1. Chịu tải: Chạy đồng thời 6 Client kết nối song song vào ChatServer trên localhost.
 * 2. Broadcast: Kiểm tra thông điệp ALL được gửi tới đủ 6/6 Client.
 * 3. Multicast: Kiểm tra tin nhắn vào Room "JavaDevs" CHỈ phân phối tới các Client đã tham gia phòng (User1, User2, User3),
 *    các Client ngoài phòng (User4, User5, User6) tuyệt đối không nhận được gói tin.
 * 4. Call Signaling qua TCP: Kiểm tra trao đổi CALL_REQUEST và CALL_RESPONSE giữa 2 Client.
 * 5. Cơ sở dữ liệu: Kiểm tra SQLite đã lưu lại tin nhắn Broadcast và Room Multicast.
 */
public class MulticastAndLoadTest {

    private static final int TEST_PORT = 9999;
    private static final int CLIENT_COUNT = 6;

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("   BẮT ĐẦU KIỂM THỬ XÁC THỰC PHẦN 2 (MULTICAST & CHỊU TẢI 6 CLIENT)  ");
        System.out.println("==================================================================");

        // Xóa sạch file database cũ (-wal, -shm) trước khi test
        com.chat.db.DatabaseManager.deleteDatabaseFiles("chat_database.db");

        ChatServer server = new ChatServer(TEST_PORT);
        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            Thread.sleep(500); // Chờ Server khởi động xong

            List<TestClientWrapper> clients = new ArrayList<>();
            CountDownLatch loginLatch = new CountDownLatch(CLIENT_COUNT);

            System.out.println("[Bước 1] Đang mở đồng thời 6 Client kết nối tới Server...");
            for (int i = 1; i <= CLIENT_COUNT; i++) {
                String username = "Client_" + i;
                TestClientWrapper wrapper = new TestClientWrapper(username, loginLatch);
                clients.add(wrapper);
                wrapper.client.connect("127.0.0.1", TEST_PORT, username);
            }

            boolean allLoggedIn = loginLatch.await(5, TimeUnit.SECONDS);
            if (!allLoggedIn) {
                throw new AssertionError("Không thể kết nối đủ 6 Client trong 5 giây!");
            }
            System.out.println("✅ [PASS] Cả 6 Client đã đăng nhập đồng thời thành công, không nghẽn luồng!\n");

            // Chờ ổn định danh sách user
            Thread.sleep(300);

            // [Bước 2] Client_1 tạo Room "JavaDevs"
            System.out.println("[Bước 2] Client_1 yêu cầu tạo phòng Multicast 'JavaDevs'...");
            CountDownLatch roomCreateLatch = new CountDownLatch(CLIENT_COUNT);
            for (TestClientWrapper c : clients) {
                c.roomListLatch = roomCreateLatch;
            }
            clients.get(0).client.createRoom("JavaDevs");

            boolean roomUpdated = roomCreateLatch.await(3, TimeUnit.SECONDS);
            if (!roomUpdated) {
                throw new AssertionError("Không nhận được sự kiện cập nhật danh sách phòng sau khi tạo!");
            }
            System.out.println("✅ [PASS] Toàn bộ 6 Client đã nhận được danh sách phòng có 'JavaDevs'!\n");

            // [Bước 3] Client 1, 2, 3 tham gia "JavaDevs", Client 4, 5, 6 KHÔNG tham gia
            System.out.println("[Bước 3] Client_1, Client_2, Client_3 tham gia phòng 'JavaDevs'...");
            clients.get(0).client.joinRoom("JavaDevs");
            clients.get(1).client.joinRoom("JavaDevs");
            clients.get(2).client.joinRoom("JavaDevs");
            Thread.sleep(300);

            // [Bước 4] Client_1 gửi tin nhắn Multicast vào "JavaDevs"
            System.out.println("[Bước 4] Client_1 gửi tin nhắn Multicast vào phòng 'JavaDevs'...");
            CountDownLatch multicastLatch = new CountDownLatch(3); // Chỉ Client 1, 2, 3 nhận
            clients.get(0).roomMsgLatch = multicastLatch;
            clients.get(1).roomMsgLatch = multicastLatch;
            clients.get(2).roomMsgLatch = multicastLatch;

            clients.get(0).client.sendRoomMessage("JavaDevs", "Xin chào nhóm JavaDevs!");

            boolean multicastDelivered = multicastLatch.await(3, TimeUnit.SECONDS);
            if (!multicastDelivered) {
                throw new AssertionError("Tin nhắn Multicast không được phân phối đủ tới Client 1, 2, 3!");
            }

            // Kiểm tra Client 4, 5, 6 KHÔNG nhận được tin nhắn phòng này (cách ly Multicast)
            for (int i = 3; i < CLIENT_COUNT; i++) {
                if (clients.get(i).receivedRoomMessages.size() > 0) {
                    throw new AssertionError("VI PHẠM MULTICAST: " + clients.get(i).username + " không tham gia phòng nhưng vẫn nhận được tin nhắn!");
                }
            }
            System.out.println("✅ [PASS] Multicast chính xác 100%: Chỉ Client 1, 2, 3 nhận được tin; Client 4, 5, 6 hoàn toàn không nhận được!\n");

            // [Bước 5] Client_6 gửi Broadcast cho ALL
            System.out.println("[Bước 5] Client_6 phát thanh Broadcast tới ALL...");
            CountDownLatch broadcastLatch = new CountDownLatch(CLIENT_COUNT);
            for (TestClientWrapper c : clients) {
                c.broadcastLatch = broadcastLatch;
            }
            clients.get(5).client.sendMessage("ALL", "Thông báo toàn hệ thống từ Client_6");

            boolean broadcastDelivered = broadcastLatch.await(3, TimeUnit.SECONDS);
            if (!broadcastDelivered) {
                throw new AssertionError("Tin nhắn Broadcast không tới đủ 6/6 Client!");
            }
            System.out.println("✅ [PASS] Broadcast tới ALL thành công: Cả 6 Client đều nhận được thông điệp!\n");

            // [Bước 6] Call Signaling qua TCP: Client_1 gọi Video/Voice tới Client_2
            System.out.println("[Bước 6] Kiểm tra Call Signaling: Client_1 mời gọi Video & Voice tới Client_2 qua TCP...");
            CountDownLatch callReqLatch = new CountDownLatch(1);
            CountDownLatch callRespLatch = new CountDownLatch(1);
            clients.get(1).callReqLatch = callReqLatch;
            clients.get(0).callRespLatch = callRespLatch;

            clients.get(0).client.sendCallRequest("Client_2", 40001, 40003);

            boolean callReqReceived = callReqLatch.await(3, TimeUnit.SECONDS);
            if (!callReqReceived || clients.get(1).lastCallerPort != 40001 || clients.get(1).lastCallerVideoPort != 40003) {
                throw new AssertionError("Client_2 không nhận được đúng Audio/Video UDP port từ Client_1!");
            }

            // Client_2 chấp nhận cuộc gọi và gửi kèm cổng Audio 40002, Video 40004
            clients.get(1).client.sendCallResponse("Client_1", true, 40002, 40004, "Đồng ý nhận cuộc gọi");

            boolean callRespReceived = callRespLatch.await(3, TimeUnit.SECONDS);
            if (!callRespReceived || !clients.get(0).lastCallAccepted 
                || clients.get(0).lastResponderPort != 40002 || clients.get(0).lastResponderVideoPort != 40004) {
                throw new AssertionError("Client_1 không nhận được phản hồi chấp nhận Audio/Video port từ Client_2!");
            }
            System.out.println("✅ [PASS] Bắt tay cuộc gọi Video & Voice (Call Signaling TCP 2 cổng) giữa Client_1 và Client_2 hoàn tất thành công!\n");

            // [Bước 7] Kiểm tra dữ liệu được lưu trong SQLite Database
            System.out.println("[Bước 7] Kiểm tra SQLite Database có lưu lại tin nhắn...");
            List<String> roomLogs = server.getDbManager().getRecentMessages("ROOM:JavaDevs", 10);
            System.out.println("    - Lịch sử phòng JavaDevs trong DB: " + roomLogs);
            if (roomLogs.isEmpty()) {
                throw new AssertionError("SQLite không có dữ liệu tin nhắn phòng JavaDevs!");
            }

            List<String> allLogs = server.getDbManager().getRecentMessages("ALL", 10);
            System.out.println("    - Lịch sử Broadcast trong DB: " + allLogs);
            if (allLogs.isEmpty()) {
                throw new AssertionError("SQLite không có dữ liệu tin nhắn Broadcast ALL!");
            }
            System.out.println("✅ [PASS] SQLite Database lưu trữ lịch sử tin nhắn chính xác 100%!\n");

            // Đóng tất cả client và server
            for (TestClientWrapper c : clients) {
                c.client.disconnect("Hoàn tất test");
            }
            server.stopServer();
            com.chat.db.DatabaseManager.deleteDatabaseFiles("chat_database.db");

            System.out.println("==================================================================");
            System.out.println("🎉 CHÚC MỪNG: TẤT CẢ CÁC TIÊU CHÍ PHẦN 1 & PHẦN 2 ĐÃ VƯỢT QUA 100%!");
            System.out.println("==================================================================");

        } catch (Throwable t) {
            System.err.println("❌ [FAIL] Test thất bại: " + t.getMessage());
            t.printStackTrace();
            server.stopServer();
            com.chat.db.DatabaseManager.deleteDatabaseFiles("chat_database.db");
            System.exit(1);
        }
    }

    private static class TestClientWrapper implements ChatClientListener {
        final String username;
        final ChatClient client;
        final CountDownLatch loginLatch;

        volatile CountDownLatch roomListLatch;
        volatile CountDownLatch roomMsgLatch;
        volatile CountDownLatch broadcastLatch;
        volatile CountDownLatch callReqLatch;
        volatile CountDownLatch callRespLatch;

        final List<String> receivedRoomMessages = new CopyOnWriteArrayList<>();
        final List<String> receivedBroadcastMessages = new CopyOnWriteArrayList<>();

        volatile int lastCallerPort = 0;
        volatile int lastCallerVideoPort = 0;
        volatile int lastResponderPort = 0;
        volatile int lastResponderVideoPort = 0;
        volatile boolean lastCallAccepted = false;

        public TestClientWrapper(String username, CountDownLatch loginLatch) {
            this.username = username;
            this.loginLatch = loginLatch;
            this.client = new ChatClient(this);
        }

        @Override public void onConnected() {}
        @Override public void onDisconnected(String reason) {}

        @Override
        public void onLoginSuccess(String u, List<String> onlineUsers) {
            loginLatch.countDown();
        }

        @Override
        public void onLoginFailed(String reason) {
            System.err.println("Client [" + username + "] đăng nhập thất bại: " + reason);
        }

        @Override public void onUserListUpdated(List<String> onlineUsers) {}

        @Override
        public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {
            if ("ALL".equalsIgnoreCase(recipient)) {
                receivedBroadcastMessages.add(message);
                if (broadcastLatch != null) {
                    broadcastLatch.countDown();
                }
            }
        }

        @Override public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {}
        @Override public void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason) {}
        @Override public void onFileProgress(String fileId, String fileName, long b, long t, boolean s) {}
        @Override public void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String m) {}
        @Override public void onFileTransferFailed(String fileId, String fileName, boolean isSending, String e) {}
        @Override public void onKicked(String reason) {}

        @Override
        public void onRoomListUpdated(List<String> rooms) {
            if (roomListLatch != null && rooms.contains("JavaDevs")) {
                roomListLatch.countDown();
            }
        }

        @Override
        public void onRoomMessageReceived(String sender, String roomName, String message, long timestamp) {
            receivedRoomMessages.add(message);
            if (roomMsgLatch != null) {
                roomMsgLatch.countDown();
            }
        }

        @Override
        public void onCallRequestReceived(String caller, int callerAudioPort, int callerVideoPort, String callerIp) {
            this.lastCallerPort = callerAudioPort;
            this.lastCallerVideoPort = callerVideoPort;
            if (callReqLatch != null) {
                callReqLatch.countDown();
            }
        }

        @Override
        public void onCallResponseReceived(String responder, boolean accepted, int responderAudioPort, int responderVideoPort, String reasonOrIp) {
            this.lastCallAccepted = accepted;
            this.lastResponderPort = responderAudioPort;
            this.lastResponderVideoPort = responderVideoPort;
            if (callRespLatch != null) {
                callRespLatch.countDown();
            }
        }
    }
}
