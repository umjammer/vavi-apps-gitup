package vavi.apps.gitup.ui;

import java.nio.file.Path;
import javax.swing.*;

public class HooksPanelProbe {
    public static void main(String[] a) throws Exception {
        Path w = Path.of(".").toAbsolutePath().normalize();
        HooksPanel[] p = new HooksPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new HooksPanel();
            p[0].setHooks(new vavi.apps.gitup.model.GitHooks(w, w.resolve(".git")));
            p[0].setSize(250, 300);
        });
        Thread.sleep(2000);
        SwingUtilities.invokeAndWait(() -> {
            JTree t = (JTree) ((JScrollPane) p[0].getComponent(0)).getViewport().getView();
            System.out.println("rows=" + t.getRowCount());
            for (int i = 0; i < t.getRowCount(); i++) System.out.println(t.getPathForRow(i));
            p[0].doLayout(); ((JScrollPane) p[0].getComponent(0)).doLayout();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(250, 300, 1);
            p[0].printAll(img.getGraphics());
            try { javax.imageio.ImageIO.write(img, "png", new java.io.File(a[0])); } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.exit(0);
    }
}
