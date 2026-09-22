package com.smallzhuge.worktreesync;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import com.smallzhuge.worktreesync.model.SyncPlan;
import com.smallzhuge.worktreesync.model.WorktreeInfo;
import com.smallzhuge.worktreesync.sync.IdeaSettingsSyncer;
import com.smallzhuge.worktreesync.util.WorktreeDetector;

/**
 * 打开新 worktree 时的选择弹窗。
 *
 * <p>布局用单个 GridBagLayout 纵向堆三个 section（检测结果 / 设置来源 / 同步项），
 * 每个 section 都 fill=HORIZONTAL，这样三个 titled border 严格等宽对齐 ——
 * 用 BoxLayout 会各自按内容宽度居中，出现「同步项」面板缩进的错位。
 */
public class WorktreeSyncDialog extends DialogWrapper {

    private static final String[] RANGE_LABELS = {"最近 7 天", "最近 15 天", "最近 30 天", "全部"};
    private static final int[] RANGE_DAYS = {7, 15, 30, 0};

    /** 次要文案颜色，跟随明暗主题。 */
    private static final Color HINT_FG = new JBColor(new Color(0x6E6E6E), new Color(0xA2A9B1));

    private static final int PREF_WIDTH = 760;
    /** 缩进量，让子项与复选框文字左边缘对齐（复选框图标宽度约 18px）。 */
    private static final int INDENT = 22;

    private final Project project;
    private final Path projectDir;
    private final List<WorktreeInfo> candidates;

    private final JRadioButton useDefaultsRadio =
            new JRadioButton(html("<b>使用 IDEA 默认项目设置</b>" + gap() + hint("不继承任何来源")));
    private final JRadioButton inheritRadio =
            new JRadioButton(html("<b>继承某个 worktree 的项目设置</b>"));
    private final JComboBox<WorktreeInfo> sourceCombo = new JComboBox<>();
    private final JButton browseButton = new JButton("浏览...");

    private final JCheckBox ideaBox = new JCheckBox(html(
            "<b>IDEA 构建设置</b>" + gap() + hint("编译进程堆 / 构建 VM options / 全局编码")
                    + gap() + hint("· 即时生效")));
    private final JCheckBox mavenBox = new JCheckBox(html(
            "<b>Maven 设置</b>" + gap() + hint("Maven home / settings.xml / 本地仓库 / 远程仓库")
                    + gap() + hint("· 即时生效")));
    private final JCheckBox claudeConfigBox = new JCheckBox(html(
            "<b>Claude Code 项目配置</b>" + gap() + hint(".claude/settings.json、.mcp.json、CLAUDE.md")));
    private final JCheckBox claudeSessionsBox = new JCheckBox(html(
            "<b>Claude Code 会话记录</b>" + gap() + hint("迁移会话并按新路径改写 cwd")));
    private final JComboBox<String> rangeCombo = new JComboBox<>(RANGE_LABELS);
    private final JCheckBox overwriteBox = new JCheckBox(html(
            "覆盖目标已存在的同名文件" + gap() + hint("默认只补齐缺失文件")));
    private final JCheckBox rememberBox = new JCheckBox("记住本次选择，此项目不再询问");

    private final JLabel currentHeapLabel = new JLabel(html(hint(currentHeapText())));

    public WorktreeSyncDialog(Project project, Path projectDir, List<WorktreeInfo> candidates) {
        super(project, false);
        this.project = project;
        this.projectDir = projectDir;
        this.candidates = candidates;
        for (WorktreeInfo info : candidates) {
            sourceCombo.addItem(info);
        }
        setTitle("Worktree Sync — 继承设置与会话");
        setOKButtonText("应用并同步");
        setCancelButtonText("本次跳过");
        init();
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected JComponent createCenterPanel() {
        // 只约束宽度、高度始终由布局实时计算。
        // 早先写的是 root.setPreferredSize(new Dimension(PREF_WIDTH, root.getPreferredSize().height))，
        // 把当时算出的高度冻结住了 —— 而那一刻对话框还没 pack、字体/UI 尚未最终生效，
        // 渲染时内容比这个高度高，最后一行（「当前项目编译堆」）就被 section 边框裁掉。
        JPanel root = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(Math.max(PREF_WIDTH, size.width), size.height);
            }
        };
        root.setBorder(JBUI.Borders.empty(4, 10, 2, 10));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 0);
        root.add(section(headerTitle(), buildHeaderContent()), c);

        c.gridy = 1;
        c.insets = new Insets(8, 0, 0, 0);
        root.add(section("设置来源", buildSourceContent()), c);

        c.gridy = 2;
        root.add(section("同步项", buildSyncContent()), c);

        return root;
    }

    /** 当前项目是主仓库还是 worktree，标题要说清，否则用户不理解为什么在这里弹窗。 */
    private String headerTitle() {
        return WorktreeDetector.isMainRepo(projectDir) ? "当前项目是主仓库" : "检测到 Git Worktree";
    }

    private String candidatesSummary() {
        if (candidates.isEmpty()) {
            return WorktreeDetector.isMainRepo(projectDir)
                    ? "这个仓库还没有 worktree；可用「浏览...」手动指定，或先 git worktree add 一个"
                    : "未自动探测到同仓库的其他 worktree，请用「浏览...」手动指定";
        }
        return WorktreeDetector.isMainRepo(projectDir)
                ? "已自动探测到 " + candidates.size() + " 个同仓库 worktree"
                : "已自动探测到 " + candidates.size() + " 个（主仓库 + 同仓库 worktree）";
    }

    private JComponent buildHeaderContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(2, 6, 6, 6));

        addRow(panel, 0, "当前目录", projectDir == null ? "(未知)" : projectDir.toString(), false);
        addRow(panel, 1, "候选来源", candidatesSummary(), candidates.isEmpty());
        if (WorktreeDetector.isMainRepo(projectDir)) {
            addRow(panel, 2, "说明", "主仓库不会被自动弹窗，这里是从 Tools 菜单进来的手动入口", true);
        }
        return panel;
    }

    private JComponent buildSourceContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(2, 6, 6, 6));

        ButtonGroup group = new ButtonGroup();
        group.add(useDefaultsRadio);
        group.add(inheritRadio);
        // 必须显式选中一个：JRadioButton 默认是全部未选中，
        // 候选为空时会呈现「两个都没选」的困惑状态。
        if (candidates.isEmpty()) {
            useDefaultsRadio.setSelected(true);
        } else {
            inheritRadio.setSelected(true);
        }

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        c.insets = new Insets(0, 0, 4, 0);
        panel.add(useDefaultsRadio, c);

        c.gridy = 1;
        panel.add(inheritRadio, c);

        JPanel sourceRow = new JPanel(new BorderLayout(8, 0));
        sourceRow.setBorder(JBUI.Borders.emptyLeft(INDENT));
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        sourceRow.add(browseButton, BorderLayout.EAST);

        c.gridy = 2;
        c.gridwidth = 1;
        c.insets = new Insets(4, 0, 6, 0);
        panel.add(sourceRow, c);

        c.gridy = 3;
        c.gridwidth = 2;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(currentHeapLabel, c);

        sourceCombo.setEnabled(inheritRadio.isSelected());
        browseButton.setEnabled(inheritRadio.isSelected());
        useDefaultsRadio.addActionListener(e -> updateSourceEnabled());
        inheritRadio.addActionListener(e -> updateSourceEnabled());
        browseButton.addActionListener(e -> browseForSource());
        return panel;
    }

    private JComponent buildSyncContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(2, 6, 6, 6));

        ideaBox.setSelected(true);
        mavenBox.setSelected(true);
        claudeConfigBox.setSelected(true);
        claudeSessionsBox.setSelected(true);
        rangeCombo.setSelectedIndex(0);
        overwriteBox.setSelected(false);
        rememberBox.setSelected(true);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(1, 0, 1, 0);

        int row = 0;

        c.gridy = row++;
        panel.add(ideaBox, c);

        c.gridy = row++;
        panel.add(mavenBox, c);

        c.gridy = row++;
        panel.add(claudeConfigBox, c);

        c.gridy = row++;
        panel.add(claudeSessionsBox, c);

        JPanel rangeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rangeRow.setBorder(JBUI.Borders.emptyLeft(INDENT));
        rangeRow.add(new JLabel("会话时间范围："));
        rangeRow.add(rangeCombo);

        c.gridy = row++;
        c.insets = new Insets(1, 0, 6, 0);
        panel.add(rangeRow, c);

        c.gridy = row++;
        c.insets = new Insets(1, 0, 1, 0);
        panel.add(overwriteBox, c);

        c.gridy = row;
        c.insets = new Insets(6, 0, 1, 0);
        panel.add(rememberBox, c);

        claudeSessionsBox.addActionListener(e -> rangeCombo.setEnabled(claudeSessionsBox.isSelected()));
        return panel;
    }

    private static JPanel section(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static void addRow(JPanel panel, int row, String label, String value, boolean asHint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(1, 0, 1, 8);
        panel.add(new JLabel(label + "："), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 0, 1, 0);
        JLabel valueLabel = asHint ? new JLabel(html(hint(value))) : new JLabel(value);
        valueLabel.setToolTipText(value);
        panel.add(valueLabel, c);
    }

    // ------------------------------------------------------------------ 交互

    private void updateSourceEnabled() {
        boolean inherit = inheritRadio.isSelected();
        sourceCombo.setEnabled(inherit);
        browseButton.setEnabled(inherit);
    }

    private void browseForSource() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle("选择设置来源目录")
                .withDescription("选择要继承其 IDEA 设置的目录（通常是主仓库或其他 worktree）");
        try {
            VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
            if (chosen == null) {
                return;
            }
            WorktreeInfo manual = new WorktreeInfo(Paths.get(chosen.getPath()), null, false);
            sourceCombo.addItem(manual);
            sourceCombo.setSelectedItem(manual);
            inheritRadio.setSelected(true);
            updateSourceEnabled();
        } catch (Throwable t) {
            Messages.showWarningDialog(project,
                    "打开目录选择器失败：" + t.getMessage(), "Worktree Sync");
        }
    }

    @Override
    protected ValidationInfo doValidate() {
        if (inheritRadio.isSelected() && sourceCombo.getSelectedItem() == null) {
            return new ValidationInfo("请选择要继承的来源 worktree，或改用「使用 IDEA 默认项目设置」。");
        }
        if (!ideaBox.isSelected() && !mavenBox.isSelected()
                && !claudeConfigBox.isSelected() && !claudeSessionsBox.isSelected()) {
            return new ValidationInfo("请至少勾选一个同步项。");
        }
        return null;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return candidates.isEmpty() ? browseButton : sourceCombo;
    }

    /** 读取用户的选择。 */
    public SyncPlan getPlan() {
        SyncPlan plan = new SyncPlan();
        plan.ideaSettings = ideaBox.isSelected();
        plan.mavenSettings = mavenBox.isSelected();
        plan.claudeProjectConfig = claudeConfigBox.isSelected();
        plan.claudeSessions = claudeSessionsBox.isSelected();
        plan.overwriteExisting = overwriteBox.isSelected();
        plan.rememberDecision = rememberBox.isSelected();

        int index = rangeCombo.getSelectedIndex();
        plan.sessionDays = (index >= 0 && index < RANGE_DAYS.length) ? RANGE_DAYS[index] : 7;

        if (inheritRadio.isSelected()) {
            Object selected = sourceCombo.getSelectedItem();
            plan.sourceDir = selected instanceof WorktreeInfo ? ((WorktreeInfo) selected).path : null;
        } else {
            plan.sourceDir = null;
        }
        return plan;
    }

    // ------------------------------------------------------------------ 文案工具

    /** 当前项目的编译堆，让用户一眼看到「现在的值」。 */
    private String currentHeapText() {
        int heap = -1;
        try {
            CompilerConfiguration configuration = CompilerConfiguration.getInstance(project);
            if (configuration != null) {
                heap = configuration.getBuildProcessHeapSize(IdeaSettingsSyncer.JPS_DEFAULT_HEAP_MB);
            }
        } catch (Throwable ignored) {
        }
        if (heap <= 0) {
            return "当前项目编译堆：未知";
        }
        return heap == IdeaSettingsSyncer.JPS_DEFAULT_HEAP_MB
                ? "当前项目编译堆：" + heap + "M（IDEA 内置默认，偏小）"
                : "当前项目编译堆：" + heap + "M";
    }

    private static String html(String body) {
        return "<html>" + body + "</html>";
    }

    /** 主副文案之间的间隔，用不换行空格避免被断行。 */
    private static String gap() {
        return "&nbsp;&nbsp;";
    }

    private static String hint(String text) {
        return "<font color='#" + hex(HINT_FG) + "'>" + text + "</font>";
    }

    private static String hex(Color color) {
        int rgb = color.getRGB();
        return String.format(Locale.ROOT, "%02x%02x%02x",
                (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
