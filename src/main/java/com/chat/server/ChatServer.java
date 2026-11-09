package com.chat.server;

import com.chat.db.DatabaseManager;
import com.chat.model.AccessLog;
import com.chat.model.User;
import com.chat.protocol.Packet;
import com.chat.protocol.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ChatServer - Server TCP Socket hỗ trợ Broadcast, Multicast (Room/Group Chat), 
 * Call Signaling cho Voice Call UDP và lưu trữ SQLite nhúng.
 */
public class ChatServer {

    public static final int DEFAULT_PORT = 8888;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public interface ServerEventListener {
        void onServerStarted(int port);
        void onServerError(String errorMessage);
        void onClientLoggedIn(User user);
        void onClientLoggedOut(User user, String reason);
        void onAccessLogAdded(AccessLog log);
        void onServerLog(String message);
    }

    private final int port;
    private volatile boolean isRunning = true;
    private ServerSocket serverSocket;

    // Quản lý client đang kết nối và người dùng hoạt động an toàn đa luồng
    private final Map<String, ClientHandler> onlineClients = new ConcurrentHashMap<>();
    private final Map<String, User> activeUsers = new ConcurrentHashMap<>();
    private final List<AccessLog> accessLogs = new CopyOnWriteArrayList<>();

    // Quản lý phòng Multicast: Key = roomName, Value = Set các username tham gia phòng đó
    private final Map<String, Set<String>> roomMembers = new ConcurrentHashMap<>();

    // Cơ sở dữ liệu SQLite nhúng
    private final DatabaseManager dbManager = new DatabaseManager();

    private ServerEventListener eventListener;

    public ChatServer(int port) {
        this.port = port;
    }

    public void setEventListener(ServerEventListener listener) {
        this.eventListener = listener;
    }

    public void start() {
        try {
            // 1. Khởi tạo cơ sở dữ liệu SQLite và nạp danh sách phòng
            try {
                dbManager.init();
                List<String> existingRooms = dbManager.getAllRooms();
                for (String r : existingRooms) {
                    roomMembers.put(r, ConcurrentHashMap.newKeySet());
                }
                log("Đã khởi tạo SQLite Database. Nạp thành công " + roomMembers.size() + " phòng chat.");
            } catch (Exception ex) {
                log("⚠️ Lỗi khởi tạo SQLite Database: " + ex.getMessage());
            }

            // 2. Mở cổng ServerSocket lắng nghe kết nối TCP
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
            isRunning = true;
            printServerBanner();

            if (eventListener != null) {
                eventListener.onServerStarted(port);
            }

            // Khởi động luồng đọc lệnh console của Server (cho phép gõ /kick, /users ngay trên terminal)
            startConsoleCommandListener();

            while (isRunning && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    Thread handlerThread = new Thread(new ClientHandler(clientSocket));
                    handlerThread.start();
                } catch (IOException e) {
                    if (isRunning) {
                        log("Lỗi tiếp nhận kết nối: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            isRunning = false;
            log("Lỗi khởi động Server trên cổng " + port + ": " + e.getMessage());
            if (eventListener != null) {
                eventListener.onServerError(e.getMessage());
            }
        }
    }

    public synchronized void stopServer() {
        isRunning = false;
        try {
            // Ngắt toàn bộ client đang online
            for (ClientHandler client : onlineClients.values()) {
                client.forceDisconnect("Server dừng hoạt động");
            }
            onlineClients.clear();
            activeUsers.clear();
            roomMembers.clear();

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            // Đóng kết nối SQLite
            dbManager.close();

            log("Server đã dừng hoàn toàn.");
        } catch (IOException ignored) {
        }
    }

    /**
     * Kích (Kick) một người dùng khỏi phòng chat.
     */
    public boolean kickClient(String username, String reason) {
        ClientHandler client = onlineClients.get(username);
        User user = activeUsers.get(username);

        if (client != null) {
            String finalReason = (reason == null || reason.trim().isEmpty()) ? "Vi phạm nội quy phòng chat" : reason.trim();
            log(">>> [ADMIN ACTION] Đang kích Client [" + username + "] khỏi phòng chat (Lý do: " + finalReason + ")...");

            // 1. Gửi gói tin KICK cho chính Client đó
            Packet kickPacket = Packet.createKick(username, finalReason);
            client.sendPacket(kickPacket);

            // 2. Ghi nhận nhật ký truy cập (AccessLog)
            String ip = user != null ? user.getIpAddress() : "N/A";
            int clientPort = user != null ? user.getPort() : 0;
            AccessLog logEntry = new AccessLog(username, AccessLog.ActionType.KICKED, ip, clientPort, finalReason);
            accessLogs.add(logEntry);
            if (eventListener != null) {
                eventListener.onAccessLogAdded(logEntry);
            }

            // 3. Đóng kết nối và gỡ khỏi map
            client.forceDisconnect("Bị Server kích: " + finalReason);

            // 4. Thông báo công khai cho toàn phòng
            broadcast(Packet.createChatMessage("SERVER", "ALL", "⚠️ Quản trị viên đã kích [" + username + "] khỏi phòng chat (Lý do: " + finalReason + ")."));

            return true;
        }
        return false;
    }

    public void broadcast(Packet packet) {
        for (ClientHandler client : onlineClients.values()) {
            client.sendPacket(packet);
        }
    }

    public boolean sendDirect(String recipientUsername, Packet packet) {
        ClientHandler recipient = onlineClients.get(recipientUsername);
        if (recipient != null) {
            recipient.sendPacket(packet);
            return true;
        }
        return false;
    }

    public List<String> getOnlineUsernames() {
        return new ArrayList<>(onlineClients.keySet());
    }

    public List<User> getActiveUsersList() {
        return new ArrayList<>(activeUsers.values());
    }

    public List<AccessLog> getAccessLogs() {
        return Collections.unmodifiableList(accessLogs);
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void printServerBanner() {
        System.out.println("==================================================================");
        System.out.println("      SIMPLE CHAT TCP SERVER - QUẢN TRỊ VIÊN & KICK SẴN SÀNG      ");
        System.out.println("==================================================================");
        System.out.println("[+] Lắng nghe trên cổng: " + port);
        System.out.println("[+] Danh sách IP LAN của máy này để Client kết nối tới:");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getAddress().length == 4) {
                        System.out.println("    -> Card [" + ni.getDisplayName() + "]: " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        System.out.println("==================================================================");
        System.out.println("[*] Lệnh Admin Console:");
        System.out.println("    - /kick <username> [lý do] : Kích người dùng ra khỏi phòng");
        System.out.println("    - /users                   : Xem danh sách người dùng và thời gian online");
        System.out.println("    - /broadcast <nội dung>    : Phát tin nhắn Admin cho tất cả Client");
        System.out.println("==================================================================\n");
    }

    private void log(String message) {
        String time = LocalTime.now().format(TIME_FORMATTER);
        String formatted = "[" + time + "][Server] " + message;
        System.out.println(formatted);
        if (eventListener != null) {
            eventListener.onServerLog(formatted);
        }
    }

    /**
     * Cho phép gõ lệnh quản trị trực tiếp trên Terminal của Server.
     */
    private void startConsoleCommandListener() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (isRunning) {
                if (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("/kick ")) {
                        String[] parts = line.split("\\s+", 3);
                        String target = parts[1];
                        String reason = (parts.length > 2) ? parts[2] : "Bị quản trị viên kích";
                        boolean ok = kickClient(target, reason);
                        if (!ok) {
                            System.out.println("[-] Không tìm thấy người dùng [" + target + "] đang online.");
                        }
                    } else if (line.equalsIgnoreCase("/users")) {
                        System.out.println("--- DANH SÁCH NGƯỜI DÙNG ONLINE (" + activeUsers.size() + ") ---");
                        for (User u : activeUsers.values()) {
                            System.out.println("• " + u.getUsername() + " | IP: " + u.getIpAddress() + ":" + u.getPort() 
                                               + " | Vào lúc: " + u.getFormattedConnectedTime() 
                                               + " | Online: " + u.getFormattedDuration());
                        }
                        System.out.println("----------------------------------------------");
                    } else if (line.startsWith("/broadcast ")) {
                        String msg = line.substring(11).trim();
                        broadcast(Packet.createChatMessage("📢 ADMIN", "ALL", msg));
                        log("[Phát thanh Admin]: " + msg);
                    } else {
                        System.out.println("[!] Lệnh không rõ. Dùng: /kick <user> [lý do], /users, /broadcast <tin>");
                    }
                }
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private String username;
        private User userModel;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                if (!handleLogin()) {
                    return;
                }

                while (isRunning) {
                    Packet packet = Packet.readFrom(in);

                    switch (packet.getType()) {
                        case CHAT_MESSAGE:
                            handleChatMessage(packet);
                            break;

                        case FILE_REQUEST:
                            handleFileRequest(packet);
                            break;

                        case FILE_RESPONSE:
                            handleFileResponse(packet);
                            break;

                        case FILE_CHUNK:
                            handleFileChunk(packet);
                            break;

                        case ROOM_CREATE:
                            handleRoomCreate(packet);
                            break;

                        case ROOM_JOIN:
                            handleRoomJoin(packet);
                            break;

                        case ROOM_LEAVE:
                            handleRoomLeave(packet);
                            break;

                        case ROOM_MESSAGE:
                            handleRoomMessage(packet);
                            break;

                        case CALL_REQUEST:
                            handleCallRequest(packet);
                            break;

                        case CALL_RESPONSE:
                            handleCallResponse(packet);
                            break;

                        case CALL_END:
                            handleCallEnd(packet);
                            break;

                        case HISTORY_REQUEST:
                            handleHistoryRequest(packet);
                            break;

                        case LOGOUT:
                            log("Client [" + username + "] yêu cầu đăng xuất.");
                            cleanup("Người dùng tự đăng xuất", AccessLog.ActionType.LOGOUT);
                            return;

                        default:
                            break;
                    }
                }

            } catch (IOException e) {
                log("Client [" + (username != null ? username : socket.getRemoteSocketAddress()) + "] mất kết nối (" + e.getMessage() + ")");
                cleanup("Mất kết nối", AccessLog.ActionType.CONNECTION_LOST);
            }
        }

        private boolean handleLogin() throws IOException {
            Packet loginReq = Packet.readFrom(in);
            if (loginReq.getType() != PacketType.LOGIN_REQUEST) {
                sendPacket(Packet.createLoginResponse(false, "Gói tin đầu tiên phải là LOGIN_REQUEST", null));
                return false;
            }

            String requestedName = loginReq.getSender() != null ? loginReq.getSender().trim() : "";

            if (requestedName.isEmpty() || requestedName.equalsIgnoreCase("ALL") || requestedName.equalsIgnoreCase("SERVER")) {
                sendPacket(Packet.createLoginResponse(false, "Username không hợp lệ!", null));
                return false;
            }

            List<String> currentUsers;
            synchronized (onlineClients) {
                if (onlineClients.containsKey(requestedName)) {
                    sendPacket(Packet.createLoginResponse(false, "Tên đăng nhập '" + requestedName + "' đã có người sử dụng!", null));
                    return false;
                }
                this.username = requestedName;

                // Lưu người dùng vào cơ sở dữ liệu SQLite
                dbManager.saveOrUpdateUser(this.username);

                // Mặc định tự động tham gia "Phòng Chung"
                Set<String> defaultRoom = roomMembers.computeIfAbsent("Phòng Chung", k -> ConcurrentHashMap.newKeySet());
                defaultRoom.add(this.username);

                // Tạo đối tượng User và ghi vết vào danh sách activeUsers
                String ip = socket.getInetAddress().getHostAddress();
                int clientPort = socket.getPort();
                this.userModel = new User(username, ip, clientPort);
                activeUsers.put(this.username, userModel);

                // Ghi nhận AccessLog
                AccessLog logEntry = new AccessLog(username, AccessLog.ActionType.LOGIN, ip, clientPort, "Đăng nhập thành công");
                accessLogs.add(logEntry);

                if (eventListener != null) {
                    eventListener.onClientLoggedIn(userModel);
                    eventListener.onAccessLogAdded(logEntry);
                }

                // Gửi LOGIN_RESPONSE và danh sách phòng cho client này TRƯỚC khi đưa vào onlineClients
                // Điều này ngăn chặn triệt để xung đột Race Condition khi các client khác broadcast đồng thời
                currentUsers = new ArrayList<>(onlineClients.keySet());
                currentUsers.add(this.username);

                sendPacket(Packet.createLoginResponse(true, "Đăng nhập thành công!", currentUsers));
                sendPacket(Packet.createRoomListUpdate(new ArrayList<>(roomMembers.keySet())));

                // Sau khi gói tin đăng nhập đã gửi tới client an toàn, mới kích hoạt nhận broadcast
                onlineClients.put(this.username, this);
            }

            log("Client [" + username + "] đăng nhập lúc " + userModel.getFormattedConnectedTime() 
                + " từ " + socket.getRemoteSocketAddress());

            broadcast(Packet.createUserListUpdate(currentUsers));
            broadcast(Packet.createChatMessage("SERVER", "ALL", ">>> [" + username + "] đã tham gia phòng chat."));
            return true;
        }

        private void handleChatMessage(Packet packet) {
            String recipient = packet.getRecipient();
            String msg = packet.getContent();

            if (recipient.equalsIgnoreCase("ALL")) {
                log("[" + username + " -> Mọi người]: " + msg);
                // Lưu vào SQLite Database (Broadcast)
                dbManager.saveMessage(username, "ALL", msg, "BROADCAST", packet.getTimestamp());
                broadcast(Packet.createChatMessage(username, "ALL", msg));
            } else {
                log("[" + username + " -> " + recipient + " (Riêng)]: " + msg);
                // Lưu vào SQLite Database (Private 1-1)
                dbManager.saveMessage(username, recipient, msg, "PRIVATE", packet.getTimestamp());
                boolean delivered = sendDirect(recipient, Packet.createChatMessage(username, recipient, msg));
                if (delivered) {
                    sendPacket(Packet.createChatMessage(username, recipient, msg));
                } else {
                    sendPacket(Packet.createChatMessage("SERVER", username, "Người dùng '" + recipient + "' không online!"));
                }
            }
        }

        // =========================================================================
        // XỬ LÝ CÁC TÁC VỤ MULTICAST ROOM (NHÓM CHAT)
        // =========================================================================

        private void handleRoomCreate(Packet packet) {
            String rName = packet.getRoomName() != null ? packet.getRoomName().trim() : "";
            if (rName.isEmpty()) return;

            boolean created = dbManager.createRoom(rName, username);
            if (created) {
                Set<String> members = roomMembers.computeIfAbsent(rName, k -> ConcurrentHashMap.newKeySet());
                members.add(username);
                log("[Multicast] Client [" + username + "] đã tạo phòng mới: '" + rName + "'");

                // Broadcast danh sách phòng cập nhật tới TẤT CẢ các Client trong hệ thống
                broadcast(Packet.createRoomListUpdate(new ArrayList<>(roomMembers.keySet())));
                sendPacket(Packet.createChatMessage("SERVER", username, "Đã tạo và tham gia phòng [" + rName + "] thành công!"));
            } else {
                sendPacket(Packet.createChatMessage("SERVER", username, "Phòng [" + rName + "] đã tồn tại!"));
            }
        }

        private void handleRoomJoin(Packet packet) {
            String rName = packet.getRoomName() != null ? packet.getRoomName().trim() : "";
            if (rName.isEmpty()) return;

            Set<String> members = roomMembers.get(rName);
            if (members != null) {
                members.add(username);
                log("[Multicast] Client [" + username + "] đã tham gia phòng: '" + rName + "'");
                sendPacket(Packet.createChatMessage("SERVER", username, "Bạn đã tham gia phòng [" + rName + "]!"));

                // MULTICAST: Chỉ thông báo tới các thành viên có mặt trong phòng này
                Packet notify = Packet.createChatMessage("SERVER", "ROOM:" + rName, ">>> [" + username + "] đã tham gia phòng " + rName);
                for (String member : members) {
                    ClientHandler h = onlineClients.get(member);
                    if (h != null) h.sendPacket(notify);
                }
            } else {
                sendPacket(Packet.createChatMessage("SERVER", username, "Phòng [" + rName + "] không tồn tại!"));
            }
        }

        private void handleRoomLeave(Packet packet) {
            String rName = packet.getRoomName() != null ? packet.getRoomName().trim() : "";
            if (rName.isEmpty()) return;

            Set<String> members = roomMembers.get(rName);
            if (members != null && members.remove(username)) {
                log("[Multicast] Client [" + username + "] đã rời phòng: '" + rName + "'");
                sendPacket(Packet.createChatMessage("SERVER", username, "Bạn đã rời khỏi phòng [" + rName + "]."));

                // MULTICAST: Thông báo tới các thành viên còn lại trong phòng
                Packet notify = Packet.createChatMessage("SERVER", "ROOM:" + rName, "<<< [" + username + "] đã rời phòng " + rName);
                for (String member : members) {
                    ClientHandler h = onlineClients.get(member);
                    if (h != null) h.sendPacket(notify);
                }
            }
        }

        /**
         * MULTICAST MESSAGE ROUTING:
         * Phân phối tin nhắn chỉ tới các Client nằm trong cùng phòng chat.
         * Tuyệt đối không gửi tới các Client ngoài phòng!
         */
        private void handleRoomMessage(Packet packet) {
            String rName = packet.getRoomName();
            String msg = packet.getContent();
            Set<String> members = roomMembers.get(rName);

            if (members != null && members.contains(username)) {
                log("[Multicast][" + rName + "] " + username + ": " + msg);
                // Lưu tin nhắn vào SQLite Database
                dbManager.saveMessage(username, "ROOM:" + rName, msg, "ROOM", packet.getTimestamp());

                // ĐỊNH TUYẾN MULTICAST: Duyệt danh sách thành viên phòng và gửi gói tin
                Packet roomMsgPacket = Packet.createRoomMessage(username, rName, msg);
                for (String member : members) {
                    ClientHandler h = onlineClients.get(member);
                    if (h != null) {
                        h.sendPacket(roomMsgPacket);
                    }
                }
            } else {
                sendPacket(Packet.createChatMessage("SERVER", username, "Bạn cần tham gia phòng '" + rName + "' trước khi gửi tin!"));
            }
        }

        // =========================================================================
        // XỬ LÝ CALL SIGNALING (BẮT TAY CUỘC GỌI VOICE CALL QUA TCP)
        // =========================================================================

        private void handleCallRequest(Packet packet) {
            String callee = packet.getRecipient();
            String callerIp = socket.getInetAddress().getHostAddress();
            log("[Call Signaling - TCP] " + username + " (" + callerIp + ") gọi Voice/Video tới [" + callee + "] (AudioPort: " 
                + packet.getUdpPort() + ", VideoPort: " + packet.getVideoUdpPort() + ")");
            ClientHandler target = onlineClients.get(callee);
            if (target != null) {
                // Đính kèm callerIp, AudioPort và VideoPort vào gói tin
                Packet fwd = Packet.createCallRequest(username, callee, packet.getUdpPort(), packet.getVideoUdpPort(), callerIp);
                target.sendPacket(fwd);
            } else {
                sendPacket(Packet.createCallResponse(callee, username, false, 0, 0, "Người nhận hiện không online"));
            }
        }

        private void handleCallResponse(Packet packet) {
            String caller = packet.getRecipient();
            String responderIp = socket.getInetAddress().getHostAddress();
            log("[Call Signaling - TCP] " + username + " (" + responderIp + ") phản hồi cuộc gọi từ [" + caller + "]: " 
                + (packet.isSuccess() ? "ĐỒNG Ý (AudioPort: " + packet.getUdpPort() + ", VideoPort: " + packet.getVideoUdpPort() + ")" : "TỪ CHỐI (" + packet.getContent() + ")"));
            ClientHandler target = onlineClients.get(caller);
            if (target != null) {
                // Nếu đồng ý: content chứa IP của responder; nếu từ chối: content chứa lý do
                String msgOrIp = packet.isSuccess() ? responderIp : packet.getContent();
                Packet fwd = Packet.createCallResponse(username, caller, packet.isSuccess(), packet.getUdpPort(), packet.getVideoUdpPort(), msgOrIp);
                target.sendPacket(fwd);
            }
        }

        private void handleCallEnd(Packet packet) {
            String peer = packet.getRecipient();
            log("[Call Signaling - TCP] " + username + " kết thúc/gác máy cuộc gọi với [" + peer + "]");
            ClientHandler target = onlineClients.get(peer);
            if (target != null) {
                target.sendPacket(packet);
            }
        }

        // =========================================================================
        // TRÍCH XUẤT LỊCH SỬ TIN NHẮN TỪ SQLITE
        // =========================================================================

        private void handleHistoryRequest(Packet packet) {
            String target = packet.getRecipient();
            List<String> history = dbManager.getRecentMessages(target, 50);
            sendPacket(Packet.createHistoryResponse(target, history));
        }

        private void handleFileRequest(Packet packet) {
            String recipient = packet.getRecipient();
            log("[File Request] " + username + " muốn gửi file '" + packet.getFileName() 
                + "' (" + packet.getFileSize() + " bytes) cho [" + recipient + "]");

            boolean forwarded = sendDirect(recipient, packet);
            if (!forwarded) {
                sendPacket(Packet.createFileResponse(packet.getFileId(), "SERVER", username, false, 
                    "Người nhận '" + recipient + "' không online hoặc đã ngắt kết nối!"));
            }
        }

        private void handleFileResponse(Packet packet) {
            String requester = packet.getRecipient();
            log("[File Response] " + username + " " + (packet.isSuccess() ? "ĐỒNG Ý" : "TỪ CHỐI") 
                + " nhận file từ [" + requester + "]: " + packet.getContent());
            sendDirect(requester, packet);
        }

        private void handleFileChunk(Packet packet) {
            sendDirect(packet.getRecipient(), packet);
        }

        public synchronized void sendPacket(Packet packet) {
            try {
                if (out != null && !socket.isClosed()) {
                    packet.writeTo(out);
                }
            } catch (IOException ignored) {
            }
        }

        public void forceDisconnect(String reason) {
            cleanup(reason, null);
        }

        private synchronized void cleanup(String reason, AccessLog.ActionType actionType) {
            if (username == null) {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException ignored) {
                }
                return;
            }

            String userToClean = this.username;
            this.username = null; // Đặt null ngay để chống gọi đúp từ các luồng khác

            ClientHandler removed = onlineClients.remove(userToClean);
            User removedUser = activeUsers.remove(userToClean);

            // Tự động gỡ user khỏi tất cả các phòng Multicast đã tham gia và thông báo cho phòng
            for (Map.Entry<String, Set<String>> entry : roomMembers.entrySet()) {
                String rName = entry.getKey();
                Set<String> members = entry.getValue();
                if (members != null && members.remove(userToClean)) {
                    Packet roomNotice = Packet.createChatMessage("SERVER", "ROOM:" + rName, "<<< [" + userToClean + "] đã rời phòng " + rName);
                    for (String member : members) {
                        ClientHandler h = onlineClients.get(member);
                        if (h != null) h.sendPacket(roomNotice);
                    }
                }
            }

            if (removed != null && actionType != null) {
                String ip = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "N/A";
                int clientPort = socket.getPort();
                AccessLog logEntry = new AccessLog(userToClean, actionType, ip, clientPort, reason);
                accessLogs.add(logEntry);

                if (eventListener != null) {
                    eventListener.onAccessLogAdded(logEntry);
                    eventListener.onClientLoggedOut(removedUser, reason);
                }
            } else if (removed != null && eventListener != null && removedUser != null) {
                eventListener.onClientLoggedOut(removedUser, reason);
            }

            log("Client [" + userToClean + "] đã thoát (" + reason + ").");
            List<String> remainingUsers = getOnlineUsernames();
            broadcast(Packet.createUserListUpdate(remainingUsers));
            broadcast(Packet.createChatMessage("SERVER", "ALL", "<<< [" + userToClean + "] đã rời phòng chat."));

            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public Map<String, Set<String>> getRoomMembers() {
        return Collections.unmodifiableMap(roomMembers);
    }

    public List<String> getRoomList() {
        return new ArrayList<>(roomMembers.keySet());
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Cổng không hợp lệ, dùng cổng mặc định: " + DEFAULT_PORT);
            }
        }

        ChatServer server = new ChatServer(port);
        server.start();
    }
}
