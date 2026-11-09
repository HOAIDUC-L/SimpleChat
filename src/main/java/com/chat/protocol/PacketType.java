package com.chat.protocol;

/**
 * PacketType định nghĩa các mã loại gói tin (OpCode) trong giao thức SimpleChat.
 */
public enum PacketType {
    /** 1: Client gửi yêu cầu đăng nhập kèm username */
    LOGIN_REQUEST((byte) 1),

    /** 2: Server phản hồi kết quả đăng nhập (thành công/thất bại, lý do, danh sách user) */
    LOGIN_RESPONSE((byte) 2),

    /** 3: Server thông báo cập nhật danh sách người dùng đang online */
    USER_LIST_UPDATE((byte) 3),

    /** 4: Tin nhắn văn bản (có thể gửi ALL hoặc gửi riêng 1 user) */
    CHAT_MESSAGE((byte) 4),

    /** 5: Yêu cầu gửi file (tên file, kích thước, người gửi, người nhận) */
    FILE_REQUEST((byte) 5),

    /** 6: Phản hồi chấp nhận hoặc từ chối nhận file */
    FILE_RESPONSE((byte) 6),

    /** 7: Khối dữ liệu nhị phân của file truyền theo luồng byte */
    FILE_CHUNK((byte) 7),

    /** 8: Client thông báo đăng xuất / ngắt kết nối */
    LOGOUT((byte) 8),

    /** 9: Server thông báo kích (kick) một client khỏi phòng chat */
    KICK((byte) 9),

    // =========================================================================
    // CÁC GÓI TIN MULTICAST & QUẢN LÝ PHÒNG (ROOM/GROUP CHAT) - TRUYỀN QUA TCP
    // =========================================================================
    // * Giải thích lý do dùng TCP:
    //   Việc tạo phòng, tham gia, rời phòng và gửi tin nhắn trong nhóm yêu cầu
    //   độ tin cậy 100% (không được rơi rớt tin nhắn hoặc sai lệch trạng thái thành viên).
    //   TCP đảm bảo phân phối chính xác từng gói tin đến tất cả thành viên trong nhóm.
    
    /** 10: Client yêu cầu tạo phòng chat mới */
    ROOM_CREATE((byte) 10),

    /** 11: Client yêu cầu tham gia (join) vào một phòng chat */
    ROOM_JOIN((byte) 11),

    /** 12: Client yêu cầu rời khỏi (leave) một phòng chat */
    ROOM_LEAVE((byte) 12),

    /** 13: Server gửi danh sách tất cả các phòng hiện có cho Client */
    ROOM_LIST_UPDATE((byte) 13),

    /** 14: Tin nhắn Multicast gửi đến một phòng cụ thể (chỉ thành viên trong phòng nhận) */
    ROOM_MESSAGE((byte) 14),

    // =========================================================================
    // CÁC GÓI TIN BẮT TAY BÁO HIỆU CUỘC GỌI (CALL SIGNALING) - TRUYỀN QUA TCP
    // =========================================================================
    // * Giải thích lý do Signaling dùng TCP còn Audio dùng UDP:
    //   - Signaling (Báo hiệu): Trao đổi lời mời gọi, thỏa thuận IP và cổng UDP,
    //     chấp nhận/từ chối, gác máy. Bắt buộc dùng TCP vì nếu mất gói tin báo hiệu,
    //     cuộc gọi không thể thiết lập hoặc bên kia không nghe chuông.
    //   - Audio Stream (Truyền tiếng): Dùng UDP sau khi đã bắt tay thành công qua TCP.
    
    /** 15: Client A mời gọi Voice tới Client B (kèm IP/Port UDP của A) */
    CALL_REQUEST((byte) 15),

    /** 16: Client B phản hồi Đồng ý hoặc Từ chối cuộc gọi (kèm Port UDP của B) */
    CALL_RESPONSE((byte) 16),

    /** 17: Thông báo kết thúc/gác máy hoặc hủy cuộc gọi */
    CALL_END((byte) 17),

    // =========================================================================
    // CÁC GÓI TIN TRUY VẤN LỊCH SỬ CHAT TỪ CƠ SỞ DỮ LIỆU SQLITE - TRUYỀN QUA TCP
    // =========================================================================
    /** 18: Client yêu cầu lấy lịch sử tin nhắn của một phòng/người dùng */
    HISTORY_REQUEST((byte) 18),

    /** 19: Server trả về danh sách lịch sử tin nhắn được lưu từ SQLite */
    HISTORY_RESPONSE((byte) 19);

    private final byte code;

    PacketType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static PacketType fromByte(byte code) {
        for (PacketType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Mã gói tin không hợp lệ: " + code);
    }
}
