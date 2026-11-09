package com.chat.gui;

import com.chat.media.UdpMediaEngine;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * VideoCallWindow - Cửa sổ giao diện cuộc gọi Video trực tiếp (Video Call Frame)
 * theo phong cách giao diện hiện đại (Dark Mode tương tự Zoom / Google Meet / Discord).
 *
 * TÍNH NĂNG CHÍNH:
 * 1. Khung hình lớn trung tâm (Remote Video): Hiển thị video trực tiếp từ đối phương qua mạng UDP.
 * 2. Khung hình nhỏ ở góc (Local Video Preview - PiP): Hiển thị khung hình camera/màn hình của chính mình.
 * 3. Bảng điều khiển phía dưới:
 *    - Nút 🎙️ Tắt/Bật Micro (Mute/Unmute).
 *    - Nút 📹 Tắt/Bật Camera.
 *    - Nút 📴 Gác Máy (End Call).
 * 4. Đồng hồ đếm thời gian cuộc gọi (Call Duration Timer).
 */
public class VideoCallWindow extends JFrame {

    private static final Color COLOR_BG_DARK = new Color(15, 23, 42);      // Slate 900
    private static final Color COLOR_BAR_DARK = new Color(30, 41, 59);     // Slate 800
    private static final Color COLOR_CARD_BG = new Color(51, 65, 85);      // Slate 700
    private static final Color COLOR_PRIMARY = new Color(59, 130, 246);    // Blue 500
    private static final Color COLOR_DANGER = new Color(239, 68, 68);      // Red 500
    private static final Color COLOR_SUCCESS = new Color(34, 197, 94);     // Green 500
    private static final Color COLOR_TEXT_MUTED = new Color(148, 163, 184);// Slate 400

    private final String remotePeer;
    private final String localUser;
    private final UdpMediaEngine mediaEngine;
    private final Runnable onEndCallAction;

    private VideoPanel remoteVideoPanel;
    private LocalPreviewPanel localVideoPanel;

    private JButton btnToggleMic;
    private JButton btnToggleCam;
    private JButton btnEndCall;

    private JLabel lblDuration;
    private JLabel lblStatusBadge;
    private Timer durationTimer;
    private int callSeconds = 0;

    public VideoCallWindow(String remotePeer, String localUser, UdpMediaEngine mediaEngine, Runnable onEndCallAction) {
        this.remotePeer = remotePeer;
        this.localUser = localUser;
        this.mediaEngine = mediaEngine;
        this.onEndCallAction = onEndCallAction;

        initComponents();
        startDurationTimer();
    }

    private void initComponents() {
        setTitle("SimpleChat - Cuộc gọi Video: [" + remotePeer + "]");
        setSize(780, 580);
        setMinimumSize(new Dimension(640, 480));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        getContentPane().setBackground(COLOR_BG_DARK);
        setLayout(new BorderLayout());

        // Xử lý khi bấm nút X trên cửa sổ
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleEndCall();
            }
        });

        // 1. THANH TIÊU ĐỀ TRÊN CÙNG (Top Bar)
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setBackground(COLOR_BAR_DARK);
        topBar.setBorder(new EmptyBorder(10, 16, 10, 16));

        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        topLeft.setOpaque(false);

        JLabel lblAvatar = new JLabel(remotePeer.isEmpty() ? "U" : remotePeer.substring(0, 1).toUpperCase());
        lblAvatar.setPreferredSize(new Dimension(32, 32));
        lblAvatar.setOpaque(true);
        lblAvatar.setBackground(COLOR_PRIMARY);
        lblAvatar.setForeground(Color.WHITE);
        lblAvatar.setFont(new Font("SansSerif", Font.BOLD, 15));
        lblAvatar.setHorizontalAlignment(SwingConstants.CENTER);
        lblAvatar.setBorder(new LineBorder(Color.WHITE, 1, true));
        topLeft.add(lblAvatar);

        JPanel titleBox = new JPanel(new BorderLayout(0, 2));
        titleBox.setOpaque(false);
        JLabel lblTitle = new JLabel("Cuộc gọi với " + remotePeer);
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
        lblTitle.setForeground(Color.WHITE);
        titleBox.add(lblTitle, BorderLayout.NORTH);

        lblStatusBadge = new JLabel("● Đang kết nối UDP P2P (Audio 16kHz & Video 320x240)");
        lblStatusBadge.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblStatusBadge.setForeground(COLOR_SUCCESS);
        titleBox.add(lblStatusBadge, BorderLayout.SOUTH);
        topLeft.add(titleBox);

        topBar.add(topLeft, BorderLayout.WEST);

        lblDuration = new JLabel("⏱ 00:00");
        lblDuration.setFont(new Font("Monospaced", Font.BOLD, 14));
        lblDuration.setForeground(Color.WHITE);
        lblDuration.setBorder(new CompoundBorder(
            new LineBorder(COLOR_CARD_BG, 1, true),
            new EmptyBorder(4, 10, 4, 10)
        ));
        topBar.add(lblDuration, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // 2. KHU VỰC VIDEO TRUNG TÂM (Main Video Area + Picture-in-Picture)
        JPanel videoContainer = new JPanel(null) {
            @Override
            public void doLayout() {
                super.doLayout();
                // Tự động căn chỉnh Remote Video lấp đầy toàn bộ
                remoteVideoPanel.setBounds(0, 0, getWidth(), getHeight());

                // Khung hình nhỏ của mình (Local PiP) ở góc dưới bên phải
                int pipW = 190;
                int pipH = 140;
                int pad = 16;
                localVideoPanel.setBounds(getWidth() - pipW - pad, getHeight() - pipH - pad, pipW, pipH);
            }
        };
        videoContainer.setBackground(COLOR_BG_DARK);

        remoteVideoPanel = new VideoPanel(remotePeer);
        localVideoPanel = new LocalPreviewPanel(localUser);

        videoContainer.add(localVideoPanel);  // Add PiP trước để hiển thị đè lên trên
        videoContainer.add(remoteVideoPanel);

        add(videoContainer, BorderLayout.CENTER);

        // 3. BẢNG ĐIỀU KHIỂN DƯỚI CÙNG (Bottom Controls Bar)
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 12));
        bottomBar.setBackground(COLOR_BAR_DARK);
        bottomBar.setBorder(new EmptyBorder(4, 0, 8, 0));

        btnToggleMic = createControlButton("🎙️ Tắt Mic", new Color(71, 85, 105));
        btnToggleMic.addActionListener(e -> toggleMic());
        bottomBar.add(btnToggleMic);

        btnToggleCam = createControlButton("📹 Tắt Cam", new Color(71, 85, 105));
        btnToggleCam.addActionListener(e -> toggleCam());
        bottomBar.add(btnToggleCam);

        btnEndCall = createControlButton("📴 Kết thúc", COLOR_DANGER);
        btnEndCall.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnEndCall.setPreferredSize(new Dimension(130, 42));
        btnEndCall.addActionListener(e -> handleEndCall());
        bottomBar.add(btnEndCall);

        add(bottomBar, BorderLayout.SOUTH);
    }

    private JButton createControlButton(String text, Color bgColor) {
        JButton btn = new JButton(text);
        btn.setPreferredSize(new Dimension(120, 42));
        btn.setBackground(bgColor);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btn.setFocusPainted(false);
        btn.setBorder(new LineBorder(COLOR_CARD_BG, 1, true));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void toggleMic() {
        boolean nowMuted = !mediaEngine.isMicMuted();
        mediaEngine.setMicMuted(nowMuted);
        if (nowMuted) {
            btnToggleMic.setText("🔇 Bật Mic");
            btnToggleMic.setBackground(COLOR_DANGER);
        } else {
            btnToggleMic.setText("🎙️ Tắt Mic");
            btnToggleMic.setBackground(new Color(71, 85, 105));
        }
    }

    private void toggleCam() {
        boolean nowEnabled = !mediaEngine.isCameraEnabled();
        mediaEngine.setCameraEnabled(nowEnabled);
        if (nowEnabled) {
            btnToggleCam.setText("📹 Tắt Cam");
            btnToggleCam.setBackground(new Color(71, 85, 105));
        } else {
            btnToggleCam.setText("🚫 Bật Cam");
            btnToggleCam.setBackground(COLOR_DANGER);
        }
    }

    private void handleEndCall() {
        if (durationTimer != null) {
            durationTimer.stop();
        }
        setVisible(false);
        dispose();
        if (onEndCallAction != null) {
            onEndCallAction.run();
        }
    }

    private void startDurationTimer() {
        callSeconds = 0;
        durationTimer = new Timer(1000, e -> {
            callSeconds++;
            int m = callSeconds / 60;
            int s = callSeconds % 60;
            lblDuration.setText(String.format("⏱ %02d:%02d", m, s));
        });
        durationTimer.start();
    }

    /**
     * Cập nhật khung hình nhận được từ đối phương (Remote Video).
     */
    public void updateRemoteFrame(BufferedImage frame) {
        if (remoteVideoPanel != null && frame != null) {
            SwingUtilities.invokeLater(() -> remoteVideoPanel.setFrame(frame));
        }
    }

    /**
     * Cập nhật khung hình camera/màn hình của chính mình (Local PiP Preview).
     */
    public void updateLocalFrame(BufferedImage frame) {
        if (localVideoPanel != null && frame != null) {
            SwingUtilities.invokeLater(() -> localVideoPanel.setFrame(frame));
        }
    }

    public void closeWindow() {
        if (durationTimer != null) {
            durationTimer.stop();
        }
        setVisible(false);
        dispose();
    }

    // =========================================================================
    // CÁC THÀNH PHẦN HIỂN THỊ VIDEO TUỲ BIẾN (CUSTOM VIDEO PANELS)
    // =========================================================================

    /**
     * Khung hình lớn trung tâm hiển thị hình ảnh đối phương.
     */
    private static class VideoPanel extends JPanel {
        private final String peerName;
        private BufferedImage currentFrame;

        public VideoPanel(String peerName) {
            this.peerName = peerName;
            setBackground(COLOR_BG_DARK);
        }

        public void setFrame(BufferedImage frame) {
            this.currentFrame = frame;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (currentFrame != null) {
                // Vẽ co giãn hình ảnh giữ nguyên tỉ lệ (Aspect Ratio)
                int panelW = getWidth();
                int panelH = getHeight();
                int imgW = currentFrame.getWidth();
                int imgH = currentFrame.getHeight();

                double scale = Math.min((double) panelW / imgW, (double) panelH / imgH);
                int drawW = (int) (imgW * scale);
                int drawH = (int) (imgH * scale);
                int x = (panelW - drawW) / 2;
                int y = (panelH - drawH) / 2;

                g2.drawImage(currentFrame, x, y, drawW, drawH, null);

                // Nhãn góc hiển thị tên đối phương
                g2.setColor(new Color(0, 0, 0, 160));
                g2.fillRoundRect(16, panelH - 42, 160, 26, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.drawString("👤 " + peerName, 26, panelH - 25);

            } else {
                // Trạng thái chờ: Hiển thị Avatar xung động và thông báo
                int cx = getWidth() / 2;
                int cy = getHeight() / 2 - 20;

                g2.setColor(new Color(30, 41, 59));
                g2.fillOval(cx - 50, cy - 50, 100, 100);

                g2.setColor(COLOR_PRIMARY);
                g2.drawOval(cx - 50, cy - 50, 100, 100);

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 36));
                String initial = peerName.isEmpty() ? "?" : peerName.substring(0, 1).toUpperCase();
                g2.drawString(initial, cx - 12, cy + 12);

                g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
                g2.setColor(COLOR_TEXT_MUTED);
                String waitingText = "Đang chờ luồng hình ảnh từ " + peerName + "...";
                int textW = g2.getFontMetrics().stringWidth(waitingText);
                g2.drawString(waitingText, cx - (textW / 2), cy + 80);
            }
        }
    }

    /**
     * Khung hình nhỏ Picture-in-Picture góc dưới hiển thị Local Preview của chính mình.
     */
    private static class LocalPreviewPanel extends JPanel {
        private final String localName;
        private BufferedImage currentFrame;

        public LocalPreviewPanel(String localName) {
            this.localName = localName;
            setOpaque(false);
            setBorder(new CompoundBorder(
                new LineBorder(new Color(59, 130, 246, 200), 2, true),
                new EmptyBorder(1, 1, 1, 1)
            ));
        }

        public void setFrame(BufferedImage frame) {
            this.currentFrame = frame;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int w = getWidth();
            int h = getHeight();

            if (currentFrame != null) {
                g2.drawImage(currentFrame, 0, 0, w, h, null);
            } else {
                g2.setColor(new Color(30, 41, 59));
                g2.fillRect(0, 0, w, h);
            }

            // Nhãn "Bạn (Local)"
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, h - 22, w, 22);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 10));
            g2.drawString("Bạn (" + localName + ")", 6, h - 7);
        }
    }
}
