package com.chat.client;

import java.util.List;

/**
 * Interface lắng nghe sự kiện mạng từ ChatClient.
 * Giúp tách biệt hoàn toàn giữa tầng xử lý mạng (Network Core) và tầng hiển thị giao diện (Swing GUI).
 */
public interface ChatClientListener {
    void onConnected();
    void onDisconnected(String reason);
    void onLoginSuccess(String username, List<String> onlineUsers);
    void onLoginFailed(String reason);
    void onUserListUpdated(List<String> onlineUsers);
    void onChatMessageReceived(String sender, String recipient, String message, long timestamp);
    void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize);
    void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason);
    void onFileProgress(String fileId, String fileName, long bytesTransferred, long totalBytes, boolean isSending);
    void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String message);
    void onFileTransferFailed(String fileId, String fileName, boolean isSending, String errorMessage);
    void onKicked(String reason);

    // =========================================================================
    // SỰ KIỆN MULTICAST ROOM & VOICE CALL UDP & LỊCH SỬ CHAT
    // Sử dụng default method để tương thích ngược với các class implement cũ
    // =========================================================================

    /** Cập nhật danh sách các phòng chat (Multicast Rooms) từ Server */
    default void onRoomListUpdated(List<String> rooms) {}

    /** Nhận tin nhắn Multicast gửi đến một phòng chat cụ thể */
    default void onRoomMessageReceived(String sender, String roomName, String message, long timestamp) {}

    /** Nhận tín hiệu mời gọi Voice & Video Call từ đối phương kèm cổng Audio UDP, Video UDP và IP */
    default void onCallRequestReceived(String caller, int callerAudioPort, int callerVideoPort, String callerIp) {
        onCallRequestReceived(caller, callerAudioPort, callerIp);
    }

    /** Nhận tín hiệu mời gọi Voice Call từ đối phương kèm cổng UDP và IP */
    default void onCallRequestReceived(String caller, int callerUdpPort, String callerIp) {
        onCallRequestReceived(caller, callerUdpPort);
    }

    /** Nhận tín hiệu mời gọi Voice Call từ đối phương kèm cổng UDP (backward-compatible) */
    default void onCallRequestReceived(String caller, int callerUdpPort) {}

    /** Nhận phản hồi đồng ý hoặc từ chối Voice & Video Call từ đối phương */
    default void onCallResponseReceived(String responder, boolean accepted, int responderAudioPort, int responderVideoPort, String reasonOrIp) {
        onCallResponseReceived(responder, accepted, responderAudioPort, reasonOrIp);
    }

    /** Nhận phản hồi đồng ý hoặc từ chối Voice Call từ đối phương */
    default void onCallResponseReceived(String responder, boolean accepted, int responderUdpPort, String reasonOrIp) {}

    /** Nhận tín hiệu kết thúc/gác máy Voice Call */
    default void onCallEnded(String sender, String reason) {}

    /** Nhận lịch sử tin nhắn từ SQLite Database do Server phản hồi */
    default void onHistoryReceived(String target, List<String> messages) {}
}
