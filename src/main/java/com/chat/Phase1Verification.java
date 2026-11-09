package com.chat;

import com.chat.db.DatabaseManager;
import com.chat.protocol.Packet;
import com.chat.protocol.PacketType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.List;

/**
 * Lớp kiểm thử tự động xác thực Phần 1:
 * 1. Đóng gói và giải mã nhị phân tất cả các OpCode mới (Multicast Room, Voice Call Signaling, SQLite History).
 * 2. Lưu trữ và truy vấn SQLite nhúng thông qua DatabaseManager.
 */
public class Phase1Verification {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   BẮT ĐẦU KIỂM THỬ XÁC THỰC PHẦN 1 (GIAO THỨC & DB) ");
        System.out.println("==================================================");

        try {
            testPacketSerialization();
            System.out.println("✅ [PASS] Kiểm thử Packet Serialization / Deserialization thành công!\n");

            testDatabaseManager();
            System.out.println("✅ [PASS] Kiểm thử DatabaseManager SQLite thành công!\n");

            System.out.println("🎉 TẤT CẢ CÁC MỤC KIỂM THỬ PHẦN 1 ĐÃ VƯỢT QUA 100%!");
        } catch (Throwable t) {
            System.err.println("❌ [FAIL] Kiểm thử thất bại: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void testPacketSerialization() throws Exception {
        System.out.println("[1] Đang kiểm tra đóng gói và giải mã nhị phân các PacketType mới...");

        // 1. ROOM_CREATE
        Packet p1 = Packet.createRoomCreate("Alice", "GameDev");
        Packet r1 = roundTrip(p1);
        assertEquals(PacketType.ROOM_CREATE, r1.getType());
        assertEquals("Alice", r1.getSender());
        assertEquals("GameDev", r1.getRoomName());

        // 2. ROOM_JOIN
        Packet p2 = Packet.createRoomJoin("Bob", "GameDev");
        Packet r2 = roundTrip(p2);
        assertEquals(PacketType.ROOM_JOIN, r2.getType());
        assertEquals("Bob", r2.getSender());
        assertEquals("GameDev", r2.getRoomName());

        // 3. ROOM_LEAVE
        Packet p3 = Packet.createRoomLeave("Bob", "GameDev");
        Packet r3 = roundTrip(p3);
        assertEquals(PacketType.ROOM_LEAVE, r3.getType());
        assertEquals("Bob", r3.getSender());
        assertEquals("GameDev", r3.getRoomName());

        // 4. ROOM_LIST_UPDATE
        Packet p4 = Packet.createRoomListUpdate(List.of("Phòng Chung", "GameDev", "Java 17"));
        Packet r4 = roundTrip(p4);
        assertEquals(PacketType.ROOM_LIST_UPDATE, r4.getType());
        assertEquals(3, r4.getRoomList().size());
        assertEquals("GameDev", r4.getRoomList().get(1));

        // 5. ROOM_MESSAGE
        Packet p5 = Packet.createRoomMessage("Alice", "GameDev", "Chào mọi người trong phòng!");
        Packet r5 = roundTrip(p5);
        assertEquals(PacketType.ROOM_MESSAGE, r5.getType());
        assertEquals("Alice", r5.getSender());
        assertEquals("GameDev", r5.getRoomName());
        assertEquals("Chào mọi người trong phòng!", r5.getContent());

        // 6. CALL_REQUEST (Audio & Video UDP ports)
        Packet p6 = Packet.createCallRequest("Alice", "Bob", 50005, 50007, "192.168.1.10");
        Packet r6 = roundTrip(p6);
        assertEquals(PacketType.CALL_REQUEST, r6.getType());
        assertEquals("Alice", r6.getSender());
        assertEquals("Bob", r6.getRecipient());
        assertEquals(50005, r6.getUdpPort());
        assertEquals(50007, r6.getVideoUdpPort());
        assertEquals("192.168.1.10", r6.getContent());

        // 7. CALL_RESPONSE (Audio & Video UDP ports)
        Packet p7 = Packet.createCallResponse("Bob", "Alice", true, 50006, 50008, "192.168.1.20");
        Packet r7 = roundTrip(p7);
        assertEquals(PacketType.CALL_RESPONSE, r7.getType());
        assertEquals("Bob", r7.getSender());
        assertEquals("Alice", r7.getRecipient());
        assertEquals(true, r7.isSuccess());
        assertEquals(50006, r7.getUdpPort());
        assertEquals(50008, r7.getVideoUdpPort());
        assertEquals("192.168.1.20", r7.getContent());

        // 8. CALL_END
        Packet p8 = Packet.createCallEnd("Alice", "Bob", "Gác máy");
        Packet r8 = roundTrip(p8);
        assertEquals(PacketType.CALL_END, r8.getType());
        assertEquals("Alice", r8.getSender());
        assertEquals("Bob", r8.getRecipient());
        assertEquals("Gác máy", r8.getContent());

        // 9. HISTORY_REQUEST
        Packet p9 = Packet.createHistoryRequest("Alice", "ROOM:GameDev");
        Packet r9 = roundTrip(p9);
        assertEquals(PacketType.HISTORY_REQUEST, r9.getType());
        assertEquals("Alice", r9.getSender());
        assertEquals("ROOM:GameDev", r9.getRecipient());

        // 10. HISTORY_RESPONSE
        Packet p10 = Packet.createHistoryResponse("ROOM:GameDev", List.of("[12:00] Alice: Alo", "[12:01] Bob: Có gì đó"));
        Packet r10 = roundTrip(p10);
        assertEquals(PacketType.HISTORY_RESPONSE, r10.getType());
        assertEquals("ROOM:GameDev", r10.getRecipient());
        assertEquals(2, r10.getHistoryList().size());
        assertEquals("[12:00] Alice: Alo", r10.getHistoryList().get(0));
    }

    private static void testDatabaseManager() throws Exception {
        System.out.println("[2] Đang kiểm tra DatabaseManager SQLite...");
        String testDb = "test_phase1.db";
        File dbFile = new File(testDb);
        if (dbFile.exists()) {
            dbFile.delete();
        }

        DatabaseManager db = new DatabaseManager(testDb);
        db.init();

        // 1. Kiểm tra seed phòng mặc định
        List<String> rooms = db.getAllRooms();
        System.out.println("    - Danh sách phòng mặc định: " + rooms);
        if (rooms.size() < 4) {
            throw new AssertionError("Phải có ít nhất 4 phòng mặc định");
        }

        // 2. Tạo phòng mới
        boolean created = db.createRoom("TestRoom1", "Tester");
        if (!created) throw new AssertionError("Tạo phòng mới thất bại");
        boolean duplicate = db.createRoom("TestRoom1", "Tester2");
        if (duplicate) throw new AssertionError("Không được cho phép tạo trùng tên phòng");

        // 3. Lưu người dùng
        db.saveOrUpdateUser("userA");
        db.saveOrUpdateUser("userB");

        // 4. Lưu và lấy tin nhắn
        long now = System.currentTimeMillis();
        db.saveMessage("userA", "ROOM:TestRoom1", "Tin nhắn test phòng", "ROOM", now);
        db.saveMessage("userB", "ROOM:TestRoom1", "Tin phản hồi phòng", "ROOM", now + 100);

        List<String> roomHistory = db.getRecentMessages("ROOM:TestRoom1", 10);
        System.out.println("    - Lịch sử phòng TestRoom1: " + roomHistory);
        if (roomHistory.size() != 2) {
            throw new AssertionError("Lịch sử phòng phải có 2 tin nhắn, thực tế có: " + roomHistory.size());
        }

        db.close();
        if (dbFile.exists()) {
            dbFile.delete();
        }
        File walFile = new File(testDb + "-wal");
        if (walFile.exists()) walFile.delete();
        File shmFile = new File(testDb + "-shm");
        if (shmFile.exists()) shmFile.delete();
    }

    private static Packet roundTrip(Packet original) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        return Packet.readFrom(dis);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Kỳ vọng: " + expected + ", Thực tế: " + actual);
    }
}
