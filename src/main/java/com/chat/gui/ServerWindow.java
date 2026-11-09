package com.chat.gui;

import com.chat.model.AccessLog;
import com.chat.model.User;
import com.chat.protocol.Packet;
import com.chat.server.ChatServer;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * ServerWindow - Giao diện Quản trị viên (Admin Dashboard) của ChatServer.
 * 
 * Chức năng:
 * 1. Xem danh sách người dùng đang online thời gian thực:
 *    - Tên người dùng, Địa chỉ IP, Cổng, Thời gian vào, Thời lượng kết nối.
 * 2. Kích (Kick) người dùng bất kỳ kèm lý do chỉ định.
 * 3. Nhật ký truy cập (Access Log): Theo dõi chi tiết lịch sử Đăng nhập, Đăng xuất, Bị kích.
 * 4. Phát thanh thông báo toàn Server (Admin Broadcast).
 * 5. Bật / Tắt Server linh hoạt bằng nút bấm.
 */
public class ServerWindow extends JFrame implements ChatServer.ServerEventListener {

    private static final Color COLOR_PRIMARY = new Color(37, 99, 235);
    private static final Color COLOR_SUCCESS = new Color(16, 185, 129);
    private static final Color COLOR_DANGER = new Color(239, 68, 68);
    private static final Color COLOR_BORDER = new Color(226, 232, 240);
    private static final Color COLOR_BG_LIGHT = new Color(248, 250, 252);
    private static final Color COLOR_TEXT_DARK = new Color(30, 41, 59);
    private static final Color COLOR_TEXT_MUTED = new Color(100, 116, 139);

    private JTextField txtPort;
    private JButton btnToggleServer;
    private JLabel lblStatusBadge;
    private JLabel lblLocalIPs;

    // Bảng 1: Người dùng đang Online
    private DefaultTableModel onlineUsersTableModel;
    private JTable tblOnlineUsers;
    private JButton btnKickUser;
    private JLabel lblSelectedUser;
    private JTextField txtBroadcast;
    private JButton btnBroadcast;
    private JLabel lblOnlineCount;

    // Bảng 2: Lịch sử Ra/Vào (Access Logs)
    private DefaultTableModel accessLogsTableModel;
    private JTable tblAccessLogs;

    // Console Log
    private JTextArea txtConsoleLog;

    private ChatServer server;
    private Thread serverThread;
    private Timer durationTimer;

    public ServerWindow() {
        initUI();
    }

    private void initUI() {
        setTitle("SimpleChat - Bảng Điều Khiển Quản Trị Server");
        setSize(920, 680);
        setMinimumSize(new Dimension(800, 550));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(COLOR_BG_LIGHT);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));

        // 1. THANH TRÊN CÙNG: Điều khiển bật/tắt và hiển thị IP
        root.add(buildTopControlPanel(), BorderLayout.NORTH);

        // 2. PHẦN TRUNG TÂM: Các Tab chức năng (Người dùng online, Nhật ký truy cập, Console Log)
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 13));
        tabbedPane.addTab("👥 Người dùng Online & Quản trị", buildOnlineUsersTab());
        tabbedPane.addTab("📜 Lịch sử Ra/Vào (Access Logs)", buildAccessLogsTab());
        tabbedPane.addTab("🖥️ Nhật ký Hệ thống (Console)", buildConsoleLogTab());
        root.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(root);

        // Timer tự động cập nhật thời lượng online mỗi giây
        durationTimer = new Timer(1000, e -> updateOnlineDurations());
        durationTimer.start();

        // Tự động khởi chạy Server khi mở cửa sổ
        startServer();
    }

    private JPanel buildTopControlPanel() {
        JPanel card = new JPanel(new BorderLayout(10, 8));
        card.setBackground(Color.WHITE);
        card.setBorder(new CompoundBorder(new LineBorder(COLOR_BORDER, 1, true), new EmptyBorder(10, 12, 10, 12)));

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        JLabel lblTitle = new JLabel("🛡️ Server Control Panel");
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        lblTitle.setForeground(COLOR_TEXT_DARK);
        topRow.add(lblTitle, BorderLayout.WEST);

        lblStatusBadge = new JLabel("● Đang khởi động...");
        lblStatusBadge.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblStatusBadge.setForeground(COLOR_TEXT_MUTED);
        topRow.add(lblStatusBadge, BorderLayout.EAST);
        card.add(topRow, BorderLayout.NORTH);

        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        bottomRow.setOpaque(false);

        bottomRow.add(new JLabel("Cổng (Port):"));
        txtPort = new JTextField("8888", 5);
        txtPort.setFont(new Font("SansSerif", Font.PLAIN, 13));
        bottomRow.add(txtPort);

        btnToggleServer = new JButton("Dừng Server");
        btnToggleServer.setBackground(COLOR_DANGER);
        btnToggleServer.setForeground(Color.WHITE);
        btnToggleServer.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnToggleServer.setFocusPainted(false);
        btnToggleServer.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnToggleServer.addActionListener(e -> toggleServerState());
        bottomRow.add(btnToggleServer);

        JButton btnOpenClient = new JButton("➕ Mở Client Chat mới");
        btnOpenClient.setBackground(COLOR_PRIMARY);
        btnOpenClient.setForeground(Color.WHITE);
        btnOpenClient.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnOpenClient.setFocusPainted(false);
        btnOpenClient.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnOpenClient.setToolTipText("Mở một cửa sổ Chat Client mới để thử nghiệm kết nối và nhắn tin");
        btnOpenClient.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                ChatWindow clientWindow = new ChatWindow();
                clientWindow.setVisible(true);
            });
        });
        bottomRow.add(btnOpenClient);

        lblLocalIPs = new JLabel("Đang quét IP mạng...");
        lblLocalIPs.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblLocalIPs.setForeground(COLOR_PRIMARY);
        bottomRow.add(lblLocalIPs);

        card.add(bottomRow, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildOnlineUsersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Hướng dẫn giải thích trạng thái rỗng
        JPanel helperPanel = new JPanel(new BorderLayout(8, 0));
        helperPanel.setBackground(new Color(239, 246, 255));
        helperPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(191, 219, 254), 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));

        JLabel lblGuide = new JLabel("<html><b>💡 Lưu ý:</b> Trang này là Máy Chủ (Server). Khi chưa có Client nào kết nối, bảng sẽ trống. Bấm nút <b>'➕ Mở Client Chat'</b> bên phải để mở cửa sổ chat và kết nối thử nghiệm!</html>");
        lblGuide.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblGuide.setForeground(new Color(30, 64, 175));
        helperPanel.add(lblGuide, BorderLayout.CENTER);

        JButton btnQuickClient = new JButton("➕ Mở Client Chat");
        btnQuickClient.setBackground(COLOR_PRIMARY);
        btnQuickClient.setForeground(Color.WHITE);
        btnQuickClient.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnQuickClient.setFocusPainted(false);
        btnQuickClient.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnQuickClient.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                ChatWindow cw = new ChatWindow();
                cw.setVisible(true);
            });
        });
        helperPanel.add(btnQuickClient, BorderLayout.EAST);
        panel.add(helperPanel, BorderLayout.NORTH);

        // Bảng danh sách người dùng online
        String[] columns = {"Username", "Địa chỉ IP", "Cổng (Port)", "Thời gian vào", "Thời lượng Online"};
        onlineUsersTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tblOnlineUsers = new JTable(onlineUsersTableModel);
        tblOnlineUsers.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblOnlineUsers.setRowHeight(28);
        tblOnlineUsers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblOnlineUsers.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 13));

        tblOnlineUsers.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = tblOnlineUsers.getSelectedRow();
                if (row >= 0) {
                    String username = (String) onlineUsersTableModel.getValueAt(row, 0);
                    lblSelectedUser.setText("Đang chọn: [" + username + "]");
                    btnKickUser.setEnabled(true);
                } else {
                    lblSelectedUser.setText("Chưa chọn người dùng nào");
                    btnKickUser.setEnabled(false);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tblOnlineUsers);
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1, true));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Thanh công cụ bên dưới: Số lượng kết nối, Nút Kick và Thanh phát thanh (Broadcast)
        JPanel bottomTools = new JPanel();
        bottomTools.setLayout(new BoxLayout(bottomTools, BoxLayout.Y_AXIS));
        bottomTools.setOpaque(false);

        // Dòng 1: Thống kê số lượng online
        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        countRow.setOpaque(false);
        countRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblOnlineCount = new JLabel("👥 Đang có 0 người dùng kết nối (Chưa có ai online)");
        lblOnlineCount.setFont(new Font("SansSerif", Font.BOLD, 12));
        lblOnlineCount.setForeground(COLOR_TEXT_MUTED);
        countRow.add(lblOnlineCount);
        bottomTools.add(countRow);

        // Dòng 2: Nút Kick người dùng
        JPanel kickRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        kickRow.setOpaque(false);
        kickRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        btnKickUser = new JButton("🚫 Kích người dùng này (Kick)");
        btnKickUser.setBackground(COLOR_DANGER);
        btnKickUser.setForeground(Color.WHITE);
        btnKickUser.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnKickUser.setFocusPainted(false);
        btnKickUser.setEnabled(false);
        btnKickUser.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnKickUser.addActionListener(e -> handleKickSelectedUser());
        kickRow.add(btnKickUser);

        lblSelectedUser = new JLabel("Chưa chọn người dùng nào");
        lblSelectedUser.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblSelectedUser.setForeground(COLOR_TEXT_MUTED);
        kickRow.add(lblSelectedUser);
        bottomTools.add(kickRow);

        bottomTools.add(Box.createVerticalStrut(4));

        // Dòng 3: Phát tin nhắn Admin tới tất cả Client
        JPanel broadcastRow = new JPanel(new BorderLayout(6, 0));
        broadcastRow.setOpaque(false);
        broadcastRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        broadcastRow.setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(4, 8, 4, 8)
        ));

        broadcastRow.add(new JLabel("📢 Thông báo Admin:"), BorderLayout.WEST);
        txtBroadcast = new JTextField();
        txtBroadcast.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtBroadcast.setBorder(new EmptyBorder(2, 6, 2, 6));
        txtBroadcast.addActionListener(e -> handleBroadcastAnnouncement());
        broadcastRow.add(txtBroadcast, BorderLayout.CENTER);

        btnBroadcast = new JButton("Phát thanh (Gửi tất cả)");
        btnBroadcast.setBackground(COLOR_PRIMARY);
        btnBroadcast.setForeground(Color.WHITE);
        btnBroadcast.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnBroadcast.setFocusPainted(false);
        btnBroadcast.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnBroadcast.addActionListener(e -> handleBroadcastAnnouncement());
        broadcastRow.add(btnBroadcast, BorderLayout.EAST);
        bottomTools.add(broadcastRow);

        panel.add(bottomTools, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAccessLogsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        String[] columns = {"Thời gian", "Username", "Hành động", "IP : Port", "Chi tiết / Lý do"};
        accessLogsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tblAccessLogs = new JTable(accessLogsTableModel);
        tblAccessLogs.setFont(new Font("SansSerif", Font.PLAIN, 12));
        tblAccessLogs.setRowHeight(26);
        tblAccessLogs.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));

        // Tùy biến màu cột "Hành động"
        tblAccessLogs.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String act = (value != null) ? value.toString() : "";
                if (act.contains("Đăng nhập")) {
                    c.setForeground(new Color(22, 101, 52)); // Xanh lá
                } else if (act.contains("kích")) {
                    c.setForeground(new Color(185, 28, 28)); // Đỏ
                } else {
                    c.setForeground(COLOR_TEXT_MUTED);
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(tblAccessLogs);
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1, true));
        panel.add(scrollPane, BorderLayout.CENTER);

        JLabel lblHint = new JLabel("<html><span style='color:#64748B;'>ℹ️ Bảng ghi nhận toàn bộ mốc thời gian đăng nhập, đăng xuất và sự kiện bị kích khỏi phòng chat.</span></html>");
        panel.add(lblHint, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildConsoleLogTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        txtConsoleLog = new JTextArea();
        txtConsoleLog.setEditable(false);
        txtConsoleLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txtConsoleLog.setBackground(new Color(15, 23, 42)); // Nền tối phong cách terminal
        txtConsoleLog.setForeground(new Color(248, 250, 252));
        txtConsoleLog.setCaretColor(Color.WHITE);

        JScrollPane scrollPane = new JScrollPane(txtConsoleLog);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    // --- Xử lý sự kiện điều khiển Server ---

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
        } catch (NumberFormatException e) {
            port = ChatServer.DEFAULT_PORT;
            txtPort.setText(String.valueOf(port));
        }

        lblStatusBadge.setText("🟡 Đang khởi động cổng " + port + "...");
        lblStatusBadge.setForeground(new Color(217, 119, 6));

        server = new ChatServer(port);
        server.setEventListener(this);

        serverThread = new Thread(() -> server.start());
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void stopServer() {
        if (server != null) {
            server.stopServer();
        }
        lblStatusBadge.setText("🔴 Đã dừng");
        lblStatusBadge.setForeground(COLOR_DANGER);
        btnToggleServer.setText("Bật Server");
        btnToggleServer.setBackground(COLOR_SUCCESS);
        txtPort.setEditable(true);
        onlineUsersTableModel.setRowCount(0);
        lblOnlineCount.setText("👥 Đang có 0 người dùng kết nối (Server đã dừng)");
        lblSelectedUser.setText("Chưa chọn người dùng nào");
        btnKickUser.setEnabled(false);
    }

    private void toggleServerState() {
        if (server != null && server.isRunning()) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void handleKickSelectedUser() {
        int row = tblOnlineUsers.getSelectedRow();
        if (row < 0) return;

        String username = (String) onlineUsersTableModel.getValueAt(row, 0);

        String reason = JOptionPane.showInputDialog(
            this,
            "Nhập lý do kích người dùng [" + username + "] khỏi phòng chat:",
            "Kích người dùng",
            JOptionPane.WARNING_MESSAGE
        );

        if (reason != null && !reason.trim().isEmpty()) {
            boolean success = server.kickClient(username, reason.trim());
            if (success) {
                JOptionPane.showMessageDialog(this, "Đã kích thành công người dùng [" + username + "]!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Không thể kích người dùng (có thể họ vừa offline)!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleBroadcastAnnouncement() {
        String msg = txtBroadcast.getText().trim();
        if (msg.isEmpty() || server == null || !server.isRunning()) return;

        server.broadcast(Packet.createChatMessage("📢 QUẢN TRỊ VIÊN", "ALL", msg));
        txtBroadcast.setText("");
        logConsole("[Phát thanh Admin]: " + msg);
    }

    private void updateOnlineDurations() {
        if (server == null || !server.isRunning()) return;

        java.util.List<User> users = server.getActiveUsersList();
        for (int i = 0; i < onlineUsersTableModel.getRowCount(); i++) {
            String uname = (String) onlineUsersTableModel.getValueAt(i, 0);
            for (User u : users) {
                if (u.getUsername().equals(uname)) {
                    onlineUsersTableModel.setValueAt(u.getFormattedDuration(), i, 4);
                    break;
                }
            }
        }
    }

    private void updateLocalIPDisplay() {
        StringBuilder sb = new StringBuilder("IP LAN: ");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getAddress().length == 4) {
                        sb.append(addr.getHostAddress()).append("  ");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        lblLocalIPs.setText(sb.toString());
    }

    private void logConsole(String line) {
        SwingUtilities.invokeLater(() -> {
            txtConsoleLog.append(line + "\n");
            txtConsoleLog.setCaretPosition(txtConsoleLog.getDocument().getLength());
        });
    }

    // --- Triển khai ChatServer.ServerEventListener ---

    @Override
    public void onServerStarted(int port) {
        SwingUtilities.invokeLater(() -> {
            lblStatusBadge.setText("🟢 Đang hoạt động trên cổng " + port);
            lblStatusBadge.setForeground(COLOR_SUCCESS);
            btnToggleServer.setText("Dừng Server");
            btnToggleServer.setBackground(COLOR_DANGER);
            txtPort.setEditable(false);
            updateLocalIPDisplay();
            logConsole("[HỆ THỐNG]: Server đã khởi động thành công trên cổng " + port + ". Đang sẵn sàng nhận kết nối từ Client!");
        });
    }

    @Override
    public void onServerError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            boolean isPortConflict = errorMessage != null && 
                (errorMessage.contains("Address already in use") || errorMessage.contains("bị chiếm dụng"));
            
            if (isPortConflict) {
                String currentPort = txtPort.getText().trim();
                lblStatusBadge.setText("🔴 Lỗi: Cổng " + currentPort + " đang bị chiếm dụng!");
                lblStatusBadge.setForeground(COLOR_DANGER);
                btnToggleServer.setText("Khởi động lại");
                btnToggleServer.setBackground(COLOR_PRIMARY);
                txtPort.setEditable(true);

                JOptionPane.showMessageDialog(
                    this,
                    "⚠️ Cổng mạng " + currentPort + " đang bị chiếm dụng bởi tiến trình khác!\n\n" +
                    "Nguyên nhân phổ biến:\n" +
                    "1. Bạn đã bấm chạy ChatServer hoặc một ServerWindow khác trong IntelliJ/Terminal.\n" +
                    "2. Hãy bấm dừng tiến trình cũ (nút vuông màu đỏ ⏹️ trong IntelliJ).\n" +
                    "3. Hoặc đổi sang cổng khác (ví dụ: 8889, 9000) rồi bấm 'Khởi động lại'.\n\n" +
                    "Chi tiết: " + errorMessage,
                    "Xung đột cổng mạng (Port Conflict)",
                    JOptionPane.WARNING_MESSAGE
                );
            } else {
                lblStatusBadge.setText("🔴 Lỗi Server: " + errorMessage);
                lblStatusBadge.setForeground(COLOR_DANGER);
                btnToggleServer.setText("Khởi động lại");
                btnToggleServer.setBackground(COLOR_PRIMARY);
                txtPort.setEditable(true);
            }
            logConsole("[LỖI SERVER]: " + errorMessage);
        });
    }

    @Override
    public void onClientLoggedIn(User user) {
        SwingUtilities.invokeLater(() -> {
            onlineUsersTableModel.addRow(new Object[]{
                user.getUsername(),
                user.getIpAddress(),
                user.getPort(),
                user.getFormattedConnectedTime(),
                user.getFormattedDuration()
            });
            lblOnlineCount.setText("👥 Đang có " + onlineUsersTableModel.getRowCount() + " người dùng online");
        });
    }

    @Override
    public void onClientLoggedOut(User user, String reason) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < onlineUsersTableModel.getRowCount(); i++) {
                if (onlineUsersTableModel.getValueAt(i, 0).equals(user.getUsername())) {
                    onlineUsersTableModel.removeRow(i);
                    break;
                }
            }
            int count = onlineUsersTableModel.getRowCount();
            if (count > 0) {
                lblOnlineCount.setText("👥 Đang có " + count + " người dùng online");
            } else {
                lblOnlineCount.setText("👥 Đang có 0 người dùng kết nối (Chưa có ai online)");
            }
            if (tblOnlineUsers.getSelectedRow() < 0) {
                btnKickUser.setEnabled(false);
                lblSelectedUser.setText("Chưa chọn người dùng nào");
            }
        });
    }

    @Override
    public void onAccessLogAdded(AccessLog log) {
        SwingUtilities.invokeLater(() -> {
            accessLogsTableModel.insertRow(0, new Object[]{
                log.getFormattedTime(),
                log.getUsername(),
                log.getAction().getDisplayName(),
                log.getIpAddress() + ":" + log.getPort(),
                log.getDetails()
            });
        });
    }

    @Override
    public void onServerLog(String message) {
        logConsole(message);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            ServerWindow window = new ServerWindow();
            window.setVisible(true);
        });
    }
}
