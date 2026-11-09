package com.chat.client;

import com.chat.protocol.Packet;
import com.chat.protocol.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatClient của phía Client.
 * 
 * Quản lý:
 * - Kết nối Socket tới ChatServer.
 * - Luồng đọc gói tin nền (Reader Thread).
 * - Luồng truyền file bất đồng bộ qua ExecutorService để không làm gián đoạn việc chat.
 * - Quản lý các phiên nhận file (FileTransferSession).
 */
public class ChatClient {

    public static final int CHUNK_SIZE = 16384; // 16KB mỗi khối dữ liệu truyền

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean isConnected = false;
    private String currentUsername = "";

    private ChatClientListener listener;

    // Quản lý các file đang chờ gửi: Key = fileId, Value = File
    private final Map<String, File> pendingSendFiles = new ConcurrentHashMap<>();

    // Quản lý người nhận của file gửi: Key = fileId, Value = recipient username
    private final Map<String, String> pendingSendRecipients = new ConcurrentHashMap<>();

    // Quản lý các phiên đang nhận file: Key = fileId, Value = FileTransferSession
    private final Map<String, FileTransferSession> activeReceives = new ConcurrentHashMap<>();

    // Thread pool dùng riêng cho việc đọc file và đẩy byte lên mạng
    private final ExecutorService fileTransferExecutor = Executors.newCachedThreadPool();

    public ChatClient(ChatClientListener listener) {
        this.listener = listener;
    }

    public void setListener(ChatClientListener listener) {
        this.listener = listener;
    }


    public void connect(String host, int port, String username) {
        new Thread(() -> {
            try {
                this.socket = new Socket(host, port);
                this.in = new DataInputStream(socket.getInputStream());
                this.out = new DataOutputStream(socket.getOutputStream());
                this.currentUsername = username;
                this.isConnected = true;

                if (listener != null) {
                    listener.onConnected();
                }

                // Gửi LOGIN_REQUEST
                Packet loginReq = Packet.createLoginRequest(username);
                sendPacket(loginReq);

                // Chờ phản hồi LOGIN_RESPONSE
                Packet loginResp = Packet.readFrom(in);
                while (loginResp.getType() != PacketType.LOGIN_RESPONSE) {
                    loginResp = Packet.readFrom(in);
                }
                if (!loginResp.isSuccess()) {
                    String reason = loginResp.getContent() != null ? loginResp.getContent() : "Không xác định";
                    disconnect("Đăng nhập thất bại: " + reason);
                    if (listener != null) {
                        listener.onLoginFailed(reason);
                    }
                    return;
                }

                if (listener != null) {
                    listener.onLoginSuccess(username, loginResp.getUserList());
                }

                // Bắt đầu vòng lặp đọc gói tin liên tục từ Server
                readIncomingPackets();

            } catch (IOException e) {
                disconnect("Không thể kết nối tới Server: " + e.getMessage());
                if (listener != null) {
                    listener.onLoginFailed("Lỗi kết nối: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * Vòng lặp nhận dữ liệu từ Server.
     */
    private void readIncomingPackets() {
        try {
            while (isConnected && !socket.isClosed()) {
                Packet packet = Packet.readFrom(in);

                switch (packet.getType()) {
                    case USER_LIST_UPDATE:
                        if (listener != null) {
                            listener.onUserListUpdated(packet.getUserList());
                        }
                        break;

                    case CHAT_MESSAGE:
                        if (listener != null) {
                            listener.onChatMessageReceived(
                                packet.getSender(), packet.getRecipient(), 
                                packet.getContent(), packet.getTimestamp()
                            );
                        }
                        break;

                    case FILE_REQUEST:
                        if (listener != null) {
                            listener.onFileRequestReceived(
                                packet.getFileId(), packet.getSender(), 
                                packet.getFileName(), packet.getFileSize()
                            );
                        }
                        break;

                    case FILE_RESPONSE:
                        handleFileResponse(packet);
                        break;

                    case FILE_CHUNK:
                        handleFileChunk(packet);
                        break;

                    case KICK:
                        String kickReason = packet.getContent();
                        disconnect("Bị Server kích (" + kickReason + ")");
                        if (listener != null) {
                            listener.onKicked(kickReason);
                        }
                        return;

                    case ROOM_LIST_UPDATE:
                        if (listener != null) {
                            listener.onRoomListUpdated(packet.getRoomList());
                        }
                        break;

                    case ROOM_MESSAGE:
                        if (listener != null) {
                            listener.onRoomMessageReceived(
                                packet.getSender(), packet.getRoomName(),
                                packet.getContent(), packet.getTimestamp()
                            );
                        }
                        break;

                    case CALL_REQUEST:
                        if (listener != null) {
                            listener.onCallRequestReceived(packet.getSender(), packet.getUdpPort(), packet.getVideoUdpPort(), packet.getContent());
                        }
                        break;

                    case CALL_RESPONSE:
                        if (listener != null) {
                            listener.onCallResponseReceived(
                                packet.getSender(), packet.isSuccess(),
                                packet.getUdpPort(), packet.getVideoUdpPort(), packet.getContent()
                            );
                        }
                        break;

                    case CALL_END:
                        if (listener != null) {
                            listener.onCallEnded(packet.getSender(), packet.getContent());
                        }
                        break;

                    case HISTORY_RESPONSE:
                        if (listener != null) {
                            listener.onHistoryReceived(packet.getRecipient(), packet.getHistoryList());
                        }
                        break;

                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (isConnected) {
                disconnect("Mất kết nối với Server: " + e.getMessage());
            }
        }
    }

    /**
     * Gửi tin nhắn văn bản (Broadcast hoặc Private).
     */
    public void sendMessage(String recipient, String message) {
        if (!isConnected) return;
        Packet packet = Packet.createChatMessage(currentUsername, recipient, message);
        sendPacket(packet);
    }

    /**
     * Gửi yêu cầu truyền file cho Client khác.
     */
    public void sendFileRequest(String recipient, File file) {
        if (!isConnected || file == null || !file.exists()) {
            if (listener != null) {
                listener.onFileTransferFailed("", file != null ? file.getName() : "null", true, "File không tồn tại!");
            }
            return;
        }

        // Kiểm tra dung lượng file hợp lệ (> 0 byte)
        long fileSize = file.length();
        if (fileSize <= 0) {
            if (listener != null) {
                listener.onFileTransferFailed("", file.getName(), true, "Không thể gửi file có dung lượng 0 byte!");
            }
            return;
        }

        String fileId = UUID.randomUUID().toString();
        pendingSendFiles.put(fileId, file);
        pendingSendRecipients.put(fileId, recipient);

        Packet req = Packet.createFileRequest(fileId, currentUsername, recipient, file.getName(), fileSize);
        sendPacket(req);
    }

    /**
     * Xử lý phản hồi chấp nhận hoặc từ chối gửi file.
     */
    private void handleFileResponse(Packet packet) {
        String fileId = packet.getFileId();
        boolean accepted = packet.isSuccess();

        if (listener != null) {
            listener.onFileResponseReceived(fileId, packet.getSender(), accepted, packet.getContent());
        }

        File file = pendingSendFiles.get(fileId);
        String recipient = pendingSendRecipients.get(fileId);

        if (file == null || recipient == null) {
            return;
        }

        if (accepted) {
            // Đối phương đồng ý, kích hoạt luồng truyền các chunk byte nhị phân trong thread pool
            fileTransferExecutor.submit(() -> streamFileChunks(fileId, recipient, file));
        } else {
            // Bị từ chối, giải phóng file khỏi danh sách chờ
            pendingSendFiles.remove(fileId);
            pendingSendRecipients.remove(fileId);
        }
    }

    /**
     * Đọc file theo chuỗi nhị phân và gửi tuần tự qua Socket (Chạy trên luồng nền).
     */
    private void streamFileChunks(String fileId, String recipient, File file) {
        long totalBytes = file.length();
        int totalChunks = (int) Math.ceil((double) totalBytes / CHUNK_SIZE);
        long bytesSent = 0L;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1 && isConnected) {
                chunkIndex++;
                byte[] chunkData;
                if (bytesRead == CHUNK_SIZE) {
                    chunkData = buffer.clone();
                } else {
                    chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                }

                Packet chunkPacket = Packet.createFileChunk(fileId, currentUsername, recipient, chunkIndex, totalChunks, chunkData);
                sendPacket(chunkPacket);

                bytesSent += bytesRead;

                if (listener != null) {
                    listener.onFileProgress(fileId, file.getName(), bytesSent, totalBytes, true);
                }

                // Nghỉ cực ngắn (2ms) để tránh chiếm đoạt 100% băng thông socket làm nghẽn gói tin chat
                Thread.sleep(2);
            }

            if (listener != null) {
                listener.onFileTransferCompleted(fileId, file.getName(), true, "Đã gửi thành công " + file.getName() + " (" + totalBytes + " bytes)");
            }

        } catch (Exception e) {
            if (listener != null) {
                listener.onFileTransferFailed(fileId, file.getName(), true, "Lỗi khi truyền file: " + e.getMessage());
            }
        } finally {
            pendingSendFiles.remove(fileId);
            pendingSendRecipients.remove(fileId);
        }
    }

    /**
     * Chấp nhận yêu cầu nhận file và chỉ định thư mục lưu.
     */
    public void acceptFileRequest(String fileId, String sender, String rawFileName, long fileSize, File saveDirectory) {
        try {
            FileTransferSession session = new FileTransferSession(fileId, sender, rawFileName, fileSize, saveDirectory);
            activeReceives.put(fileId, session);

            // Gửi phản hồi FILE_RESPONSE đồng ý tới người gửi
            Packet response = Packet.createFileResponse(fileId, currentUsername, sender, true, "Sẵn sàng nhận dữ liệu");
            sendPacket(response);

        } catch (IOException e) {
            if (listener != null) {
                listener.onFileTransferFailed(fileId, rawFileName, false, "Không thể tạo file để lưu: " + e.getMessage());
            }
            rejectFileRequest(fileId, sender, "Không thể tạo file trên đĩa cứng: " + e.getMessage());
        }
    }

    /**
     * Từ chối nhận file.
     */
    public void rejectFileRequest(String fileId, String sender, String reason) {
        Packet response = Packet.createFileResponse(fileId, currentUsername, sender, false, reason);
        sendPacket(response);
    }

    /**
     * Tiếp nhận và ghi khối dữ liệu nhị phân vào file đĩa cứng.
     */
    private void handleFileChunk(Packet packet) {
        String fileId = packet.getFileId();
        FileTransferSession session = activeReceives.get(fileId);

        if (session == null) {
            return;
        }

        try {
            session.writeChunk(packet.getChunkData());

            if (listener != null) {
                listener.onFileProgress(
                    fileId, session.getOriginalFileName(), 
                    session.getBytesReceived(), session.getTotalBytes(), false
                );
            }

            // Kiểm tra xem đã nhận đủ khối cuối cùng chưa
            if (packet.getChunkIndex() >= packet.getTotalChunks() || session.getBytesReceived() >= session.getTotalBytes()) {
                session.finish();
                activeReceives.remove(fileId);

                if (listener != null) {
                    listener.onFileTransferCompleted(
                        fileId, session.getOriginalFileName(), false, 
                        "Đã lưu thành công vào: " + session.getTargetFile().getAbsolutePath()
                    );
                }
            }

        } catch (IOException e) {
            session.abort();
            activeReceives.remove(fileId);
            if (listener != null) {
                listener.onFileTransferFailed(fileId, session.getOriginalFileName(), false, "Lỗi khi ghi dữ liệu file: " + e.getMessage());
            }
        }
    }

    /**
     * Gửi gói tin an toàn đồng bộ (Thread-safe).
     */
    public synchronized void sendPacket(Packet packet) {
        try {
            if (out != null && isConnected && !socket.isClosed()) {
                packet.writeTo(out);
            }
        } catch (IOException e) {
            disconnect("Lỗi ghi dữ liệu mạng: " + e.getMessage());
        }
    }

    /**
     * Ngắt kết nối và đóng tài nguyên sạch sẽ.
     */
    public synchronized void disconnect(String reason) {
        if (!isConnected) return;
        isConnected = false;

        try {
            if (out != null && !socket.isClosed()) {
                Packet logout = Packet.createLogout(currentUsername);
                logout.writeTo(out);
            }
        } catch (Exception ignored) {
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }

        // Hủy bỏ các phiên nhận file dở dang
        for (FileTransferSession session : activeReceives.values()) {
            session.abort();
        }
        activeReceives.clear();
        pendingSendFiles.clear();
        pendingSendRecipients.clear();

        if (listener != null) {
            listener.onDisconnected(reason);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    // =========================================================================
    // CÁC PHƯƠNG THỨC GIAO TIẾP MULTICAST ROOM (NHÓM CHAT) - QUA TCP
    // =========================================================================

    /**
     * Yêu cầu Server tạo phòng chat mới.
     */
    public void createRoom(String roomName) {
        if (!isConnected || roomName == null || roomName.trim().isEmpty()) return;
        sendPacket(Packet.createRoomCreate(currentUsername, roomName.trim()));
    }

    /**
     * Yêu cầu tham gia vào một phòng chat để nhận tin nhắn Multicast.
     */
    public void joinRoom(String roomName) {
        if (!isConnected || roomName == null || roomName.trim().isEmpty()) return;
        sendPacket(Packet.createRoomJoin(currentUsername, roomName.trim()));
    }

    /**
     * Yêu cầu rời khỏi một phòng chat.
     */
    public void leaveRoom(String roomName) {
        if (!isConnected || roomName == null || roomName.trim().isEmpty()) return;
        sendPacket(Packet.createRoomLeave(currentUsername, roomName.trim()));
    }

    /**
     * Gửi tin nhắn Multicast vào một phòng chat (chỉ các thành viên trong phòng nhận được).
     */
    public void sendRoomMessage(String roomName, String message) {
        if (!isConnected || roomName == null || message == null || message.trim().isEmpty()) return;
        sendPacket(Packet.createRoomMessage(currentUsername, roomName.trim(), message.trim()));
    }

    // =========================================================================
    // CÁC PHƯƠNG THỨC BẮT TAY BÁO HIỆU VOICE CALL (CALL SIGNALING) - QUA TCP
    // =========================================================================

    /**
     * Gửi yêu cầu gọi thoại/video tới một người dùng khác kèm cổng Audio UDP và Video UDP của mình.
     */
    public void sendCallRequest(String recipient, int myAudioPort, int myVideoPort) {
        if (!isConnected || recipient == null || recipient.trim().isEmpty()) return;
        sendPacket(Packet.createCallRequest(currentUsername, recipient.trim(), myAudioPort, myVideoPort, ""));
    }

    public void sendCallRequest(String recipient, int myAudioPort) {
        sendCallRequest(recipient, myAudioPort, 0);
    }

    /**
     * Phản hồi cuộc gọi (Đồng ý hoặc Từ chối) kèm cổng Audio UDP và Video UDP của mình.
     */
    public void sendCallResponse(String caller, boolean accepted, int myAudioPort, int myVideoPort, String reason) {
        if (!isConnected || caller == null) return;
        sendPacket(Packet.createCallResponse(currentUsername, caller.trim(), accepted, myAudioPort, myVideoPort, reason));
    }

    public void sendCallResponse(String caller, boolean accepted, int myAudioPort, String reason) {
        sendCallResponse(caller, accepted, myAudioPort, 0, reason);
    }

    /**
     * Gửi tín hiệu kết thúc hoặc gác máy cuộc gọi thoại.
     */
    public void sendCallEnd(String recipient, String reason) {
        if (!isConnected || recipient == null) return;
        sendPacket(Packet.createCallEnd(currentUsername, recipient.trim(), reason));
    }

    // =========================================================================
    // TRUY VẤN LỊCH SỬ CHAT TỪ SQLITE DATABASE - QUA TCP
    // =========================================================================

    /**
     * Yêu cầu Server trích xuất lịch sử tin nhắn gần nhất cho một phòng hoặc cuộc trò chuyện.
     */
    public void requestHistory(String target) {
        if (!isConnected || target == null) return;
        sendPacket(Packet.createHistoryRequest(currentUsername, target.trim()));
    }
}
