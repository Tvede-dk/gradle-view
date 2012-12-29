package com.github.rholder.gradle.ui;

import com.github.rholder.gradle.dependency.DependencyCellRenderer;
import com.github.rholder.gradle.dependency.GradleDependency;
import com.github.rholder.gradle.log.ToolingLogger;
import com.github.rholder.gradle.service.GradleService;
import com.github.rholder.gradle.service.GradleServiceListener;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.treeStructure.SimpleTree;
import com.intellij.util.Consumer;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class DependencyViewer extends SimpleToolWindowPanel {

    private final Project project;
    private final ToolWindow toolWindow;
    private final Splitter splitter;
    private final ToolingLogger toolingLogger;
    private String gradleBaseDir;
    private boolean shouldPromptForCurrentProject;

    public DependencyViewer(Project p, ToolWindow t) {
        super(true, true);
        this.project = p;
        this.toolWindow = t;
        this.splitter = new Splitter();
        this.toolingLogger = new ToolingLogger() {
            public void log(final String line) {
                // TODO lots of log messages will freeze the dispatch thread, fix this
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if(gradleBaseDir != null) {
                            toolWindow.setTitle("- " + gradleBaseDir + " - " + line);
                        }
                    }
                });
            }
        };
        shouldPromptForCurrentProject = true;


        // TODO clean all of this up
        GradleService gradleService = ServiceManager.getService(project, GradleService.class);
        gradleService.addListener(new GradleServiceListener() {
            public void refresh() {
                if(shouldPromptForCurrentProject) {
                    switch(useCurrentProjectBuild()) {
                        case 0: gradleBaseDir = project.getBasePath();
                                break;
                        default: // do nothing, stay null
                    }
                    shouldPromptForCurrentProject = false;
                }

                if(gradleBaseDir == null) {
                    promptForGradleBaseDir();
                }

                updateView(new GradleDependency("Loading..."));

                new SwingWorker<GradleDependency, Void>() {
                    protected GradleDependency doInBackground() throws Exception {
                        Map<String, GradleDependency> dependencyMap = GradleService.loadProjectDependencies(gradleBaseDir, toolingLogger);
                        GradleDependency root = dependencyMap.get("root");
                        updateView(root);
                        return root;
                    }
                }.execute();
            }
            public void reset() {
                gradleBaseDir = null;
                refresh();
            }
        });
        gradleService.refresh();

        setContent(splitter);
        final ActionManager actionManager = ActionManager.getInstance();
        ActionToolbar actionToolbar = actionManager.createActionToolbar("Gradle View Toolbar",
                (DefaultActionGroup)actionManager.getAction("GradleView.NavigatorActionsToolbar"), true);

        actionToolbar.setTargetComponent(splitter);
        setToolbar(actionToolbar.getComponent());

    }

    public void updateView(GradleDependency dependency) {
        // TODO replace this hack with something that populates the GradleDependency graph

        TreeModel leftModel = new DefaultTreeModel(getNode(dependency));
        final SimpleTree leftTree = new SimpleTree(leftModel);
        leftTree.setCellRenderer(new DependencyCellRenderer());

        TreeModel rightModel = new DefaultTreeModel(generateSortedDependencies(dependency));
        final SimpleTree rightTree = new SimpleTree(rightModel);
        rightTree.setCellRenderer(new DependencyCellRenderer());

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if(gradleBaseDir != null) {
                    toolWindow.setTitle("- " + gradleBaseDir);
                }
                splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(leftTree));
                splitter.setSecondComponent(ScrollPaneFactory.createScrollPane(rightTree));
            }
        });
    }

    private DefaultMutableTreeNode getNode(GradleDependency dependency) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(dependency);
        for(GradleDependency d : dependency.dependencies) {
            node.add(getNode(d));
        }
        return node;
    }

    // --- begin sorted deps
    private DefaultMutableTreeNode generateSortedDependencies(GradleDependency root) {
        // top level GradleDependency instances are actually the configuration strings
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(root);
        for(GradleDependency configuration : root.dependencies) {
            DefaultMutableTreeNode configurationNode = new DefaultMutableTreeNode(configuration);
            rootNode.add(configurationNode);

            // TODO filter dupes here by fixing equals/hashCode, though there are no dupes unless Gradle is broken...
            Set<GradleDependency> childDependencies = getChildrenFromRootNode(configuration);
            for(GradleDependency d : childDependencies) {
                if(!d.isOmitted()) {
                    configurationNode.add(new DefaultMutableTreeNode(d));
                }
            }
        }
        return rootNode;
    }

    private Set<GradleDependency> getChildrenFromRootNode(GradleDependency dependency) {
        Set<GradleDependency> sortedDependencies = new TreeSet<GradleDependency>();
        for(GradleDependency d : dependency.dependencies) {
            sortedDependencies.addAll(getChildrenNodes(d));
        }
        return sortedDependencies;
    }

    private Set<GradleDependency> getChildrenNodes(GradleDependency dependency) {
        Set<GradleDependency> sortedDependencies = new TreeSet<GradleDependency>();
        sortedDependencies.add(dependency);
        for(GradleDependency d : dependency.dependencies) {
            sortedDependencies.addAll(getChildrenNodes(d));
        }
        return sortedDependencies;
    }
    // --- end sorted deps

    private void promptForGradleBaseDir() {
        FileChooserDescriptor fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        fcd.setShowFileSystemRoots(true);
        fcd.setTitle("Choose a Gradle folder...");
        fcd.setDescription("Pick the top level directory to use when viewing dependencies (in case you have a multi-module project)");
        fcd.setHideIgnored(false);

        FileChooser.chooseFiles(fcd, project, project.getBaseDir(), new Consumer<List<VirtualFile>>() {
            @Override
            public void consume(List<VirtualFile> files) {
                gradleBaseDir = files.get(0).getPath();
            }
        });
    }

    private int useCurrentProjectBuild() {
        return Messages.showYesNoDialog(
                "Would you like to view the current project's Gradle dependencies?",
                "Use this project's Gradle build",
                null);
    }
}
