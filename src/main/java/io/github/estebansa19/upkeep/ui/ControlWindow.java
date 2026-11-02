package io.github.estebansa19.upkeep.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.BooleanSupplier;

/**
 * The small window shown while Upkeep runs, so it can be started by double-clicking and stopped without a console:
 * the page lives in the browser, closing this window quits.
 */
public final class ControlWindow {
    private ControlWindow() {}

    /** {@code busy}: whether quitting now would interrupt a copy or a download. */
    public static void show(String url, Runnable openBrowser, BooleanSupplier busy) {
        SwingUtilities.invokeLater(() -> {
            systemLook();
            JFrame f = new JFrame("Upkeep");
            f.setIconImages(AppIcon.windowIcons());
            f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) { quit(f, busy); }
            });

            JLabel text = new JLabel("<html><b>Upkeep is running.</b><br>It works in your browser, at " + url
                + "<br><br>Keep this window open while you use it.<br>Closing it stops Upkeep.</html>");
            JButton open = new JButton("Open in browser");
            open.addActionListener(e -> openBrowser.run());
            JButton quit = new JButton("Quit");
            quit.addActionListener(e -> quit(f, busy));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(open);
            buttons.add(quit);

            JPanel p = new JPanel(new BorderLayout(16, 16));
            p.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
            p.add(new JLabel(new ImageIcon(AppIcon.draw(48))), BorderLayout.WEST);
            p.add(text, BorderLayout.CENTER);
            p.add(buttons, BorderLayout.SOUTH);
            f.setContentPane(p);
            f.getRootPane().setDefaultButton(open);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    private static void quit(JFrame f, BooleanSupplier busy) {
        if (busy.getAsBoolean() && JOptionPane.showConfirmDialog(f, "Upkeep is still writing a world copy or downloading a server.\n"
            + "Quit anyway? An unfinished copy stays where it is.", "Upkeep", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            != JOptionPane.YES_OPTION) return;
        System.exit(0);
    }

    /** A message box for problems that happen before the page can show anything. */
    public static void error(String message) {
        systemLook();
        JOptionPane.showMessageDialog(null, message, "Upkeep", JOptionPane.ERROR_MESSAGE);
    }

    private static void systemLook() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look works too
        }
    }
}
