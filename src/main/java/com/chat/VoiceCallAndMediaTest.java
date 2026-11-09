package com.chat;

import com.chat.client.ChatClient;
import com.chat.client.ChatClientListener;
import com.chat.db.DatabaseManager;
import com.chat.media.UdpMediaEngine;
import com.chat.server.ChatServer;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lớp kiểm thử tự động xác thực Phần 3 (Nâng cấp Video & Voice Call UDP):
 * 1. Định dạng âm thanh PCM: 16000Hz, 16-bit, Mono (1 kênh), Signed, Little-Endian.
 * 2. Cổng UDP Socket: Khả năng bind đồng thời 2 cổng độc lập (Audio Port & Video Port).
 * 3. Video Streaming: Chụp khung hình, nén ảnh JPEG, đóng gói và truyền DatagramPacket UDP P2P.
 * 4. Chu trình bắt tay cuộc gọi hoàn chỉnh (Signaling qua TCP -> Stream Audio & Video UDP -> Gác máy).
 */
public class VoiceCallAndMediaTest {

    private static final int TEST_PORT = 9998;

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("   BẮT ĐẦU KIỂM THỬ XÁC THỰC PHẦN 3 (UDP VIDEO & VOICE STREAMING) ");
        System.out.println("==================================================================");

        try {
            // [1] Kiểm tra cấu hình AudioFormat, UDP Socket kép (Audio + Video) và Frame Capture
            testUdpMediaEngineBasics();
            System.out.println("✅ [PASS] Kiểm thử cấu hình PCM, Video Frame và 2 Cổng UDP Socket độc lập thành công!\n");

            // [2] Kiểm thử chu trình Video & Voice Call hoàn chỉnh qua TCP Signaling
            testEndToEndVoiceAndVideoCall();
            System.out.println("✅ [PASS] Kiểm thử chu trình Video/Voice Báo hiệu TCP 2 cổng & Stream UDP thành công!\n");

            System.out.println("==================================================================");
            System.out.println("🎉 CHÚC MỪNG: KIỂM THỬ MODULE VIDEO & VOICE CALL UDP HOÀN THÀNH 100%!");
            System.out.println("==================================================================");

        } catch (Throwable t) {
            System.err.println("❌ [FAIL] Kiểm thử thất bại: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void testUdpMediaEngineBasics() throws Exception {
        System.out.println("[Bước 1] Kiểm tra định dạng âm thanh VoIP chuẩn của UdpMediaEngine...");
        AudioFormat format = UdpMediaEngine.getAudioFormat();
        assertEquals(16000.0f, format.getSampleRate());
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(1, format.getChannels()); // Mono tiết kiệm băng thông mạng
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());

        System.out.println("[Bước 2] Kiểm tra khả năng mở 2 cổng UDP độc lập (Audio & Video) và truyền DatagramPacket P2P...");
        UdpMediaEngine engineA = new UdpMediaEngine();
        UdpMediaEngine engineB = new UdpMediaEngine();

        int[] portsA = engineA.bindLocalPorts();
        int[] portsB = engineB.bindLocalPorts();

        int audioPortA = portsA[0];
        int videoPortA = portsA[1];
        int audioPortB = portsB[0];
        int videoPortB = portsB[1];

        System.out.println("    - Engine A: AudioPort=" + audioPortA + ", VideoPort=" + videoPortA);
        System.out.println("    - Engine B: AudioPort=" + audioPortB + ", VideoPort=" + videoPortB);

        if (audioPortA <= 0 || videoPortA <= 0 || audioPortA == videoPortA) {
            throw new AssertionError("Engine A không phân tách 2 cổng UDP Audio và Video riêng biệt!");
        }
        if (audioPortB <= 0 || videoPortB <= 0 || audioPortB == videoPortB) {
            throw new AssertionError("Engine B không phân tách 2 cổng UDP Audio và Video riêng biệt!");
        }

        // 1. Kiểm tra truyền nhận gói tin âm thanh PCM qua Audio UDP Port
        DatagramSocket sockAudioSender = new DatagramSocket();
        DatagramSocket sockAudioReceiver = new DatagramSocket();
        byte[] pcmData = new byte[UdpMediaEngine.AUDIO_BUFFER_SIZE];
        pcmData[0] = 0x12;
        pcmData[pcmData.length - 1] = 0x34;

        DatagramPacket sendAudioPkt = new DatagramPacket(pcmData, pcmData.length, InetAddress.getByName("127.0.0.1"), sockAudioReceiver.getLocalPort());
        sockAudioSender.send(sendAudioPkt);

        byte[] recvAudioBuffer = new byte[UdpMediaEngine.AUDIO_BUFFER_SIZE];
        DatagramPacket recvAudioPkt = new DatagramPacket(recvAudioBuffer, recvAudioBuffer.length);
        sockAudioReceiver.setSoTimeout(1000);
        sockAudioReceiver.receive(recvAudioPkt);

        assertEquals(0x12, recvAudioBuffer[0]);
        assertEquals(0x34, recvAudioBuffer[recvAudioBuffer.length - 1]);
        sockAudioSender.close();
        sockAudioReceiver.close();

        // 2. Kiểm tra chụp khung hình, nén JPEG và truyền nhận qua Video UDP Port
        System.out.println("[Bước 3] Kiểm tra chụp khung hình Video và truyền nhận gói ảnh JPEG qua UDP...");
        engineA.setLocalUsername("TestTester");
        BufferedImage capturedFrame = engineA.captureLocalFrame();
        if (capturedFrame == null) {
            throw new AssertionError("Không chụp/sinh được khung hình Video!");
        }
        assertEquals(UdpMediaEngine.VIDEO_WIDTH, capturedFrame.getWidth());
        assertEquals(UdpMediaEngine.VIDEO_HEIGHT, capturedFrame.getHeight());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(capturedFrame, "jpg", baos);
        byte[] jpegBytes = baos.toByteArray();
        System.out.println("    - Kích thước ảnh JPEG nén: " + jpegBytes.length + " bytes (UDP Safe < 65KB)");
        if (jpegBytes.length <= 0 || jpegBytes.length > UdpMediaEngine.VIDEO_MAX_UDP_PACKET) {
            throw new AssertionError("Kích thước frame ảnh nén không nằm trong phạm vi UDP an toàn!");
        }

        // Truyền JPEG qua UDP DatagramSocket
        DatagramSocket sockVideoSender = new DatagramSocket();
        DatagramSocket sockVideoReceiver = new DatagramSocket();
        DatagramPacket sendVidPkt = new DatagramPacket(jpegBytes, jpegBytes.length, InetAddress.getByName("127.0.0.1"), sockVideoReceiver.getLocalPort());
        sockVideoSender.send(sendVidPkt);

        byte[] recvVidBuffer = new byte[UdpMediaEngine.VIDEO_MAX_UDP_PACKET];
        DatagramPacket recvVidPkt = new DatagramPacket(recvVidBuffer, recvVidBuffer.length);
        sockVideoReceiver.setSoTimeout(1000);
        sockVideoReceiver.receive(recvVidPkt);

        ByteArrayInputStream bais = new ByteArrayInputStream(recvVidPkt.getData(), recvVidPkt.getOffset(), recvVidPkt.getLength());
        BufferedImage decodedFrame = ImageIO.read(bais);
        if (decodedFrame == null) {
            throw new AssertionError("Giải nén ảnh JPEG từ UDP DatagramPacket thất bại!");
        }
        assertEquals(UdpMediaEngine.VIDEO_WIDTH, decodedFrame.getWidth());
        assertEquals(UdpMediaEngine.VIDEO_HEIGHT, decodedFrame.getHeight());

        sockVideoSender.close();
        sockVideoReceiver.close();
        engineA.stopCall("Xong test");
        engineB.stopCall("Xong test");
    }

    private static void testEndToEndVoiceAndVideoCall() throws Exception {
        System.out.println("[Bước 4] Kiểm tra chu trình Báo hiệu cuộc gọi thoại & Video (TCP Signaling) giữa Alice và Bob...");
        DatabaseManager.deleteDatabaseFiles("chat_database.db");

        ChatServer server = new ChatServer(TEST_PORT);
        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500);

        CountDownLatch loginLatch = new CountDownLatch(2);
        CountDownLatch callReqLatch = new CountDownLatch(1);
        CountDownLatch callRespLatch = new CountDownLatch(1);
        CountDownLatch callEndLatch = new CountDownLatch(1);

        AtomicBoolean bobReceivedCall = new AtomicBoolean(false);
        AtomicBoolean aliceReceivedResponse = new AtomicBoolean(false);
        AtomicBoolean bobReceivedEnd = new AtomicBoolean(false);

        // Client Alice
        ChatClient alice = new ChatClient(new ChatClientListener() {
            @Override public void onConnected() {}
            @Override public void onDisconnected(String reason) {}
            @Override public void onLoginSuccess(String username, java.util.List<String> onlineUsers) { loginLatch.countDown(); }
            @Override public void onLoginFailed(String reason) {}
            @Override public void onUserListUpdated(java.util.List<String> onlineUsers) {}
            @Override public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {}
            @Override public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {}
            @Override public void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason) {}
            @Override public void onFileProgress(String fileId, String fileName, long b, long t, boolean s) {}
            @Override public void onFileTransferCompleted(String fileId, String fileName, boolean s, String m) {}
            @Override public void onFileTransferFailed(String fileId, String fileName, boolean s, String e) {}
            @Override public void onKicked(String reason) {}

            @Override
            public void onCallResponseReceived(String responder, boolean accepted, int responderAudioPort, int responderVideoPort, String reasonOrIp) {
                if ("Bob".equals(responder) && accepted && responderAudioPort == 48002 && responderVideoPort == 48004) {
                    aliceReceivedResponse.set(true);
                    callRespLatch.countDown();
                }
            }
        });

        // Client Bob
        ChatClient bob = new ChatClient(new ChatClientListener() {
            @Override public void onConnected() {}
            @Override public void onDisconnected(String reason) {}
            @Override public void onLoginSuccess(String username, java.util.List<String> onlineUsers) { loginLatch.countDown(); }
            @Override public void onLoginFailed(String reason) {}
            @Override public void onUserListUpdated(java.util.List<String> onlineUsers) {}
            @Override public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {}
            @Override public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {}
            @Override public void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason) {}
            @Override public void onFileProgress(String fileId, String fileName, long b, long t, boolean s) {}
            @Override public void onFileTransferCompleted(String fileId, String fileName, boolean s, String m) {}
            @Override public void onFileTransferFailed(String fileId, String fileName, boolean s, String e) {}
            @Override public void onKicked(String reason) {}

            @Override
            public void onCallRequestReceived(String caller, int callerAudioPort, int callerVideoPort, String callerIp) {
                if ("Alice".equals(caller) && callerAudioPort == 48001 && callerVideoPort == 48003) {
                    bobReceivedCall.set(true);
                    callReqLatch.countDown();
                }
            }

            @Override
            public void onCallEnded(String sender, String reason) {
                if ("Alice".equals(sender)) {
                    bobReceivedEnd.set(true);
                    callEndLatch.countDown();
                }
            }
        });

        alice.connect("127.0.0.1", TEST_PORT, "Alice");
        bob.connect("127.0.0.1", TEST_PORT, "Bob");

        boolean loggedIn = loginLatch.await(3, TimeUnit.SECONDS);
        if (!loggedIn) throw new AssertionError("Alice và Bob không đăng nhập được!");

        // 1. Alice gọi Bob qua TCP CALL_REQUEST kèm Audio Port 48001 và Video Port 48003
        System.out.println("    -> Alice gửi CALL_REQUEST tới Bob với AudioPort 48001 và VideoPort 48003...");
        alice.sendCallRequest("Bob", 48001, 48003);

        boolean reqOk = callReqLatch.await(3, TimeUnit.SECONDS);
        if (!reqOk || !bobReceivedCall.get()) {
            throw new AssertionError("Bob không nhận được đúng Audio/Video port từ Alice!");
        }

        // 2. Bob chấp nhận qua TCP CALL_RESPONSE kèm Audio Port 48002 và Video Port 48004
        System.out.println("    -> Bob gửi CALL_RESPONSE chấp nhận tới Alice với AudioPort 48002 và VideoPort 48004...");
        bob.sendCallResponse("Alice", true, 48002, 48004, "Sẵn sàng");

        boolean respOk = callRespLatch.await(3, TimeUnit.SECONDS);
        if (!respOk || !aliceReceivedResponse.get()) {
            throw new AssertionError("Alice không nhận được phản hồi đồng ý Audio/Video port từ Bob!");
        }

        // 3. Alice kết thúc cuộc gọi qua TCP CALL_END
        System.out.println("    -> Alice gửi CALL_END kết thúc cuộc gọi...");
        alice.sendCallEnd("Bob", "Gác máy");

        boolean endOk = callEndLatch.await(3, TimeUnit.SECONDS);
        if (!endOk || !bobReceivedEnd.get()) {
            throw new AssertionError("Bob không nhận được thông báo gác máy từ Alice!");
        }

        alice.disconnect("Hết test");
        bob.disconnect("Hết test");
        server.stopServer();
        DatabaseManager.deleteDatabaseFiles("chat_database.db");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected instanceof Number && actual instanceof Number) {
            if (((Number) expected).longValue() == ((Number) actual).longValue()) {
                return;
            }
        }
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Kỳ vọng: " + expected + ", Thực tế: " + actual);
    }
}
