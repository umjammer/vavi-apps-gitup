/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.laf;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Paint;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatRoundBorder;
import vavi.apps.gitup.ui.LafPlugin;


/**
 * FlatlafLafPlugin.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-01 nsano initial version <br>
 */
public class FlatlafLafPlugin implements LafPlugin {

    @Override
    public boolean accepts(String name) {
        return name != null && "com.formdev.flatlaf.FlatLightLaf".contains(name);
    }

    @Override
    public void init() {
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusedBorderColor", UIManager.getColor("Component.borderColor"));
        UIManager.put("Table.focusCellHighlightBorder", UIManager.getBorder("Table.cellNoFocusBorder"));
        UIManager.put("Table.focusSelectedCellHighlightBorder", UIManager.getBorder("Table.cellNoFocusBorder"));
        UIManager.put("List.focusCellHighlightBorder", UIManager.getBorder("List.cellNoFocusBorder"));
        UIManager.put("List.focusSelectedCellHighlightBorder", UIManager.getBorder("List.cellNoFocusBorder"));
        UIManager.put("Tree.showCellFocusIndicator", false);
        UIManager.put("SplitPaneDivider.focusable", false);
        Color control = UIManager.getColor("control");
        UIManager.put("Button.borderColor", control);
        UIManager.put("Button.default.borderColor", control);
        UIManager.put("ComboBox.background", control);
        UIManager.put("ComboBox.editableBackground", control);
        UIManager.put("ComboBox.buttonBackground", control);
        UIManager.put("ComboBox.buttonSeparatorColor", control);
        UIManager.put("ComboBox.buttonArrowColor", Color.darkGray);
        UIManager.put("ComboBox.buttonHoverArrowColor", Color.darkGray);
        UIManager.put("ComboBox.buttonPressedArrowColor", Color.darkGray);
        UIManager.put("ComboBoxUI", ComboBoxUI.class.getName());
        UIManager.put("ComboBox.border", new FlatRoundBorder() {
            @Override
            protected Paint getBorderColor(Component c) {
                if (c instanceof JComboBox<?> jcb && jcb.getUI() instanceof ComboBoxUI ui && (ui.isHover() || ui.isPressed())) {
                    return ui.getHoverOrPressedBackground();
                }
                return control;
            }
        });
    }

    /** ComboBoxUI with normal button hover and pressed effect */
    public static class ComboBoxUI extends FlatComboBoxUI {
        public static ComponentUI createUI(JComponent c) {
            return new ComboBoxUI();
        }

        private boolean hovered;
        private boolean pressedState;

        private final MouseListener hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (comboBox != null) {
                    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), comboBox);
                    if (!comboBox.contains(p)) {
                        setHovered(false);
                    }
                } else {
                    setHovered(false);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    setPressed(true);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    setPressed(false);
                }
            }
        };

        private void setHovered(boolean h) {
            if (comboBox != null && !comboBox.isEnabled()) {
                h = false;
            }
            if (this.hovered != h) {
                this.hovered = h;
                this.hover = h;
                if (comboBox != null) {
                    comboBox.repaint();
                }
            }
        }

        private void setPressed(boolean p) {
            if (comboBox != null && !comboBox.isEnabled()) {
                p = false;
            }
            if (this.pressedState != p) {
                this.pressedState = p;
                this.pressed = p;
                if (comboBox != null) {
                    comboBox.repaint();
                }
            }
        }

        public boolean isHover() {
            return hovered && comboBox != null && comboBox.isEnabled();
        }

        public boolean isPressed() {
            return pressedState && comboBox != null && comboBox.isEnabled();
        }

        public Color getHoverOrPressedBackground() {
            if (isPressed()) {
                Color pressedBg = UIManager.getColor("Button.toolbar.pressedBackground");
                if (pressedBg != null) return pressedBg;
                Color p = UIManager.getColor("Button.pressedBackground");
                if (p != null) return p;
                return getHoverBackground().darker();
            }
            return getHoverBackground();
        }

        public Color getHoverBackground() {
            Color hoverBg = UIManager.getColor("Button.toolbar.hoverBackground");
            if (hoverBg != null) return hoverBg;
            Color hover = UIManager.getColor("Button.hoverBackground");
            if (hover != null) return hover;
            return UIManager.getColor("control").darker();
        }

        @Override
        protected void installListeners() {
            super.installListeners();
            if (comboBox != null) {
                comboBox.removeMouseListener(hoverListener);
                comboBox.addMouseListener(hoverListener);
            }
        }

        @Override
        protected void uninstallListeners() {
            if (comboBox != null) {
                comboBox.removeMouseListener(hoverListener);
            }
            super.uninstallListeners();
        }

        @Override
        public void configureArrowButton() {
            super.configureArrowButton();
            if (arrowButton != null) {
                arrowButton.removeMouseListener(hoverListener);
                arrowButton.addMouseListener(hoverListener);
            }
        }

        @Override
        public void unconfigureArrowButton() {
            if (arrowButton != null) {
                arrowButton.removeMouseListener(hoverListener);
            }
            super.unconfigureArrowButton();
        }

        @Override
        public void configureEditor() {
            super.configureEditor();
            if (editor != null) {
                editor.removeMouseListener(hoverListener);
                editor.addMouseListener(hoverListener);
            }
        }

        @Override
        public void unconfigureEditor() {
            if (editor != null) {
                editor.removeMouseListener(hoverListener);
            }
            super.unconfigureEditor();
        }

        @Override
        protected Color getBackground(boolean enabled) {
            if (enabled && !comboBox.isEditable() && (isHover() || isPressed())) {
                return getHoverOrPressedBackground();
            }
            return super.getBackground(enabled);
        }

        @Override
        public void update(Graphics g, JComponent c) {
            Color oldBtnBg = buttonBackground;
            if (comboBox.isEnabled() && !comboBox.isEditable() && (isHover() || isPressed())) {
                buttonBackground = getHoverOrPressedBackground();
            }
            try {
                super.update(g, c);
            } finally {
                buttonBackground = oldBtnBg;
            }
        }
    }
}
