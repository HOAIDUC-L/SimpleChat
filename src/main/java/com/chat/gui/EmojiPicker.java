package com.chat.gui;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * EmojiPicker - Hộp thoại Popup chọn biểu tượng cảm xúc (Emoji Picker) cho SimpleChat.
 * Thiết kế phẳng hiện đại, trực quan, hỗ trợ chèn nhanh các emoji phổ biến vào ô nhập tin nhắn.
 */
public class EmojiPicker extends JPopupMenu {

    public interface EmojiSelectListener {
        void onEmojiSelected(String emoji);
    }

    private static final String[] POPULAR_EMOJIS = {
        // Biểu cảm khuôn mặt vui nhộn
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
        "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋",
        "😜", "🤪", "😎", "🤩", "🥳", "😏", "😴", "😷",
        "🤔", "🤫", "🫡", "🤗", "🥺", "😢", "😭", "😱",
        // Cử chỉ & Bàn tay
        "👍", "👎", "👌", "✌️", "🤞", "🤟", "👊", "👏",
        "🙌", "🫶", "🤝", "🙏", "💪", "👋", "✍️", "✨",
        // Trái tim & Cảm xúc
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "💔", "❤️‍🔥", "💖", "💘", "🔥", "🎉", "🚀", "💯",
        // Đồ vật & Biểu tượng tiện ích
        "☕", "🍕", "🍔", "🍻", "🎮", "💻", "📱", "💡",
        "⭐", "🌟", "🏆", "⚽", "🚗", "✈️", "🎵", "💬"
    };

    private static final Color COLOR_BG = Color.WHITE;
    private static final Color COLOR_BORDER = new Color(226, 232, 240);
    private static final Color COLOR_HOVER = new Color(241, 245, 249);
    private static final Color COLOR_HEADER_TEXT = new Color(71, 85, 105);

    private final EmojiSelectListener listener;

    public EmojiPicker(EmojiSelectListener listener) {
        this.listener = listener;
        initUI();
    }

    private void initUI() {
        setBackground(COLOR_BG);
        setBorder(new CompoundBorder(
            new LineBorder(COLOR_BORDER, 1, true),
            new EmptyBorder(8, 8, 8, 8)
        ));

        JPanel contentPanel = new JPanel(new BorderLayout(0, 8));
        contentPanel.setBackground(COLOR_BG);

        // Header tiêu đề
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(COLOR_BG);
        JLabel lblTitle = new JLabel("Biểu tượng cảm xúc");
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        lblTitle.setForeground(COLOR_HEADER_TEXT);
        headerPanel.add(lblTitle, BorderLayout.WEST);

        JLabel lblCount = new JLabel(POPULAR_EMOJIS.length + " icons");
        lblCount.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblCount.setForeground(new Color(148, 163, 184));
        headerPanel.add(lblCount, BorderLayout.EAST);
        contentPanel.add(headerPanel, BorderLayout.NORTH);

        // Lưới Emoji: 8 cột
        int cols = 8;
        int rows = (int) Math.ceil((double) POPULAR_EMOJIS.length / cols);
        JPanel gridPanel = new JPanel(new GridLayout(rows, cols, 4, 4));
        gridPanel.setBackground(COLOR_BG);

        Font emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, 18);

        for (String emoji : POPULAR_EMOJIS) {
            JButton btnEmoji = new JButton(emoji);
            btnEmoji.setFont(emojiFont);
            btnEmoji.setPreferredSize(new Dimension(36, 36));
            btnEmoji.setFocusPainted(false);
            btnEmoji.setBorderPainted(false);
            btnEmoji.setContentAreaFilled(false);
            btnEmoji.setOpaque(true);
            btnEmoji.setBackground(COLOR_BG);
            btnEmoji.setCursor(new Cursor(Cursor.HAND_CURSOR));
            btnEmoji.setMargin(new Insets(2, 2, 2, 2));

            // Hiệu ứng hover đổi màu nền mượt mà
            btnEmoji.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    btnEmoji.setBackground(COLOR_HOVER);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    btnEmoji.setBackground(COLOR_BG);
                }
            });

            btnEmoji.addActionListener(e -> {
                if (listener != null) {
                    listener.onEmojiSelected(emoji);
                }
                setVisible(false);
            });

            gridPanel.add(btnEmoji);
        }

        JScrollPane scrollPane = new JScrollPane(gridPanel);
        scrollPane.setPreferredSize(new Dimension(320, 240));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        add(contentPanel);
    }

    /**
     * Hiển thị popup bên trên hoặc ngay cạnh một component (thường là ô nhập liệu hoặc nút emoji).
     */
    public void showAbove(Component invoker) {
        if (invoker == null) return;
        int x = 0;
        int y = -getPreferredSize().height - 5;
        show(invoker, x, y);
    }
}
