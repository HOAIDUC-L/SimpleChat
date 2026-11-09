package com.chat.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lớp Packet đóng gói cấu trúc của tất cả các thông điệp truyền qua TCP Socket.
 *
 * 1. Không dùng String để đọc/ghi file nhị phân vì String sẽ làm sai lệch các byte không phải ký tự.
 * 2. DataOutputStream & DataInputStream cung cấp các hàm ghi/đọc nhị phân chuẩn xác:
 *    - writeByte / readByte
 *    - writeUTF / readUTF (đọc chuỗi độ dài tối đa 64KB có gắn kèm header 2 byte độ dài)
 *    - writeLong / readLong (8 bytes - dùng cho kích thước file)
 *    - writeInt / readInt (4 bytes - dùng cho độ dài mảng byte)
 *    - readFully(byte[]) (đảm bảo đọc đủ số byte từ socket, tránh lỗi phân mảnh TCP).
 */
public class Packet {

    private PacketType type;
    private String sender = "";
    private String recipient = "";     // "ALL" nghĩa là gửi mọi người, hoặc username cụ thể
    private String content = "";       // Nội dung chat hoặc thông điệp phản hồi
    private boolean success = false;   // Dùng cho LOGIN_RESPONSE hoặc FILE_RESPONSE
    private List<String> userList = new ArrayList<>(); // Danh sách user online

    // Các trường dành riêng cho tính năng truyền file
    private String fileId = "";
    private String fileName = "";
    private long fileSize = 0L;
    private int chunkIndex = 0;
    private int totalChunks = 0;
    private byte[] chunkData = new byte[0];

    private long timestamp = System.currentTimeMillis();

    // =========================================================================
    // CÁC TRƯỜNG DÀNH CHO MULTICAST ROOM, VOICE CALL SIGNALING & CHAT HISTORY
    // =========================================================================
    private String roomName = "";                      // Tên phòng/nhóm chat (Multicast)
    private int udpPort = 0;                           // Cổng UDP thỏa thuận cho Voice Call (Audio)
    private int videoUdpPort = 0;                      // Cổng UDP thỏa thuận cho Video Call (Video Streaming)
    private List<String> roomList = new ArrayList<>(); // Danh sách phòng do Server trả về
    private List<String> historyList = new ArrayList<>(); // Lịch sử tin nhắn lấy từ SQLite

    // Constructor mặc định cho việc đọc từ luồng
    public Packet() {
    }

    // --- Các Factory Method tạo nhanh gói tin theo ngữ cảnh ---

    public static Packet createLoginRequest(String username) {
        Packet p = new Packet();
        p.type = PacketType.LOGIN_REQUEST;
        p.sender = username;
        return p;
    }

    public static Packet createLoginResponse(boolean success, String message, List<String> onlineUsers) {
        Packet p = new Packet();
        p.type = PacketType.LOGIN_RESPONSE;
        p.success = success;
        p.content = message;
        if (onlineUsers != null) {
            p.userList = new ArrayList<>(onlineUsers);
        }
        return p;
    }

    public static Packet createUserListUpdate(List<String> onlineUsers) {
        Packet p = new Packet();
        p.type = PacketType.USER_LIST_UPDATE;
        if (onlineUsers != null) {
            p.userList = new ArrayList<>(onlineUsers);
        }
        return p;
    }

    public static Packet createChatMessage(String sender, String recipient, String content) {
        Packet p = new Packet();
        p.type = PacketType.CHAT_MESSAGE;
        p.sender = sender;
        p.recipient = (recipient == null || recipient.trim().isEmpty()) ? "ALL" : recipient;
        p.content = content;
        p.timestamp = System.currentTimeMillis();
        return p;
    }

    public static Packet createFileRequest(String fileId, String sender, String recipient, String fileName, long fileSize) {
        Packet p = new Packet();
        p.type = PacketType.FILE_REQUEST;
        p.fileId = fileId;
        p.sender = sender;
        p.recipient = recipient;
        p.fileName = fileName;
        p.fileSize = fileSize;
        return p;
    }

    public static Packet createFileResponse(String fileId, String sender, String recipient, boolean accepted, String reason) {
        Packet p = new Packet();
        p.type = PacketType.FILE_RESPONSE;
        p.fileId = fileId;
        p.sender = sender;
        p.recipient = recipient;
        p.success = accepted;
        p.content = reason;
        return p;
    }

    public static Packet createFileChunk(String fileId, String sender, String recipient, int chunkIndex, int totalChunks, byte[] data) {
        Packet p = new Packet();
        p.type = PacketType.FILE_CHUNK;
        p.fileId = fileId;
        p.sender = sender;
        p.recipient = recipient;
        p.chunkIndex = chunkIndex;
        p.totalChunks = totalChunks;
        p.chunkData = data;
        return p;
    }

    public static Packet createLogout(String username) {
        Packet p = new Packet();
        p.type = PacketType.LOGOUT;
        p.sender = username;
        return p;
    }

    public static Packet createKick(String targetUsername, String reason) {
        Packet p = new Packet();
        p.type = PacketType.KICK;
        p.sender = "SERVER";
        p.recipient = targetUsername;
        p.content = (reason == null || reason.trim().isEmpty()) ? "Bị quản trị viên kích khỏi phòng chat" : reason;
        return p;
    }

    // --- Các Factory Method cho Room (Multicast) ---

    public static Packet createRoomCreate(String sender, String roomName) {
        Packet p = new Packet();
        p.type = PacketType.ROOM_CREATE;
        p.sender = sender;
        p.roomName = roomName;
        return p;
    }

    public static Packet createRoomJoin(String sender, String roomName) {
        Packet p = new Packet();
        p.type = PacketType.ROOM_JOIN;
        p.sender = sender;
        p.roomName = roomName;
        return p;
    }

    public static Packet createRoomLeave(String sender, String roomName) {
        Packet p = new Packet();
        p.type = PacketType.ROOM_LEAVE;
        p.sender = sender;
        p.roomName = roomName;
        return p;
    }

    public static Packet createRoomListUpdate(List<String> rooms) {
        Packet p = new Packet();
        p.type = PacketType.ROOM_LIST_UPDATE;
        if (rooms != null) {
            p.roomList = new ArrayList<>(rooms);
        }
        return p;
    }

    public static Packet createRoomMessage(String sender, String roomName, String content) {
        Packet p = new Packet();
        p.type = PacketType.ROOM_MESSAGE;
        p.sender = sender;
        p.roomName = roomName;
        p.content = content;
        p.timestamp = System.currentTimeMillis();
        return p;
    }

    // --- Các Factory Method cho Call Signaling (Bắt tay Voice & Video Call qua TCP) ---

    public static Packet createCallRequest(String caller, String callee, int callerAudioPort, int callerVideoPort, String callerIp) {
        Packet p = new Packet();
        p.type = PacketType.CALL_REQUEST;
        p.sender = caller;
        p.recipient = callee;
        p.udpPort = callerAudioPort;
        p.videoUdpPort = callerVideoPort;
        p.content = (callerIp != null) ? callerIp : "";
        return p;
    }

    public static Packet createCallRequest(String caller, String callee, int callerAudioPort, String callerIp) {
        return createCallRequest(caller, callee, callerAudioPort, 0, callerIp);
    }

    public static Packet createCallRequest(String caller, String callee, int callerAudioPort) {
        return createCallRequest(caller, callee, callerAudioPort, 0, "");
    }

    public static Packet createCallResponse(String responder, String caller, boolean accepted, int responderAudioPort, int responderVideoPort, String reasonOrIp) {
        Packet p = new Packet();
        p.type = PacketType.CALL_RESPONSE;
        p.sender = responder;
        p.recipient = caller;
        p.success = accepted;
        p.udpPort = responderAudioPort;
        p.videoUdpPort = responderVideoPort;
        p.content = (reasonOrIp != null) ? reasonOrIp : "";
        return p;
    }

    public static Packet createCallResponse(String responder, String caller, boolean accepted, int responderAudioPort, String reasonOrIp) {
        return createCallResponse(responder, caller, accepted, responderAudioPort, 0, reasonOrIp);
    }

    public static Packet createCallEnd(String sender, String recipient, String reason) {
        Packet p = new Packet();
        p.type = PacketType.CALL_END;
        p.sender = sender;
        p.recipient = recipient;
        p.content = (reason != null) ? reason : "";
        return p;
    }

    // --- Các Factory Method cho Lịch sử Chat SQLite ---

    public static Packet createHistoryRequest(String sender, String target) {
        Packet p = new Packet();
        p.type = PacketType.HISTORY_REQUEST;
        p.sender = sender;
        p.recipient = target;
        return p;
    }

    public static Packet createHistoryResponse(String target, List<String> messages) {
        Packet p = new Packet();
        p.type = PacketType.HISTORY_RESPONSE;
        p.recipient = target;
        if (messages != null) {
            p.historyList = new ArrayList<>(messages);
        }
        return p;
    }

    // --- Phương thức tuần tự hóa (Serialization) ghi ra Socket ---

    /**
     * Ghi gói tin ra DataOutputStream theo giao thức nhị phân.
     */
    public synchronized void writeTo(DataOutputStream out) throws IOException {
        // Luôn ghi 1 byte đầu tiên là mã loại gói tin (OpCode)
        out.writeByte(type.getCode());

        switch (type) {
            case LOGIN_REQUEST:
            case LOGOUT:
                out.writeUTF(sender);
                break;

            case LOGIN_RESPONSE:
                out.writeBoolean(success);
                out.writeUTF(content != null ? content : "");
                out.writeInt(userList.size());
                for (String user : userList) {
                    out.writeUTF(user);
                }
                break;

            case USER_LIST_UPDATE:
                out.writeInt(userList.size());
                for (String user : userList) {
                    out.writeUTF(user);
                }
                break;

            case CHAT_MESSAGE:
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeUTF(content);
                out.writeLong(timestamp);
                break;

            case FILE_REQUEST:
                out.writeUTF(fileId);
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeUTF(fileName);
                out.writeLong(fileSize);
                break;

            case FILE_RESPONSE:
                out.writeUTF(fileId);
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeBoolean(success);
                out.writeUTF(content != null ? content : "");
                break;

            case FILE_CHUNK:
                out.writeUTF(fileId);
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeInt(chunkIndex);
                out.writeInt(totalChunks);
                // Ghi kích thước mảng byte trước, sau đó ghi toàn bộ mảng byte thô
                out.writeInt(chunkData.length);
                out.write(chunkData);
                break;

            case KICK:
                out.writeUTF(recipient);
                out.writeUTF(content != null ? content : "");
                break;

            case ROOM_CREATE:
            case ROOM_JOIN:
            case ROOM_LEAVE:
                out.writeUTF(sender);
                out.writeUTF(roomName != null ? roomName : "");
                break;

            case ROOM_LIST_UPDATE:
                out.writeInt(roomList.size());
                for (String room : roomList) {
                    out.writeUTF(room);
                }
                break;

            case ROOM_MESSAGE:
                out.writeUTF(sender);
                out.writeUTF(roomName != null ? roomName : "");
                out.writeUTF(content != null ? content : "");
                out.writeLong(timestamp);
                break;

            case CALL_REQUEST:
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeInt(udpPort);
                out.writeInt(videoUdpPort);
                out.writeUTF(content != null ? content : "");
                break;

            case CALL_RESPONSE:
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeBoolean(success);
                out.writeInt(udpPort);
                out.writeInt(videoUdpPort);
                out.writeUTF(content != null ? content : "");
                break;

            case CALL_END:
                out.writeUTF(sender);
                out.writeUTF(recipient);
                out.writeUTF(content != null ? content : "");
                break;

            case HISTORY_REQUEST:
                out.writeUTF(sender);
                out.writeUTF(recipient);
                break;

            case HISTORY_RESPONSE:
                out.writeUTF(recipient);
                out.writeInt(historyList.size());
                for (String msg : historyList) {
                    out.writeUTF(msg);
                }
                break;
        }

        // Đẩy toàn bộ dữ liệu từ buffer ra kết nối mạng ngay lập tức
        out.flush();
    }

    // --- Phương thức giải mã (Deserialization) đọc từ Socket ---

    /**
     * Đọc và giải mã 1 gói tin từ DataInputStream.
     */
    public static Packet readFrom(DataInputStream in) throws IOException {
        // Đọc 1 byte đầu tiên để xác định OpCode
        byte code = in.readByte();
        PacketType type = PacketType.fromByte(code);

        Packet p = new Packet();
        p.type = type;

        switch (type) {
            case LOGIN_REQUEST:
            case LOGOUT:
                p.sender = in.readUTF();
                break;

            case LOGIN_RESPONSE:
                p.success = in.readBoolean();
                p.content = in.readUTF();
                int count = in.readInt();
                p.userList = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    p.userList.add(in.readUTF());
                }
                break;

            case USER_LIST_UPDATE:
                int userCount = in.readInt();
                p.userList = new ArrayList<>(userCount);
                for (int i = 0; i < userCount; i++) {
                    p.userList.add(in.readUTF());
                }
                break;

            case CHAT_MESSAGE:
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.content = in.readUTF();
                p.timestamp = in.readLong();
                break;

            case FILE_REQUEST:
                p.fileId = in.readUTF();
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.fileName = in.readUTF();
                p.fileSize = in.readLong();
                break;

            case FILE_RESPONSE:
                p.fileId = in.readUTF();
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.success = in.readBoolean();
                p.content = in.readUTF();
                break;

            case FILE_CHUNK:
                p.fileId = in.readUTF();
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.chunkIndex = in.readInt();
                p.totalChunks = in.readInt();
                int dataLen = in.readInt();
                p.chunkData = new byte[dataLen];
                // Bắt buộc dùng readFully() để đọc đủ số byte kể cả khi TCP phân mảnh
                in.readFully(p.chunkData);
                break;

            case KICK:
                p.sender = "SERVER";
                p.recipient = in.readUTF();
                p.content = in.readUTF();
                break;

            case ROOM_CREATE:
            case ROOM_JOIN:
            case ROOM_LEAVE:
                p.sender = in.readUTF();
                p.roomName = in.readUTF();
                break;

            case ROOM_LIST_UPDATE:
                int rCount = in.readInt();
                p.roomList = new ArrayList<>(rCount);
                for (int i = 0; i < rCount; i++) {
                    p.roomList.add(in.readUTF());
                }
                break;

            case ROOM_MESSAGE:
                p.sender = in.readUTF();
                p.roomName = in.readUTF();
                p.content = in.readUTF();
                p.timestamp = in.readLong();
                break;

            case CALL_REQUEST:
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.udpPort = in.readInt();
                p.videoUdpPort = in.readInt();
                p.content = in.readUTF();
                break;

            case CALL_RESPONSE:
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.success = in.readBoolean();
                p.udpPort = in.readInt();
                p.videoUdpPort = in.readInt();
                p.content = in.readUTF();
                break;

            case CALL_END:
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                p.content = in.readUTF();
                break;

            case HISTORY_REQUEST:
                p.sender = in.readUTF();
                p.recipient = in.readUTF();
                break;

            case HISTORY_RESPONSE:
                p.recipient = in.readUTF();
                int hCount = in.readInt();
                p.historyList = new ArrayList<>(hCount);
                for (int i = 0; i < hCount; i++) {
                    p.historyList.add(in.readUTF());
                }
                break;
        }

        return p;
    }

    // --- Getters & Setters ---

    public PacketType getType() {
        return type;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getContent() {
        return content;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getUserList() {
        return Collections.unmodifiableList(userList);
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public byte[] getChunkData() {
        return chunkData;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getRoomName() {
        return roomName;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public int getVideoUdpPort() {
        return videoUdpPort;
    }

    public void setVideoUdpPort(int videoUdpPort) {
        this.videoUdpPort = videoUdpPort;
    }

    public List<String> getRoomList() {
        return Collections.unmodifiableList(roomList);
    }

    public List<String> getHistoryList() {
        return Collections.unmodifiableList(historyList);
    }
}
