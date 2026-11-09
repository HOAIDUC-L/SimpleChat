package com.chat.gui;

import com.chat.client.ChatClient;
import com.chat.client.ChatClientListener;
import com.chat.media.UdpMediaEngine;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ChatWindow - Giao diện Client chính của SimpleChat theo phong cách Flat UI hiện đại (tương tự Zalo/Messenger).
 *
 * TÍNH NĂNG NỔI BẬT:
 * 1. Đa vùng chọn trò chuyện trực quan:
 *    - 🌐 Chat Chung (Toàn hệ thống - Broadcast)
 *    - 🏘️ Danh sách Phòng Chat (Group/Room - Multicast) kèm nút Tạo phòng & Tham gia
 *    - 👤 Bạn bè trực tuyến (Tin nhắn riêng 1-1)
 * 2. Biểu tượng cảm xúc (Emoji Picker): Popup Emoji trực quan ngay cạnh ô nhập liệu.
 * 3. Voice Call UDP qua Báo hiệu TCP (Hybrid Network):
 *    - Nút Gọi Thoại 📞 khi chọn 1 bạn bè trực tuyến.
 *    - Trao đổi thỏa thuận IP/Port qua TCP (Signaling), sau đó kích hoạt stream âm thanh P2P qua UDP DatagramSocket.
 * 4. Truy vấn lịch sử tin nhắn từ cơ sở dữ liệu SQLite nhúng.
 * 5. Khung chat bong bóng (HTML Chat Bubbles) phong cách Zalo/Messenger sang trọng.
 */
public class ChatWindow extends JFrame implements ChatClientListener {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Bảng màu Flat UI hiện đại
    private static final Color COLOR_PRIMARY = new Color(37, 99, 235);       // Xanh dương chủ đạo
    private static final Color COLOR_PRIMARY_HOVER = new Color(29, 78, 216);
    private static final Color COLOR_SUCCESS = new Color(16, 185, 129);      // Xanh lá kết nối
    private static final Color COLOR_DANGER = new Color(239, 68, 68);        // Đỏ ngắt kết nối / Gác máy
    private static final Color COLOR_BG_LIGHT = new Color(248, 250, 252);     // Nền xám nhạt hiện đại
    private static final Color COLOR_BORDER = new Color(226, 232, 240);       // Viền thẻ
    private static final Color COLOR_TEXT_DARK = new Color(30, 41, 59);      // Chữ tối màu dễ đọc
    private static final Color COLOR_TEXT_MUTED = new Color(100, 116, 139);   // Chữ phụ mờ
    private static final Color COLOR_PURPLE = new Color(124, 58, 237);       // Tím riêng tư

    // Thành phần kết nối
    private JTextField txtHost;
    private JTextField txtPort;
    private JTextField txtUsername;
    private JButton btnConnect;
    private JButton btnDisconnect;
    private JLabel lblOnlineStatusBadge;

    // Phân vùng Sidebar: Tabs Room & Friends
    private JTabbedPane sidebarTabbedPane;
    private DefaultListModel<String> roomListModel;
    private JList<String> roomList;
    private JButton btnCreateRoom;
    private JButton btnJoinRoom;
    private JButton btnLeaveRoom;

    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel lblUserCount;

    // Header của khung chat
    private JLabel lblTargetRecipientBadge;
    private JButton btnVoiceCall;
    private JButton btnEndCall;
    private JButton btnHistory;

    // Khung hiển thị chat HTML (Bong bóng chat)
    private JTextPane chatPane;
    private HTMLEditorKit htmlKit;
    private HTMLDocument htmlDoc;
    private JTextField txtMessageInput;
    private JButton btnEmoji;
    private JButton btnSend;
    private EmojiPicker emojiPicker;

    // Khu vực truyền file
    private JButton btnChooseFile;
    private JButton btnSendFile;
    private JLabel lblSelectedFileChip;
    private File selectedFile;

    // Tiến trình & Trạng thái
    private JProgressBar progressBar;
    private JLabel lblStatusText;

    // Core logic
    private final ChatClient client;
    private final UdpMediaEngine mediaEngine;
    private String myUsername = "";
    private String activeTarget = "ALL"; // "ALL", "ROOM:<tên_phòng>", hoặc username
    private final Set<String> joinedRooms = new HashSet<>();

    // Quản lý trạng thái cuộc gọi & Cửa sổ Video Call
    private volatile String activeCallPeer = null;
    private volatile VideoCallWindow videoCallWindow = null;

    public ChatWindow() {
        this.client = new ChatClient(this);
        this.mediaEngine = new UdpMediaEngine(new UdpMediaEngine.MediaEngineListener() {
            @Override
            public void onCallStarted(int localAudioPort, int localVideoPort, String remoteHost, int remoteAudioPort, int remoteVideoPort) {
                SwingUtilities.invokeLater(() -> {
                    lblStatusText.setText("📹 Đang trong cuộc gọi Video với [" + activeCallPeer + "] (Audio: " + localAudioPort + ", Video: " + localVideoPort + ")");
                    btnVoiceCall.setVisible(false);
                    btnEndCall.setVisible(true);
                    appendHtml("<div style='text-align: center; margin: 8px 0;'>"
                             + "<span style='background-color: #DCFCE7; color: #166534; padding: 4px 14px; font-size: 11px; border: 1px solid #BBF7D0;'>"
                             + "📹 <b>Cuộc gọi Video & Voice UDP đã bắt đầu</b> (Audio: " + localAudioPort + "->" + remoteAudioPort 
                             + " | Video: " + localVideoPort + "->" + remoteVideoPort + ")</span></div>");

                    // Tự động mở cửa sổ Video Call riêng biệt (Floating Video Call Window)
                    if (videoCallWindow == null || !videoCallWindow.isVisible()) {
                        videoCallWindow = new VideoCallWindow(activeCallPeer, myUsername, mediaEngine, () -> handleEndCall());
                        videoCallWindow.setVisible(true);
                    }
                });
            }

            @Override
            public void onLocalFrameCaptured(java.awt.image.BufferedImage localFrame) {
                if (videoCallWindow != null && videoCallWindow.isVisible()) {
                    videoCallWindow.updateLocalFrame(localFrame);
                }
            }

            @Override
            public void onRemoteFrameReceived(java.awt.image.BufferedImage remoteFrame) {
                if (videoCallWindow != null && videoCallWindow.isVisible()) {
                    videoCallWindow.updateRemoteFrame(remoteFrame);
                }
            }

            @Override
            public void onCallStopped(String reason) {
                SwingUtilities.invokeLater(() -> {
                    lblStatusText.setText("Cuộc gọi kết thúc: " + reason);
                    btnVoiceCall.setVisible(true);
                    btnEndCall.setVisible(false);
                    if (videoCallWindow != null) {
                        videoCallWindow.closeWindow();
                        videoCallWindow = null;
                    }
                    activeCallPeer = null;
                    appendHtml("<div style='text-align: center; margin: 8px 0;'>"
                             + "<span style='background-color: #FEE2E2; color: #991B1B; padding: 4px 14px; font-size: 11px; border: 1px solid #FECACA;'>"
                             + "📴 Cuộc gọi đã kết thúc (" + reason + ")</span></div>");
                });
            }

            @Override
            public void onError(String errorMessage) {
                SwingUtilities.invokeLater(() -> {
                    lblStatusText.setText("Lỗi Media UDP: " + errorMessage);
                });
            }
        });

        initModernUI();
    }

    private void initModernUI() {
        setTitle("SimpleChat - Hệ thống Chat & Voice Call (TCP / UDP / SQLite)");
        setSize(980, 720);
        setMinimumSize(new Dimension(860, 600));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        // Đóng cửa sổ ngắt kết nối an toàn
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (videoCallWindow != null) {
                    videoCallWindow.closeWindow();
                    videoCallWindow = null;
                }
                if (mediaEngine.isCalling()) {
                    mediaEngine.stopCall("Đóng cửa sổ");
                }
                if (client.isConnected()) {
                    client.disconnect("Đóng cửa sổ");
                }
                dispose();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout(10, 10));
        rootPanel.setBackground(COLOR_BG_LIGHT);
        rootPanel.setBorder(new EmptyBorder(10, 12, 10, 12));

        // 1. THANH TRÊN CÙNG: Flat Connection Bar & Branding
        rootPanel.add(buildTopHeaderPanel(), BorderLayout.NORTH);

        // 2. CỘT TRÁI: Phân tách Phòng Chat (Multicast) và Bạn Bè Online
        rootPanel.add(buildLeftSidebar(), BorderLayout.WEST);

        // 3. KHU VỰC TRUNG TÂM: Bong bóng chat, Nút Gọi thoại, Emoji & Nhập liệu
        rootPanel.add(buildCenterChatPanel(), BorderLayout.CENTER);

        // 4. THANH DƯỚI CÙNG: ProgressBar & Status Bar
        rootPanel.add(buildBottomStatusPanel(), BorderLayout.SOUTH);

        setContentPane(rootPanel);
        updateConnectionState(false);
    }

    /**
     * Header & Thanh kết nối phẳng sang trọng.
     */
    private JPanel buildTopHeaderPanel() {
        JPanel container = new JPanel(new BorderLayout(0, 6));
        container.setOpaque(false);

        // Dòng trên: Logo và Trạng thái
        JPanel brandRow = new JPanel(new BorderLayout());
        brandRow.setOpaque(false);

        JLabel lblTitle = new JLabel("💬 SimpleChat Pro (TCP Chat / UDP Voice / SQLite)");
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 17));
        lblTitle.setForeground(COLOR_TEXT_DARK);
        brandRow.add(lblTitle, BorderLayout.WEST);

        lblOnlineStatusBadge = new JLabel("● Chưa kết nối");
        lblOnlineStatusBadge.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblOnlineStatusBadge.setForeground(COLOR_TEXT_MUTED);
        brandRow.add(lblOnlineStatusBadge, BorderLayout.EAST);
        container.add(brandRow, BorderLayout.NORTH);

        // Dòng dưới: Form nhập IP, Port, Username và Nút bấm
        JPanel formCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        formCard.setBackground(Color.WHITE);
        formCard.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(4, 8, 4, 8)
        ));

        formCard.add(createFieldLabel("IP Server:"));
        txtHost = createStyledTextField("127.0.0.1", 11);
        txtHost.setToolTipText("Nhập IP Server (127.0.0.1 nếu chạy cùng máy)");
        txtHost.addActionListener(e -> handleConnect());
        formCard.add(txtHost);

        formCard.add(createFieldLabel("Port:"));
        txtPort = createStyledTextField("8888", 5);
        txtPort.setToolTipText("Cổng TCP Server (mặc định 8888)");
        txtPort.addActionListener(e -> handleConnect());
        formCard.add(txtPort);

        formCard.add(createFieldLabel("Username:"));
        txtUsername = createStyledTextField("User" + (int)(Math.random() * 900 + 100), 10);
        txtUsername.setToolTipText("Tên hiển thị của bạn");
        txtUsername.addActionListener(e -> handleConnect());
        formCard.add(txtUsername);

        btnConnect = createFlatButton("Kết nối", COLOR_SUCCESS);
        btnConnect.addActionListener(e -> handleConnect());
        formCard.add(btnConnect);

        btnDisconnect = createFlatButton("Ngắt kết nối", COLOR_DANGER);
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> client.disconnect("Người dùng chủ động ngắt kết nối"));
        formCard.add(btnDisconnect);

        container.add(formCard, BorderLayout.CENTER);
        return container;
    }

    /**
     * Cột bên trái: Thiết kế Tab phân tách rõ rệt giữa Danh sách Phòng (Multicast) và Bạn Bè Online.
     */
    private JPanel buildLeftSidebar() {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setPreferredSize(new Dimension(270, 0));
        card.setBackground(Color.WHITE);
        card.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(6, 6, 6, 6)
        ));

        sidebarTabbedPane = new JTabbedPane();
        sidebarTabbedPane.setFont(new Font("SansSerif", Font.BOLD, 12));

        // TAB 1: DANH SÁCH BẠN BÈ ONLINE & BROADCAST
        JPanel friendsPanel = new JPanel(new BorderLayout(0, 6));
        friendsPanel.setBackground(Color.WHITE);

        JPanel friendsHeader = new JPanel(new BorderLayout());
        friendsHeader.setOpaque(false);
        JLabel lblFriendsTitle = new JLabel("Trực tuyến");
        lblFriendsTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblFriendsTitle.setForeground(COLOR_TEXT_DARK);
        friendsHeader.add(lblFriendsTitle, BorderLayout.WEST);

        lblUserCount = new JLabel("(0 online)");
        lblUserCount.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblUserCount.setForeground(COLOR_TEXT_MUTED);
        friendsHeader.add(lblUserCount, BorderLayout.EAST);
        friendsPanel.add(friendsHeader, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new ModernUserListRenderer());
        userList.setBackground(Color.WHITE);

        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && userList.getSelectedValue() != null) {
                // Hủy chọn bên danh sách phòng
                roomList.clearSelection();
                updateActiveChatTarget();
            }
        });

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createEmptyBorder());
        userScroll.getViewport().setBackground(Color.WHITE);
        friendsPanel.add(userScroll, BorderLayout.CENTER);

        JLabel lblFriendsHint = new JLabel("<html><center style='color:#94A3B8; font-size:10px;'>💡 Bấm để Chat 1-1 hoặc Gọi Voice</center></html>");
        lblFriendsHint.setHorizontalAlignment(SwingConstants.CENTER);
        friendsPanel.add(lblFriendsHint, BorderLayout.SOUTH);

        sidebarTabbedPane.addTab("👤 Bạn bè", friendsPanel);

        // TAB 2: DANH SÁCH PHÒNG CHAT (MULTICAST ROOMS)
        JPanel roomsPanel = new JPanel(new BorderLayout(0, 6));
        roomsPanel.setBackground(Color.WHITE);

        JPanel roomsHeader = new JPanel(new BorderLayout());
        roomsHeader.setOpaque(false);
        JLabel lblRoomsTitle = new JLabel("Phòng (Multicast)");
        lblRoomsTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblRoomsTitle.setForeground(COLOR_TEXT_DARK);
        roomsHeader.add(lblRoomsTitle, BorderLayout.WEST);

        btnCreateRoom = createOutlineButton("➕ Tạo Phòng");
        btnCreateRoom.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnCreateRoom.addActionListener(e -> handleCreateRoom());
        roomsHeader.add(btnCreateRoom, BorderLayout.EAST);
        roomsPanel.add(roomsHeader, BorderLayout.NORTH);

        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setCellRenderer(new ModernRoomListRenderer(joinedRooms));
        roomList.setBackground(Color.WHITE);

        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && roomList.getSelectedValue() != null) {
                // Hủy chọn bên danh sách bạn bè
                userList.clearSelection();
                updateActiveChatTarget();
            }
        });

        JScrollPane roomScroll = new JScrollPane(roomList);
        roomScroll.setBorder(BorderFactory.createEmptyBorder());
        roomScroll.getViewport().setBackground(Color.WHITE);
        roomsPanel.add(roomScroll, BorderLayout.CENTER);

        // Thanh thao tác vào/rời phòng
        JPanel roomActionRow = new JPanel(new GridLayout(1, 2, 6, 0));
        roomActionRow.setOpaque(false);
        btnJoinRoom = createOutlineButton("🚪 Vào Phòng");
        btnJoinRoom.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnJoinRoom.addActionListener(e -> handleJoinRoom());
        roomActionRow.add(btnJoinRoom);

        btnLeaveRoom = createOutlineButton("↩️ Rời Phòng");
        btnLeaveRoom.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnLeaveRoom.addActionListener(e -> handleLeaveRoom());
        roomActionRow.add(btnLeaveRoom);
        roomsPanel.add(roomActionRow, BorderLayout.SOUTH);

        sidebarTabbedPane.addTab("🏘️ Phòng Chat", roomsPanel);

        card.add(sidebarTabbedPane, BorderLayout.CENTER);
        return card;
    }

    /**
     * Khung chat trung tâm: Bong bóng tin nhắn HTML, Nút Gọi thoại, Nút Emoji và Soạn thảo tin nhắn.
     */
    private JPanel buildCenterChatPanel() {
        JPanel centerCard = new JPanel(new BorderLayout(0, 8));
        centerCard.setBackground(Color.WHITE);
        centerCard.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        // 1. THANH TIÊU ĐỀ CUỘC TRÒ CHUYỆN (Chat Target Header)
        JPanel targetHeader = new JPanel(new BorderLayout(10, 0));
        targetHeader.setOpaque(false);
        targetHeader.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));

        lblTargetRecipientBadge = new JLabel("💬 Đang gửi đến: 🌐 Tất cả mọi người (Phòng chung)");
        lblTargetRecipientBadge.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblTargetRecipientBadge.setForeground(COLOR_PRIMARY);
        targetHeader.add(lblTargetRecipientBadge, BorderLayout.CENTER);

        // Cụm nút thao tác bên phải: Gọi Thoại 📞, Gác Máy 📴, Xem Lịch Sử 📜
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerActions.setOpaque(false);

        btnHistory = createOutlineButton("📜 Lịch Sử");
        btnHistory.setToolTipText("Trích xuất lịch sử tin nhắn từ cơ sở dữ liệu SQLite");
        btnHistory.addActionListener(e -> handleRequestHistory());
        headerActions.add(btnHistory);

        btnVoiceCall = createFlatButton("📹 Gọi Video (UDP)", COLOR_SUCCESS);
        btnVoiceCall.setToolTipText("Bắt đầu cuộc gọi Video & Âm thanh thời gian thực qua UDP");
        btnVoiceCall.setVisible(false); // Chỉ bật khi chọn user 1-1
        btnVoiceCall.addActionListener(e -> handleVoiceCall());
        headerActions.add(btnVoiceCall);

        btnEndCall = createFlatButton("📴 Gác Máy", COLOR_DANGER);
        btnEndCall.setVisible(false);
        btnEndCall.addActionListener(e -> handleEndCall());
        headerActions.add(btnEndCall);

        targetHeader.add(headerActions, BorderLayout.EAST);
        centerCard.add(targetHeader, BorderLayout.NORTH);

        // 2. KHUNG HIỂN THỊ TIN NHẮN (HTML CHAT BUBBLES)
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setContentType("text/html");
        htmlKit = new HTMLEditorKit();
        chatPane.setEditorKit(htmlKit);
        htmlDoc = (HTMLDocument) chatPane.getDocument();

        // CSS Định dạng bong bóng chat phong cách Zalo/Messenger
        String baseCSS = "body { font-family: sans-serif; margin: 6px; padding: 0; background-color: #FFFFFF; } "
                       + ".msg-box { margin: 6px 0px; } "
                       + ".time { font-size: 9px; color: #94A3B8; margin-left: 6px; } ";
        htmlKit.getStyleSheet().addRule(baseCSS);

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(new LineBorder(COLOR_BORDER, 1, true));
        chatScroll.getViewport().setBackground(Color.WHITE);
        centerCard.add(chatScroll, BorderLayout.CENTER);

        // 3. VÙNG NHẬP LIỆU PHÍA DƯỚI (Input + Emoji + File)
        JPanel bottomArea = new JPanel(new BorderLayout(0, 6));
        bottomArea.setOpaque(false);

        // Dòng nhập text kèm nút Emoji và Gửi
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setOpaque(false);

        // Nút mở Emoji Picker
        btnEmoji = new JButton("😀");
        btnEmoji.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        btnEmoji.setPreferredSize(new Dimension(42, 36));
        btnEmoji.setFocusPainted(false);
        btnEmoji.setBackground(new Color(241, 245, 249));
        btnEmoji.setBorder(new LineBorder(COLOR_BORDER, 1, true));
        btnEmoji.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnEmoji.setToolTipText("Chèn biểu tượng cảm xúc");

        // Khởi tạo EmojiPicker popup
        emojiPicker = new EmojiPicker(emoji -> {
            int pos = txtMessageInput.getCaretPosition();
            String current = txtMessageInput.getText();
            String prefix = current.substring(0, pos);
            String suffix = current.substring(pos);
            txtMessageInput.setText(prefix + emoji + suffix);
            txtMessageInput.setCaretPosition(pos + emoji.length());
            txtMessageInput.requestFocus();
        });

        btnEmoji.addActionListener(e -> {
            if (emojiPicker.isVisible()) {
                emojiPicker.setVisible(false);
            } else {
                emojiPicker.showAbove(btnEmoji);
            }
        });
        inputRow.add(btnEmoji, BorderLayout.WEST);

        txtMessageInput = new JTextField();
        txtMessageInput.setPreferredSize(new Dimension(0, 36));
        txtMessageInput.setFont(new Font("SansSerif", Font.PLAIN, 14));
        txtMessageInput.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(4, 10, 4, 10)
        ));
        txtMessageInput.addActionListener(e -> handleSendMessage());
        inputRow.add(txtMessageInput, BorderLayout.CENTER);

        btnSend = createFlatButton("Gửi ➤", COLOR_PRIMARY);
        btnSend.setPreferredSize(new Dimension(90, 36));
        btnSend.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnSend.addActionListener(e -> handleSendMessage());
        inputRow.add(btnSend, BorderLayout.EAST);
        bottomArea.add(inputRow, BorderLayout.CENTER);

        // Thanh truyền file
        JPanel fileBar = new JPanel(new BorderLayout(8, 0));
        fileBar.setOpaque(false);
        fileBar.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel fileLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fileLeft.setOpaque(false);

        btnChooseFile = createOutlineButton("📎 Chọn File...");
        btnChooseFile.addActionListener(e -> handleChooseFile());
        fileLeft.add(btnChooseFile);

        lblSelectedFileChip = new JLabel("Chưa chọn file nào");
        lblSelectedFileChip.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblSelectedFileChip.setForeground(COLOR_TEXT_MUTED);
        fileLeft.add(lblSelectedFileChip);
        fileBar.add(fileLeft, BorderLayout.CENTER);

        btnSendFile = createFlatButton("📤 Gửi File", COLOR_PURPLE);
        btnSendFile.addActionListener(e -> handleSendFile());
        fileBar.add(btnSendFile, BorderLayout.EAST);

        bottomArea.add(fileBar, BorderLayout.SOUTH);
        centerCard.add(bottomArea, BorderLayout.SOUTH);

        return centerCard;
    }

    /**
     * Thanh dưới cùng: ProgressBar và Trạng thái hệ thống.
     */
    private JPanel buildBottomStatusPanel() {
        JPanel statusCard = new JPanel(new BorderLayout(10, 0));
        statusCard.setOpaque(false);
        statusCard.setBorder(new EmptyBorder(2, 4, 2, 4));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Sẵn sàng");
        progressBar.setPreferredSize(new Dimension(280, 20));
        progressBar.setForeground(COLOR_PRIMARY);
        progressBar.setBackground(new Color(241, 245, 249));
        progressBar.setBorder(new LineBorder(COLOR_BORDER, 1, true));
        statusCard.add(progressBar, BorderLayout.WEST);

        lblStatusText = new JLabel("Trạng thái: Chưa kết nối");
        lblStatusText.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblStatusText.setForeground(COLOR_TEXT_MUTED);
        statusCard.add(lblStatusText, BorderLayout.CENTER);

        return statusCard;
    }

    // --- Các hàm tiện ích tạo UI Flat ---

    private JLabel createFieldLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        lbl.setForeground(COLOR_TEXT_DARK);
        return lbl;
    }

    private JTextField createStyledTextField(String defaultValue, int cols) {
        JTextField tf = new JTextField(defaultValue, cols);
        tf.setPreferredSize(new Dimension(tf.getPreferredSize().width, 30));
        tf.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tf.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(2, 8, 2, 8)
        ));
        return tf;
    }

    private JButton createFlatButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width + 12, 30));
        return btn;
    }

    private JButton createOutlineButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setForeground(COLOR_TEXT_DARK);
        btn.setBackground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(new CompoundBorder(new LineBorder(COLOR_BORDER, 1, true), new EmptyBorder(4, 10, 4, 10)));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // --- Xử lý sự kiện người dùng ---

    private void handleConnect() {
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();
        String user = txtUsername.getText().trim();

        if (host.isEmpty()) host = "127.0.0.1";
        if (portStr.isEmpty()) portStr = "8888";

        if (user.isEmpty() || user.equalsIgnoreCase("ALL") || user.equalsIgnoreCase("SERVER")) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Username hợp lệ!", "Username không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port phải là số nguyên!", "Port không hợp lệ", JOptionPane.ERROR_MESSAGE);
            return;
        }

        lblStatusText.setText("Đang kết nối tới " + host + ":" + port + "...");
        btnConnect.setEnabled(false);
        client.connect(host, port, user);
    }

    /**
     * Chuyển đổi đối tượng nhận tin (Target: Broadcast ALL, Room Multicast, hoặc 1-1 Chat).
     */
    private void updateActiveChatTarget() {
        String selectedUser = userList.getSelectedValue();
        String selectedRoom = roomList.getSelectedValue();

        if (selectedRoom != null) {
            // Đang chọn 1 phòng chat (Multicast)
            activeTarget = "ROOM:" + selectedRoom;
            boolean hasJoined = joinedRooms.contains(selectedRoom);
            String joinStatus = hasJoined ? " (✅ Đã tham gia)" : " (⚠️ Chưa tham gia)";
            lblTargetRecipientBadge.setText("💬 Phòng: 🏘️ [" + selectedRoom + "] (Multicast)" + joinStatus);
            lblTargetRecipientBadge.setForeground(new Color(13, 148, 136)); // Teal
            btnVoiceCall.setVisible(false);

        } else if (selectedUser != null && !selectedUser.startsWith("[Tất cả")) {
            // Đang chọn 1 bạn bè trực tuyến (Private 1-1)
            activeTarget = selectedUser;
            lblTargetRecipientBadge.setText("💬 Chat riêng: 🔒 [" + selectedUser + "] (Tin nhắn 1-1)");
            lblTargetRecipientBadge.setForeground(COLOR_PURPLE);

            // Bật nút Gọi Thoại Voice Call nếu chưa trong cuộc gọi
            btnVoiceCall.setVisible(!mediaEngine.isCalling());
            btnVoiceCall.setText("📞 Gọi Voice với " + selectedUser);

        } else {
            // Mặc định: Broadcast ALL
            activeTarget = "ALL";
            lblTargetRecipientBadge.setText("💬 Đang gửi đến: 🌐 Tất cả mọi người (Phòng chung - Broadcast)");
            lblTargetRecipientBadge.setForeground(COLOR_PRIMARY);
            btnVoiceCall.setVisible(false);
        }
    }

    private void handleSendMessage() {
        String msg = txtMessageInput.getText().trim();
        if (msg.isEmpty()) return;

        if (activeTarget.startsWith("ROOM:")) {
            String roomName = activeTarget.substring(5);
            if (!joinedRooms.contains(roomName)) {
                JOptionPane.showMessageDialog(this, "Bạn cần bấm 'Vào Phòng' trước khi gửi tin vào phòng [" + roomName + "]!", "Chưa tham gia phòng", JOptionPane.WARNING_MESSAGE);
                return;
            }
            client.sendRoomMessage(roomName, msg);
        } else {
            client.sendMessage(activeTarget, msg);
        }

        txtMessageInput.setText("");
        txtMessageInput.requestFocus();
    }

    private void handleCreateRoom() {
        String roomName = JOptionPane.showInputDialog(this, "Nhập tên phòng chat mới cần tạo:", "Tạo Phòng Mới", JOptionPane.PLAIN_MESSAGE);
        if (roomName != null && !roomName.trim().isEmpty()) {
            client.createRoom(roomName.trim());
            joinedRooms.add(roomName.trim());
        }
    }

    private void handleJoinRoom() {
        String selectedRoom = roomList.getSelectedValue();
        if (selectedRoom == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một phòng trong danh sách để tham gia!", "Chưa chọn phòng", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        client.joinRoom(selectedRoom);
        joinedRooms.add(selectedRoom);
        roomList.repaint();
        updateActiveChatTarget();
    }

    private void handleLeaveRoom() {
        String selectedRoom = roomList.getSelectedValue();
        if (selectedRoom == null) return;
        client.leaveRoom(selectedRoom);
        joinedRooms.remove(selectedRoom);
        roomList.repaint();
        updateActiveChatTarget();
    }

    /**
     * Bắt đầu cuộc gọi Video & Voice: Mở 2 cổng UDP (Audio & Video) và gửi CALL_REQUEST qua TCP.
     */
    private void handleVoiceCall() {
        if (!client.isConnected()) return;
        if (activeTarget.equals("ALL") || activeTarget.startsWith("ROOM:")) {
            JOptionPane.showMessageDialog(this, "Cuộc gọi Video chỉ áp dụng cho cuộc trò chuyện 1-1 với bạn bè trực tuyến!", "Chọn đối phương", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try {
            mediaEngine.setLocalUsername(myUsername);
            int[] ports = mediaEngine.bindLocalPorts();
            int localAudioPort = ports[0];
            int localVideoPort = ports[1];
            activeCallPeer = activeTarget;
            lblStatusText.setText("Đang gọi Video tới [" + activeCallPeer + "]... Chờ đối phương bắt máy...");
            appendHtml("<div style='text-align: center; margin: 6px 0;'>"
                     + "<span style='background-color: #FEF3C7; color: #B45309; padding: 4px 12px; font-size: 11px; border: 1px solid #FDE68A;'>"
                     + "📹 Đang mời gọi Video & Voice tới <b>" + activeCallPeer + "</b>...</span></div>");

            // Gửi gói tin TCP Signaling: CALL_REQUEST với cả 2 cổng Audio và Video
            client.sendCallRequest(activeCallPeer, localAudioPort, localVideoPort);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Không thể mở cổng UDP cho cuộc gọi: " + e.getMessage(), "Lỗi Cuộc Gọi", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Gác máy / Hủy cuộc gọi.
     */
    private void handleEndCall() {
        if (activeCallPeer != null) {
            client.sendCallEnd(activeCallPeer, "Người dùng gác máy");
        }
        if (videoCallWindow != null) {
            videoCallWindow.closeWindow();
            videoCallWindow = null;
        }
        mediaEngine.stopCall("Bạn đã gác máy");
    }

    /**
     * Yêu cầu xem lịch sử chat từ SQLite Database.
     */
    private void handleRequestHistory() {
        if (!client.isConnected()) return;
        lblStatusText.setText("Đang lấy lịch sử chat từ SQLite cho [" + activeTarget + "]...");
        client.requestHistory(activeTarget);
    }

    private void handleChooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn file cần gửi");
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile();
            long size = selectedFile.length();
            lblSelectedFileChip.setText("📄 " + selectedFile.getName() + " (" + formatFileSize(size) + ")");
            lblSelectedFileChip.setForeground(COLOR_PRIMARY);
        }
    }

    private void handleSendFile() {
        if (selectedFile == null || !selectedFile.exists()) {
            JOptionPane.showMessageDialog(this, "Vui lòng bấm 'Chọn File...' để chọn tệp cần gửi!", "Chưa chọn file", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (activeTarget.equalsIgnoreCase("ALL") || activeTarget.startsWith("ROOM:")) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một bạn bè cụ thể bên tab 'Bạn bè' để gửi file 1-1!", "Chọn người nhận file", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Gửi file '" + selectedFile.getName() + "' (" + formatFileSize(selectedFile.length()) + ") đến [" + activeTarget + "]?",
            "Xác nhận gửi file",
            JOptionPane.YES_NO_OPTION
        );

        if (confirm == JOptionPane.YES_OPTION) {
            lblStatusText.setText("Đang gửi yêu cầu gửi file tới [" + activeTarget + "]...");
            client.sendFileRequest(activeTarget, selectedFile);
        }
    }

    private void updateConnectionState(boolean connected) {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtHost.setEditable(!connected);
        txtPort.setEditable(!connected);
        txtUsername.setEditable(!connected);

        txtMessageInput.setEnabled(connected);
        btnSend.setEnabled(connected);
        btnEmoji.setEnabled(connected);
        btnChooseFile.setEnabled(connected);
        btnSendFile.setEnabled(connected);
        btnCreateRoom.setEnabled(connected);
        btnJoinRoom.setEnabled(connected);
        btnLeaveRoom.setEnabled(connected);
        btnHistory.setEnabled(connected);

        if (connected) {
            lblOnlineStatusBadge.setText("🟢 Đang trực tuyến (" + myUsername + ")");
            lblOnlineStatusBadge.setForeground(COLOR_SUCCESS);
            joinedRooms.add("Phòng Chung");
        } else {
            lblOnlineStatusBadge.setText("● Chưa kết nối");
            lblOnlineStatusBadge.setForeground(COLOR_TEXT_MUTED);
            userListModel.clear();
            roomListModel.clear();
            joinedRooms.clear();
            lblUserCount.setText("(0 online)");
            lblTargetRecipientBadge.setText("💬 Đang gửi đến: [Chưa kết nối]");
            lblStatusText.setText("Trạng thái: Chưa kết nối");
            btnVoiceCall.setVisible(false);
            btnEndCall.setVisible(false);
            if (mediaEngine.isCalling()) {
                mediaEngine.stopCall("Mất kết nối server");
            }
        }
    }

    private void appendHtml(String htmlSnippet) {
        try {
            htmlKit.insertHTML(htmlDoc, htmlDoc.getLength(), htmlSnippet, 0, 0, null);
            chatPane.setCaretPosition(htmlDoc.getLength());
        } catch (Exception ignored) {
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("\n", "<br/>");
    }

    // --- CÁC CALLBACK TỪ CHATCLIENTLISTENER ---

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> lblStatusText.setText("Đã kết nối Socket, đang xác thực..."));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            updateConnectionState(false);
            appendHtml("<div style='text-align: center; margin: 8px 0;'>"
                     + "<span style='background-color: #FEE2E2; color: #DC2626; padding: 4px 12px; font-size: 11px; border: 1px solid #FECACA;'>"
                     + "⚠️ Đã ngắt kết nối: " + reason + "</span></div>");
            lblStatusText.setText("Đã ngắt kết nối: " + reason);
        });
    }

    @Override
    public void onLoginSuccess(String username, List<String> onlineUsers) {
        SwingUtilities.invokeLater(() -> {
            this.myUsername = username;
            updateConnectionState(true);
            setTitle("SimpleChat - Đang online: [" + username + "]");
            lblStatusText.setText("Đăng nhập thành công!");
            appendHtml("<div style='text-align: center; margin: 10px 0;'>"
                     + "<span style='background-color: #DCFCE7; color: #15803D; padding: 5px 15px; font-size: 12px; font-weight: bold; border: 1px solid #BBF7D0;'>"
                     + "🎉 Chào mừng <b>" + username + "</b> tham gia SimpleChat!</span></div>");
            onUserListUpdated(onlineUsers);
        });
    }

    @Override
    public void onLoginFailed(String reason) {
        SwingUtilities.invokeLater(() -> {
            updateConnectionState(false);
            JOptionPane.showMessageDialog(this, "Đăng nhập thất bại: " + reason, "Lỗi đăng nhập", JOptionPane.ERROR_MESSAGE);
        });
    }

    @Override
    public void onUserListUpdated(List<String> onlineUsers) {
        SwingUtilities.invokeLater(() -> {
            String currentSelected = userList.getSelectedValue();
            userListModel.clear();
            userListModel.addElement("[Tất cả mọi người (ALL)]");

            int otherCount = 0;
            for (String user : onlineUsers) {
                if (!user.equalsIgnoreCase(myUsername)) {
                    userListModel.addElement(user);
                    otherCount++;
                }
            }
            lblUserCount.setText("(" + (otherCount + 1) + " online)");

            if (currentSelected != null && userListModel.contains(currentSelected)) {
                userList.setSelectedValue(currentSelected, true);
            } else if (roomList.isSelectionEmpty()) {
                userList.setSelectedIndex(0);
            }
            updateActiveChatTarget();
        });
    }

    @Override
    public void onRoomListUpdated(List<String> rooms) {
        SwingUtilities.invokeLater(() -> {
            String currentSelected = roomList.getSelectedValue();
            roomListModel.clear();
            for (String r : rooms) {
                roomListModel.addElement(r);
            }
            if (currentSelected != null && roomListModel.contains(currentSelected)) {
                roomList.setSelectedValue(currentSelected, true);
            }
            updateActiveChatTarget();
        });
    }

    @Override
    public void onRoomMessageReceived(String sender, String roomName, String message, long timestamp) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).format(TIME_FORMATTER);

            if (sender.equalsIgnoreCase(myUsername)) {
                // Tin nhắn phòng do chính mình gửi đi (Thẻ xanh bo góc)
                appendHtml("<div class='msg-box' style='margin-left: 50px;'>"
                         + "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='right'>"
                         + "<span style='font-size: 11px; color: #0D9488;'>Tôi (🏘️ Phòng: <b>" + roomName + "</b>)</span>"
                         + "<span class='time'>" + time + "</span>"
                         + "</td></tr><tr><td align='right'>"
                         + "<div style='background-color: #F0FDFA; border: 1px solid #99F6E4; border-right: 4px solid #0D9488; padding: 8px 12px; margin-top: 2px; font-size: 13px; color: #115E59;'>"
                         + escapeHtml(message)
                         + "</div></td></tr></table></div>");
            } else {
                // Tin nhắn Multicast từ thành viên khác trong phòng
                appendHtml("<div class='msg-box' style='margin-right: 50px;'>"
                         + "<span style='font-size: 11px; font-weight: bold; color: #0D9488;'>👥 [" + sender + " @ Phòng: " + roomName + "]</span>"
                         + "<span class='time'>" + time + "</span>"
                         + "<div style='background-color: #F0FDFA; border: 1px solid #CCFBF1; border-left: 4px solid #0D9488; padding: 8px 12px; margin-top: 2px; font-size: 13px; color: #134E4A;'>"
                         + escapeHtml(message)
                         + "</div></div>");
            }
        });
    }

    @Override
    public void onChatMessageReceived(String sender, String recipient, String message, long timestamp) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).format(TIME_FORMATTER);

            if (sender.equalsIgnoreCase("SERVER")) {
                appendHtml("<div style='text-align: center; margin: 6px 0;'>"
                         + "<span style='background-color: #F1F5F9; color: #475569; padding: 3px 12px; font-size: 11px; border: 1px solid #E2E8F0;'>"
                         + "🔔 " + message + " <span class='time'>" + time + "</span></span></div>");

            } else if (sender.equalsIgnoreCase(myUsername)) {
                String targetInfo = recipient.equalsIgnoreCase("ALL") ? "Phòng chung" : "Riêng tới <b>" + recipient + "</b>";
                appendHtml("<div class='msg-box' style='margin-left: 50px;'>"
                         + "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='right'>"
                         + "<span style='font-size: 11px; color: #64748B;'>Tôi (" + targetInfo + ")</span>"
                         + "<span class='time'>" + time + "</span>"
                         + "</td></tr><tr><td align='right'>"
                         + "<div style='background-color: #EFF6FF; border: 1px solid #BFDBFE; border-right: 4px solid #2563EB; padding: 8px 12px; margin-top: 2px; font-size: 13px; color: #1E3A8A;'>"
                         + escapeHtml(message)
                         + "</div></td></tr></table></div>");

            } else if (recipient.equalsIgnoreCase("ALL")) {
                appendHtml("<div class='msg-box' style='margin-right: 50px;'>"
                         + "<span style='font-size: 11px; font-weight: bold; color: #2563EB;'>👤 " + sender + "</span>"
                         + "<span class='time'>" + time + "</span>"
                         + "<div style='background-color: #F8FAFC; border: 1px solid #E2E8F0; border-left: 4px solid #2563EB; padding: 8px 12px; margin-top: 2px; font-size: 13px; color: #0F172A;'>"
                         + escapeHtml(message)
                         + "</div></div>");

            } else {
                appendHtml("<div class='msg-box' style='margin-right: 40px;'>"
                         + "<span style='font-size: 11px; font-weight: bold; color: #7C3AED;'>🔒 [Tin riêng từ " + sender + "]</span>"
                         + "<span class='time'>" + time + "</span>"
                         + "<div style='background-color: #FAF5FF; border: 1px solid #DDD6FE; border-left: 4px solid #7C3AED; padding: 8px 12px; margin-top: 2px; font-size: 13px; color: #4C1D95;'>"
                         + escapeHtml(message)
                         + "</div></div>");
            }
        });
    }

    // --- CALL SIGNALING VOICE & VIDEO CALL VỚI UDP MEDIA ENGINE ---

    @Override
    public void onCallRequestReceived(String caller, int callerAudioPort, int callerVideoPort, String callerIp) {
        SwingUtilities.invokeLater(() -> {
            int option = JOptionPane.showConfirmDialog(
                this,
                "📹 Người dùng [" + caller + "] đang gọi Video & Voice cho bạn!\n\n"
                + "Bạn có đồng ý bắt máy để trò chuyện không?",
                "Cuộc gọi Video đến",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (option == JOptionPane.YES_OPTION) {
                try {
                    mediaEngine.setLocalUsername(myUsername);
                    int[] myPorts = mediaEngine.bindLocalPorts();
                    int myAudioPort = myPorts[0];
                    int myVideoPort = myPorts[1];
                    this.activeCallPeer = caller;

                    // Phân giải host đích của Caller
                    String remoteHost = (callerIp != null && !callerIp.isEmpty() && !callerIp.equals("127.0.0.1") && !callerIp.equals("0:0:0:0:0:0:0:1"))
                                      ? callerIp : txtHost.getText().trim();

                    // Bắt đầu truyền nhận Audio và Video qua UDP Socket
                    mediaEngine.startCall(remoteHost, callerAudioPort, callerVideoPort);

                    // Phản hồi TCP Signaling: ĐỒNG Ý
                    client.sendCallResponse(caller, true, myAudioPort, myVideoPort, "Đồng ý nhận cuộc gọi");

                } catch (Exception e) {
                    client.sendCallResponse(caller, false, 0, 0, "Lỗi cổng UDP: " + e.getMessage());
                }
            } else {
                // Từ chối cuộc gọi
                client.sendCallResponse(caller, false, 0, 0, "Người nhận bận hoặc từ chối");
            }
        });
    }

    @Override
    public void onCallResponseReceived(String responder, boolean accepted, int responderAudioPort, int responderVideoPort, String reasonOrIp) {
        SwingUtilities.invokeLater(() -> {
            if (accepted) {
                String remoteHost = (reasonOrIp != null && !reasonOrIp.isEmpty() && !reasonOrIp.equals("127.0.0.1") && !reasonOrIp.equals("0:0:0:0:0:0:0:1") && !reasonOrIp.contains(" "))
                                  ? reasonOrIp : txtHost.getText().trim();

                // Bắt đầu truyền nhận âm thanh và video qua UDP Socket
                mediaEngine.startCall(remoteHost, responderAudioPort, responderVideoPort);
                lblStatusText.setText("Đối phương đã đồng ý. Đang kết nối Video Call UDP...");

            } else {
                mediaEngine.stopCall(reasonOrIp);
                if (videoCallWindow != null) {
                    videoCallWindow.closeWindow();
                    videoCallWindow = null;
                }
                JOptionPane.showMessageDialog(this, "Cuộc gọi bị từ chối: " + reasonOrIp, "Cuộc gọi kết thúc", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    @Override
    public void onCallEnded(String sender, String reason) {
        SwingUtilities.invokeLater(() -> {
            if (videoCallWindow != null) {
                videoCallWindow.closeWindow();
                videoCallWindow = null;
            }
            mediaEngine.stopCall(reason);
        });
    }

    @Override
    public void onHistoryReceived(String target, List<String> messages) {
        SwingUtilities.invokeLater(() -> {
            lblStatusText.setText("Đã nạp " + messages.size() + " tin nhắn lịch sử từ SQLite.");
            appendHtml("<div style='text-align: center; margin: 10px 0;'>"
                     + "<span style='background-color: #F3F4F6; color: #374151; padding: 4px 14px; font-size: 11px; border: 1px dashed #9CA3AF;'>"
                     + "📜 <b>LỊCH SỬ TIN NHẮN TỪ SQLITE (" + target + ")</b></span></div>");

            for (String msg : messages) {
                appendHtml("<div style='margin: 4px 10px; font-size: 12px; color: #4B5563;'>" + escapeHtml(msg) + "</div>");
            }
            appendHtml("<div style='text-align: center; margin: 6px 0;'><hr style='border: 0; border-top: 1px solid #E5E7EB;'/></div>");
        });
    }

    // --- CÁC CALLBACK TRUYỀN FILE ---

    @Override
    public void onFileRequestReceived(String fileId, String sender, String fileName, long fileSize) {
        SwingUtilities.invokeLater(() -> {
            int option = JOptionPane.showConfirmDialog(
                this,
                "Người dùng [" + sender + "] muốn gửi cho bạn file:\n\n"
                + "📄 Tên file: " + fileName + "\n"
                + "📦 Dung lượng: " + formatFileSize(fileSize) + "\n\n"
                + "Bạn có đồng ý nhận không?",
                "Yêu cầu gửi file mới",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (option == JOptionPane.YES_OPTION) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Chọn thư mục lưu file '" + fileName + "'");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int res = chooser.showSaveDialog(this);
                if (res == JFileChooser.APPROVE_OPTION) {
                    File saveDir = chooser.getSelectedFile();
                    lblStatusText.setText("Đang chuẩn bị nhận file...");
                    client.acceptFileRequest(fileId, sender, fileName, fileSize, saveDir);
                } else {
                    client.rejectFileRequest(fileId, sender, "Hủy chọn thư mục");
                }
            } else {
                client.rejectFileRequest(fileId, sender, "Từ chối nhận file");
            }
        });
    }

    @Override
    public void onFileResponseReceived(String fileId, String responder, boolean accepted, String reason) {
        SwingUtilities.invokeLater(() -> {
            if (accepted) {
                appendHtml("<div style='margin: 6px 0; background-color: #ECFDF5; border: 1px solid #A7F3D0; padding: 6px 12px; font-size: 12px; color: #065F46;'>"
                         + "✅ <b>[" + responder + "]</b> đã ĐỒNG Ý nhận file. Đang truyền dữ liệu qua TCP...</div>");
                lblStatusText.setText("Đang gửi file qua TCP...");
            } else {
                appendHtml("<div style='margin: 6px 0; background-color: #FEF2F2; border: 1px solid #FECACA; padding: 6px 12px; font-size: 12px; color: #991B1B;'>"
                         + "❌ <b>[" + responder + "]</b> đã TỪ CHỐI nhận file (" + reason + ")</div>");
                lblStatusText.setText("Yêu cầu gửi file bị từ chối.");
            }
        });
    }

    @Override
    public void onFileProgress(String fileId, String fileName, long bytesTransferred, long totalBytes, boolean isSending) {
        SwingUtilities.invokeLater(() -> {
            int percent = (int) ((bytesTransferred * 100) / totalBytes);
            progressBar.setValue(percent);
            String action = isSending ? "Đang gửi" : "Đang nhận";
            String progressText = action + " " + fileName + ": " + percent + "% (" + formatFileSize(bytesTransferred) + " / " + formatFileSize(totalBytes) + ")";
            progressBar.setString(progressText);
            lblStatusText.setText(progressText);
        });
    }

    @Override
    public void onFileTransferCompleted(String fileId, String fileName, boolean isSending, String message) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(100);
            progressBar.setString("Hoàn tất 100%");
            lblStatusText.setText("Truyền file thành công: " + fileName);
            appendHtml("<div style='margin: 6px 0; background-color: #F0FDF4; border: 1px solid #86EFAC; padding: 8px 12px; font-size: 12px; color: #166534;'>"
                     + "🎉 <b>Truyền file thành công!</b> " + message + "</div>");
            JOptionPane.showMessageDialog(this, message, "Truyền file thành công", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    @Override
    public void onFileTransferFailed(String fileId, String fileName, boolean isSending, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setString("Thất bại");
            lblStatusText.setText("Lỗi file: " + errorMessage);
            appendHtml("<div style='margin: 6px 0; background-color: #FEF2F2; border: 1px solid #FECACA; padding: 8px 12px; font-size: 12px; color: #991B1B;'>"
                     + "⚠️ <b>Lỗi truyền file:</b> " + errorMessage + "</div>");
        });
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> {
            updateConnectionState(false);
            appendHtml("<div style='text-align: center; margin: 10px 0;'>"
                     + "<span style='background-color: #FEE2E2; color: #991B1B; padding: 6px 16px; font-weight: bold; border: 1px solid #F87171;'>"
                     + "🚫 BẠN ĐÃ BỊ SERVER KÍCH KHỎI PHÒNG CHAT: " + reason + "</span></div>");
            JOptionPane.showMessageDialog(this, "Bạn đã bị Quản trị viên kích: " + reason, "Bị ngắt kết nối", JOptionPane.WARNING_MESSAGE);
        });
    }

    // --- RENDERER TÙY BIẾN CHO JLIST PHÒNG VÀ NGƯỜI DÙNG ---

    private static class ModernUserListRenderer extends DefaultListCellRenderer {
        private static final Color[] AVATAR_COLORS = {
            new Color(59, 130, 246), new Color(16, 185, 129),
            new Color(245, 158, 11), new Color(139, 92, 246),
            new Color(236, 72, 153), new Color(14, 165, 233)
        };

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String val = (value != null) ? value.toString() : "";
            JPanel itemPanel = new JPanel(new BorderLayout(8, 0));
            itemPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
            itemPanel.setBackground(isSelected ? new Color(224, 231, 255) : Color.WHITE);

            if (val.startsWith("[Tất cả")) {
                JLabel lblIcon = new JLabel("🌐");
                lblIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
                itemPanel.add(lblIcon, BorderLayout.WEST);

                JLabel lblName = new JLabel("Tất cả mọi người");
                lblName.setFont(new Font("SansSerif", Font.BOLD, 12));
                lblName.setForeground(new Color(30, 58, 138));
                itemPanel.add(lblName, BorderLayout.CENTER);
            } else {
                String initial = val.isEmpty() ? "?" : val.substring(0, 1).toUpperCase();
                Color avatarColor = AVATAR_COLORS[Math.abs(val.hashCode()) % AVATAR_COLORS.length];

                JLabel avatar = new JLabel(initial, SwingConstants.CENTER);
                avatar.setFont(new Font("SansSerif", Font.BOLD, 12));
                avatar.setForeground(Color.WHITE);
                avatar.setOpaque(true);
                avatar.setBackground(avatarColor);
                avatar.setPreferredSize(new Dimension(26, 26));
                itemPanel.add(avatar, BorderLayout.WEST);

                JLabel lblName = new JLabel("🟢 " + val);
                lblName.setFont(new Font("SansSerif", Font.PLAIN, 13));
                lblName.setForeground(COLOR_TEXT_DARK);
                itemPanel.add(lblName, BorderLayout.CENTER);
            }
            return itemPanel;
        }
    }

    private static class ModernRoomListRenderer extends DefaultListCellRenderer {
        private final Set<String> joinedRooms;

        public ModernRoomListRenderer(Set<String> joinedRooms) {
            this.joinedRooms = joinedRooms;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String val = (value != null) ? value.toString() : "";
            JPanel itemPanel = new JPanel(new BorderLayout(8, 0));
            itemPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
            itemPanel.setBackground(isSelected ? new Color(204, 251, 241) : Color.WHITE);

            JLabel lblIcon = new JLabel("🏘️");
            lblIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
            itemPanel.add(lblIcon, BorderLayout.WEST);

            JLabel lblName = new JLabel(val);
            lblName.setFont(new Font("SansSerif", Font.BOLD, 12));
            lblName.setForeground(new Color(15, 118, 110));
            itemPanel.add(lblName, BorderLayout.CENTER);

            boolean isJoined = joinedRooms.contains(val);
            JLabel lblStatus = new JLabel(isJoined ? "✅ Đã vào" : "Chưa vào");
            lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 10));
            lblStatus.setForeground(isJoined ? new Color(16, 185, 129) : new Color(148, 163, 184));
            itemPanel.add(lblStatus, BorderLayout.EAST);

            return itemPanel;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            ChatWindow window = new ChatWindow();
            window.setVisible(true);
        });
    }
}
