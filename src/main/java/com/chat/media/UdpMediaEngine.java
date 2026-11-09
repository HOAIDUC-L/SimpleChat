package com.chat.media;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UdpMediaEngine - Module truyền nhận ÂM THANH (Voice) và HÌNH ẢNH (Video Streaming) thời gian thực
 * chạy song song trên nền tảng giao thức mạng UDP (DatagramSocket).
 *
 * =========================================================================
 * TẠI SAO TRUYỀN VIDEO VÀ AUDIO PHẢI DÙNG UDP VÀ TÁCH RIÊNG 2 CỔNG?
 * (Trọng tâm câu hỏi vấn đáp bảo vệ đồ án trước giảng viên):
 * =========================================================================
 * 1. Tại sao dùng UDP thay vì TCP?
 *    - UDP phi kết nối, không có cờ bắt tay (No Handshake), header chỉ 8 byte (TCP >= 20 byte).
 *    - Tránh hiện tượng nghẽn luồng tích lũy (Head-of-Line Blocking): Nếu mất 1 frame nhỏ,
 *      TCP sẽ dừng toàn bộ stream lại để truyền lại, gây trễ hình và trễ tiếng tích lũy.
 *      Với UDP, frame mất sẽ được bỏ qua, hiển thị ngay frame mới nhất -> Giữ độ trễ cực thấp.
 *
 * 2. Tại sao phải phân tách riêng 2 cổng (Audio UDP Port & Video UDP Port)?
 *    - Đặc thù của Audio: Gói tin rất nhỏ (1024 bytes) nhưng phải gửi đều đặn chu kỳ ~32ms.
 *      Chỉ cần trễ vài mili-giây là tai người nghe tiếng rè hoặc giật.
 *    - Đặc thù của Video: Kích thước gói tin lớn hơn nhiều (khung ảnh JPEG nén ~8KB - 15KB).
 *    - Nếu gửi chung 1 cổng UDP: Gói video lớn có thể chen ngang hoặc làm tràn buffer của
 *      gói âm thanh, gây nghẽn tiếng.
 *    - Việc tách riêng 2 cổng UDP độc lập cho phép CPU xử lý âm thanh và hình ảnh song song
 *      trên 2 luồng riêng biệt, không tranh chấp tài nguyên mạng hay bộ đệm socket.
 */
public class UdpMediaEngine {

    // --- Cấu hình Audio VoIP chuẩn HD ---
    public static final float SAMPLE_RATE = 16000.0f; // 16kHz
    public static final int SAMPLE_SIZE_IN_BITS = 16;  // 16-bit
    public static final int CHANNELS = 1;              // Mono (tiết kiệm băng thông)
    public static final boolean SIGNED = true;
    public static final boolean BIG_ENDIAN = false;
    public static final int AUDIO_BUFFER_SIZE = 1024;  // ~32ms âm thanh / gói UDP

    // --- Cấu hình Video Streaming ---
    public static final int VIDEO_WIDTH = 320;
    public static final int VIDEO_HEIGHT = 240;
    public static final int VIDEO_MAX_UDP_PACKET = 65507; // Kích thước tối đa của 1 UDP Datagram payload
    public static final int VIDEO_FRAME_DELAY_MS = 45;    // ~22 FPS truyền mượt mà, không nghẽn mạng

    public interface MediaEngineListener {
        void onCallStarted(int localAudioPort, int localVideoPort, String remoteHost, int remoteAudioPort, int remoteVideoPort);
        default void onCallStarted(int localPort, String remoteHost, int remotePort) {}
        void onCallStopped(String reason);
        void onError(String errorMessage);

        /** Sự kiện nhận được khung hình nội bộ của chính mình (Local Video Preview) */
        default void onLocalFrameCaptured(BufferedImage localFrame) {}

        /** Sự kiện nhận được khung hình từ đối phương qua mạng UDP (Remote Video) */
        default void onRemoteFrameReceived(BufferedImage remoteFrame) {}
    }

    // Các socket UDP độc lập cho Audio và Video
    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;

    // Thiết bị phần cứng âm thanh
    private TargetDataLine microphoneLine;
    private SourceDataLine speakerLine;

    // Các cờ điều khiển trạng thái đa luồng
    private final AtomicBoolean isCalling = new AtomicBoolean(false);
    private final AtomicBoolean isMicMuted = new AtomicBoolean(false);
    private final AtomicBoolean isCameraEnabled = new AtomicBoolean(true);

    // Các luồng thực thi song song
    private Thread audioCaptureThread;
    private Thread audioPlaybackThread;
    private Thread videoSendThread;
    private Thread videoReceiveThread;

    private MediaEngineListener listener;
    private Robot screenRobot;
    private String localUsername = "Tôi";
    private int frameCounter = 0;

    public UdpMediaEngine() {
        initRobot();
    }

    public UdpMediaEngine(MediaEngineListener listener) {
        this.listener = listener;
        initRobot();
    }

    private void initRobot() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                screenRobot = new Robot();
            }
        } catch (AWTException | SecurityException ignored) {
            screenRobot = null;
        }
    }

    public void setListener(MediaEngineListener listener) {
        this.listener = listener;
    }

    public void setLocalUsername(String username) {
        if (username != null && !username.trim().isEmpty()) {
            this.localUsername = username.trim();
        }
    }

    /**
     * Mở đồng thời cả 2 cổng UDP ngẫu nhiên (ephemeral ports) cho Audio và Video.
     * @return mảng 2 phần tử [localAudioPort, localVideoPort]
     */
    public synchronized int[] bindLocalPorts() throws SocketException {
        if (audioSocket == null || audioSocket.isClosed()) {
            audioSocket = new DatagramSocket(0);
        }
        if (videoSocket == null || videoSocket.isClosed()) {
            videoSocket = new DatagramSocket(0);
        }
        return new int[] { audioSocket.getLocalPort(), videoSocket.getLocalPort() };
    }

    /**
     * Tương thích ngược: Mở và trả về cổng Audio UDP.
     */
    public synchronized int bindLocalPort() throws SocketException {
        return bindLocalPorts()[0];
    }

    public synchronized int getLocalAudioPort() {
        return (audioSocket != null && !audioSocket.isClosed()) ? audioSocket.getLocalPort() : 0;
    }

    public synchronized int getLocalVideoPort() {
        return (videoSocket != null && !videoSocket.isClosed()) ? videoSocket.getLocalPort() : 0;
    }

    public synchronized int getLocalPort() {
        return getLocalAudioPort();
    }

    // --- Điều khiển Bật/Tắt Micro & Camera ---

    public void setMicMuted(boolean muted) {
        this.isMicMuted.set(muted);
    }

    public boolean isMicMuted() {
        return isMicMuted.get();
    }

    public void setCameraEnabled(boolean enabled) {
        this.isCameraEnabled.set(enabled);
    }

    public boolean isCameraEnabled() {
        return isCameraEnabled.get();
    }

    /**
     * Bắt đầu cuộc gọi Voice & Video trực tiếp P2P qua UDP.
     */
    public synchronized boolean startCall(String remoteHost, int remoteAudioPort, int remoteVideoPort) {
        if (isCalling.get()) {
            return true;
        }

        try {
            if (audioSocket == null || audioSocket.isClosed() || videoSocket == null || videoSocket.isClosed()) {
                bindLocalPorts();
            }

            AudioFormat format = getAudioFormat();

            // 1. Khởi tạo Microphone (TargetDataLine)
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
            if (AudioSystem.isLineSupported(micInfo)) {
                try {
                    microphoneLine = (TargetDataLine) AudioSystem.getLine(micInfo);
                    microphoneLine.open(format, AUDIO_BUFFER_SIZE * 4);
                    microphoneLine.start();
                } catch (LineUnavailableException e) {
                    notifyError("Không thể mở Microphone: " + e.getMessage());
                }
            }

            // 2. Khởi tạo Loa phát (SourceDataLine)
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
            if (AudioSystem.isLineSupported(speakerInfo)) {
                try {
                    speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                    speakerLine.open(format, AUDIO_BUFFER_SIZE * 4);
                    speakerLine.start();
                } catch (LineUnavailableException e) {
                    notifyError("Không thể mở Loa phát âm thanh: " + e.getMessage());
                }
            }

            isCalling.set(true);

            // 3. Khởi động 2 luồng Audio (Thu âm & Phát loa)
            startAudioCaptureThread(remoteHost, remoteAudioPort);
            startAudioPlaybackThread();

            // 4. Khởi động 2 luồng Video (Chụp/Stream & Nhận/Vẽ) nếu cổng Video khả dụng
            if (remoteVideoPort > 0) {
                startVideoSendThread(remoteHost, remoteVideoPort);
            }
            startVideoReceiveThread();

            if (listener != null) {
                listener.onCallStarted(audioSocket.getLocalPort(), videoSocket.getLocalPort(), 
                                       remoteHost, remoteAudioPort, remoteVideoPort);
                listener.onCallStarted(audioSocket.getLocalPort(), remoteHost, remoteAudioPort);
            }

            return true;

        } catch (Exception e) {
            stopCall("Lỗi khởi động cuộc gọi: " + e.getMessage());
            notifyError(e.getMessage());
            return false;
        }
    }

    public synchronized boolean startCall(String remoteHost, int remoteAudioPort) {
        return startCall(remoteHost, remoteAudioPort, 0);
    }

    // =========================================================================
    // LUỒNG XỬ LÝ ÂM THANH QUA UDP (AUDIO THREADS)
    // =========================================================================

    private void startAudioCaptureThread(String remoteHost, int remoteAudioPort) {
        audioCaptureThread = new Thread(() -> {
            byte[] buffer = new byte[AUDIO_BUFFER_SIZE];
            try {
                InetAddress remoteAddr = InetAddress.getByName(remoteHost);

                while (isCalling.get() && !Thread.currentThread().isInterrupted()) {
                    if (microphoneLine != null && microphoneLine.isOpen()) {
                        int bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                        // Nếu Mic không bị mute thì gửi gói tin qua UDP
                        if (bytesRead > 0 && !isMicMuted.get() && audioSocket != null && !audioSocket.isClosed()) {
                            DatagramPacket packet = new DatagramPacket(buffer, bytesRead, remoteAddr, remoteAudioPort);
                            audioSocket.send(packet);
                        }
                    } else {
                        Thread.sleep(40);
                    }
                }
            } catch (IOException e) {
                if (isCalling.get()) {
                    notifyError("Lỗi luồng gửi âm thanh UDP: " + e.getMessage());
                }
            } catch (InterruptedException ignored) {
            }
        }, "VoiceCall-Audio-Capture");

        audioCaptureThread.setDaemon(true);
        audioCaptureThread.start();
    }

    private void startAudioPlaybackThread() {
        audioPlaybackThread = new Thread(() -> {
            byte[] buffer = new byte[AUDIO_BUFFER_SIZE];
            try {
                while (isCalling.get() && !Thread.currentThread().isInterrupted()) {
                    if (audioSocket != null && !audioSocket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        audioSocket.receive(packet); // Chặn chờ gói tin âm thanh UDP

                        int len = packet.getLength();
                        if (len > 0 && speakerLine != null && speakerLine.isOpen()) {
                            speakerLine.write(packet.getData(), packet.getOffset(), len);
                        }
                    }
                }
            } catch (SocketException ignored) {
            } catch (IOException e) {
                if (isCalling.get()) {
                    notifyError("Lỗi luồng phát âm thanh UDP: " + e.getMessage());
                }
            }
        }, "VoiceCall-Audio-Playback");

        audioPlaybackThread.setDaemon(true);
        audioPlaybackThread.start();
    }

    // =========================================================================
    // LUỒNG XỬ LÝ HÌNH ẢNH QUA UDP (VIDEO STREAMING THREADS)
    // =========================================================================

    /**
     * Luồng gửi Video: Chụp khung hình liên tục (320x240) -> Nén JPG -> Gửi DatagramPacket qua UDP.
     */
    private void startVideoSendThread(String remoteHost, int remoteVideoPort) {
        videoSendThread = new Thread(() -> {
            try {
                InetAddress remoteAddr = InetAddress.getByName(remoteHost);

                while (isCalling.get() && !Thread.currentThread().isInterrupted()) {
                    BufferedImage frame = captureLocalFrame();

                    // Bắn sự kiện preview khung hình của chính mình lên giao diện
                    if (listener != null && frame != null) {
                        listener.onLocalFrameCaptured(frame);
                    }

                    // Nếu Camera được bật, tiến hành nén JPEG và gửi qua UDP Socket
                    if (isCameraEnabled.get() && frame != null && videoSocket != null && !videoSocket.isClosed()) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(frame, "jpg", baos);
                        byte[] jpegBytes = baos.toByteArray();

                        if (jpegBytes.length > 0 && jpegBytes.length <= VIDEO_MAX_UDP_PACKET) {
                            DatagramPacket packet = new DatagramPacket(jpegBytes, jpegBytes.length, remoteAddr, remoteVideoPort);
                            videoSocket.send(packet);
                        }
                    }

                    Thread.sleep(VIDEO_FRAME_DELAY_MS);
                }
            } catch (IOException e) {
                if (isCalling.get()) {
                    notifyError("Lỗi luồng gửi video UDP: " + e.getMessage());
                }
            } catch (InterruptedException ignored) {
            }
        }, "VideoCall-Send-Thread");

        videoSendThread.setDaemon(true);
        videoSendThread.start();
    }

    /**
     * Luồng nhận Video: Đọc DatagramPacket từ videoSocket -> Giải nén ImageIO -> Bắn sự kiện lên UI.
     */
    private void startVideoReceiveThread() {
        videoReceiveThread = new Thread(() -> {
            byte[] buffer = new byte[VIDEO_MAX_UDP_PACKET];
            try {
                while (isCalling.get() && !Thread.currentThread().isInterrupted()) {
                    if (videoSocket != null && !videoSocket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        videoSocket.receive(packet); // Chặn chờ gói tin hình ảnh UDP

                        int len = packet.getLength();
                        if (len > 0) {
                            ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), packet.getOffset(), len);
                            BufferedImage remoteFrame = ImageIO.read(bais);

                            if (remoteFrame != null && listener != null) {
                                listener.onRemoteFrameReceived(remoteFrame);
                            }
                        }
                    }
                }
            } catch (SocketException ignored) {
            } catch (IOException e) {
                if (isCalling.get()) {
                    notifyError("Lỗi luồng nhận video UDP: " + e.getMessage());
                }
            }
        }, "VideoCall-Receive-Thread");

        videoReceiveThread.setDaemon(true);
        videoReceiveThread.start();
    }

    /**
     * Chụp khung hình cục bộ: Ưu tiên chụp màn hình qua Robot, nếu không khả dụng thì
     * tự động sinh khung hình hoạt họa mô phỏng webcam sắc nét, có đồng hồ thời gian thực và biểu đồ sóng.
     */
    public BufferedImage captureLocalFrame() {
        frameCounter++;

        // Trường hợp camera bị tắt: Vẽ màn hình đen kèm biểu tượng "Camera đã tắt"
        if (!isCameraEnabled.get()) {
            BufferedImage offImage = new BufferedImage(VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = offImage.createGraphics();
            g.setColor(new Color(24, 24, 27));
            g.fillRect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            g.setColor(new Color(239, 68, 68));
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString("🚫 CAMERA ĐÃ TẮT", 90, 115);
            g.setColor(Color.LIGHT_GRAY);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("[" + localUsername + "]", 120, 140);
            g.dispose();
            return offImage;
        }

        // 1. Thử chụp màn hình trung tâm bằng Robot
        if (screenRobot != null) {
            try {
                int captureW = 480;
                int captureH = 360;
                int screenW = Toolkit.getDefaultToolkit().getScreenSize().width;
                int screenH = Toolkit.getDefaultToolkit().getScreenSize().height;
                int startX = Math.max(0, (screenW - captureW) / 2);
                int startY = Math.max(0, (screenH - captureH) / 2);

                BufferedImage screenCapture = screenRobot.createScreenCapture(new Rectangle(startX, startY, captureW, captureH));
                
                // Scale về 320x240 để đóng vừa vặn gói tin UDP
                BufferedImage scaled = new BufferedImage(VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(screenCapture, 0, 0, VIDEO_WIDTH, VIDEO_HEIGHT, null);
                
                // Vẽ watermark thời gian và nhãn user
                g2.setColor(new Color(0, 0, 0, 150));
                g2.fillRect(0, VIDEO_HEIGHT - 24, VIDEO_WIDTH, 24);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                g2.drawString("🔴 SCREEN LIVE | " + localUsername + " (" + timeStr + ")", 8, VIDEO_HEIGHT - 8);
                g2.dispose();
                return scaled;

            } catch (Exception ignored) {
            }
        }

        // 2. Dự phòng: Sinh khung hình hoạt họa mô phỏng webcam chuyên nghiệp
        BufferedImage animImage = new BufferedImage(VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = animImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Nền chuyển sắc Gradient
        Color c1 = new Color(15, 23, 42);
        Color c2 = new Color(30, 41, 59);
        g.setColor(c1);
        g.fillRect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);

        // Vòng tròn Avatar người dùng
        int cx = VIDEO_WIDTH / 2;
        int cy = VIDEO_HEIGHT / 2 - 15;
        int radius = 45;
        g.setColor(new Color(13, 148, 136));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        String initial = localUsername.isEmpty() ? "U" : localUsername.substring(0, 1).toUpperCase();
        g.drawString(initial, cx - 8, cy + 8);

        // Tên người dùng
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString(localUsername, cx - (localUsername.length() * 4), cy + radius + 20);

        // Biểu đồ sóng âm thanh động mô phỏng nói chuyện
        g.setColor(new Color(56, 189, 248));
        for (int i = 0; i < 9; i++) {
            int waveHeight = (int) (Math.sin((frameCounter * 0.3) + i) * 12 + 15);
            g.fillRect(cx - 40 + (i * 9), cy + radius + 30 - waveHeight / 2, 5, waveHeight);
        }

        // Thanh trạng thái phía trên
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, VIDEO_WIDTH, 22);
        g.setColor(new Color(74, 222, 128));
        g.fillOval(8, 7, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.S"));
        g.drawString("UDP CAM STREAM (320x240) • " + time, 22, 16);

        g.dispose();
        return animImage;
    }

    /**
     * Dừng cuộc gọi và giải phóng sạch sẽ tài nguyên (Mic, Loa, Socket UDP Audio, Socket UDP Video).
     */
    public synchronized void stopCall(String reason) {
        if (!isCalling.getAndSet(false)) {
            return;
        }

        // 1. Dừng Microphone
        if (microphoneLine != null) {
            try {
                microphoneLine.stop();
                microphoneLine.close();
            } catch (Exception ignored) {
            }
            microphoneLine = null;
        }

        // 2. Dừng Loa
        if (speakerLine != null) {
            try {
                speakerLine.drain();
                speakerLine.stop();
                speakerLine.close();
            } catch (Exception ignored) {
            }
            speakerLine = null;
        }

        // 3. Đóng Socket UDP Audio
        if (audioSocket != null && !audioSocket.isClosed()) {
            audioSocket.close();
            audioSocket = null;
        }

        // 4. Đóng Socket UDP Video
        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
            videoSocket = null;
        }

        // 5. Ngắt toàn bộ luồng
        if (audioCaptureThread != null) {
            audioCaptureThread.interrupt();
            audioCaptureThread = null;
        }
        if (audioPlaybackThread != null) {
            audioPlaybackThread.interrupt();
            audioPlaybackThread = null;
        }
        if (videoSendThread != null) {
            videoSendThread.interrupt();
            videoSendThread = null;
        }
        if (videoReceiveThread != null) {
            videoReceiveThread.interrupt();
            videoReceiveThread = null;
        }

        if (listener != null) {
            listener.onCallStopped(reason != null ? reason : "Cuộc gọi đã kết thúc.");
        }
    }

    public boolean isCalling() {
        return isCalling.get();
    }

    public static AudioFormat getAudioFormat() {
        return new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
    }

    private void notifyError(String msg) {
        if (listener != null) {
            listener.onError(msg);
        }
    }
}
