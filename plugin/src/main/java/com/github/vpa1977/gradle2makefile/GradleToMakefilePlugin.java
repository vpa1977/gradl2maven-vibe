package com.github.vpa1977.gradle2makefile;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.file.copy.CopySpecInternal;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskState;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.ZipEntryCompression;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.api.tasks.scala.ScalaCompileOptions;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GradleToMakefilePlugin implements Plugin<Project> {

    private List<String> makefileLines = new ArrayList<>();
    private ArrayList<String> taskDependencies = new ArrayList<>();
    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;

        makefileLines.add("# Generated Makefile from Gradle build");
        makefileLines.add("# Project: " + project.getName());
        makefileLines.add("");
        makefileLines.add(".PHONY: all clean");
        makefileLines.add("");

        project.getGradle().getTaskGraph().whenReady(taskGraph -> {
            project.getGradle().addListener(new TaskExecutionListener() {
                @Override
                public void beforeExecute(Task task) {
                    processTask(task, project);
                }

                @Override
                public void afterExecute(Task task, TaskState state) {
                }
            });
        });

        project.getGradle().buildFinished(result -> {
            generateMakefile();
        });
    }

    private void processTask(Task task, Project project) {
        String taskName = getUniqueName(task);
        String taskClass = task.getClass().getSimpleName();
        taskDependencies.add(taskName);
        // Emit the makefile rule header: "taskName: dep1 dep2 ..."
        StringBuilder ruleHeader = new StringBuilder();
        ruleHeader.append(taskName);
        ruleHeader.append(":");
        for (Task dep : project.getGradle().getTaskGraph().getDependencies(task)) {
          ruleHeader.append(" ").append(getUniqueName(dep));
        }
        makefileLines.add(ruleHeader.toString());

        if (taskClass.contains("JavaCompile")) {
            handleJavaCompile(task);
        } else if (taskClass.contains("ScalaCompile")) {
            handleScalaCompile(task);
        } else if (taskClass.contains("Jar")) {
            handleJarTask(task);
        } else if (taskClass.contains("Copy")) {
            handleCopyTask(task);
        } else {
            handleUnknownTask(task);
        }
    }

    private static @NotNull String getUniqueName(Task task) {
        return task.getPath().replace(":", "a");
    }

    private void handleJavaCompile(Task task) {
        try {
            org.gradle.api.tasks.compile.JavaCompile compileTask = (org.gradle.api.tasks.compile.JavaCompile)task;

            FileTree sourceFiles = compileTask.getSource();
            if (sourceFiles.isEmpty()) {
                return;
            }
            StringBuilder command = generateJavaCompile(compileTask, sourceFiles);

            makefileLines.add(command.toString());
            makefileLines.add("");

        } catch (Exception e) {
            makefileLines.add("# Error processing JavaCompile task: " + e.getMessage());
        }
    }

    private static @NotNull StringBuilder generateJavaCompile(JavaCompile compileTask, FileTree sourceFiles) {
        var destinationDir = compileTask.getDestinationDirectory().getAsFile().get();
        var classpath = compileTask.getClasspath();
        var options = compileTask.getOptions();

        String destDirPath = destinationDir.toString();

        StringBuilder command = new StringBuilder();
        command.append("\t@mkdir -p ").append(destDirPath).append("\n");
        command.append("\t@echo 'Compiling Java sources...'\n");
        command.append("\tjavac ");

        if (classpath != null) {
            var classPathString = new StringBuilder();
            for (var entry : classpath.getFiles()) {
                classPathString.append(entry.getAbsolutePath()).append(":");
            }
            if (!classPathString.isEmpty()) {
                command.append("-cp ").append(classPathString).append(" ");
            }
        }


        command.append("-d ").append(destDirPath).append(" ");

        Class<?> optionsClass = options.getClass();
        try {
            Object encoding = optionsClass.getMethod("getEncoding").invoke(options);
            if (encoding != null) {
                command.append("-encoding ").append(encoding).append(" ");
            }
        } catch (Exception e) {
        }

        try {
            Object sourceCompat = optionsClass.getMethod("getSourceCompatibility").invoke(compileTask);
            if (sourceCompat != null) {
                command.append("-source ").append(sourceCompat).append(" ");
            }

            Object targetCompat = optionsClass.getMethod("getTargetCompatibility").invoke(compileTask);
            if (targetCompat != null) {
                command.append("-target ").append(targetCompat).append(" ");
            }
        } catch (Exception e) {
        }

        for (var file : sourceFiles.getFiles()) {
            command.append(file.toString()).append(" ");
        }
        return command;
    }

    private void handleScalaCompile(Task task) {

        try {
            ScalaCompile compileTask = (ScalaCompile) task;

            FileTree sourceFiles = compileTask.getSource();
            if (sourceFiles.isEmpty()) {
                return;
            }

            var destinationDir = compileTask.getDestinationDirectory().getAsFile().get();
            String destDirPath = destinationDir.toString();

            StringBuilder command = new StringBuilder();
            command.append("\t@mkdir -p ").append(destDirPath).append("\n");



            command.append("\t@echo 'Compiling Scala sources...'\n");

            // Build the scalaClasspath string for invoking the compiler via java
            var scalaClasspath = compileTask.getScalaClasspath();
            StringBuilder scalaClasspathStr = new StringBuilder();
            if (scalaClasspath != null) {
                for (var entry : scalaClasspath.getFiles()) {
                    if (scalaClasspathStr.length() > 0) {
                        scalaClasspathStr.append(":");
                    }
                    scalaClasspathStr.append(entry.getAbsolutePath());
                }
            }

            String scalaClasspathStrRet = scalaClasspathStr.toString();

            command.append("\tjava");
            if (scalaClasspathStr.length() > 0) {
                command.append(" -cp ").append(scalaClasspathStrRet);
            }
            command.append(" scala.tools.nsc.Main");

            // Compile classpath (project dependencies)
            StringBuilder cpStr = new StringBuilder();
            var classpath = compileTask.getClasspath();
            if (classpath != null && !classpath.isEmpty()) {
                for (var entry : classpath.getFiles()) {
                    if (cpStr.length() > 0) {
                        cpStr.append(":");
                    }
                    cpStr.append(entry.getAbsolutePath());
                }
                command.append(" -classpath ").append(cpStr);
            }

            command.append(" -d ").append(destDirPath);

            // ScalaCompileOptions flags
            ScalaCompileOptions scalaOpts = compileTask.getScalaCompileOptions();
            if (scalaOpts != null) {
                if (Boolean.TRUE.equals(scalaOpts.isDeprecation())) {
                    command.append(" -deprecation");
                }
                if (Boolean.TRUE.equals(scalaOpts.isUnchecked())) {
                    command.append(" -unchecked");
                }
                if (Boolean.TRUE.equals(scalaOpts.isOptimize())) {
                    command.append(" -optimise");
                }
            }

            // Scala compiler plugins
            var compilerPlugins = compileTask.getScalaCompilerPlugins();
            if (compilerPlugins != null) {
                for (var plugin : compilerPlugins.getFiles()) {
                    command.append(" -Xplugin:").append(plugin.getAbsolutePath());
                }
            }

            // Source files
            for (var file : sourceFiles.getFiles()) {
                command.append(" ").append(file.getAbsolutePath());
            }

            makefileLines.add(command.toString());
            command = new StringBuilder();
            var javaFiles = sourceFiles.getFiles().stream().filter( x-> x.getName().endsWith(".java")).toList();
            if (!javaFiles.isEmpty()) {
                command.append("\tjavac");
                if (!scalaClasspathStrRet.isEmpty()) {
                    command.append(" -cp ").append(cpStr).append(":").append(destDirPath);
                }
                command.append(" -d ").append(destDirPath);
                // Source files

                for (var file : javaFiles) {
                    command.append(" ").append(file.getAbsolutePath());
                }
                makefileLines.add(command.toString());
            }

            makefileLines.add("");

        } catch (Exception e) {
            makefileLines.add("# Error processing ScalaCompile task: " + e.getMessage());
        }
    }


    private void handleJarTask(Task task) {
        try {
            if (true) {
                makefileLines.add("\techo jar is not supported yet");
                return;
            }
            Jar jarTask = (Jar) task;

            // Use the typed API to get the real output File — no string parsing needed.
            File outputJar = jarTask.getArchiveFile().get().getAsFile();
            String destDir = outputJar.getParent();

            makefileLines.add("\t@mkdir -p " + destDir);
            makefileLines.add("\t@echo 'Creating JAR file...'");

            // Collect unique input directories from the source file tree.
            // For each directory we add a  "-C <dir> ."  fragment so that
            // `jar` picks up all class files while preserving the package layout.
            java.util.LinkedHashSet<File> inputDirs = new java.util.LinkedHashSet<>();
            jarTask.getSource().getAsFileTree().visit(details -> {
                if (!details.isDirectory()) {
                    // Walk up until we find the root registered with the copy spec.
                    // The relative path depth tells us how many levels to go up.
                    File file = details.getFile();
                    int depth = details.getRelativePath().getSegments().length;
                    File root = file;
                    for (int i = 0; i < depth; i++) {
                        root = root.getParentFile();
                    }
                    if (root != null) {
                        inputDirs.add(root);
                    }
                }
            });

            StringBuilder jarCmd = new StringBuilder("\tjar cf ");
            jarCmd.append(outputJar.getAbsolutePath());
            for (File dir : inputDirs) {
                jarCmd.append(" -C ").append(dir.getAbsolutePath()).append(" .");
            }
            makefileLines.add(jarCmd.toString());
            makefileLines.add("");

        } catch (Exception e) {
            makefileLines.add("# Error processing Jar task: " + e.getMessage());
        }
    }

    public String createJarCall(Jar task) {
        StringBuilder flags = new StringBuilder("jar c");
        StringBuilder args = new StringBuilder();

        // 1. Handle Compression
        if (task.getEntryCompression() == ZipEntryCompression.STORED) {
            flags.append("0");
        }

        flags.append("f");

        // 2. Handle Manifest (-m)
        // Gradle dynamically generates the manifest in the task's temporary directory.
        File manifestFile = new File(task.getTemporaryDir(), "MANIFEST.MF");
        flags.append("m");

        // The order of arguments must match the order of 'f' and 'm' flags.
        File archiveFile = task.getArchiveFile().get().getAsFile();
        args.append(" \"").append(archiveFile.getAbsolutePath()).append("\"");
        args.append(" \"").append(manifestFile.getAbsolutePath()).append("\"");

        // 3. Extract source directories using CopySpec
        Set<String> sourceDirs = extractSourceRoots(task, task.getRootSpec());

        if (sourceDirs.isEmpty()) {
            args.append(" -C \"<source-directory>\" .");
        } else {
            for (String dir : sourceDirs) {
                args.append(" -C \"").append(dir).append("\" .");
            }
        }

        return flags.toString() + args.toString();
    }

    /**
     * Recursively walks the Gradle CopySpec tree to extract source directories.
     * Uses the public file-tree visitor to discover source roots by walking up
     * from each file by its relative-path depth.
     */
    private Set<String> extractSourceRoots(Jar task, CopySpec spec) {
        Set<String> roots = new LinkedHashSet<>();

        if (spec instanceof CopySpecInternal) {
            CopySpecInternal internalSpec = (CopySpecInternal) spec;

            internalSpec.eachFile(details -> {
                File file = details.getFile();
                int depth = details.getRelativePath().getSegments().length;
                File root = file;
                for (int i = 0; i < depth; i++) {
                    root = root.getParentFile();
                }
                if (root != null && root.isDirectory()) {
                    roots.add(root.getAbsolutePath());
                }
            });

            // Recursively evaluate any nested CopySpecs (e.g., from('src') { into 'dest' })
            for (CopySpec child : internalSpec.getChildren()) {
                roots.addAll(extractSourceRoots(task, child));
            }
        }

        return roots;
    }

    private void handleCopyTask(Task task) {
        try {
            Copy cp = (Copy) task;
            File destinationDir = cp.getDestinationDir();

            cp.getSource().getAsFileTree().visit(details -> {
                if (details.isDirectory()) {
                    return;
                }
                String relativePath = details.getRelativePath().getPathString();
                File destFile = new File(destinationDir, relativePath);
                String destParent = destFile.getParent();
                makefileLines.add("\t@mkdir -p " + destParent);
                makefileLines.add("\t@cp " + details.getFile().getAbsolutePath() + " " + destFile.getAbsolutePath());
            });

            makefileLines.add("");
        } catch (Exception e) {
            makefileLines.add("# Error processing Copy task: " + e.getMessage());
        }
    }

    private void handleUnknownTask(Task task) {
        String pluginName = "unknown";
        try {
            if (task.getClass().getName().contains("org.gradle")) {
                pluginName = "gradle-core";
            } else {
                String className = task.getClass().getName();
                if (className.contains(".")) {
                    pluginName = className.substring(0, className.lastIndexOf("."));
                }
            }
        } catch (Exception e) {
        }

        String warning = String.format(
            "# WARNING: task %s added by plugin %s is not known",
            task.getName(),
            pluginName
        );

        if (!makefileLines.contains(warning)) {
            makefileLines.add(warning);
            makefileLines.add("# Task class: " + task.getClass().getSimpleName());
            makefileLines.add("");
        }

        project.getLogger().warn("Task {} added by plugin {} is not known",
            task.getName(), pluginName);
    }

    private void generateMakefile() {
        File makefileDir = new File(project.getProjectDir(), "build");
        makefileDir.mkdirs();

        File makefile = new File(makefileDir, "Makefile");

        try (FileWriter writer = new FileWriter(makefile)) {
            // "all" depends on every task that was collected
            StringBuilder allLine = new StringBuilder("all:");
            for (String name : taskDependencies) {
                allLine.append(" ").append(name);
            }
            makefileLines.add(allLine.toString());
            makefileLines.add("");
            makefileLines.add("clean:");
            makefileLines.add("\t@echo 'Cleaning build artifacts...'");
            makefileLines.add("\trm -rf build/classes");
            makefileLines.add("\trm -rf build/libs");
            makefileLines.add("");

            for (String line : makefileLines) {
                writer.write(line + "\n");
            }

            project.getLogger().lifecycle("Makefile generated at: " + makefile.getAbsolutePath());
        } catch (IOException e) {
            project.getLogger().error("Failed to generate Makefile", e);
        }
    }
}
